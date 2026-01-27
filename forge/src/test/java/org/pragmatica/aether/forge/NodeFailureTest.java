package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Tests for node failure and recovery scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Single node failure with quorum maintained</li>
 *   <li>Double node failure with quorum maintained</li>
 *   <li>Leader failure and re-election</li>
 *   <li>Node recovery (via restartNode)</li>
 *   <li>Rolling restart</li>
 *   <li>Minority partition (quorum lost) and recovery</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
class NodeFailureTest {
    private static final int BASE_PORT = 5150;
    private static final int BASE_MGMT_PORT = 5250;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeEach
    void setUp(TestInfo testInfo) {
        int portOffset = getPortOffset(testInfo);
        cluster = forgeCluster(5, BASE_PORT + portOffset, BASE_MGMT_PORT + portOffset, "nf");
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
            case "singleNodeFailure_clusterMaintainsQuorum" -> 0;
            case "twoNodeFailure_clusterMaintainsQuorum" -> 20;
            case "leaderFailure_newLeaderElected" -> 40;
            case "nodeRecovery_newNodeJoinsCluster" -> 60;
            case "manualRollingRestart_maintainsQuorum" -> 80;
            case "minorityPartition_quorumLost_thenRecovered" -> 100;
            default -> 120;
        };
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
    void singleNodeFailure_clusterMaintainsQuorum() {
        assertThat(cluster.nodeCount()).isEqualTo(5);

        // Kill one node
        cluster.killNode("nf-3")
               .await();
        assertThat(cluster.nodeCount()).isEqualTo(4);

        // Cluster should still have quorum (leader present)
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Operations should still work
        var health = getHealthFromAnyNode();
        assertThat(health).contains("status");
    }

    @Test
    void twoNodeFailure_clusterMaintainsQuorum() {
        // Kill two nodes (5 - 2 = 3, still majority)
        cluster.killNode("nf-2")
               .await();
        cluster.killNode("nf-4")
               .await();
        assertThat(cluster.nodeCount()).isEqualTo(3);

        // Cluster should still have quorum
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        var status = cluster.status();
        var nodeIds = status.nodes().stream()
                            .map(ForgeCluster.NodeStatus::id)
                            .toList();
        assertThat(nodeIds).contains("nf-1");
        assertThat(nodeIds).contains("nf-3");
        assertThat(nodeIds).contains("nf-5");
    }

    @Test
    void leaderFailure_newLeaderElected() {
        var originalLeaderId = cluster.currentLeader().unwrap();

        // Kill the leader
        cluster.killNode(originalLeaderId)
               .await();

        // Wait for new leader election
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var newLeader = cluster.currentLeader();
                   return newLeader.isPresent() &&
                          !newLeader.unwrap().equals(originalLeaderId);
               });

        // New leader should be different
        var newLeaderId = cluster.currentLeader().unwrap();
        assertThat(newLeaderId).isNotEqualTo(originalLeaderId);
    }

    @Test
    void nodeRecovery_newNodeJoinsCluster() {
        // Kill a node
        cluster.killNode("nf-2")
               .await();

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 4);

        // Add a new node to replace the killed one
        cluster.addNode()
               .await();

        // Wait for node to join
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.nodeCount() == 5);

        // Cluster should have quorum
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());
    }

    @Test
    void manualRollingRestart_maintainsQuorum() {
        // Perform a manual rolling restart: kill and add nodes one at a time
        var nodeIds = cluster.status().nodes().stream()
                             .map(ForgeCluster.NodeStatus::id)
                             .toList();

        for (var nodeId : nodeIds) {
            cluster.killNode(nodeId).await();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 4);

            cluster.addNode().await();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.nodeCount() == 5);

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> cluster.currentLeader().isPresent());
        }

        // Cluster should still have quorum after rolling restart
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        assertThat(cluster.nodeCount()).isEqualTo(5);
    }

    @Test
    void minorityPartition_quorumLost_thenRecovered() {
        // Kill majority (3 of 5)
        cluster.killNode("nf-1")
               .await();
        cluster.killNode("nf-2")
               .await();
        cluster.killNode("nf-3")
               .await();

        assertThat(cluster.nodeCount()).isEqualTo(2);

        // Remaining nodes may not have quorum (no leader or degraded)
        // Add a new node to restore quorum (3 nodes needed)
        cluster.addNode()
               .await();

        // Wait for quorum to be restored
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        // Cluster should be healthy again
        var health = getHealthFromAnyNode();
        assertThat(health).doesNotContain("\"error\"");
    }

    private String getHealthFromAnyNode() {
        var status = cluster.status();
        if (status.nodes().isEmpty()) {
            return "";
        }
        var port = status.nodes().getFirst().mgmtPort();
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (Exception e) {
            return "";
        }
    }
}
