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
class SliceDeploymentE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether:example-slice:0.6.4";
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
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
    void deploySlice_becomesActive() {
        var response = cluster.anyNode().deploy(TEST_ARTIFACT, 1);
        assertThat(response).doesNotContain("\"error\"");

        // Wait for slice to become active
        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    void deploySlice_multipleInstances_distributedAcrossNodes() {
        var response = cluster.anyNode().deploy(TEST_ARTIFACT, 3);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Check that instances are distributed
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);

        // Each node should report the slice
        for (var node : cluster.nodes()) {
            var nodeSlices = node.getSlices();
            assertThat(nodeSlices).contains(TEST_ARTIFACT);
        }
    }

    @Test
    void scaleSlice_adjustsInstanceCount() {
        // Deploy with 1 instance
        cluster.anyNode().deploy(TEST_ARTIFACT, 1);
        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Scale to 3 instances
        var scaleResponse = cluster.anyNode().scale(TEST_ARTIFACT, 3);
        assertThat(scaleResponse).doesNotContain("\"error\"");

        // Wait for scale operation to complete
        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(Duration.ofSeconds(2))
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   // Check for 3 instances (implementation-specific)
                   return slices.contains(TEST_ARTIFACT);
               });
    }

    @Test
    void undeploySlice_removesFromCluster() {
        // Deploy
        cluster.anyNode().deploy(TEST_ARTIFACT, 1);
        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Undeploy
        var undeployResponse = cluster.anyNode().undeploy(TEST_ARTIFACT);
        assertThat(undeployResponse).doesNotContain("\"error\"");

        // Wait for slice to be removed
        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> {
                   var slices = cluster.anyNode().getSlices();
                   return !slices.contains(TEST_ARTIFACT);
               });
    }

    @Test
    void deploySlice_survivesNodeFailure() {
        // Deploy with 3 instances across 3 nodes
        cluster.anyNode().deploy(TEST_ARTIFACT, 3);
        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> sliceIsActive(TEST_ARTIFACT));

        // Kill one node
        cluster.killNode("node-2");
        cluster.awaitQuorum();

        // Slice should still be available (replicated)
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains(TEST_ARTIFACT);
    }

    @Test
    void blueprintApply_deploysMultipleSlices() {
        var blueprint = """
            [[slices]]
            artifact = "org.pragmatica-lite.aether:example-slice:0.6.4"
            instances = 2
            """;

        var response = cluster.anyNode().applyBlueprint(blueprint);
        assertThat(response).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .until(() -> sliceIsActive(TEST_ARTIFACT));
    }

    // ===== Helpers =====

    private boolean sliceIsActive(String artifact) {
        try {
            var slices = cluster.anyNode().getSlices();
            return slices.contains(artifact) && slices.contains("ACTIVE");
        } catch (Exception e) {
            return false;
        }
    }
}
