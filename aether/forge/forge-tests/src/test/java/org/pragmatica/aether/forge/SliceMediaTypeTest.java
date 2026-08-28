// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Proves the #339 `produces` media types reach the wire: a deployed slice route declared with
/// `produces = "text/csv"` returns the `text/csv` Content-Type, and a route declared with
/// `produces = "application/octet-stream"` returns the raw bytes VERBATIM (never JSON-wrapped)
/// under the binary Content-Type.
///
/// The echo-slice carries two such routes (`GET /csv/{label}`, `GET /binary/{seed}`); the test
/// deploys it into an in-JVM Ember cluster and invokes the routes on the application HTTP port.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceMediaTypeTest {
    private static final int BASE_PORT = 6300;
    private static final int BASE_MGMT_PORT = 6400;
    private static final int BASE_APP_HTTP_PORT = 6500;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = TestArtifacts.ECHO_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:slice-media-type:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final int BINARY_SEED = 65;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "smt");
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        deployEchoSlice();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Test
    void csvRoute_returnsTextCsvContentType() {
        var port = appPort();
        var response = http.sendString(getRequest(port, "/csv/widget"))
                           .await()
                           .onFailure(cause -> {
                               throw new AssertionError("CSV request failed: " + cause.message());
                           });

        response.onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.header("Content-Type").or("")).startsWith("text/csv");
            assertThat(result.body()).isEqualTo("label,length\nwidget,6");
        });
    }

    @Test
    void binaryRoute_returnsVerbatimBytesUnderOctetStream() {
        var port = appPort();
        var expected = new byte[]{(byte) BINARY_SEED,
                                  (byte) (BINARY_SEED + 1),
                                  (byte) (BINARY_SEED + 2),
                                  (byte) (BINARY_SEED + 3)};

        var response = http.sendBytes(getRequest(port, "/binary/" + BINARY_SEED))
                           .await()
                           .onFailure(cause -> {
                               throw new AssertionError("Binary request failed: " + cause.message());
                           });

        response.onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.header("Content-Type").or("")).startsWith("application/octet-stream");
            // Verbatim passthrough: the exact bytes, NOT a JSON-encoded array/base64 string.
            assertThat(result.body()).isEqualTo(expected);
        });
    }

    private void deployEchoSlice() {
        var deployResponse = deploy(TEST_ARTIFACT);
        assertThat(deployResponse)
            .describedAs("Deployment response")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed()) {
                       throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                   }
               })
               .until(this::routesReady);
    }

    private boolean routesReady() {
        return getSlices().contains("echo-slice") && !cluster.getAvailableAppHttpPorts().isEmpty();
    }

    private int appPort() {
        var ports = cluster.getAvailableAppHttpPorts();
        assertThat(ports).describedAs("available app HTTP ports").isNotEmpty();
        return ports.getFirst();
    }

    private HttpRequest getRequest(int port, String path) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + path))
                          .GET()
                          .timeout(Duration.ofSeconds(10))
                          .build();
    }

    private String deploy(String artifact) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 1
            """.formatted(BLUEPRINT_ID, artifact);
        var leaderPort = cluster.getLeaderManagementPort().or(anyMgmtPort());
        return postBlueprintWithRetry(leaderPort, blueprint);
    }

    private String postBlueprintWithRetry(int port, String body) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = httpRequestBlueprint(port, body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }
            if (attempt < 3) {
                sleepQuietly();
            }
        }
        return lastResponse;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private String httpRequestBlueprint(int port, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/v1/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private boolean sliceHasFailed() {
        return cluster.slicesStatus()
                      .stream()
                      .anyMatch(s -> s.artifact().equals(TEST_ARTIFACT) && s.state().equals("FAILED"));
    }

    private String getSlices() {
        return httpGet(anyMgmtPort(), "/api/v1/slices/status");
    }

    private String httpGet(int port, String path) {
        return http.sendString(getRequest(port, path))
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }
}
