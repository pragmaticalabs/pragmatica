package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.e2e.containers.AetherCluster;

import java.nio.file.Path;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

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
 */
class TtmE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(30);
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

    @Nested
    class TtmStatusEndpoint {

        @Test
        void ttmStatus_returnsValidJson() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            assertThat(status).doesNotContain("\"error\"");
            assertThat(status).contains("\"enabled\":");
            assertThat(status).contains("\"state\":");
        }

        @Test
        void ttmStatus_showsDisabledByDefault() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            // TTM is disabled by default (no model file in container)
            assertThat(status).contains("\"enabled\":false");
            assertThat(status).contains("\"state\":\"STOPPED\"");
        }

        @Test
        void ttmStatus_includesConfigurationDetails() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            assertThat(status).contains("\"inputWindowMinutes\":");
            assertThat(status).contains("\"evaluationIntervalMs\":");
            assertThat(status).contains("\"confidenceThreshold\":");
        }
    }

    @Nested
    class TtmClusterBehavior {

        @Test
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
        void ttmStatus_consistentAcrossCluster() {
            cluster.awaitLeader();

            // All nodes should report consistent TTM state
            var statuses = cluster.nodes().stream()
                                  .map(node -> node.getTtmStatus())
                                  .toList();

            // All should show disabled (no model)
            for (var status : statuses) {
                assertThat(status).contains("\"enabled\":false");
            }
        }

        @Test
        void ttmStatus_survivesLeaderFailure() {
            cluster.awaitLeader();

            // Get initial status
            var initialStatus = cluster.anyNode().getTtmStatus();
            assertThat(initialStatus).doesNotContain("\"error\"");

            // Kill the leader
            cluster.killNode("node-1");

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
    class TtmNoForecastWhenDisabled {

        @Test
        void ttmStatus_showsNoForecastWhenDisabled() {
            cluster.awaitLeader();

            var status = cluster.anyNode().getTtmStatus();

            // When disabled, no forecast should be present
            assertThat(status).contains("\"hasForecast\":false");
            assertThat(status).doesNotContain("\"lastForecast\"");
        }
    }
}
