package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for graceful shutdown scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Node shutdown leaves cluster in healthy state</li>
 *   <li>Peers detect and respond to node shutdown</li>
 *   <li>Slices are handled appropriately during shutdown</li>
 *   <li>Shutdown during ongoing operations</li>
 * </ul>
 */
class GracefulShutdownE2ETest extends AbstractE2ETest {

    @Test
    void nodeShutdown_peersDetectDisconnection() {
        cluster.awaitLeader();

        // Get initial peer count
        var initialHealth = cluster.nodes().get(0).getHealth();
        assertThat(initialHealth).contains("\"connectedPeers\":2");

        // Shutdown node-3
        cluster.killNode("node-3");

        // Remaining nodes should detect the disconnection
        await().atMost(DEFAULT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var health = cluster.nodes().get(0).getHealth();
                   return health.contains("\"connectedPeers\":1");
               });

        // Cluster still has quorum with 2 nodes
        cluster.awaitQuorum();
    }

    @Test
    void nodeShutdown_clusterRemainsFunctional() {
        cluster.awaitLeader();

        // Deploy a slice
        deployAndAssert(TEST_ARTIFACT, 2);
        awaitSliceVisible("echo-slice");

        // Shutdown one node
        cluster.killNode("node-2");

        // Cluster should still be functional
        cluster.awaitQuorum();

        // Management API should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":2");

        // Slice should still be accessible
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("echo-slice");
    }

    @Test
    void shutdownDuringDeployment_handledGracefully() {
        cluster.awaitLeader();

        // Start a deployment
        deployAndAssert(TEST_ARTIFACT, 3);

        // Immediately shutdown a node (deployment may still be in progress)
        cluster.killNode("node-3");

        // Wait for quorum
        cluster.awaitQuorum();

        // Cluster should recover and deployment should eventually complete
        await().atMost(DEFAULT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   try {
                       var slices = cluster.anyNode().getSlices();
                       return slices.contains("echo-slice") || !slices.contains("\"error\"");
                   } catch (Exception e) {
                       return false;
                   }
               });

        // Verify cluster is healthy
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
    }

    @Test
    void leaderShutdown_newLeaderElected() {
        cluster.awaitLeader();
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();

        // Shutdown the leader
        cluster.killNode(originalLeader.nodeId());

        // Wait for new quorum and leader
        cluster.awaitQuorum();
        cluster.awaitLeader();

        // New leader should be elected
        var newLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        assertThat(newLeader.nodeId()).isNotEqualTo(originalLeader.nodeId());

        // Cluster should be functional
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
    }
}
