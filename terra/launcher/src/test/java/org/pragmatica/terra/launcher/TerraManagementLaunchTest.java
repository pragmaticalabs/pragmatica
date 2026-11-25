// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.launcher;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.terra.http.TerraHttpServer;
import org.pragmatica.terra.management.ManagementConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3651: the management endpoints through a real launch: terra.toml and the environment reach a served endpoint,
/// the defaults serve `/actuator/health`, a slice route inside the base path stops the launch, and a separate
/// management listener keeps the application's TLS.
class TerraManagementLaunchTest {
    private static final String KEY = "management-key-0123456789abcdef";
    private static final String HTTP = "[http]\nport = 0\n[api_keys.ops]\nkey = \"" + KEY + "\"\n";

    @TempDir Path directory;

    @Test void launch_defaultConfiguration_answersActuatorHealth() throws Exception {
        var host = launch("test:ok:1", HTTP);
        try {
            var health = get(host.port(), "/actuator/health");

            assertThat(health.statusCode()).isEqualTo(200);
            assertThat(compact(health)).contains("\"status\":\"UP\"");
            assertThat(get(host.port(), "/actuator/info").statusCode()).isEqualTo(404);
            assertThat(get(host.port(), "/actuator/metrics").statusCode()).isEqualTo(404);
        } finally { close(host); }
    }

    /// The `[management]` sections of terra.toml reach the served endpoints; `include` is a comma list or a TOML array.
    @Test void launch_managementSections_reachServedEndpoints_arrayAndCommaIncludeAlike() throws Exception {
        var forms = Map.of("[\"health\", \"info\"]", "array", "\"health,info\"", "comma list");

        for (var form : forms.entrySet()) {
            var host = launch("test:ok:1", HTTP + """
                [management.endpoints.web.exposure]
                include = %s
                [management.endpoints.web]
                base-path = "/ops"
                [management.info.env]
                enabled = true
                [info.app]
                name = "demo"
                """.formatted(form.getKey()));
            try {
                var info = get(host.port(), "/ops/info");

                assertThat(info.statusCode()).as(form.getValue()).isEqualTo(200);
                assertThat(compact(info)).as(form.getValue()).contains("\"name\":\"demo\"");
                assertThat(get(host.port(), "/ops/health").statusCode()).as(form.getValue()).isEqualTo(200);
                assertThat(get(host.port(), "/actuator/health").statusCode()).as(form.getValue()).isEqualTo(404);
                assertThat(get(host.port(), "/ops/metrics").statusCode()).as(form.getValue()).isEqualTo(404);
            } finally { close(host); }
        }
    }

    @Test void load_malformedExposureList_refusedAtLoadNamingTheKey() throws Exception {
        for (var include : new String[]{"\"[health\"", "[\"health\", \"in fo\"]", "\"\\\"health\\\"\""}) {
            Files.writeString(directory.resolve("blueprint.toml"), "[[slices]]\nartifact = \"test:ok:1\"\n");
            Files.writeString(directory.resolve("terra.toml"), HTTP + "[management.endpoints.web.exposure]\ninclude = " + include + "\n");
            var loaded = TerraLaunchPlan.load(directory).await(timeSpan(10).seconds());

            assertThat(loaded.isFailure()).as(include).isTrue();
            assertThat(loaded.<String>fold(cause -> cause.message(), _ -> "loaded")).as(include).contains("management.endpoints.web.exposure.include");
        }
    }

    /// A slice route inside the management base path stops the launch naming the route; the same slice launches once
    /// the base path is elsewhere, so the refusal is the reserved path and nothing else.
    @Test void launch_sliceRouteUnderTheBasePath_refusedNamingTheRoute() throws Exception {
        write("test:actuator:1", HTTP);
        var plan = TerraLaunchPlan.load(directory).await(timeSpan(10).seconds()).unwrap();
        var refused = plan.start().await(timeSpan(15).seconds());

        assertThat(refused.isFailure()).isTrue();
        assertThat(refused.<String>fold(cause -> cause.message(), _ -> "started")).contains("/actuator/x");

        var moved = launch("test:actuator:1", HTTP + "[management.endpoints.web]\nbase-path = \"/ops\"\n");
        try {
            assertThat(get(moved.port(), "/actuator/x").statusCode()).isEqualTo(200);
        } finally { close(moved); }
    }

    /// With `http.tls_certificate` set, a separate management port inherits the TLS: https answers there, plain http
    /// does not, and discovery advertises https on that listener and on the shared one.
    @Test void launch_tlsApplication_managementListenerKeepsTls_andDiscoveryAdvertisesHttps() throws Exception {
        var certificate = new SelfSignedCertificate("localhost");
        try {
            Files.copy(certificate.certificate().toPath(), directory.resolve("cert.pem"));
            Files.copy(certificate.privateKey().toPath(), directory.resolve("key.pem"));
        } finally { certificate.delete(); }
        var tls = HTTP.replace("port = 0\n", "port = 0\ntls_certificate = \"cert.pem\"\ntls_key = \"key.pem\"\n");

        var separate = launch("test:ok:1", tls + "[management.server]\nport = 0\n");
        try {
            var port = separate.mountPort("/actuator").unwrap();

            assertThat(port).isNotEqualTo(separate.port());
            assertThat(secureGet(port, "/actuator/health", "X-API-Key", KEY).statusCode()).isEqualTo(200);
            assertThat(plainStatus(port, "/actuator/health")).isNotEqualTo(200);
            assertThat(compact(secureGet(port, "/actuator", "X-API-Key", KEY))).contains("\"href\":\"https://localhost:" + port + "/actuator\"");
            assertThat(secureGet(separate.port(), "/actuator/health", "X-API-Key", KEY).statusCode()).isEqualTo(404);
        } finally { close(separate); }

        var shared = launch("test:ok:1", tls);
        try {
            assertThat(compact(secureGet(shared.port(), "/actuator", "X-API-Key", KEY)))
                .contains("\"href\":\"https://localhost:" + shared.port() + "/actuator\"");
            assertThat(compact(secureGet(shared.port(), "/actuator", "X-API-Key", KEY, "X-Forwarded-Proto", "http")))
                .as("the listener's own TLS outranks the proxy's X-Forwarded-Proto")
                .contains("\"href\":\"https://localhost:" + shared.port() + "/actuator\"");
        } finally { close(shared); }
    }

    /// Environment names carry no dash: `_` reads as `-` in a management key, and one setting given in both forms with
    /// different values is refused (equal values agree).
    @Test void managementKeys_environmentUnderscoreForm_readsAsTheDashedKey_andConflictingFormsAreRefused() throws Exception {
        Files.writeString(directory.resolve("terra.toml"), "[management.endpoints.web]\nbase-path = \"/toml\"\n");
        var environment = TerraConfiguration.environment(Map.of("TERRA_MANAGEMENT__ENDPOINTS__WEB__BASE_PATH", "/env",
                                                                "TERRA_MANAGEMENT__ENDPOINT__HEALTH__SHOW_DETAILS", "always",
                                                                "TERRA_INFO__APP__BUILD_ID", "7")).unwrap();
        var deployment = new TerraConfiguration.Deployment(ConfigurationProvider.builder().withSource(environment).build(),
                                                           ConfigurationProvider.builder().build());
        var conflict = TerraLaunchPlan.managementKeys(TerraConfiguration.host(directory, deployment).unwrap());

        assertThat(conflict.isFailure()).isTrue();
        assertThat(conflict.<String>fold(cause -> cause.message(), _ -> "accepted")).contains("management.endpoints.web.base-path");

        Files.writeString(directory.resolve("terra.toml"), "[management.endpoints.web]\nbase-path = \"/env\"\n");
        var agreed = TerraLaunchPlan.managementKeys(TerraConfiguration.host(directory, deployment).unwrap()).unwrap();

        assertThat(agreed).containsEntry("management.endpoints.web.base-path", "/env")
                          .containsEntry("management.endpoint.health.show-details", "always")
                          .as("only management.* keys read _ as -; an info.* key keeps its name")
                          .containsEntry("info.app.build_id", "7");
        assertThat(basePathOf(agreed)).isEqualTo("/env");

        var envOnly = new TerraConfiguration.Deployment(ConfigurationProvider.builder().withSource(environment).build(), ConfigurationProvider.builder().build());
        Files.delete(directory.resolve("terra.toml"));
        assertThat(basePathOf(TerraLaunchPlan.managementKeys(TerraConfiguration.host(directory, envOnly).unwrap()).unwrap())).isEqualTo("/env");
    }

    private static String basePathOf(Map<String, String> keys) {
        return ManagementConfig.managementConfig(keys).unwrap().basePath();
    }

    private static String compact(HttpResponse<String> response) {
        return response.body().replaceAll("\\s", "");
    }

    private void write(String artifact, String terraToml) throws IOException {
        Files.writeString(directory.resolve("blueprint.toml"), "[[slices]]\nartifact = \"" + artifact + "\"\n");
        Files.writeString(directory.resolve("terra.toml"), terraToml);
    }

    private TerraHttpServer launch(String artifact, String terraToml) throws IOException {
        write(artifact, terraToml);
        return TerraLaunchPlan.load(directory).await(timeSpan(10).seconds()).unwrap().start().await(timeSpan(15).seconds()).unwrap();
    }

    private static HttpResponse<String> get(int port, String path) throws Exception {
        try (var client = HttpClient.newHttpClient()) {
            return client.send(request("http", port, path), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static int plainStatus(int port, String path) throws Exception {
        try {
            return get(port, path).statusCode();
        } catch (IOException e) {
            return -1;
        }
    }

    private static HttpResponse<String> secureGet(int port, String path, String... headers) throws Exception {
        var trust = new X509TrustManager() {
            public java.security.cert.X509Certificate[] getAcceptedIssuers() { return new java.security.cert.X509Certificate[0]; }
            public void checkClientTrusted(java.security.cert.X509Certificate[] chain, String type) {}
            public void checkServerTrusted(java.security.cert.X509Certificate[] chain, String type) {}
        };
        var tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[]{trust}, new java.security.SecureRandom());
        try (var client = HttpClient.newBuilder().sslContext(tls).build()) {
            var builder = HttpRequest.newBuilder(URI.create("https://localhost:" + port + path)).timeout(Duration.ofSeconds(5));
            if (headers.length > 0) { builder.headers(headers); }
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static HttpRequest request(String scheme, int port, String path) {
        return HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + port + path)).timeout(Duration.ofSeconds(5)).build();
    }

    private static void close(TerraHttpServer host) {
        assertThat(host.close().await(timeSpan(10).seconds()).isSuccess()).isTrue();
    }
}
