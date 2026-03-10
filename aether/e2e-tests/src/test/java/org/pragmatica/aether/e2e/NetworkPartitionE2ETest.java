package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// E2E test verifying cluster behavior during and after a network partition.
///
///
/// Disconnects two nodes from the cluster network, verifies the majority partition
/// retains quorum and leadership, then reconnects the isolated nodes and verifies
/// the cluster reconverges to full membership.
class NetworkPartitionE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void networkPartition_majorityRetainsQuorum_reconvergesAfterHeal() {
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        // Partition: isolate node-4 and node-5
        cluster.disconnectNode("node-4");
        cluster.disconnectNode("node-5");

        // Verify majority partition (node-1, node-2, node-3) retains quorum
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var health = cluster.node("node-1").getHealth();
                   return health.contains("\"ready\":true");
               });

        // Heal partition: reconnect isolated nodes
        cluster.reconnectNode("node-4");
        cluster.reconnectNode("node-5");

        // Verify full cluster reconvergence
        cluster.awaitClusterConverged();

        assertThat(cluster.runningNodeCount()).isEqualTo(5);
    }
}
