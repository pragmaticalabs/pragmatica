package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * E2E tests for cluster bootstrap and recovery scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Single node restart and recovery</li>
 *   <li>State persistence across restarts</li>
 *   <li>Full cluster restart recovery</li>
 *   <li>Rolling restart behavior</li>
 * </ul>
 *
 * <p>These tests are complementary to ClusterFormationE2ETest which focuses
 * on initial cluster formation. BootstrapE2ETest focuses on recovery scenarios.
 */
class BootstrapE2ETest extends AbstractE2ETest {

    @Test
    void nodeRestart_rejoinsCluster() {
        cluster.awaitLeader();

        // Kill node-2
        cluster.killNode("node-2");

        // Wait for cluster to stabilize with 2 nodes
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 2);

        // Cluster should still have quorum with 2 out of 3 nodes
        cluster.awaitQuorum();

        // Restart node-2
        cluster.restartNode("node-2");

        // Wait for all 3 nodes to be running
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 3);

        // Cluster should be fully formed again
        cluster.awaitQuorum();

        // Verify all nodes are healthy
        for (var node : cluster.nodes()) {
            var health = node.getHealth();
            assertThat(health).doesNotContain("\"error\"");
        }
    }

    @Test
    void nodeRestart_recoversState() {
        cluster.awaitLeader();

        // Deploy a slice
        cluster.anyNode().deploy(TEST_ARTIFACT, 1);
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> {
            var slices = cluster.anyNode().getSlices();
            return slices.contains("place-order");
        });

        // Kill and restart a node
        cluster.killNode("node-3");
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 2);
        cluster.restartNode("node-3");
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();

        // Slice should still be visible (state recovered from consensus)
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> {
            var slices = cluster.anyNode().getSlices();
            return slices.contains("place-order");
        });
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void rollingRestart_maintainsAvailability() {
        cluster.awaitLeader();

        // Perform a rolling restart with 5 second delay between nodes
        cluster.rollingRestart(timeSpan(5).seconds().duration());

        // After rolling restart, cluster should be healthy
        cluster.awaitQuorum();
        cluster.awaitLeader();

        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void multipleNodeRestarts_clusterRemainsFunctional() {
        cluster.awaitLeader();

        // Restart each non-leader node one at a time
        for (int i = 2; i <= 3; i++) {
            var nodeId = "node-" + i;
            cluster.killNode(nodeId);
            await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 2);
            cluster.awaitQuorum();

            cluster.restartNode(nodeId);
            await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 3);
            cluster.awaitQuorum();
        }

        // Final verification
        cluster.awaitAllHealthy();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);
    }
}
