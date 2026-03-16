package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Tests node kill and replacement recovery in a containerized cluster.
///
///
/// Verifies that killing a node and adding a replacement restores the cluster
/// to full size, and that deployed slices are recovered on the new node.
class NodeRecoveryE2ETest extends AbstractE2ETest {

    @Test
    void nodeKillAndReplace_clusterRecoversToFullSize() {
        cluster.awaitClusterConverged();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Kill a non-leader node
        cluster.killNode("node-3");
        assertThat(cluster.runningNodeCount()).isEqualTo(2);

        // Cluster should maintain quorum with 2 of 3
        cluster.awaitQuorum();

        // Add replacement node
        cluster.addNode();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Wait for full convergence
        cluster.awaitClusterConverged();
        cluster.awaitAllHealthy();

        // Verify all nodes report correct cluster size
        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    void nodeKillAndReplace_deployedSliceRecoveredOnNewNode() {
        cluster.awaitClusterConverged();

        // Deploy slice across all nodes
        deployAndAwaitActive(TEST_ARTIFACT, 3);
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Kill a node
        cluster.killNode("node-2");
        cluster.awaitQuorum();

        // Slice should still be invocable on remaining nodes
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var response = cluster.anyNode().invokeGet("/echo/recovery-test");
                   assertThat(response).doesNotContain("\"error\"");
               });

        // Add replacement
        cluster.addNode();
        cluster.awaitClusterConverged();

        // Slice should eventually be active on all 3 nodes again
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);
    }
}
