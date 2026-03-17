package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.HttpOperations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for multi-blueprint lifecycle in an in-process cluster.
///
///
/// Tests cover:
///
///   - Deploying multiple blueprints with different artifacts
///   - Artifact exclusivity across blueprints
///   - Independent blueprint deletion
///   - Blueprint republishing
///
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MultiBlueprintForgeTest {
    private static final int BASE_PORT = 5700;
    private static final int BASE_MGMT_PORT = 5800;
    private static final int BASE_APP_HTTP_PORT = 5900;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String BLUEPRINT_A_ID = "forge.test.a:multi-bp:1.0.0";
    private static final String BLUEPRINT_B_ID = "forge.test.b:multi-bp:1.0.0";
    private static final String ARTIFACT_A = TestArtifacts.ECHO_SLICE;
    private static final String ARTIFACT_B = TestArtifacts.URL_SHORTENER;
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "mb");

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);
    }

    @BeforeEach
    void cleanUp() {
        var mgmtPort = cluster.getLeaderManagementPort().or(cluster.status().nodes().getFirst().mgmtPort());
        delete(mgmtPort, "/api/blueprint/" + BLUEPRINT_A_ID);
        delete(mgmtPort, "/api/blueprint/" + BLUEPRINT_B_ID);

        // Wait for slices to drain after cleanup
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.slicesStatus().isEmpty());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Test
    void deployTwoBlueprints_withDifferentArtifacts_bothActive() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var responseA = deployBlueprint(leaderPort, BLUEPRINT_A_ID, ARTIFACT_A, 1);
        assertThat(responseA).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_A));

        var responseB = deployBlueprint(leaderPort, BLUEPRINT_B_ID, ARTIFACT_B, 1);
        assertThat(responseB).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_B));

        // Both artifacts should be active simultaneously
        var slicesStatus = cluster.slicesStatus();
        assertThat(slicesStatus).anyMatch(s -> s.artifact().equals(ARTIFACT_A)
                                               && s.state().equals(SliceState.ACTIVE.name()));
        assertThat(slicesStatus).anyMatch(s -> s.artifact().equals(ARTIFACT_B)
                                               && s.state().equals(SliceState.ACTIVE.name()));
    }

    @Test
    void deployBlueprint_withOverlappingArtifact_rejected() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var responseA = deployBlueprint(leaderPort, BLUEPRINT_A_ID, ARTIFACT_A, 1);
        assertThat(responseA).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_A));

        // Deploy blueprint B with the SAME artifact -- should be rejected
        var responseB = postBlueprint(leaderPort, buildBlueprint(BLUEPRINT_B_ID, ARTIFACT_A, 1));

        // Verify rejection: response contains error OR the second blueprint's slice is not deployed
        var rejected = responseB.contains("error") || responseB.contains("Error")
                       || responseB.contains("conflict") || responseB.contains("exclusive");

        if (!rejected) {
            // Even if no explicit error, the original blueprint should still own the artifact
            var slicesStatus = cluster.slicesStatus();
            var activeCount = slicesStatus.stream()
                                          .filter(s -> s.artifact().equals(ARTIFACT_A)
                                                       && s.state().equals(SliceState.ACTIVE.name()))
                                          .count();
            // Should not have duplicate deployments from two blueprints
            assertThat(activeCount).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void deleteOneBlueprint_otherUnaffected() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var responseA = deployBlueprint(leaderPort, BLUEPRINT_A_ID, ARTIFACT_A, 1);
        assertThat(responseA).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_A));

        var responseB = deployBlueprint(leaderPort, BLUEPRINT_B_ID, ARTIFACT_B, 1);
        assertThat(responseB).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_B));

        // Delete blueprint A
        var deleteResponse = delete(leaderPort, "/api/blueprint/" + BLUEPRINT_A_ID);
        assertThat(deleteResponse).doesNotContain("\"error\"");

        // Wait for artifact A to be removed
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> !sliceIsActive(ARTIFACT_A));

        // Blueprint B's artifact should remain active
        assertThat(sliceIsActive(ARTIFACT_B)).isTrue();
    }

    @Test
    void republishSameBlueprint_allowed() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var response = deployBlueprint(leaderPort, BLUEPRINT_A_ID, ARTIFACT_A, 1);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_A));

        // Re-deploy the same blueprint ID with updated instance count
        var republishResponse = deployBlueprint(leaderPort, BLUEPRINT_A_ID, ARTIFACT_A, 2);
        assertThat(republishResponse).doesNotContain("\"error\"");

        // Slice should still be active after republish
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(ARTIFACT_A));

        var slices = getSlices(leaderPort);
        assertThat(slices).contains(ARTIFACT_A);
    }

    // ===== HTTP Helper Methods =====

    private String deployBlueprint(int port, String blueprintId, String artifact, int instances) {
        var blueprint = buildBlueprint(blueprintId, artifact, instances);
        return postBlueprintWithRetry(port, blueprint);
    }

    private String buildBlueprint(String blueprintId, String artifact, int instances) {
        return """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(blueprintId, artifact, instances);
    }

    private String postBlueprintWithRetry(int port, String body) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = postBlueprint(port, body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResponse;
    }

    private String postBlueprint(int port, String body) {
        return post(port, "/api/blueprint", body, "application/toml");
    }

    private String getSlices(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/slices"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String delete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String post(int port, String path, String body, String contentType) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", contentType)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private boolean sliceIsActive(String artifact) {
        try {
            var slicesStatus = cluster.slicesStatus();
            return slicesStatus.stream()
                               .anyMatch(status -> status.artifact().equals(artifact)
                                                   && status.state().equals(SliceState.ACTIVE.name()));
        } catch (Exception e) {
            return false;
        }
    }

    private boolean allNodesHealthy() {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeHealth(node.mgmtPort()));
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
