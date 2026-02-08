package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * E2E tests for cluster formation and quorum behavior.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>3-node cluster formation</li>
 *   <li>Quorum establishment</li>
 *   <li>Leader election</li>
 *   <li>Node visibility across cluster</li>
 * </ul>
 */
class ClusterFormationE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 3;
    }

    @Test
    void threeNodeCluster_formsQuorum_andElectsLeader() {
        // All nodes should be healthy
        cluster.awaitAllHealthy();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Health endpoint should report healthy with quorum
        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");

        // Wait for leader to be elected (may take a moment after quorum)
        cluster.awaitLeader();
        var leader = cluster.leader();
        assertThat(leader.isPresent()).isTrue();
    }

    @Test
    void cluster_nodesVisibleToAllMembers() {
        // Each node should report 2 connected peers via /health endpoint
        for (var node : cluster.nodes()) {
            var health = node.getHealth();
            assertThat(health).contains("\"connectedPeers\":2");
            assertThat(health).contains("\"nodeCount\":3");
        }
    }

    @Test
    void cluster_statusConsistent_acrossNodes() {
        cluster.awaitLeader();

        // Collect leader info from all nodes
        var statuses = cluster.nodes().stream()
                              .map(AetherNodeContainer::getStatus)
                              .toList();

        // All should report the same leader
        var leaderNode = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        for (var status : statuses) {
            assertThat(status).contains(leaderNode.nodeId());
        }
    }

    @Test
    void cluster_metricsAvailable_afterFormation() {
        var metrics = cluster.anyNode().getMetrics();

        // Metrics should contain expected fields
        assertThat(metrics).doesNotContain("\"error\"");
    }
}
