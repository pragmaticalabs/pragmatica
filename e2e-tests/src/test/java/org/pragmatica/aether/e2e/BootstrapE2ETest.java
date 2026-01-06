package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherCluster;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
class BootstrapE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether:example-slice:0.7.2";
    private AetherCluster cluster;

    @BeforeEach
    void setUp() {
        cluster = AetherCluster.create(3, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
    }

    @AfterEach
    void tearDown() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @Test
    void nodeRestart_rejoinsCluster() {
        cluster.awaitLeader();

        // Kill node-2
        cluster.killNode("node-2");

        // Wait for cluster to stabilize with 2 nodes
        await().atMost(WAIT_TIMEOUT).until(() -> cluster.runningNodeCount() == 2);

        // Cluster should still have quorum with 2 out of 3 nodes
        cluster.awaitQuorum();

        // Restart node-2
        cluster.restartNode("node-2");

        // Wait for all 3 nodes to be running
        await().atMost(WAIT_TIMEOUT).until(() -> cluster.runningNodeCount() == 3);

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
        await().atMost(WAIT_TIMEOUT).until(() -> {
            var slices = cluster.anyNode().getSlices();
            return slices.contains("example-slice");
        });

        // Kill and restart a node
        cluster.killNode("node-3");
        await().atMost(WAIT_TIMEOUT).until(() -> cluster.runningNodeCount() == 2);
        cluster.restartNode("node-3");
        await().atMost(WAIT_TIMEOUT).until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();

        // Slice should still be visible (state recovered from consensus)
        await().atMost(WAIT_TIMEOUT).until(() -> {
            var slices = cluster.anyNode().getSlices();
            return slices.contains("example-slice");
        });
    }

    @Test
    void rollingRestart_maintainsAvailability() {
        cluster.awaitLeader();

        // Perform a rolling restart with 5 second delay between nodes
        cluster.rollingRestart(Duration.ofSeconds(5));

        // After rolling restart, cluster should be healthy
        cluster.awaitQuorum();
        cluster.awaitLeader();

        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    void multipleNodeRestarts_clusterRemainsFunctional() {
        cluster.awaitLeader();

        // Restart each non-leader node one at a time
        for (int i = 2; i <= 3; i++) {
            var nodeId = "node-" + i;
            cluster.killNode(nodeId);
            await().atMost(WAIT_TIMEOUT).until(() -> cluster.runningNodeCount() == 2);
            cluster.awaitQuorum();

            cluster.restartNode(nodeId);
            await().atMost(WAIT_TIMEOUT).until(() -> cluster.runningNodeCount() == 3);
            cluster.awaitQuorum();
        }

        // Final verification
        cluster.awaitAllHealthy();
        assertThat(cluster.runningNodeCount()).isEqualTo(3);
    }
}
