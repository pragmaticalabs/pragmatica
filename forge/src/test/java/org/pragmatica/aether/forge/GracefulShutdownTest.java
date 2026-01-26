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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Tests for graceful shutdown scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Node shutdown leaves cluster in healthy state</li>
 *   <li>Peers detect and respond to node shutdown</li>
 *   <li>Slices are handled appropriately during shutdown</li>
 *   <li>Shutdown during ongoing operations</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class GracefulShutdownTest {
    private static final int BASE_PORT = 5630;
    private static final int BASE_MGMT_PORT = 5730;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.example:place-order-place-order:0.0.1-test";

    // Per-method port offsets to avoid TIME_WAIT conflicts between test methods
    private static final Map<String, Integer> METHOD_PORT_OFFSETS = Map.of(
        "nodeShutdown_peersDetectDisconnection", 0,
        "nodeShutdown_clusterRemainsFunctional", 20,
        "shutdownDuringDeployment_handledGracefully", 40,
        "leaderShutdown_newLeaderElected", 60
    );

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        var methodName = testInfo.getTestMethod().orElseThrow().getName();
        var portOffset = METHOD_PORT_OFFSETS.getOrDefault(methodName, 0);

        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "gs");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        awaitQuorum();
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            Thread.sleep(1000);
        }
    }

    @Test
    void nodeShutdown_peersDetectDisconnection() {
        awaitLeader();

        // Get initial peer count
        var initialHealth = getHealthFromAnyNode();
        assertThat(initialHealth).contains("\"connectedPeers\":2");

        // Shutdown gs-3
        cluster.killNode("gs-3")
               .await();

        // Remaining nodes should detect the disconnection
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var health = getHealthFromAnyNode();
                   return health.contains("\"connectedPeers\":1");
               });

        // Cluster still has quorum with 2 nodes
        awaitQuorum();
    }

    @Test
    void nodeShutdown_clusterRemainsFunctional() {
        awaitLeader();

        // Deploy a slice
        var deployResponse = deploySlice(TEST_ARTIFACT, 2);
        assertDeploymentSucceeded(deployResponse);

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   var slices = getSlicesFromAnyNode();
                   if (slices.contains("\"error\"")) {
                       throw new AssertionError("Slice query failed: " + slices);
                   }
               })
               .until(() -> {
                   var slices = getSlicesFromAnyNode();
                   return slices.contains("place-order-place-order");
               });

        // Shutdown one node
        cluster.killNode("gs-2")
               .await();

        // Cluster should still be functional
        awaitQuorum();

        // Management API should still work
        var health = getHealthFromAnyNode();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":2");

        // Slice should still be accessible
        var slices = getSlicesFromAnyNode();
        assertThat(slices).contains("place-order-place-order");
    }

    @Test
    void shutdownDuringDeployment_handledGracefully() {
        awaitLeader();

        // Start a deployment
        var deployResponse = deploySlice(TEST_ARTIFACT, 3);
        assertDeploymentSucceeded(deployResponse);

        // Immediately shutdown a node (deployment may still be in progress)
        cluster.killNode("gs-3")
               .await();

        // Wait for quorum
        awaitQuorum();

        // Cluster should recover and deployment should eventually complete
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   var slices = getSlicesFromAnyNode();
                   if (slices.contains("\"error\"") && !slices.contains("place-order-place-order")) {
                       throw new AssertionError("Slice query failed: " + slices);
                   }
               })
               .until(() -> {
                   var slices = getSlicesFromAnyNode();
                   return slices.contains("place-order-place-order");
               });

        // Verify cluster is healthy
        var health = getHealthFromAnyNode();
        assertThat(health).doesNotContain("\"error\"");
    }

    @Test
    void leaderShutdown_newLeaderElected() {
        awaitLeader();
        var originalLeader = cluster.currentLeader()
                                    .unwrap();

        // Shutdown the leader
        cluster.killNode(originalLeader)
               .await();

        // Wait for new quorum and leader
        awaitQuorum();
        awaitLeader();

        // New leader should be elected
        var newLeader = cluster.currentLeader()
                               .unwrap();
        assertThat(newLeader).isNotEqualTo(originalLeader);

        // Cluster should be functional
        var health = getHealthFromAnyNode();
        assertThat(health).doesNotContain("\"error\"");
    }

    // --- Helper methods ---

    private void awaitLeader() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader()
                                   .isPresent());
    }

    private void awaitQuorum() {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader()
                                   .isPresent() && allNodesHealthy());
    }

    private boolean allNodesHealthy() {
        var status = cluster.status();
        return status.nodes()
                     .stream()
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
            return response.statusCode() == 200 && response.body()
                                                           .contains("\"quorum\":true");
        } catch (IOException | InterruptedException e) {
            return false;
        }
    }

    private String getHealthFromAnyNode() {
        var status = cluster.status();
        if (status.nodes()
                  .isEmpty()) {
            return "";
        }
        var port = status.nodes()
                         .get(0)
                         .mgmtPort();
        return httpGet(port, "/api/health");
    }

    private String getSlicesFromAnyNode() {
        var status = cluster.status();
        if (status.nodes()
                  .isEmpty()) {
            return "";
        }
        var port = status.nodes()
                         .get(0)
                         .mgmtPort();
        // Use /api/slices/status for cluster-wide view (reads from KVStore)
        return httpGet(port, "/api/slices/status");
    }

    private String deploySlice(String artifact, int instances) {
        var leaderPort = cluster.getLeaderManagementPort()
                                .or(cluster.status()
                                           .nodes()
                                           .get(0)
                                           .mgmtPort());
        var body = "{\"artifact\":\"" + artifact + "\",\"instances\":" + instances + "}";
        return httpPost(leaderPort, "/api/deploy", body);
    }

    private void assertDeploymentSucceeded(String response) {
        assertThat(response)
            .describedAs("Deployment response")
            .doesNotContain("\"error\"")
            .contains("\"status\":\"deployed\"");
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
            return "";
        }
    }

    private String httpPost(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\":\"" + e.getMessage() + "\"}";
        }
    }
}
