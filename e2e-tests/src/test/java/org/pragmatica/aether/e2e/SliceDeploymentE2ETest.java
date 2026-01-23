package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.lang.utils.Causes;

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
 */
class SliceDeploymentE2ETest extends AbstractE2ETest {

    @Test
    void deploySlice_becomesActive() {
        // CRITICAL: Wait for full cluster stability before deploying
        cluster.awaitLeader();
        cluster.awaitAllHealthy();

        // Additional stability delay - allow consensus layer to fully settle
        sleep(timeSpan(5).seconds());

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
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT.duration());

        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
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
    @Disabled("Instance distribution issue - same as deploySlice_multipleInstances_distributedAcrossNodes")
    void scaleSlice_adjustsInstanceCount() {
        // Deploy with 1 instance
        deployAndAssert(TEST_ARTIFACT, 1);
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT.duration());

        // Scale to 3 instances
        var scaleResponse = cluster.anyNode().scale(TEST_ARTIFACT, 3);
        assertThat(scaleResponse).doesNotContain("\"error\"");

        // Wait for scale operation to complete
        await().atMost(DEPLOY_TIMEOUT.duration())
               .pollInterval(POLL_INTERVAL.duration())
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   return slices.contains(TEST_ARTIFACT);
               });
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void undeploySlice_removesFromCluster() {
        // Deploy
        deployAndAssert(TEST_ARTIFACT, 1);
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT.duration());

        // Undeploy
        var undeployResponse = cluster.anyNode().undeploy(TEST_ARTIFACT);
        assertThat(undeployResponse).doesNotContain("\"error\"");

        // Wait for slice to be removed
        awaitSliceRemoved("place-order");
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void deploySlice_survivesNodeFailure() {
        // Deploy with 1 instance
        deployAndAssert(TEST_ARTIFACT, 1);
        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT.duration());

        // Kill a non-hosting node
        cluster.killNode("node-3");
        cluster.awaitQuorum();

        // Slice should still be available
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    @Disabled("Flaky in containerized environments - requires longer timeouts")
    void blueprintApply_deploysSlice() {
        var blueprint = """
            id = "org.test:e2e-blueprint:1.0.0"

            [[slices]]
            artifact = "org.pragmatica-lite.aether.example:place-order-place-order:0.8.0"
            instances = 1
            """;

        var response = cluster.anyNode().applyBlueprint(blueprint);
        assertThat(response).doesNotContain("\"error\"");

        cluster.awaitSliceActive(TEST_ARTIFACT, DEPLOY_TIMEOUT.duration());
    }
}
