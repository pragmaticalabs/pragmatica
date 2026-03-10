package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;

/// E2E test for graceful node drain via management API.
///
///
/// Verifies that draining a node transitions it to DRAINING state,
/// and the cluster continues operating with quorum after the drained
/// node is shut down.
class NodeDrainE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void drainNode_transitionsToDraining_clusterContinues() {
        deployAndAwaitActive(TEST_ARTIFACT, 5);
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        leader.drainNode("node-3");

        cluster.awaitNodeLifecycle("node-3", "DRAINING", RECOVERY_TIMEOUT);

        cluster.killNode("node-3");

        cluster.awaitQuorum();
        assertThat(cluster.runningNodeCount()).isEqualTo(4);
    }
}
