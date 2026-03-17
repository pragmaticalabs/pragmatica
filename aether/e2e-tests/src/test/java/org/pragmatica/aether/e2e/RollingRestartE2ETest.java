package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E test for zero-downtime rolling restart of a 3-node cluster.
///
///
/// Verifies that stopping and restarting each node in sequence
/// maintains cluster availability throughout the process.
class RollingRestartE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 3;
    }

    @Test
    void rollingRestart_allNodesRestartedSequentially_clusterRemainsHealthy() {
        deployAndAwaitActive(TEST_ARTIFACT, 3);
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        var nodeIds = cluster.nodes().stream()
                             .map(AetherNodeContainer::nodeId)
                             .toList();

        for (var nodeId : nodeIds) {
            restartNodeAndVerifyCluster(nodeId);
        }

        assertThat(cluster.runningNodeCount()).isEqualTo(3);
        cluster.awaitAllHealthy();
        cluster.awaitClusterConverged();
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);
    }

    private void restartNodeAndVerifyCluster(String nodeId) {
        System.out.println("[DEBUG] Rolling restart: stopping " + nodeId);
        cluster.node(nodeId).stop();

        assertThat(cluster.runningNodeCount()).isEqualTo(2);
        cluster.awaitQuorum();
        verifySurvivingNodesHealthy(nodeId);

        System.out.println("[DEBUG] Rolling restart: restarting " + nodeId);
        cluster.node(nodeId).start();

        cluster.awaitClusterConverged();
        cluster.awaitAllNodesOnDuty();
        System.out.println("[DEBUG] Rolling restart: " + nodeId + " rejoined successfully");
    }

    private void verifySurvivingNodesHealthy(String stoppedNodeId) {
        cluster.nodes().stream()
               .filter(node -> !node.nodeId().equals(stoppedNodeId))
               .forEach(node -> assertNodeHealthy(node, stoppedNodeId));
    }

    private void assertNodeHealthy(AetherNodeContainer node, String stoppedNodeId) {
        var health = node.getHealth();
        assertThat(health)
            .describedAs("Node %s should be healthy while %s is down", node.nodeId(), stoppedNodeId)
            .contains("\"ready\":true");
    }
}
