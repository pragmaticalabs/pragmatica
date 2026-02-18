package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/// Tests for cluster bootstrap and recovery scenarios.
///
///
/// Tests cover:
///
///   - Single node restart and recovery
///   - State persistence across restarts
///   - Rolling restart behavior
///   - Multiple node restarts
///
@Execution(ExecutionMode.SAME_THREAD)
class BootstrapTest {
    private static final int BASE_PORT = 7500;
    private static final int BASE_MGMT_PORT = 7600;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(20);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:0.16.0";

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        // Use method-specific port offset to avoid port conflicts between tests
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "bt");
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
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "nodeRestart_rejoinsCluster" -> 0;
            case "nodeRestart_recoversState" -> 20;
            case "manualRollingRestart_maintainsAvailability" -> 40;
            case "multipleNodeRestarts_clusterRemainsFunctional" -> 60;
            default -> 80;
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
    @Timeout(120)
    void nodeRestart_rejoinsCluster() {
        // Kill bt-2
        cluster.killNode("bt-2")
               .await();

        // Wait for cluster to stabilize with 2 nodes
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 2);

        // Cluster should still have quorum with 2 out of 3 nodes
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Add a new node to replace the killed one
        cluster.addNode()
               .await();

        // Wait for all 3 nodes to be running
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 3);

        // Cluster should be fully formed again
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(() -> cluster.currentLeader().isPresent());

        // Verify all nodes are healthy
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(this::allNodesHealthy);

        for (var node : cluster.status().nodes()) {
            failFastIfHttpNotReady(node.mgmtPort());
            var health = getNodeHealth(node.mgmtPort());
            assertThat(health).doesNotContain("\"error\"");
        }
    }

    @Test
    @Timeout(120)
    void nodeRestart_recoversState() {
        // Wait for all nodes to be healthy before deploying
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(this::allNodesHealthy);

        // Deploy a slice
        var leaderPort = cluster.getLeaderManagementPort()
                                .unwrap();
        deploySlice(leaderPort, TEST_ARTIFACT, 1);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   var slices = getSlices(leaderPort);
                   if (slices.contains("\"error\"")) {
                       throw new AssertionError("Slice query failed: " + slices);
                   }
               })
               .until(() -> {
                   var slices = getSlices(leaderPort);
                   return slices.contains("echo-slice");
               });

        // Kill a node
        cluster.killNode("bt-3")
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 2);

        // Add a new node to replace the killed one
        cluster.addNode()
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 3);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(() -> cluster.currentLeader().isPresent());

        // Slice should still be visible (state recovered from consensus)
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   failFastIfNodesDied(3);
                   var currentLeaderPort = cluster.getLeaderManagementPort()
                                                  .or(leaderPort);
                   var slices = getSlices(currentLeaderPort);
                   if (slices.contains("\"error\"")) {
                       throw new AssertionError("Slice query failed: " + slices);
                   }
               })
               .until(() -> {
                   var currentLeaderPort = cluster.getLeaderManagementPort()
                                                  .or(leaderPort);
                   var slices = getSlices(currentLeaderPort);
                   return slices.contains("echo-slice");
               });
    }

    @Test
    @Timeout(120)
    void manualRollingRestart_maintainsAvailability() {
        // Wait for all nodes to be healthy
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(this::allNodesHealthy);

        // Snapshot current node IDs at start
        var initialNodeIds = new ArrayList<>(cluster.status().nodes().stream()
                                                     .map(ForgeCluster.NodeStatus::id)
                                                     .toList());

        for (var nodeId : initialNodeIds) {
            // Skip if node was already killed in previous iteration
            if (!cluster.status().nodes().stream()
                        .anyMatch(n -> n.id().equals(nodeId))) {
                continue;
            }

            // Kill node
            cluster.killNode(nodeId).await();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 2);

            // Add new node
            cluster.addNode().await();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 3);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(() -> failFastIfNodesDied(3))
                   .until(() -> cluster.currentLeader().isPresent());
        }

        // After rolling restart, cluster should be healthy
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(this::allNodesHealthy);

        var leaderPort = cluster.getLeaderManagementPort()
                                .unwrap();
        failFastIfHttpNotReady(leaderPort);
        var health = getNodeHealth(leaderPort);
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    @Timeout(120)
    void multipleNodeRestarts_clusterRemainsFunctional() {
        // Wait for all nodes to be healthy
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(this::allNodesHealthy);

        // Restart each non-leader node one at a time
        for (int i = 2; i <= 3; i++) {
            var nodeId = "bt-" + i;

            cluster.killNode(nodeId)
                   .await();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 2);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.currentLeader().isPresent());

            // Add a new node to replace the killed one
            cluster.addNode()
                   .await();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 3);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .failFast(() -> failFastIfNodesDied(3))
                   .until(() -> cluster.currentLeader().isPresent());
        }

        // Final verification
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> failFastIfNodesDied(3))
               .until(this::allNodesHealthy);

        assertThat(cluster.nodeCount()).isEqualTo(3);
    }

    private boolean allNodesHealthy() {
        var status = cluster.status();
        return status.nodes()
                     .stream()
                     .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private void failFastIfNodesDied(int expectedCount) {
        var actualCount = cluster.nodeCount();
        if (actualCount < expectedCount) {
            throw new AssertionError("Expected " + expectedCount + " nodes but only " + actualCount + " running");
        }
    }

    private void failFastIfHttpNotReady(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(2))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new AssertionError("HTTP server returned status " + response.statusCode() + ": " + response.body());
            }
        } catch (IOException e) {
            throw new AssertionError("HTTP server not reachable on port " + port + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while checking HTTP server");
        }
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

    private String getNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private String getSlices(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/slices"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void deploySlice(int port, String artifact, int instances) {
        var body = String.format("{\"artifact\":\"%s\",\"instances\":%d}", artifact, instances);
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/deploy"))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode())
                .as("Deploy should succeed. Response: %s", response.body())
                .isIn(200, 201, 202);
        } catch (IOException | InterruptedException e) {
            throw new AssertionError("Deploy request failed: " + e.getMessage(), e);
        }
    }
}
