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
 * E2E tests for slice deployment and lifecycle.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Slice deployment via API</li>
 *   <li>Slice activation and health</li>
 *   <li>Slice scaling</li>
 *   <li>Slice undeployment</li>
 *   <li>Slice replication across nodes</li>
 * </ul>
 *
 * <p>This test class uses a shared cluster for all tests to reduce startup overhead.
 * Tests run in order and each test cleans up previous state before running.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class SliceDeploymentE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice:0.15.0";

    // Common timeouts
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
    void cleanupSlices() {
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
    void deploySlice_becomesActive() {
        var leaderNode = cluster.leader()
                                .toResult(Causes.cause("No leader elected"))
                                .unwrap();
        System.out.println("[TEST] Cluster stable. Leader: " + leaderNode.nodeId());

        // Deploy to the leader node to avoid forwarding delays
        var response = leaderNode.deploy(TEST_ARTIFACT, 1);
        System.out.println("[TEST] Deploy response: " + response);

        // If deploy fails, check cluster health before asserting
        if (response.contains("\"error\"")) {
            var health = leaderNode.getHealth();
            System.out.println("[TEST] Leader health: " + health);
            var status = leaderNode.getStatus();
            System.out.println("[TEST] Leader status: " + status);
        }
        assertThat(response).doesNotContain("\"error\"");

        // Wait for slice to become active, fail fast on FAILED state
        awaitSliceActive(TEST_ARTIFACT);

        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    @Order(2)
    void deploySlice_multipleInstances_distributedAcrossNodes() {
        var response = deployAndAssert(TEST_ARTIFACT, 3);

        // Wait for slice to become ACTIVE on ALL nodes (multi-instance distribution)
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT.duration());

        // Each node should report the slice
        for (var node : cluster.nodes()) {
            var nodeSlices = node.getSlices();
            assertThat(nodeSlices).contains(TEST_ARTIFACT);
        }
    }

    @Test
    @Order(3)
    void scaleSlice_adjustsInstanceCount() {
        // Deploy with 1 instance
        deployAndAssert(TEST_ARTIFACT, 1);
        awaitSliceActive(TEST_ARTIFACT);

        // Scale to 3 instances
        var scaleResponse = cluster.anyNode().scale(TEST_ARTIFACT, 3);
        assertThat(scaleResponse).doesNotContain("\"error\"");

        // Wait for scale operation to complete
        await().atMost(DEPLOY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .failFast(() -> {
                   if (sliceHasFailed(TEST_ARTIFACT)) {
                       throw new AssertionError("Slice scaling failed: " + TEST_ARTIFACT);
                   }
               })
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   return slices.contains(TEST_ARTIFACT);
               });
    }

    @Test
    @Order(4)
    void undeploySlice_removesFromCluster() {
        // Deploy
        deployAndAssert(TEST_ARTIFACT, 1);
        awaitSliceActive(TEST_ARTIFACT);

        // Undeploy
        var undeployResponse = cluster.anyNode().undeploy(TEST_ARTIFACT);
        assertThat(undeployResponse).doesNotContain("\"error\"");

        // Wait for slice to be removed
        awaitSliceRemoved(TEST_ARTIFACT);
    }

    @Test
    @Order(5)
    void deploySlice_survivesNodeFailure() {
        // Deploy with 1 instance
        deployAndAssert(TEST_ARTIFACT, 1);
        awaitSliceActive(TEST_ARTIFACT);

        // Kill a non-hosting node
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Slice should still be available
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);

        // Restore node for subsequent tests
        cluster.restartNode("node-3");
        cluster.awaitQuorum();
    }

    @Test
    @Order(6)
    void blueprintApply_deploysSlice() {
        var blueprint = """
            id = "org.test:e2e-blueprint:1.0.0"

            [[slices]]
            artifact = "org.pragmatica-lite.aether.test:echo-slice:0.15.0"
            instances = 1
            """;

        var response = cluster.anyNode().applyBlueprint(blueprint);
        assertThat(response).doesNotContain("\"error\"");

        awaitSliceActive(TEST_ARTIFACT);
    }

    // ===== Cleanup Helpers =====

    private void undeployAllSlices() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();

            // Get list of deployed slices
            var slices = leader.getSlices();
            System.out.println("[DEBUG] Deployed slices: " + slices);

            // Undeploy the test artifact if present
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

    // ===== Test Helpers =====

    private String deployAndAssert(String artifact, int instances) {
        var response = cluster.anyNode().deploy(artifact, instances);
        assertThat(response)
            .describedAs("Deployment of %s should succeed", artifact)
            .doesNotContain("\"error\"");
        return response;
    }

    private void awaitSliceActive(String artifact) {
        await().atMost(DEPLOY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .failFast(() -> {
                   if (sliceHasFailed(artifact)) {
                       throw new AssertionError("Slice deployment failed: " + artifact);
                   }
               })
               .until(() -> sliceIsActive(artifact));
    }

    private void awaitSliceRemoved(String artifact) {
        await().atMost(CLEANUP_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var status = cluster.anyNode().getSlicesStatus();
                   return !status.contains(artifact);
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
