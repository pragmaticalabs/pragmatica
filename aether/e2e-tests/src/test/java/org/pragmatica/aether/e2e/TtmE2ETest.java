package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.e2e.containers.AetherCluster;
import org.pragmatica.aether.e2e.containers.AetherNodeContainer;
import org.pragmatica.lang.utils.Causes;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.e2e.TestEnvironment.adapt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * E2E tests for TTM (Tiny Time Mixers) predictive scaling.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>TTM status API endpoint</li>
 *   <li>TTM disabled state (no model file)</li>
 *   <li>TTM state consistency across cluster</li>
 *   <li>TTM leader-only behavior</li>
 * </ul>
 *
 * <p>Note: These tests verify TTM infrastructure, not prediction accuracy.
 * Prediction accuracy testing requires a trained ONNX model and is better
 * suited for unit tests with mocked predictors.
 *
 * <p>This test class uses a shared cluster with node restoration between tests.
 * Tests run in order to ensure deterministic behavior.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class TtmE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));

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
    class TtmStatusEndpoint {

        @Test
        @Order(1)
        void ttmStatus_returnsValidJson() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            assertThat(status).doesNotContain("\"error\"");
            assertThat(status).contains("\"enabled\":");
            assertThat(status).contains("\"state\":");
        }

        @Test
        @Order(2)
        void ttmStatus_showsDisabledByDefault() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            // TTM is disabled by default (no model file in container)
            assertThat(status).contains("\"enabled\":false");
            assertThat(status).contains("\"state\":\"STOPPED\"");
        }

        @Test
        @Order(3)
        void ttmStatus_includesConfigurationDetails() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            assertThat(status).contains("\"inputWindowMinutes\":");
            assertThat(status).contains("\"evaluationIntervalMs\":");
            assertThat(status).contains("\"confidenceThreshold\":");
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TtmClusterBehavior {

        @Test
        @Order(1)
        void ttmStatus_availableOnAllNodes() {
            cluster.awaitLeader();

            // All nodes should expose TTM status endpoint
            for (var node : cluster.nodes()) {
                var status = node.getTtmStatus();
                assertThat(status).doesNotContain("\"error\"");
                assertThat(status).contains("\"state\":");
            }
        }

        @Test
        @Order(2)
        void ttmStatus_consistentAcrossCluster() {
            cluster.awaitLeader();

            // All nodes should report consistent TTM state
            var statuses = cluster.nodes().stream()
                                  .map(AetherNodeContainer::getTtmStatus)
                                  .toList();

            // All should show disabled (no model)
            for (var status : statuses) {
                assertThat(status).contains("\"enabled\":false");
            }
        }

        @Test
        @Order(3)
        void ttmStatus_survivesLeaderFailure() {
            cluster.awaitLeader();

            // Get initial status
            var initialStatus = cluster.anyNode().getTtmStatus();
            assertThat(initialStatus).doesNotContain("\"error\"");

            // Kill the actual leader
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();
            cluster.killNode(leader.nodeId());

            // Wait for new quorum
            cluster.awaitQuorum();
            cluster.awaitLeader();

            // TTM status should still be available
            var newStatus = cluster.anyNode().getTtmStatus();
            assertThat(newStatus).doesNotContain("\"error\"");
            assertThat(newStatus).contains("\"state\":");
        }
    }

    @Nested
    @TestMethodOrder(MethodOrderer.OrderAnnotation.class)
    class TtmNoForecastWhenDisabled {

        @Test
        @Order(1)
        void ttmStatus_showsNoForecastWhenDisabled() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            // When disabled, no forecast should be present
            assertThat(status).contains("\"hasForecast\":false");
            assertThat(status).doesNotContain("\"lastForecast\"");
        }
    }
}
