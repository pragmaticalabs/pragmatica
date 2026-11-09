// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.forge;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberConfig;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// #2983 — a Forge cluster run from a sibling `aether.toml` with `[app-http] security_mode = "jwt"`, `jwt_algorithm = "HS256"`
/// and `auth_schemes = ["Token"]` and NO API key: the configuration goes through the real `ForgeServer.applyAppHttpSecurity`
/// into a real three-node Ember cluster running the echo slice, and ordinary HTTP requests are made against every node's app port.
///
/// Before the fix Forge applied `[app-http]` only when API keys existed and Ember dropped the JWT config, so a valid token was
/// refused with 401 "no security mode is configured". The in-run controls: a valid token is served (the route is live and
/// the config reached the node), a forged token and a missing token are refused (the node does check), and the public route
/// answers without any credential.
///
/// The HS256 secret reaches Forge through `AETHER_JWT_SECRET` exactly as on a production node; the failsafe configuration of this
/// module sets it to [#SECRET]. The management plane is declared `none` so the test can deploy the blueprint without a key.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ForgeJwtAppHttpForgeTest {
    private static final int NODES = 3;
    private static final int BASE_PORT = 29500;
    private static final int BASE_MGMT_PORT = 29600;
    private static final int BASE_APP_HTTP_PORT = 29700;
    private static final String SECRET = "a-test-only-shared-secret-of-32-bytes!";
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String PROTECTED_PATH = "/echo/jwt-probe";
    private static final String PUBLIC_PATH = "/ping";
    private static final String SIBLING = """
        [cluster]
        environment = "docker"
        nodes = 3

        [app-http]
        enabled = true
        security_mode = "jwt"
        jwt_algorithm = "HS256"
        auth_schemes = ["Token"]

        [management]
        security_mode = "none"
        """;
    private static final String BLUEPRINT = """
        id = "forge.test:forge-jwt-2983:1.0.0"

        [[slices]]
        artifact = "%s"
        instances = %d

        [security]
        override_policy = "full"

        [security.overrides]
        "GET /ping" = "public"
        """.formatted(TestArtifacts.ECHO_SLICE, NODES);
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    @TempDir
    Path dir;

    private EmberCluster cluster;
    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeAll
    void setUp() throws Exception {
        cluster = emberCluster(NODES, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "fjwt");
        Files.writeString(dir.resolve("aether.toml"), SIBLING);

        var base = EmberConfig.DEFAULT;
        var startup = new StartupConfig(Option.some(dir.resolve("forge.toml")), Option.none(), Option.none(), false, 8888, base.nodes(), 1000);
        var exits = new ArrayList<Integer>();

        new ForgeServer(startup, base, JdkHttpOperations.jdkHttpOperations()).applyAppHttpSecurity(cluster, exits::add);
        assertThat(exits).as("the sibling loads: the HS256 secret reaches Forge through AETHER_JWT_SECRET").isEmpty();
        LifecycleAwait.settled("cluster start in setUp()", cluster, cluster.start());
        await().alias("leader elected").atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        deployEchoSlice();
        await().alias("a valid token is served on every node: the route is live and the JWT config reached it")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> appPorts().stream().allMatch(port -> status(port, PROTECTED_PATH, "Token " + token(SECRET)) == 200));
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            LifecycleAwait.bestEffort("cluster stop in tearDown()", cluster, cluster.stop());
        }
    }

    @Test
    void validHs256Token_isServedOnEveryNode() {
        appPorts().forEach(port -> assertThat(send(port, PROTECTED_PATH, "Token " + token(SECRET)).statusCode()).as("port %d", port).isEqualTo(200));
    }

    @Test
    void forgedToken_isRefused401WithTheTokenChallenge() {
        appPorts().forEach(port -> assertInvalidToken(send(port, PROTECTED_PATH, "Token " + token("another-shared-secret-of-32-bytes!!!")), port));
    }

    @Test
    void garbageToken_isRefused401WithTheTokenChallenge() {
        appPorts().forEach(port -> assertInvalidToken(send(port, PROTECTED_PATH, "Token not-a-jwt"), port));
    }

    @Test
    void missingToken_isRefused401WithoutAnInvalidTokenError() {
        appPorts().forEach(port -> assertChallengeWithoutError(send(port, PROTECTED_PATH, null), port));
    }

    private static void assertChallengeWithoutError(HttpResponse<String> response, int port) {
        assertThat(response.statusCode()).as("port %d", port).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).as("port %d", port).startsWith("Token ").doesNotContain("error=");
    }

    @Test
    void publicRoute_staysOpenWithoutACredential() {
        appPorts().forEach(port -> assertThat(send(port, PUBLIC_PATH, null).statusCode()).as("port %d", port).isEqualTo(200));
    }

    private static void assertInvalidToken(HttpResponse<String> response, int port) {
        assertThat(response.statusCode()).as("port %d", port).isEqualTo(401);
        assertThat(response.headers().firstValue("WWW-Authenticate").orElse("")).as("port %d", port)
                                                                                .contains("Token ")
                                                                                .contains("error=\"invalid_token\"");
    }

    private List<Integer> appPorts() {
        return cluster.status().nodes().stream().map(node -> BASE_APP_HTTP_PORT + (node.port() - BASE_PORT)).toList();
    }

    private void deployEchoSlice() {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + cluster.getLeaderManagementPort().unwrap() + "/api/v1/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(BLUEPRINT))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        await().alias("blueprint applied")
               .atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> exchange(request).map(response -> response.body().contains("\"status\":\"applied\"")).orElse(false));
    }

    private int status(int port, String path, String authorization) {
        return exchange(request(port, path, authorization)).map(HttpResponse::statusCode).orElse(-1);
    }

    private HttpResponse<String> send(int port, String path, String authorization) {
        return exchange(request(port, path, authorization)).orElseThrow(() -> new AssertionError("no answer from app port " + port));
    }

    private static HttpRequest request(int port, String path, String authorization) {
        var builder = HttpRequest.newBuilder().uri(URI.create("http://localhost:" + port + path)).GET().timeout(Duration.ofSeconds(10));

        return (authorization == null ? builder : builder.header("Authorization", authorization)).build();
    }

    private Optional<HttpResponse<String>> exchange(HttpRequest request) {
        try {
            return Optional.of(http.send(request, HttpResponse.BodyHandlers.ofString()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return Optional.empty();
        } catch (IOException e) {
            return Optional.empty();
        }
    }

    private static String token(String secret) {
        var signingInput = B64.encodeToString("{\"alg\":\"HS256\",\"typ\":\"JWT\"}".getBytes(StandardCharsets.UTF_8)) + "."
                           + B64.encodeToString(("{\"sub\":\"forge-2983\",\"exp\":" + (System.currentTimeMillis() / 1000 + 3600) + "}").getBytes(StandardCharsets.UTF_8));

        return signingInput + "." + B64.encodeToString(hmac(secret, signingInput));
    }

    private static byte[] hmac(String secret, String data) {
        try {
            var mac = Mac.getInstance("HmacSHA256");

            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));

            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new AssertionError(e);
        }
    }
}
