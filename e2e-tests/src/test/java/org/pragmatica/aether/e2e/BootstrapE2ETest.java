package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.time.Duration;

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
 *
 * <p>This test class uses a shared cluster for all tests to reduce startup overhead.
 * Tests run in order and each test cleans up previous state before running.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class BootstrapE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.example:inventory:0.0.1-test";

    // Common timeouts
    private static final TimeSpan RECOVERY_TIMEOUT = timeSpan(90).seconds();
    private static final TimeSpan DEPLOY_TIMEOUT = timeSpan(3).minutes();
    private static final TimeSpan POLL_INTERVAL = timeSpan(2).seconds();
    private static final TimeSpan CLEANUP_TIMEOUT = timeSpan(60).seconds();

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(3, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void restoreAndCleanup() {
        // Restore any killed nodes
        restoreAllNodes();

        // Wait for cluster stability
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        sleep(timeSpan(2).seconds());

        // Undeploy all slices
        undeployAllSlices();

        // Wait for clean state
        awaitNoSlices();
    }

    @Test
    @Order(1)
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
    @Order(2)
    void nodeRestart_recoversState() {
        cluster.awaitLeader();

        // Deploy a slice
        var leader = cluster.leader()
                            .toResult(Causes.cause("No leader"))
                            .unwrap();
        leader.deploy(TEST_ARTIFACT, 1);
        await().atMost(DEPLOY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .failFast(() -> {
                   if (sliceHasFailed(TEST_ARTIFACT)) {
                       throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                   }
               })
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Kill and restart a node
        cluster.killNode("node-3");
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 2);
        cluster.restartNode("node-3");
        await().atMost(RECOVERY_TIMEOUT.duration()).until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();

        // Slice should still be visible (state recovered from consensus)
        await().atMost(RECOVERY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> sliceIsActive(TEST_ARTIFACT));
    }

    @Test
    @Order(3)
    @Disabled("Node restart infrastructure doesn't support proper cluster rejoin - restarted nodes get new identities")
    void rollingRestart_maintainsAvailability() {
        cluster.awaitLeader();

        // Perform a rolling restart with 10 second delay between nodes
        cluster.rollingRestart(timeSpan(10).seconds().duration());

        // After rolling restart, cluster should be healthy
        cluster.awaitQuorum();
        cluster.awaitLeader();

        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    @Order(4)
    @Disabled("Node restart infrastructure doesn't support proper cluster rejoin - restarted nodes get new identities")
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

    // ===== Cleanup Helpers =====

    private void restoreAllNodes() {
        // First restart any stopped nodes
        for (int i = 1; i <= 3; i++) {
            var nodeId = "node-" + i;
            try {
                if (!cluster.node(nodeId).isRunning()) {
                    System.out.println("[DEBUG] Restarting stopped node: " + nodeId);
                    cluster.restartNode(nodeId);
                }
            } catch (Exception e) {
                System.out.println("[DEBUG] Error restarting " + nodeId + ": " + e.getMessage());
            }
        }

        // Wait for all nodes with extended timeout (containers may take longer after chaos)
        await().atMost(Duration.ofSeconds(120))
               .pollInterval(POLL_INTERVAL.duration())
               .ignoreExceptions()
               .until(() -> cluster.runningNodeCount() == 3);
    }

    private void undeployAllSlices() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();

            // Get list of deployed slices
            var slices = leader.getSlices();
            System.out.println("[DEBUG] Deployed slices: " + slices);

            // Undeploy test artifact if present
            if (slices.contains(TEST_ARTIFACT)) {
                var result = leader.undeploy(TEST_ARTIFACT);
                System.out.println("[DEBUG] Undeploy " + TEST_ARTIFACT + ": " + result);
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Error undeploying slices: " + e.getMessage());
        }
    }

    private void awaitNoSlices() {
        await().atMost(CLEANUP_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .ignoreExceptions()
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   System.out.println("[DEBUG] Waiting for no slices, current: " + slices);
                   return !slices.contains(TEST_ARTIFACT);
               });
    }

    // ===== State Helpers =====

    private boolean sliceIsActive(String artifact) {
        try {
            var state = cluster.anyNode().getSliceState(artifact);
            System.out.println("[DEBUG] Slice " + artifact + " state: " + state);
            return "ACTIVE".equals(state);
        } catch (Exception e) {
            System.out.println("[DEBUG] Error checking slice state: " + e.getMessage());
            return false;
        }
    }

    private boolean sliceHasFailed(String artifact) {
        try {
            var state = cluster.anyNode().getSliceState(artifact);
            return "FAILED".equals(state);
        } catch (Exception e) {
            return false;
        }
    }

    // ===== Utility Helpers =====

    private void sleep(TimeSpan duration) {
        try {
            Thread.sleep(duration.duration().toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
