// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra.management;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.lang.Option;
import org.pragmatica.terra.http.TerraHttpServer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3724 review round: the documented contracts of the launcher README's management section that the first suite left
/// unpinned. Each test names the README sentence it holds; configuration contracts are read off [ManagementConfig],
/// the wire contracts through a real Terra host ([TerraManagementHttpTest.Host]) or [TerraManagement#handle].
class TerraManagementContractTest {
    // The API key the host of TerraManagementHttpTest accepts; that class keeps the constant private.
    private static final String KEY = "management-key-0123456789abcdef";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final HttpClient CLIENT = HttpClient.newHttpClient();

    @TempDir Path directory;

    // README: "an unrecognised `management.*` key ... stops the load". A fixed key is matched whole, never by prefix.
    @Test
    void managementConfig_fixedKeyWithASuffix_refused() {
        for (var key : List.of("management.server.portX",
                               "management.endpoints.web.base-path.x",
                               "management.info.env.enabledX",
                               "management.endpoints.enabled-by-default-x")) {
            assertThat(ManagementConfig.managementConfig(Map.of(key, "true")).isFailure()).as(key).isTrue();
        }
    }

    // `management.endpoint.<id>.enabled` needs an id: a key without one is refused, it must not crash the parse.
    @Test
    void managementConfig_endpointEnabledWithoutAnId_refusedNotCrashed() {
        assertThat(ManagementConfig.managementConfig(Map.of("management.endpoint.enabled", "true")).isFailure()).isTrue();
        assertThat(ManagementConfig.managementConfig(Map.of("management.endpoint.info.enabled", "true")).isSuccess()).isTrue();
    }

    // The metric sink namespaces are exempt exactly (their owner validates them); a key beside them is still refused.
    @Test
    void managementConfig_keyBesideTheMetricSinkNamespaces_refused() {
        for (var key : List.of("management.prometheus.typo", "management.server.management.otlp.metrics.export.url")) {
            assertThat(ManagementConfig.managementConfig(Map.of(key, "x")).isFailure()).as(key).isTrue();
        }
        for (var key : List.of("management.prometheus.metrics.export.step",
                               "management.otlp.metrics.export.url",
                               "management.opentelemetry.resource-attributes.region")) {
            assertThat(ManagementConfig.managementConfig(Map.of(key, "x")).isSuccess()).as(key).isTrue();
        }
    }

    // README: `base-path = "/actuator"  # default; a trailing slash is dropped, "" or "/" mounts at the root`.
    @Test
    void managementConfig_basePath_dropsATrailingSlash_andEmptyOrSlashIsTheRoot() {
        assertThat(basePath(Map.of())).isEqualTo("/actuator");
        assertThat(basePath(Map.of("management.endpoints.web.base-path", "/actuator/"))).isEqualTo("/actuator");
        assertThat(basePath(Map.of("management.endpoints.web.base-path", "/ops/"))).isEqualTo("/ops");
        assertThat(basePath(Map.of("management.endpoints.web.base-path", ""))).isEqualTo("/");
        assertThat(basePath(Map.of("management.endpoints.web.base-path", "/"))).isEqualTo("/");
    }

    private static String basePath(Map<String, String> keys) {
        return ManagementConfig.managementConfig(keys).unwrap().basePath();
    }

    // README: `info.*` keys are served "under /info when management.info.env.enabled" (default false).
    @Test
    void managementConfig_infoKeys_reachTheInfoEndpointOnlyWithEnvEnabled() {
        var absent = ManagementConfig.managementConfig(Map.of("info.app.name", "demo")).unwrap();
        var disabled = ManagementConfig.managementConfig(Map.of("info.app.name", "demo", "management.info.env.enabled", "false")).unwrap();
        var enabled = ManagementConfig.managementConfig(Map.of("info.app.name", "demo", "management.info.env.enabled", "true")).unwrap();

        assertThat(absent.info()).isEmpty();
        assertThat(disabled.info()).isEmpty();
        assertThat(enabled.info()).isEqualTo(Map.of("app", Map.of("name", "demo")));
    }

    // README: `info` serves `build` "from META-INF/build-info.properties": the `build.*` keys, without the prefix.
    @Test
    void info_buildInfoProperties_servedAsBuildWithoutThePrefix() throws Exception {
        Files.createDirectories(directory.resolve("META-INF"));
        Files.writeString(directory.resolve("META-INF/build-info.properties"), "build.name=demo\nbuild.version=1.2\nother.key=x\n");

        try (var loader = new URLClassLoader(new URL[] {directory.toUri().toURL()}, null)) {
            var build = TerraManagement.buildInfo(loader).unwrap();
            var management = management(Map.of("management.endpoints.web.exposure.include", "info"), build);
            var info = JSON.readTree(answer(management, "/actuator/info", true).body());

            assertThat(build).isEqualTo(Map.of("name", "demo", "version", "1.2"));
            assertThat(info.at("/build/name").asString()).isEqualTo("demo");
            assertThat(info.at("/build/version").asString()).isEqualTo("1.2");
            assertThat(info.has("other")).isFalse();
        }
    }

    // README: the `readiness` group and the root answer from the host's readiness: 503 `OUT_OF_SERVICE` while it
    // drains, 200 `UP` while it admits; liveness stays UP.
    @Test
    void health_whileTheHostDrains_readinessAndRootAnswer503OutOfService_livenessStaysUp() throws Exception {
        var management = management(Map.of("management.endpoint.health.show-components", "always"), Map.of());

        for (var path : List.of("/actuator/health", "/actuator/health/readiness")) {
            var drained = answer(management, path, false);

            assertThat(drained.statusCode()).as(path).isEqualTo(503);
            assertThat(JSON.readTree(drained.body()).get("status").asString()).as(path).isEqualTo("OUT_OF_SERVICE");
            assertThat(answer(management, path, true).statusCode()).as(path).isEqualTo(200);
        }
        assertThat(answer(management, "/actuator/health/liveness", false).statusCode()).isEqualTo(200);
    }

    private static TerraManagement management(Map<String, String> settings, Map<String, Object> build) {
        var config = ManagementConfig.managementConfig(settings).unwrap();

        return TerraManagement.terraManagement(config, Map.of(), build, Option.empty()).unwrap();
    }

    private static HttpResponseData answer(TerraManagement management, String path, boolean ready) {
        var request = HttpRequestContext.httpRequestContext(path, "GET", Map.of(), Map.of(), "request-1");

        return management.handle(request, new TerraHttpServer.Status(ready, 0, 0L, 0L), false).await(timeSpan(5).seconds()).unwrap();
    }

    // README: `show-details` and `show-components` are separate; components shown with details `never` carry no `details`.
    @Test
    void healthDetails_followShowDetailsNotShowComponents() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of("management.endpoint.health.show-components", "always"), Map.of())) {
            var component = JSON.readTree(host.get("/actuator/health/diskSpace").body());

            assertThat(component.has("status")).isTrue();
            assertThat(component.has("details")).isFalse();
        }
        try (var host = TerraManagementHttpTest.Host.start(Map.of("management.endpoint.health.show-details", "always"), Map.of())) {
            assertThat(JSON.readTree(host.get("/actuator/health/diskSpace").body()).at("/details/threshold").isNumber()).isTrue();
        }
    }

    // README: `show-components = "when-authorized"` alone: the route stays public, an authenticated caller sees the components.
    @Test
    void healthComponents_whenAuthorizedWithoutDetails_authenticatedCallerSeesThem() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of("management.endpoint.health.show-components", "when-authorized"), Map.of())) {
            assertThat(JSON.readTree(host.get("/actuator/health").body()).has("components")).isFalse();
            assertThat(JSON.readTree(host.get("/actuator/health", "X-API-Key", KEY).body()).has("components")).isTrue();
            assertThat(host.get("/actuator/health", "X-API-Key", "wrong").statusCode()).isEqualTo(401);
        }
    }

    // README: "GET only, every other method including `HEAD` answers `405` with `Allow: GET`".
    @Test
    void otherMethods_answer405WithAllowGet() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            var uri = host.uri("/actuator/health");
            var post = host.send(HttpRequest.newBuilder(uri).POST(HttpRequest.BodyPublishers.noBody()));
            var delete = host.send(HttpRequest.newBuilder(uri).DELETE());
            var head = host.send(HttpRequest.newBuilder(uri).method("HEAD", HttpRequest.BodyPublishers.noBody()));

            for (var response : List.of(post, delete, head)) {
                assertThat(response.statusCode()).as(response.request().method()).isEqualTo(405);
                assertThat(response.headers().firstValue("Allow")).as(response.request().method()).hasValue("GET");
            }
        }
    }

    // README: "a JSON answer is `application/vnd.spring-boot.actuator.v3+json` unless `Accept` prefers `application/json`,
    // and the v2 media type (and any `Accept` that admits neither) answers `406`".
    @Test
    void negotiate_applicationWildcardAdmitsV3_andTheV2TypeIsRefused() throws Exception {
        var v2 = "application/vnd.spring-boot.actuator.v2+json";

        assertThat(TerraManagement.negotiate(Option.some("application/*"))).isEqualTo(Option.some(TerraManagement.ACTUATOR_V3));
        assertThat(TerraManagement.negotiate(Option.some(v2))).isEqualTo(Option.none());
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            assertThat(host.get("/actuator/health", "Accept", "application/*").headers().firstValue("Content-Type")).hasValue(TerraManagement.ACTUATOR_V3);
            assertThat(host.get("/actuator/health", "Accept", v2).statusCode()).isEqualTo(406);
        }
    }

    // README: `discovery enabled = false` removes the HAL document at the base path; with it on, the base path with
    // or without a trailing slash is that document.
    @Test
    void discovery_disabledAnswers404_enabledAnswersAtTheBasePathWithOrWithoutASlash() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of("management.endpoints.web.discovery.enabled", "false"), Map.of())) {
            assertThat(host.get("/actuator", "X-API-Key", KEY).statusCode()).isEqualTo(404);
            assertThat(host.get("/actuator/", "X-API-Key", KEY).statusCode()).isEqualTo(404);
            assertThat(host.get("/actuator/health").statusCode()).isEqualTo(200);
        }
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            for (var path : List.of("/actuator", "/actuator/")) {
                var response = host.get(path, "X-API-Key", KEY);

                assertThat(response.statusCode()).as(path).isEqualTo(200);
                assertThat(JSON.readTree(response.body()).at("/_links/self/href").asString()).as(path).endsWith("/actuator");
            }
        }
    }

    // PR guess: the discovery href scheme is `https` when the serving listener has TLS, else `X-Forwarded-Proto`, else `http`.
    @Test
    void discovery_plainListener_hrefSchemeFollowsXForwardedProto() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            var forwarded = JSON.readTree(host.get("/actuator", "X-API-Key", KEY, "X-Forwarded-Proto", "https").body());
            var plain = JSON.readTree(host.get("/actuator", "X-API-Key", KEY).body());

            assertThat(forwarded.at("/_links/self/href").asString()).startsWith("https://");
            assertThat(forwarded.at("/_links/health/href").asString()).startsWith("https://");
            assertThat(plain.at("/_links/self/href").asString()).startsWith("http://");
        }
    }

    // README: "" or "/" mounts at the root, and discovery is "never served at base path `/`".
    @Test
    void rootBasePath_servesEndpointsAtTheRoot_withoutDiscovery() throws Exception {
        for (var basePath : List.of("/", "")) {
            var settings = Map.of("management.endpoints.web.base-path", basePath, "management.server.port", "0");

            try (var host = TerraManagementHttpTest.Host.start(settings, Map.of())) {
                var port = host.server().mountPort("/").unwrap();

                assertThat(getOn(port, "/health", "").statusCode()).as(basePath).isEqualTo(200);
                assertThat(getOn(port, "/", KEY).statusCode()).as(basePath).isEqualTo(404);
            }
        }
    }

    private static HttpResponse<String> getOn(int port, String path, String key) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5));

        if (!key.isEmpty()) { builder.header("X-API-Key", key); }

        return CLIENT.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    // F1 (#3724 review): a malformed or empty media range is answered by negotiation, never 500: 406 when nothing in
    // the header admits a JSON type, the default type when the header is empty.
    @Test
    void accept_malformedMediaRange_answers406() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            for (var accept : List.of(";", ";;", "/", "text/", "*/;q=", ",", "text/html,;")) {
                assertThat(host.get("/actuator/health", "Accept", accept).statusCode()).as("Accept '" + accept + "'").isEqualTo(406);
            }
        }
    }

    @Test
    void accept_emptyHeader_answersWithTheDefaultType() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            assertThat(host.get("/actuator/health", "Accept", "").statusCode()).isEqualTo(200);
        }
    }

    @Test
    void accept_malformedRangeBesideAMatch_matches() throws Exception {
        try (var host = TerraManagementHttpTest.Host.start(Map.of(), Map.of())) {
            assertThat(host.get("/actuator/health", "Accept", ";, application/json").statusCode()).isEqualTo(200);
        }
    }
}
