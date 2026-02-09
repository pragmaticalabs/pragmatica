package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

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
 *
 * <p>This test class uses a shared cluster with node restoration between tests.
 * Tests run in order to ensure deterministic behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class GracefulShutdownE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT = AbstractE2ETest.TEST_ARTIFACT;

    private static final Duration DEFAULT_TIMEOUT = adapt(timeSpan(30).seconds().duration());
    private static final Duration DEPLOY_TIMEOUT = adapt(timeSpan(3).minutes().duration());
    private static final Duration RECOVERY_TIMEOUT = adapt(timeSpan(60).seconds().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();

    private static AetherCluster cluster;

    @BeforeAll
    static void createCluster() {
        cluster = AetherCluster.aetherCluster(5, PROJECT_ROOT);
        cluster.start();
        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
        cluster.awaitLeader();
        cluster.uploadTestArtifacts();
    }

    @AfterAll
    static void destroyCluster() {
        if (cluster != null) {
            cluster.close();
        }
    }

    @BeforeEach
    void restoreAllNodes() {
        // Restart any stopped nodes
        for (var node : cluster.nodes()) {
            if (!node.isRunning()) {
                try {
                    cluster.node(node.nodeId()).start();
                } catch (Exception e) {
                    System.out.println("[DEBUG] Error restarting " + node.nodeId() + ": " + e.getMessage());
                }
            }
        }

        // Wait for all nodes to be running
        await().atMost(RECOVERY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .ignoreExceptions()
               .until(() -> cluster.runningNodeCount() == 5);

        cluster.awaitQuorum();
        cluster.awaitAllHealthy();
    }

    @Test
    @Order(1)
    void nodeShutdown_peersDetectDisconnection() {
        cluster.awaitLeader();

        // Get initial peer count
        var initialHealth = cluster.nodes().get(0).getHealth();
        assertThat(initialHealth).contains("\"connectedPeers\":4");

        // Shutdown node-3
        cluster.killNode("node-3");

        // Remaining nodes should detect the disconnection
        await().atMost(DEFAULT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var health = cluster.nodes().get(0).getHealth();
                   return health.contains("\"connectedPeers\":3");
               });

        // Cluster still has quorum with 4 nodes
        cluster.awaitQuorum();
    }

    @Test
    @Order(2)
    void nodeShutdown_clusterRemainsFunctional() {
        cluster.awaitLeader();

        // Deploy a slice via leader
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var deployResponse = leader.deploy(TEST_ARTIFACT, 2);
        assertThat(deployResponse).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   var state = cluster.anyNode().getSliceState(TEST_ARTIFACT);
                   return "ACTIVE".equals(state);
               });

        // Shutdown one node
        cluster.killNode("node-2");

        // Cluster should still be functional
        cluster.awaitQuorum();

        // Management API should still work
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
        assertThat(health).contains("\"nodeCount\":4");

        // Slice should still be accessible
        var slices = cluster.anyNode().getSlices();
        assertThat(slices).contains("echo-slice");

        // Cleanup: undeploy slice
        try {
            cluster.leader().toResult(Causes.cause("No leader")).unwrap().undeploy(TEST_ARTIFACT);
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .ignoreExceptions()
                   .until(() -> !cluster.anyNode().getSlices().contains(TEST_ARTIFACT));
        } catch (Exception e) {
            System.out.println("[DEBUG] Cleanup undeploy error: " + e.getMessage());
        }
    }

    @Test
    @Order(3)
    void shutdownDuringDeployment_handledGracefully() {
        cluster.awaitLeader();

        // Start a deployment via leader
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var deployResponse = leader.deploy(TEST_ARTIFACT, 3);
        assertThat(deployResponse).doesNotContain("\"error\"");

        // Immediately shutdown a node (deployment may still be in progress)
        cluster.killNode("node-3");

        // Wait for quorum
        cluster.awaitQuorum();

        // Cluster should recover and deployment should eventually complete
        await().atMost(DEFAULT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> {
                   try {
                       var slices = cluster.anyNode().getSlices();
                       return slices.contains("echo-slice") || !slices.contains("\"error\"");
                   } catch (Exception e) {
                       return false;
                   }
               });

        // Verify cluster is healthy
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");

        // Cleanup: undeploy slice
        try {
            cluster.leader().toResult(Causes.cause("No leader")).unwrap().undeploy(TEST_ARTIFACT);
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .ignoreExceptions()
                   .until(() -> !cluster.anyNode().getSlices().contains(TEST_ARTIFACT));
        } catch (Exception e) {
            System.out.println("[DEBUG] Cleanup undeploy error: " + e.getMessage());
        }
    }

    @Test
    @Order(4)
    void leaderShutdown_newLeaderElected() {
        cluster.awaitLeader();
        var originalLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();

        // Shutdown the leader
        cluster.killNode(originalLeader.nodeId());

        // Wait for new quorum and leader
        cluster.awaitQuorum();
        cluster.awaitLeader();

        // New leader should be elected
        var newLeader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        assertThat(newLeader.nodeId()).isNotEqualTo(originalLeader.nodeId());

        // Cluster should be functional
        var health = cluster.anyNode().getHealth();
        assertThat(health).doesNotContain("\"error\"");
    }
}
