package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Tests quorum loss detection and recovery via node addition in a 5-node cluster.
///
///
/// Verifies that killing a majority of nodes results in quorum loss,
/// and that adding replacement nodes restores quorum and full convergence.
class QuorumLossRecoveryE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void quorumLost_detectedAndReported_recoversWithNewNodes() {
        cluster.awaitClusterConverged();

        // Kill 3 of 5 — quorum lost
        cluster.killNode("node-3");
        cluster.killNode("node-4");
        cluster.killNode("node-5");
        assertThat(cluster.runningNodeCount()).isEqualTo(2);

        // Remaining nodes should report quorum lost
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var health = cluster.node("node-1").getHealth();
                   assertThat(health).contains("\"quorum\":false");
               });

        // Add nodes back to restore quorum
        cluster.addNode();
        cluster.addNode();
        cluster.addNode();

        // Quorum should be restored
        cluster.awaitQuorum();
        cluster.awaitClusterConverged();

        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"quorum\":true");
    }
}
