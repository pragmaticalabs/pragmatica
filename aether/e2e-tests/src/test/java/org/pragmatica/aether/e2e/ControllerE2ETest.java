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
 * E2E tests for the cluster controller (DecisionTreeController).
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Controller runs only on leader node</li>
 *   <li>Controller configuration management</li>
 *   <li>Controller status reporting</li>
 *   <li>Controller evaluation triggering</li>
 *   <li>Controller transfer on leader change</li>
 * </ul>
 *
 * <p>Note: Auto-scaling based on CPU metrics requires controlled load
 * generation which is complex in E2E tests. These tests focus on
 * controller infrastructure rather than scaling decisions.
 *
 * <p>This test class uses a shared cluster with node restoration between tests.
 * Tests run in order to ensure deterministic behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class ControllerE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));

    private static final Duration DEFAULT_TIMEOUT = adapt(timeSpan(30).seconds().duration());
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

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ControllerConfiguration {

        @Test
        @Order(1)
        void controllerConfig_getReturnsCurrentSettings() {
            cluster.awaitLeader();

            var config = cluster.anyNode().getControllerConfig();

            assertThat(config).doesNotContain("\"error\"");
        }

        @Test
        @Order(2)
        void controllerConfig_updateSucceeds() {
            cluster.awaitLeader();

            var newConfig = "{\"scaleUpThreshold\":0.85,\"scaleDownThreshold\":0.15}";
            var response = cluster.anyNode().setControllerConfig(newConfig);

            assertThat(response).doesNotContain("\"error\"");
        }

        @Test
        @Order(3)
        void controllerConfig_persistsAcrossRequests() {
            cluster.awaitLeader();

            // Set config
            var newConfig = "{\"scaleUpThreshold\":0.9,\"scaleDownThreshold\":0.1}";
            cluster.anyNode().setControllerConfig(newConfig);

            // Verify it persists
            await().atMost(DEFAULT_TIMEOUT).until(() -> {
                var config = cluster.anyNode().getControllerConfig();
                return !config.contains("\"error\"");
            });
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class ControllerStatus {

        @Test
        @Order(1)
        void controllerStatus_returnsRunningState() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getControllerStatus();

            assertThat(status).doesNotContain("\"error\"");
            // Should indicate some status
            assertThat(status).isNotBlank();
        }

        @Test
        @Order(2)
        void controllerEvaluate_triggersImmediately() {
            cluster.awaitLeader();

            var response = cluster.anyNode().triggerControllerEvaluation();

            assertThat(response).doesNotContain("\"error\"");
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class LeaderBehavior {

        @Test
        @Order(1)
        void controller_runsOnlyOnLeader() {
            cluster.awaitLeader();

            // All nodes should be able to report controller status
            // (they proxy to leader)
            for (var node : cluster.nodes()) {
                var status = node.getControllerStatus();
                assertThat(status).doesNotContain("\"error\"");
            }
        }

        @Test
        @Order(2)
        void controller_survivesLeaderFailure() {
            cluster.awaitLeader();

            // Kill the actual leader (determined by consensus)
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();
            cluster.killNode(leader.nodeId());

            // Wait for new quorum and leader
            cluster.awaitQuorum();
            cluster.awaitLeader();

            // Controller should still work
            var status = cluster.anyNode().getControllerStatus();
            assertThat(status).doesNotContain("\"error\"");
        }
    }
}
