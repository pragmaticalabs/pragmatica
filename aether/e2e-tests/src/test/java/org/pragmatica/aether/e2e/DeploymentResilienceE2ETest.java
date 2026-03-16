package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// Tests deployment resilience under node failures in a 5-node cluster.
///
///
/// Verifies that deployed slices remain invocable after node failures
/// and that the cluster maintains quorum after losing multiple nodes.
class DeploymentResilienceE2ETest extends AbstractE2ETest {

    @Override
    protected int clusterSize() {
        return 5;
    }

    @Test
    void deployedSlice_survivesNodeFailure_remainsInvocable() {
        cluster.awaitClusterConverged();

        // Deploy slice
        deployAndAwaitActive(TEST_ARTIFACT, 3);
        cluster.awaitRoutesContain("echo", DEPLOY_TIMEOUT);

        // Kill 2 non-leader nodes
        cluster.killNode("node-4");
        cluster.killNode("node-5");

        // Cluster should maintain quorum (3 of 5)
        cluster.awaitQuorum();

        // Slice should still be invocable
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .untilAsserted(() -> {
                   var response = cluster.anyNode().invokeGet("/echo/resilience-test");
                   assertThat(response).doesNotContain("\"error\"");
               });
    }

    @Test
    void twoNodeFailure_clusterMaintainsQuorum() {
        cluster.awaitClusterConverged();
        assertThat(cluster.runningNodeCount()).isEqualTo(5);

        // Kill 2 nodes
        cluster.killNode("node-4");
        cluster.killNode("node-5");
        assertThat(cluster.runningNodeCount()).isEqualTo(3);

        // Quorum should hold (3 of 5)
        cluster.awaitQuorum();

        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"status\"");
    }
}
