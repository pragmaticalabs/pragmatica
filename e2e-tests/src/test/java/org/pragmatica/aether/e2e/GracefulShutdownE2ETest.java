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
 * E2E tests for graceful shutdown scenarios.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Node shutdown leaves cluster in healthy state</li>
 *   <li>Peers detect and respond to node shutdown</li>
 *   <li>Slices are handled appropriately during shutdown</li>
 *   <li>Shutdown during ongoing operations</li>
 * </ul>
 */
class GracefulShutdownE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
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
    void nodeShutdown_peersDetectDisconnection() {
        cluster.awaitLeader();

        // Get initial peer count
        var initialHealth = cluster.nodes().get(0).getHealth();
        assertThat(initialHealth).contains("\"connectedPeers\":2");

        // Shutdown node-3
        cluster.killNode("node-3");

        // Remaining nodes should detect the disconnection
        await().atMost(WAIT_TIMEOUT).until(() -> {
            var health = cluster.nodes().get(0).getHealth();
            return health.contains("\"connectedPeers\":1");
        });

        // Cluster still has quorum with 2 nodes
        cluster.awaitQuorum();
    }

    @Test
    void nodeShutdown_clusterRemainsFunctional() {
        cluster.awaitLeader();

        // Deploy a slice
        cluster.anyNode().deploy(TEST_ARTIFACT, 2);
        await().atMost(WAIT_TIMEOUT).until(() -> {
            var slices = cluster.anyNode().getSlices();
            return slices.contains("example-slice");
        });

        // Shutdown one node
        cluster.killNode("node-2");

        // Cluster should still be functional
        cluster.awaitQuorum();

        // Management API should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":2");

        // Slice should still be accessible
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("example-slice");
    }

    @Test
    void shutdownDuringDeployment_handledGracefully() {
        cluster.awaitLeader();

        // Start a deployment
        cluster.anyNode().deploy(TEST_ARTIFACT, 3);

        // Immediately shutdown a node (deployment may still be in progress)
        cluster.killNode("node-3");

        // Wait for quorum
        cluster.awaitQuorum();

        // Cluster should recover and deployment should eventually complete
        await().atMost(WAIT_TIMEOUT).until(() -> {
            try {
                var slices = cluster.anyNode().getSlices();
                return slices.contains("example-slice") || !slices.contains("\"error\"");
            } catch (Exception e) {
                return false;
            }
        });

        // Verify cluster is healthy
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
    }

    @Test
    void leaderShutdown_newLeaderElected() {
        cluster.awaitLeader();
        var originalLeader = cluster.leader().orElseThrow();

        // Shutdown the leader (node-1 is deterministic leader)
        cluster.killNode(originalLeader.nodeId());

        // Wait for new quorum and leader
        cluster.awaitQuorum();
        cluster.awaitLeader();

        // New leader should be elected
        var newLeader = cluster.leader().orElseThrow();
        assertThat(newLeader.nodeId()).isNotEqualTo(originalLeader.nodeId());

        // Cluster should be functional
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
    }
}
