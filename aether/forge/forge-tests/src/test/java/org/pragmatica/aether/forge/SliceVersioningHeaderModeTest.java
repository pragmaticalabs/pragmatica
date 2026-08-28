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
import org.pragmatica.aether.config.ApiVersioningDetection;
import org.pragmatica.http.HttpOperations;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Proves #198 §7 HEADER-mode versioning reaches the wire: the SAME compiled two-version slices
/// deployed earlier in path mode are here deployed into a cluster configured with
/// `apiVersioningDetection = "header"`, and version selection happens via the `API-Version` request
/// header at one bare path (no `/v{N}/`):
///
///   - `GET /api/orders/{id}` + `API-Version: 1` → v1 response;
///   - same path + `API-Version: 2` → v2 response;
///   - same path, no header → latest (v2, `defaultIfMissing`);
///   - unknown version → 404;
///   - a strict slice (`requireVersionHeader = true`), no header → 400 naming the header.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceVersioningHeaderModeTest {
    private static final int BASE_PORT = 6750;
    private static final int BASE_MGMT_PORT = 6850;
    private static final int BASE_APP_HTTP_PORT = 6950;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String VERSIONED_SLICE = TestArtifacts.VERSIONED_SLICE;
    private static final String STRICT_SLICE = TestArtifacts.STRICT_VERSIONED_SLICE;
    private static final String ORDERS_BLUEPRINT_ID = "forge.test:slice-versioning-header-orders:1.0.0";
    private static final String STRICT_BLUEPRINT_ID = "forge.test:slice-versioning-header-strict:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final String VERSION_HEADER = "API-Version";
    private static final long ITEM_ID = 7L;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "smh");
        cluster.withApiVersioningDetection(ApiVersioningDetection.HEADER, VERSION_HEADER);
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        deploy(ORDERS_BLUEPRINT_ID, VERSIONED_SLICE);
        deploy(STRICT_BLUEPRINT_ID, STRICT_SLICE);
        awaitRouteReady("/api/orders/" + ITEM_ID, "1");
        awaitRouteReady("/api/strict/" + ITEM_ID, "1");
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Test
    void versionOneHeader_selectsVersionOneAtBarePath() {
        send("/api/orders/" + ITEM_ID, "1").onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("\"version\":\"v1\"");
            assertThat(result.body()).contains("v1-echo-" + ITEM_ID);
        });
    }

    @Test
    void versionTwoHeader_selectsVersionTwoAtBarePath() {
        send("/api/orders/" + ITEM_ID, "2").onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("\"version\":\"v2\"");
            assertThat(result.body()).contains("v2-echo-" + ITEM_ID);
        });
    }

    @Test
    void noHeader_selectsLatestVersion_whenDefaultIfMissing() {
        sendNoHeader("/api/orders/" + ITEM_ID).onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            // v2 is declared defaultIfMissing; latest-wins also resolves to v2.
            assertThat(result.body()).contains("\"version\":\"v2\"");
        });
    }

    @Test
    void unknownVersionHeader_returnsNotFound() {
        send("/api/orders/" + ITEM_ID, "9").onSuccess(result -> assertThat(result.statusCode()).isEqualTo(404));
    }

    @Test
    void strictSlice_noHeader_returnsBadRequestNamingHeader() {
        sendNoHeader("/api/strict/" + ITEM_ID).onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(400);
            assertThat(result.body()).contains(VERSION_HEADER);
        });
    }

    @Test
    void strictSlice_withVersionHeader_dispatchesToThatVersion() {
        send("/api/strict/" + ITEM_ID, "1").onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("strict-v1-echo-" + ITEM_ID);
        });
    }

    private void awaitRouteReady(String path, String version) {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> routeServes(path, version));
    }

    private boolean routeServes(String path, String version) {
        if (cluster.getAvailableAppHttpPorts().isEmpty()) {
            return false;
        }
        return send(path, version).map(result -> result.statusCode() == 200)
                                  .or(false);
    }

    private Result<HttpResult<String>> send(String path, String version) {
        return http.sendString(versionedRequest(appPort(), path, version))
                   .await()
                   .onFailure(cause -> {
                       throw new AssertionError("request " + path + " (v" + version + ") failed: " + cause.message());
                   });
    }

    private Result<HttpResult<String>> sendNoHeader(String path) {
        return http.sendString(getRequest(appPort(), path))
                   .await()
                   .onFailure(cause -> {
                       throw new AssertionError("request " + path + " (no header) failed: " + cause.message());
                   });
    }

    private int appPort() {
        var ports = cluster.getAvailableAppHttpPorts();
        assertThat(ports).describedAs("available app HTTP ports").isNotEmpty();
        return ports.getFirst();
    }

    private HttpRequest versionedRequest(int port, String path, String version) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + path))
                          .header(VERSION_HEADER, version)
                          .GET()
                          .timeout(Duration.ofSeconds(10))
                          .build();
    }

    private HttpRequest getRequest(int port, String path) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + path))
                          .GET()
                          .timeout(Duration.ofSeconds(10))
                          .build();
    }

    private void deploy(String blueprintId, String artifact) {
        var deployResponse = postBlueprint(blueprintId, artifact);
        assertThat(deployResponse)
            .describedAs("Deployment response for " + artifact)
            .doesNotContain("\"error\"")
            .contains("\"status\":\"applied\"");
    }

    private String postBlueprint(String blueprintId, String artifact) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 1
            """.formatted(blueprintId, artifact);
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

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }
}
