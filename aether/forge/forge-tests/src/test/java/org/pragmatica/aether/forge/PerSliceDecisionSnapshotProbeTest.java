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
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

import org.pragmatica.aether.ember.EmberCluster;

/// #422/#423/#425 full-stack proof that per-slice metric attribution is LIVE end-to-end. Two
/// distinct slices are deployed and the leader's `GET /api/controller/decisions` snapshot is read:
/// each slice must appear as its OWN per-artifact decision record (the aggregator → leader → snapshot
/// carrier path is per-slice, not one merged cluster-wide record), and neither idle slice may be
/// scaled up (no cross-slice mis-attribution).
///
/// The strong load-attribution property (load on slice B never scales the idle slice A) is proven
/// deterministically in-JVM by `ControlLoopContextAttributionTest`; this probe proves the machinery
/// is wired live in a real multi-node cluster.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerSliceDecisionSnapshotProbeTest {
    private static final int BASE_PORT = 6600;
    private static final int BASE_MGMT_PORT = 6700;
    private static final int BASE_APP_HTTP_PORT = 6800;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofSeconds(1);
    private static final String BLUEPRINT_ID = "forge.test:per-slice-attribution:1.0.0";
    private static final String SLICE_A = TestArtifacts.ECHO_SLICE;
    private static final String SLICE_B = TestArtifacts.VERSIONED_SLICE;
    private static final String SLICE_A_FRAGMENT = "echo-slice-echo-service";
    private static final String SLICE_B_FRAGMENT = "versioned-slice-versioned-echo";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "psa");
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> cluster.currentLeader().isPresent());
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(this::allNodesHealthy);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await();
        }
    }

    @Test
    void decisionsSnapshot_twoDeployedSlices_tracksEachArtifactSeparatelyAndScalesNeitherIdle() {
        deployTwoSlices();
        awaitBothSlicesActive();
        awaitBothArtifactsInDecisionSnapshot();

        var decisions = getDecisions();

        assertThat(decisions).describedAs("per-slice attribution: slice A tracked separately")
                             .contains(SLICE_A_FRAGMENT);
        assertThat(decisions).describedAs("per-slice attribution: slice B tracked separately")
                             .contains(SLICE_B_FRAGMENT);
        assertThat(decisions).describedAs("idle slices must not be scaled up (no mis-attribution)")
                             .doesNotContain("\"outcome\":\"SCALED_UP\"");
    }

    private void deployTwoSlices() {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 1

            [[slices]]
            artifact = "%s"
            instances = 1
            """.formatted(BLUEPRINT_ID, SLICE_A, SLICE_B);
        var response = postBlueprint(leaderPort(), blueprint);

        assertThat(response).describedAs("Deployment response").doesNotContain("\"error\"").contains("\"status\":\"applied\"");
    }

    private void awaitBothSlicesActive() {
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> {
            var slices = getSlices();

            return slices.contains(SLICE_A_FRAGMENT) && slices.contains(SLICE_B_FRAGMENT);
        });
    }

    private void awaitBothArtifactsInDecisionSnapshot() {
        await().atMost(WAIT_TIMEOUT).pollInterval(POLL_INTERVAL).until(() -> {
            var decisions = getDecisions();

            return decisions.contains(SLICE_A_FRAGMENT) && decisions.contains(SLICE_B_FRAGMENT);
        });
    }

    private String getDecisions() {
        return httpGet(leaderPort(), "/api/controller/decisions");
    }

    private String getSlices() {
        return httpGet(leaderPort(), "/api/slices/status");
    }

    private int leaderPort() {
        return cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
    }

    private String postBlueprint(int port, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/blueprints"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request).await().map(HttpResult::body).or(ERROR_FALLBACK);
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();

        return http.sendString(request).await().map(HttpResult::body).or(ERROR_FALLBACK);
    }

    private boolean allNodesHealthy() {
        return cluster.status().nodes().stream().allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();

        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }
}
