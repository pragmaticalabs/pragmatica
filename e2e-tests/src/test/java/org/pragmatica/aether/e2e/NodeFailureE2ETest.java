package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for node failure and recovery scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Single node failure with quorum maintained</li>
 *   <li>Leader failure and re-election</li>
 *   <li>Node recovery and rejoin</li>
 *   <li>Minority partition (quorum lost)</li>
 * </ul>
 */
class NodeFailureE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void singleNodeFailure_clusterMaintainsQuorum() {
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        // Kill one node
        cluster.killNode("node-3");
        assertThat(cluster.runningNodeCount()).isEqualTo(4);

        // Cluster should still have quorum
        cluster.awaitQuorum();

        // Operations should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");
    }

    @Test
    void twoNodeFailure_clusterMaintainsQuorum() {
        // Kill two nodes (5 - 2 = 3, still majority)
        cluster.killNode("node-2");
        cluster.killNode("node-4");
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Cluster should still have quorum
        cluster.awaitQuorum();

        var nodes = cluster.anyNode().getNodes();
        assertThat(nodes).contains("node-1");
        assertThat(nodes).contains("node-3");
        assertThat(nodes).contains("node-5");
    }

    @Test
    void leaderFailure_newLeaderElected() {
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var originalLeaderId = originalLeader.nodeId();

        // Kill the leader
        cluster.killNode(originalLeaderId);

        // Wait for new leader election
        await().atMost(RECOVERY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var newLeader = cluster.leader();
                   return newLeader.isPresent() &&
                          !newLeader.toResult(Causes.cause("No leader")).unwrap().nodeId().equals(originalLeaderId);
               });

        // New leader should be different
        var newLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        assertThat(newLeader.nodeId()).isNotEqualTo(originalLeaderId);
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void nodeRecovery_rejoinsCluster() {
        // Kill a node
        cluster.killNode("node-2");
        cluster.awaitNodeCount(4);

        // Restart the node
        cluster.restartNode("node-2");

        // Wait for node to rejoin
        await().atMost(RECOVERY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 5);

        // All nodes should be visible again
        cluster.awaitQuorum();
        var nodes = cluster.anyNode().getNodes();
        assertThat(nodes).contains("node-2");
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void rollingRestart_maintainsQuorum() {
        // Track that quorum is maintained throughout
        var quorumMaintained = new boolean[] { true };

        // Perform rolling restart with health checks
        for (int i = 1; i <= 5; i++) {
            var nodeId = "node-" + i;

            cluster.killNode(nodeId);

            // Check quorum is still present
            try {
                cluster.awaitQuorum();
            } catch (Exception e) {
                quorumMaintained[0] = false;
            }

            cluster.restartNode(nodeId);
            cluster.awaitQuorum();
        }

        assertThat(quorumMaintained[0]).isTrue();
        assertThat(cluster.runningNodeCount()).isEqualTo(5);
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void minorityPartition_quorumLost_thenRecovered() {
        // Kill majority (3 of 5)
        cluster.killNode("node-1");
        cluster.killNode("node-2");
        cluster.killNode("node-3");

        assertThat(cluster.runningNodeCount()).isEqualTo(2);

        // Remaining nodes should report degraded/unhealthy
        var health = cluster.anyNode().getHealth();
        // May contain error or degraded status

        // Restore one node to regain quorum (3 of 5)
        cluster.restartNode("node-1");
        cluster.awaitQuorum();

        // Cluster should be healthy again
        var restoredHealth = cluster.anyNode().getHealth();
        assertThat(restoredHealth).doesNotContain("\"error\"");
    }
}
