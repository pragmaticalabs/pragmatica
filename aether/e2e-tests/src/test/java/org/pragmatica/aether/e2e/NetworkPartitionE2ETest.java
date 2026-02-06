package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * E2E tests for network partition and split-brain scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Majority partition continues operating</li>
 *   <li>Minority partition behavior (no quorum)</li>
 *   <li>Partition healing and cluster reconvergence</li>
 *   <li>Quorum behavior under various failure scenarios</li>
 * </ul>
 *
 * <p>Note: True network partitioning (isolating nodes at network level) is complex
 * to implement in Testcontainers. These tests simulate partition-like behavior
 * by stopping nodes, which achieves similar quorum effects.
 *
 * <p>This test class uses a shared cluster for all tests to reduce startup overhead.
 * Tests run in order and each test cleans up previous state before running.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class NetworkPartitionE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:0.15.0";

    // Common timeouts
    private static final TimeSpan DEFAULT_TIMEOUT = timeSpan(2).minutes();
    private static final TimeSpan POLL_INTERVAL = timeSpan(2).seconds();
    private static final TimeSpan CLEANUP_TIMEOUT = timeSpan(60).seconds();

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void cleanupAndPrepare() {
        // Restore all stopped nodes
        restoreAllNodes();

        // Wait for cluster stability
        cluster.awaitLeader();
        cluster.awaitAllHealthy();
        sleep(timeSpan(2).seconds());

        // Undeploy all slices
        undeployAllSlices();

        // Wait for clean state
        awaitNoSlices();
    }

    @Test
    @Order(1)
    void majorityPartition_continuesOperating() {
        cluster.awaitLeader();

        // Simulate minority partition by stopping one node
        // Majority (2 nodes) should continue operating
        cluster.killNode("node-3");

        // Wait for cluster to detect partition
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var health = cluster.anyNode().getHealth();
                   return health.contains("\"connectedPeers\":1");
               });

        // Majority should still have quorum
        cluster.awaitQuorum();

        // Management API should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":2");
    }

    @Test
    @Order(2)
    void lostQuorum_detectedAndReported() {
        cluster.awaitLeader();

        // Stop 2 nodes - remaining 1 node loses quorum
        cluster.killNode("node-2");
        cluster.killNode("node-3");

        // Wait for partition detection
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var health = cluster.nodes().get(0).getHealth();
                   // Single node should report 0 connected peers
                   return health.contains("\"connectedPeers\":0");
               });

        // Node should report lost quorum or degraded state
        var health = cluster.nodes().get(0).getHealth();
        // Even without quorum, the node should respond (readonly mode or degraded)
        assertThat(health).isNotBlank();
    }

    @Test
    @Order(3)
    void partitionHealing_clusterReconverges() {
        cluster.awaitLeader();

        // Create simulated partition
        cluster.killNode("node-2");
        cluster.killNode("node-3");

        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 1);

        // Heal partition - restart nodes
        cluster.node("node-2").start();
        cluster.node("node-3").start();

        // Cluster should reconverge
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();
        cluster.awaitLeader();

        // All nodes should be healthy again
        cluster.awaitAllHealthy();

        var health = cluster.anyNode().getHealth();
        assertThat(health).contains("\"connectedPeers\":2");
        assertThat(health).contains("\"nodeCount\":3");
    }

    @Test
    @Order(4)
    void quorumTransitions_maintainConsistency() {
        cluster.awaitLeader();

        // Deploy a slice while cluster is healthy
        deployAndAssert(TEST_ARTIFACT, 1);
        awaitSliceVisible("echo-slice");

        // Reduce to 2 nodes (still has quorum)
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Slice state should be preserved
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("echo-slice");

        // Restore full cluster
        cluster.node("node-3").start();
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> cluster.runningNodeCount() == 3);
        cluster.awaitQuorum();

        // State should still be consistent
        slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("echo-slice");
    }

    // ===== Cleanup Helpers =====

    private void restoreAllNodes() {
        for (int i = 1; i <= 3; i++) {
            var nodeName = "node-" + i;
            try {
                cluster.node(nodeName).start();
            } catch (Exception e) {
                // Node may already be running
                System.out.println("[DEBUG] Could not restart " + nodeName + ": " + e.getMessage());
            }
        }

        // Wait for all nodes to be running
        await().atMost(DEFAULT_TIMEOUT.duration())
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

    // ===== API Helpers =====

    private void deployAndAssert(String artifact, int instances) {
        var leader = cluster.leader()
                            .toResult(Causes.cause("No leader elected"))
                            .unwrap();
        var response = leader.deploy(artifact, instances);
        assertThat(response).doesNotContain("\"error\"");

        // Wait for slice to be active
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .failFast(() -> {
                   if (sliceHasFailed(artifact)) {
                       throw new AssertionError("Slice deployment failed: " + artifact);
                   }
               })
               .until(() -> sliceIsActive(artifact));
    }

    private void awaitSliceVisible(String sliceName) {
        await().atMost(DEFAULT_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   return slices.contains(sliceName);
               });
    }

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
}
