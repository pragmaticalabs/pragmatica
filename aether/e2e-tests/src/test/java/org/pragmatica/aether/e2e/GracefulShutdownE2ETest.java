package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Tests graceful shutdown behavior: killing nodes during active deployments
/// and verifying cluster recovery.
///
///
/// Covers scenarios where nodes are killed while slices are deployed,
/// ensuring the cluster handles partial deployments gracefully and
/// leadership transfers correctly.
class GracefulShutdownE2ETest extends AbstractE2ETest {

    @Test
    void nodeKillDuringDeployment_clusterRecoversAndSliceBecomesActive() {
        cluster.awaitClusterConverged();

        // Deploy slice (deployment may still be propagating)
        deployAndAssert(TEST_ARTIFACT, 3);

        // Kill a non-leader node immediately (deployment may be in progress)
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Cluster should recover and slice should eventually be active on remaining nodes
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Verify slice is invocable
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var response = cluster.anyNode().invokeGet("/echo/shutdown-test");
                   assertThat(response).doesNotContain("\"error\"");
               });
    }

    @Test
    void leaderKill_newLeaderElected_sliceStatePreserved() {
        cluster.awaitClusterConverged();

        // Deploy slice and wait for it to become active
        deployAndAwaitActive(TEST_ARTIFACT, 3);
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Kill the leader
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var originalLeaderId = originalLeader.nodeId();
        cluster.killNode(originalLeaderId);

        // Wait for new leader election
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var newLeader = cluster.leader();
                   return newLeader.isPresent() &&
                          !newLeader.toResult(Causes.cause("No leader")).unwrap().nodeId().equals(originalLeaderId);
               });

        // Slice state should be preserved on surviving nodes
        var slicesStatus = cluster.anyNode().getSlicesStatus();
        assertThat(slicesStatus).contains(TEST_ARTIFACT);
    }
}
