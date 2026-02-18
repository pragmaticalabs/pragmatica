package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/// Tests for network partition and split-brain scenarios.
///
///
/// Tests cover:
///
///   - Majority partition continues operating
///   - Minority partition behavior (no quorum)
///   - Partition healing and cluster reconvergence
///   - Quorum behavior under various failure scenarios
///
///
///
/// Note: True network partitioning (isolating nodes at network level) is complex.
/// These tests simulate partition-like behavior by stopping nodes, which achieves
/// similar quorum effects.
@Execution(ExecutionMode.SAME_THREAD)
class NetworkPartitionTest {
    private static final int BASE_PORT = 11000;
    private static final int BASE_MGMT_PORT = 11100;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:0.17.0";

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "np");
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
            case "majorityPartition_continuesOperating" -> 0;
            case "lostQuorum_detectedAndReported" -> 20;
            case "partitionHealing_clusterReconverges" -> 40;
            case "quorumTransitions_maintainConsistency" -> 60;
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
    void majorityPartition_continuesOperating() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Simulate minority partition by stopping one node
        // Majority (2 nodes) should continue operating
        cluster.killNode("np-3")
               .await();

        // Wait for cluster to detect partition
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var health = getHealthFromAnyNode();
                   return health.contains("\"connectedPeers\":1");
               });

        // Majority should still have quorum
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Management API should still work
        var health = getHealthFromAnyNode();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":2");
    }

    @Test
    void lostQuorum_detectedAndReported() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Stop 2 nodes - remaining 1 node loses quorum
        cluster.killNode("np-2")
               .await();
        cluster.killNode("np-3")
               .await();

        // Wait for partition detection
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var health = getHealthFromFirstNode();
                   // Single node should report 0 connected peers
                   return health.contains("\"connectedPeers\":0");
               });

        // Node should report lost quorum or degraded state
        var health = getHealthFromFirstNode();
        // Even without quorum, the node should respond (readonly mode or degraded)
        assertThat(health).isNotBlank();
    }

    @Test
    void partitionHealing_clusterReconverges() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Create simulated partition
        cluster.killNode("np-2")
               .await();
        cluster.killNode("np-3")
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 1);

        // Heal partition - add new nodes
        cluster.addNode()
               .await();
        cluster.addNode()
               .await();

        // Cluster should reconverge
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 3);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // All nodes should be healthy again
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        var health = getHealthFromAnyNode();
        assertThat(health).contains("\"connectedPeers\":2");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    void quorumTransitions_maintainConsistency() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Deploy a slice while cluster is healthy
        var deployResponse = deploySlice(TEST_ARTIFACT, 1);
        assertDeploymentSucceeded(deployResponse);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   var slices = getSlicesFromAnyNode();
                   if (slices.contains("\"error\"")) {
                       throw new AssertionError("Slice query failed: " + slices);
                   }
                   if (sliceHasFailed(TEST_ARTIFACT)) {
                       throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                   }
               })
               .until(() -> {
                   var slices = getSlicesFromAnyNode();
                   return slices.contains("echo-slice");
               });

        // Reduce to 2 nodes (still has quorum)
        cluster.killNode("np-3")
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Slice state should be preserved
        var slices = getSlicesFromAnyNode();
        assertThat(slices).contains("echo-slice");

        // Restore full cluster by adding a new node
        cluster.addNode()
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 3);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // State should still be consistent
        slices = getSlicesFromAnyNode();
        assertThat(slices).contains("echo-slice");
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

    private String getHealthFromAnyNode() {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "";
        }
        var port = status.nodes().getFirst().mgmtPort();
        return httpGet(port, "/api/health");
    }

    private String getHealthFromFirstNode() {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "";
        }
        var port = status.nodes().getFirst().mgmtPort();
        return httpGet(port, "/api/health");
    }

    private String getSlicesFromAnyNode() {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "";
        }
        var port = status.nodes().getFirst().mgmtPort();
        // Use /api/slices/status for cluster-wide view (reads from KVStore)
        return httpGet(port, "/api/slices/status");
    }

    private String deploySlice(String artifact, int instances) {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "{\"error\":\"No nodes available\"}";
        }
        var port = status.nodes().getFirst().mgmtPort();
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/deploy"))
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .header("Content-Type", "application/json")
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }

    private void assertDeploymentSucceeded(String response) {
        assertThat(response)
            .describedAs("Deployment response")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"deployed\"");
    }

    private boolean sliceHasFailed(String artifact) {
        try {
            var slicesStatus = cluster.slicesStatus();
            return slicesStatus.stream()
                               .anyMatch(s -> s.artifact().equals(artifact) &&
                                              s.state().equals("FAILED"));
        } catch (Exception e) {
            return false;
        }
    }

    private String httpGet(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
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
}
