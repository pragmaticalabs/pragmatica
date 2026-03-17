package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Tests cluster degradation and recovery via node addition in a 5-node cluster.
///
///
/// Verifies that killing a majority of nodes degrades the cluster,
/// and that adding replacement nodes restores full convergence.
class QuorumLossRecoveryE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void majorityKilled_clusterDegraded_recoversWithNewNodes() {
        cluster.awaitClusterConverged();

        // Kill 3 of 5 — majority lost
        cluster.killNode("node-3");
        cluster.killNode("node-4");
        cluster.killNode("node-5");
        assertThat(cluster.runningNodeCount()).isEqualTo(2);

        // Remaining nodes should detect degraded state (not ready)
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var health = cluster.node("node-1").getHealth();
                   assertThat(health).contains("\"ready\":false");
               });

        // Add nodes back to restore cluster
        cluster.addNode();
        cluster.addNode();
        cluster.addNode();

        // Cluster should recover
        cluster.awaitQuorum();
        cluster.awaitClusterConverged();

        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"quorum\":true");
        assertThat(health).contains("\"ready\":true");
    }
}
