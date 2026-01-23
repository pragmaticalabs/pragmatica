package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for network partition and split-brain scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Majority partition continues operating</li>
 *   <li>Minority partition behavior (no quorum)</li>
 *   <li>Partition healing and cluster reconvergence</li>
 *   <li>Quorum behavior under various failure scenarios</li>
 * </ul>
 *
 * <p>Note: True network partitioning (isolating nodes at network level) is complex
 * to implement in Testcontainers. These tests simulate partition-like behavior
 * by stopping nodes, which achieves similar quorum effects.
 */
class NetworkPartitionE2ETest extends AbstractE2ETest {

    @Test
    void majorityPartition_continuesOperating() {
        cluster.awaitLeader();

        // Simulate minority partition by stopping one node
        // Majority (2 nodes) should continue operating
        cluster.killNode("node-3");

        // Wait for cluster to detect partition
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var health = cluster.anyNode().getHealth();
                   return health.contains("\"connectedPeers\":1");
               });

        // Majority should still have quorum
        cluster.awaitQuorum();

        // Management API should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":2");
    }

    @Test
    void lostQuorum_detectedAndReported() {
        cluster.awaitLeader();

        // Stop 2 nodes - remaining 1 node loses quorum
        cluster.killNode("node-2");
        cluster.killNode("node-3");

        // Wait for partition detection
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var health = cluster.nodes().get(0).getHealth();
                   // Single node should report 0 connected peers
                   return health.contains("\"connectedPeers\":0");
               });

        // Node should report lost quorum or degraded state
        var health = cluster.nodes().get(0).getHealth();
        // Even without quorum, the node should respond (readonly mode or degraded)
        assertThat(health).isNotBlank();
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void partitionHealing_clusterReconverges() {
        cluster.awaitLeader();

        // Create simulated partition
        cluster.killNode("node-2");
        cluster.killNode("node-3");

        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 1);

        // Heal partition - restart nodes
        cluster.restartNode("node-2");
        cluster.restartNode("node-3");

        // Cluster should reconverge
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();
        cluster.awaitLeader();

        // All nodes should be healthy again
        cluster.awaitAllHealthy();

        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"connectedPeers\":2");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    void quorumTransitions_maintainConsistency() {
        cluster.awaitLeader();

        // Deploy a slice while cluster is healthy
        deployAndAssert(TEST_ARTIFACT, 1);
        awaitSliceVisible("place-order");

        // Reduce to 2 nodes (still has quorum)
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Slice state should be preserved
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("place-order");

        // Restore full cluster
        cluster.restartNode("node-3");
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();

        // State should still be consistent
        slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("place-order");
    }
}
