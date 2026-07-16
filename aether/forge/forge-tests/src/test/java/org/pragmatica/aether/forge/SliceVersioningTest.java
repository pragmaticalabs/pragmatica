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

/// Proves #198 API path-mode versioning reaches the wire: a deployed two-version slice serves
/// BOTH `GET {api.prefix}/v1/{id}` and `GET {api.prefix}/v2/{id}`, and each path returns its
/// version-specific response (`getV1` vs `getV2` resolved from the same `get` bind key, D8).
///
/// The versioned-slice declares `[api] prefix = "/api/orders"` plus `[v1.routes] get` and
/// `[v2.routes] get`; the test deploys it into an in-JVM Ember cluster and invokes both versioned
/// routes on the application HTTP port.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceVersioningTest {
    private static final int BASE_PORT = 6700;
    private static final int BASE_MGMT_PORT = 6800;
    private static final int BASE_APP_HTTP_PORT = 6900;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = TestArtifacts.VERSIONED_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:slice-versioning:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final long ITEM_ID = 7L;

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

        deployVersionedSlice();
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Test
    void v1Route_servesVersionOneResponse() {
        var response = http.sendString(getRequest(appPort(), "/api/orders/v1/" + ITEM_ID))
                           .await()
                           .onFailure(cause -> {
                               throw new AssertionError("v1 request failed: " + cause.message());
                           });

        response.onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("\"version\":\"v1\"");
            assertThat(result.body()).contains("v1-echo-" + ITEM_ID);
        });
    }

    @Test
    void v2Route_servesVersionTwoResponse() {
        var response = http.sendString(getRequest(appPort(), "/api/orders/v2/" + ITEM_ID))
                           .await()
                           .onFailure(cause -> {
                               throw new AssertionError("v2 request failed: " + cause.message());
                           });

        response.onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.body()).contains("\"version\":\"v2\"");
            assertThat(result.body()).contains("v2-echo-" + ITEM_ID);
        });
    }

    @Test
    void bothVersionsServeDistinctResponsesFromOneBindKey() {
        var v1 = http.sendString(getRequest(appPort(), "/api/orders/v1/" + ITEM_ID))
                     .await()
                     .onFailure(cause -> {
                         throw new AssertionError("v1 request failed: " + cause.message());
                     });
        var v2 = http.sendString(getRequest(appPort(), "/api/orders/v2/" + ITEM_ID))
                     .await()
                     .onFailure(cause -> {
                         throw new AssertionError("v2 request failed: " + cause.message());
                     });

        v1.onSuccess(r1 -> v2.onSuccess(r2 -> {
            assertThat(r1.statusCode()).isEqualTo(200);
            assertThat(r2.statusCode()).isEqualTo(200);
            // Same `get` bind key, different version path → different version-specific handler.
            assertThat(r1.body()).isNotEqualTo(r2.body());
            assertThat(r1.body()).contains("\"version\":\"v1\"");
            assertThat(r2.body()).contains("\"version\":\"v2\"");
        }));
    }

    private void deployVersionedSlice() {
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
               .until(this::v1RouteServes);
    }

    private boolean v1RouteServes() {
        if (cluster.getAvailableAppHttpPorts().isEmpty()) {
            return false;
        }
        return http.sendString(getRequest(appPort(), "/api/orders/v1/" + ITEM_ID))
                   .await()
                   .map(result -> result.statusCode() == 200)
                   .or(false);
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
                                 .uri(URI.create("http://localhost:" + port + "/api/blueprints"))
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

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }
}
