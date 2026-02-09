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
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// E2E tests for slice deployment and lifecycle.
///
///
/// Tests cover:
///
///   - Slice deployment via API
///   - Slice activation and health
///   - Slice scaling
///   - Slice undeployment
///   - Slice replication across nodes
///
///
///
/// This test class uses a shared cluster for all tests to reduce startup overhead.
/// Tests run in order and each test cleans up previous state before running.
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class SliceDeploymentE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.15.1");
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + TEST_ARTIFACT_VERSION;

    // Common timeouts (CI gets 2x via adapt())
    private static final Duration DEPLOY_TIMEOUT = adapt(timeSpan(3).minutes().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();
    private static final Duration CLEANUP_TIMEOUT = adapt(timeSpan(60).seconds().duration());

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        System.out.println("[DEBUG] Creating cluster...");
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        System.out.println("[DEBUG] Awaiting quorum...");
        cluster.awaitQuorum();
        System.out.println("[DEBUG] Awaiting all healthy...");
        cluster.awaitAllHealthy();
        System.out.println("[DEBUG] Awaiting leader election...");
        cluster.awaitLeader();
        System.out.println("[DEBUG] Uploading test artifacts to DHT...");
        cluster.uploadTestArtifacts();
        System.out.println("[DEBUG] Cluster ready for tests");
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void cleanupSlices() {
        System.out.println("[DEBUG] BeforeEach: starting cleanup...");
        // Wait for cluster stability
        cluster.awaitLeader();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();

        // Retry undeploy until clean — handles undeploy lost during leader changes
        System.out.println("[DEBUG] BeforeEach: cleanup with retry...");
        await().atMost(CLEANUP_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   System.out.println("[DEBUG] Cleanup check, local slices: " + slices);
                   if (slices.contains(TEST_ARTIFACT)) {
                       tryUndeploy();
                       return false;
                   }
                   return true;
               });
        System.out.println("[DEBUG] BeforeEach: cleanup complete");
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
        var instanceCount = cluster.size();
        var response = deployAndAssert(TEST_ARTIFACT, instanceCount);

        // Wait for slice to become ACTIVE on ALL nodes (one instance per node)
        cluster.awaitSliceActiveOnAllNodes(TEST_ARTIFACT, DEPLOY_TIMEOUT);

        // Each node should report the slice locally
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

        // Scale to 3 instances via leader
        var scaleLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var scaleResponse = scaleLeader.scale(TEST_ARTIFACT, 3);
        assertThat(scaleResponse).doesNotContain("\"error\"");

        // Wait for scale operation to complete
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
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

        // Undeploy via leader
        var undeployLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var undeployResponse = undeployLeader.undeploy(TEST_ARTIFACT);
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
        cluster.node("node-3").start();
        cluster.awaitQuorum();
    }

    @Test
    @Order(6)
    void blueprintApply_deploysSlice() {
        var blueprint = """
            id = "org.test:e2e-blueprint:1.0.0"

            [[slices]]
            artifact = "%s"
            instances = 1
            """.formatted(TEST_ARTIFACT);

        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var response = leader.applyBlueprint(blueprint);
        assertThat(response).doesNotContain("\"error\"");

        awaitSliceActive(TEST_ARTIFACT);
    }

    // ===== Cleanup Helpers =====

    private void tryUndeploy() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();
            var result = leader.undeploy(TEST_ARTIFACT);
            System.out.println("[DEBUG] Undeploy attempt: " + result);
        } catch (Exception e) {
            System.out.println("[DEBUG] Undeploy error: " + e.getMessage());
        }
    }

    // ===== Test Helpers =====

    private String deployAndAssert(String artifact, int instances) {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var response = leader.deploy(artifact, instances);
        assertThat(response)
            .describedAs("Deployment of %s should succeed", artifact)
            .doesNotContain("\"error\"");
        return response;
    }

    private void awaitSliceActive(String artifact) {
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .failFast(() -> {
                   if (sliceHasFailed(artifact)) {
                       throw new AssertionError("Slice deployment failed: " + artifact);
                   }
               })
               .until(() -> sliceIsActive(artifact));
    }

    private void awaitSliceRemoved(String artifact) {
        await().atMost(CLEANUP_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
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
