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
import org.pragmatica.lang.Result;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Proves #198 §8.2 deprecation/sunset/successor response headers and §11.3 version-registry
/// introspection reach the wire.
///
/// The deployed two-version slice declares `[v1] deprecated = true, sunset = "2026-12-31"` and a
/// non-deprecated `[v2]` (with `defaultIfMissing = true`). A path-mode request to `v1` MUST carry
/// `Deprecation: true`, `Sunset: Thu, 31 Dec 2026 00:00:00 GMT`, and a `Link` header naming `v2` as
/// the successor; a request to the non-deprecated `v2` MUST carry none of them. The management
/// `GET /api/versions` endpoint MUST report the slice's registry: `v1` deprecated+sunset and `v2`
/// defaultIfMissing.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SliceVersionLifecycleTest {
    private static final int BASE_PORT = 12500;
    private static final int BASE_MGMT_PORT = 12600;
    private static final int BASE_APP_HTTP_PORT = 12700;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = TestArtifacts.VERSIONED_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:slice-version-lifecycle:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";
    private static final long ITEM_ID = 7L;

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "svl");
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
    void deprecatedVersion_carriesDeprecationSunsetAndSuccessorHeaders() {
        get(appPort(), "/api/orders/v1/" + ITEM_ID).onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.header("Deprecation").or("")).isEqualTo("true");
            assertThat(result.header("Sunset").or("")).isEqualTo("Thu, 31 Dec 2026 00:00:00 GMT");
            assertThat(result.header("Link").or(""))
                .isEqualTo("</api/orders/v2/" + ITEM_ID + ">; rel=\"successor-version\"");
        });
    }

    @Test
    void currentVersion_carriesNoLifecycleHeaders() {
        get(appPort(), "/api/orders/v2/" + ITEM_ID).onSuccess(result -> {
            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.header("Deprecation").isPresent()).isFalse();
            assertThat(result.header("Sunset").isPresent()).isFalse();
            assertThat(result.header("Link").isPresent()).isFalse();
        });
    }

    @Test
    void versionsEndpoint_reportsDeployedSliceRegistry() {
        get(anyMgmtPort(), "/api/v1/versions").onSuccess(result -> {
            var body = result.body();

            assertThat(body).doesNotContain("\"error\"");
            assertThat(body).contains("\"apiPrefix\":\"/api/orders\"");
            assertThat(body).contains("\"version\":1");
            assertThat(body).contains("\"deprecated\":true");
            assertThat(body).contains("\"sunset\":\"2026-12-31\"");
            assertThat(body).contains("\"version\":2");
            assertThat(body).contains("\"defaultIfMissing\":true");
        });
    }

    private Result<HttpResult<String>> get(int port, String path) {
        return http.sendString(getRequest(port, path))
                   .await()
                   .onFailure(cause -> {
                       throw new AssertionError("request to " + path + " failed: " + cause.message());
                   });
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

    private int anyMgmtPort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }
}
