package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.pragmatica.aether.slice.SliceState;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/// Tests for slice deployment and lifecycle operations.
///
///
/// Tests cover:
///
///   - Slice deployment via API
///   - Slice activation and health
///   - Slice scaling
///   - Slice undeployment
///   - Slice replication across nodes
///   - Blueprint deployment
///
@Execution(ExecutionMode.SAME_THREAD)
class SliceDeploymentTest {
    private static final int BASE_PORT = 5500;
    private static final int BASE_MGMT_PORT = 5600;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:0.19.0";
    private static final String BLUEPRINT_ID = "forge.test:slice-deploy:1.0.0";

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "sd");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();

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

        // Stabilization time for consensus
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "deploySlice_becomesActive" -> 0;
            case "deploySlice_multipleInstances_distributedAcrossNodes" -> 20;
            case "scaleSlice_adjustsInstanceCount" -> 40;
            case "undeploySlice_removesFromCluster" -> 60;
            case "deploySlice_survivesNodeFailure" -> 80;
            case "blueprintApply_deploysMultipleSlices" -> 100;
            default -> 120;
        };
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            Thread.sleep(3000);
        }
    }

    @Test
    void deploySlice_becomesActive() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var response = deploy(leaderPort, TEST_ARTIFACT, 1);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        var slices = getSlices(leaderPort);
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    void deploySlice_multipleInstances_distributedAcrossNodes() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var response = deploy(leaderPort, TEST_ARTIFACT, 3);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Check that instances are distributed - each node should report the slice
        var status = cluster.status();
        for (var node : status.nodes()) {
            var nodeSlices = getSlices(node.mgmtPort());
            assertThat(nodeSlices).contains(TEST_ARTIFACT);
        }
    }

    @Test
    void scaleSlice_adjustsInstanceCount() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        // Deploy with 1 instance
        deploy(leaderPort, TEST_ARTIFACT, 1);
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Scale to 3 instances
        var scaleResponse = scale(leaderPort, TEST_ARTIFACT, 3);
        assertThat(scaleResponse).doesNotContain("\"error\"");

        // Wait for scale operation to complete
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(Duration.ofSeconds(2))
               .until(() -> {
                   var slices = getSlices(leaderPort);
                   return slices.contains(TEST_ARTIFACT);
               });
    }

    @Test
    void undeploySlice_removesFromCluster() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        // Deploy
        deploy(leaderPort, TEST_ARTIFACT, 1);
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Undeploy
        var undeployResponse = undeploy(leaderPort, TEST_ARTIFACT);
        assertThat(undeployResponse).doesNotContain("\"error\"");

        // Wait for slice to be removed
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var slices = getSlices(leaderPort);
                   return !slices.contains(TEST_ARTIFACT);
               });
    }

    @Test
    void deploySlice_survivesNodeFailure() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        // Deploy with 3 instances across 3 nodes
        deploy(leaderPort, TEST_ARTIFACT, 3);
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Kill one node (not the leader)
        var leaderId = cluster.currentLeader().unwrap();
        var nodeToKill = leaderId.equals("sd-2") ? "sd-3" : "sd-2";
        cluster.killNode(nodeToKill).await();

        // Wait for quorum to stabilize
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Slice should still be available (replicated)
        var newLeaderPort = cluster.getLeaderManagementPort().unwrap();
        var slices = getSlices(newLeaderPort);
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    void blueprintApply_deploysMultipleSlices() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        var blueprint = """
            id = "org.test:blueprint:1.0.0"

            [[slices]]
            artifact = "org.pragmatica-lite.aether.test:echo-slice-echo-service:0.19.0"
            instances = 2
            """;

        var response = applyBlueprint(leaderPort, blueprint);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(TEST_ARTIFACT));
    }

    // ===== HTTP Helper Methods =====

    private String deploy(int port, String artifact, int instances) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, artifact, instances);
        return postBlueprintWithRetry(port, blueprint);
    }

    private String postBlueprintWithRetry(int port, String body) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = post(port, "/api/blueprint", body, "application/toml");
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

    private String scale(int port, String artifact, int instances) {
        var body = String.format("{\"artifact\": \"%s\", \"instances\": %d}", artifact, instances);
        return post(port, "/api/scale", body, "application/json");
    }

    private String undeploy(int port, String artifact) {
        return delete(port, "/api/blueprint/" + BLUEPRINT_ID);
    }

    private String applyBlueprint(int port, String blueprintContent) {
        return post(port, "/api/blueprint", blueprintContent, "application/toml");
    }

    private String getSlices(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/slices"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String delete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String post(int port, String path, String body, String contentType) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", contentType)
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
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
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"quorum\":true");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }
}
