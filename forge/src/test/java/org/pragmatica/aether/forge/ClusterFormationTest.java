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

/**
 * Tests for cluster formation and quorum behavior using ForgeCluster.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>3-node cluster formation</li>
 *   <li>Quorum establishment</li>
 *   <li>Leader election</li>
 *   <li>Node visibility across cluster</li>
 *   <li>Status consistency</li>
 *   <li>Metrics availability</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class ClusterFormationTest {
    private static final int BASE_PORT = 5020;
    private static final int BASE_MGMT_PORT = 5120;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(3, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "cf");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();
    }

    private int getPortOffset(TestInfo testInfo) {
        return switch (testInfo.getTestMethod().map(m -> m.getName()).orElse("")) {
            case "threeNodeCluster_formsQuorum_andElectsLeader" -> 0;
            case "cluster_nodesVisibleToAllMembers" -> 20;
            case "cluster_statusConsistent_acrossNodes" -> 40;
            case "cluster_metricsAvailable_afterFormation" -> 60;
            default -> 80;
        };
    }

    @AfterEach
    void tearDown() throws InterruptedException {
        if (cluster != null) {
            cluster.stop()
                   .await();
            // Allow time for ports to be released before next test
            Thread.sleep(2000);
        }
    }

    @Test
    void threeNodeCluster_formsQuorum_andElectsLeader() {
        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        // Wait for leader election
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Wait for all nodes to be healthy with quorum
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);

        // All nodes should be running
        assertThat(cluster.nodeCount()).isEqualTo(3);

        // Health endpoint should report healthy with quorum
        var anyNodePort = cluster.status().nodes().getFirst().mgmtPort();
        var health = getHealth(anyNodePort);
        assertThat(health).contains("\"status\"");

        // Leader should be elected
        var leader = cluster.currentLeader();
        assertThat(leader.isPresent()).isTrue();
    }

    @Test
    void cluster_nodesVisibleToAllMembers() {
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

        // Each node should report 2 connected peers via /api/health endpoint
        for (var node : cluster.status().nodes()) {
            var health = getHealth(node.mgmtPort());
            assertThat(health).contains("\"connectedPeers\":2");
            assertThat(health).contains("\"nodeCount\":3");
        }
    }

    @Test
    void cluster_statusConsistent_acrossNodes() {
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

        // Collect status from all nodes
        var leaderNode = cluster.currentLeader().unwrap();

        // All nodes should report the same leader via /api/status endpoint
        for (var node : cluster.status().nodes()) {
            var status = getStatus(node.mgmtPort());
            assertThat(status).contains(leaderNode);
        }
    }

    @Test
    void cluster_metricsAvailable_afterFormation() {
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

        var anyNodePort = cluster.status().nodes().getFirst().mgmtPort();
        var metrics = getMetrics(anyNodePort);

        // Metrics should not contain error
        assertThat(metrics).doesNotContain("\"error\"");
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

    private String getHealth(int port) {
        return httpGet(port, "/api/health");
    }

    private String getStatus(int port) {
        return httpGet(port, "/api/status");
    }

    private String getMetrics(int port) {
        return httpGet(port, "/api/metrics");
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
            return "error: " + e.getMessage();
        }
    }
}
