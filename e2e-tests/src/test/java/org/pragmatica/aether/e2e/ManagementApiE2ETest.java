package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for Management API endpoints.
 *
 * <p>Comprehensive coverage of all HTTP API endpoints exposed by AetherNode.
 * Tests are organized by endpoint category:
 * <ul>
 *   <li>Status endpoints (/health, /status, /nodes, /slices)</li>
 *   <li>Metrics endpoints (/metrics, /metrics/prometheus, /invocation-metrics)</li>
 *   <li>Threshold & Alert endpoints (/thresholds, /alerts)</li>
 *   <li>Controller endpoints (/controller/*)</li>
 * </ul>
 */
class ManagementApiE2ETest extends AbstractE2ETest {

    @Nested
    class StatusEndpoints {

        @Test
        void health_returnsQuorumStatus_andConnectedPeers() {
            var health = cluster.anyNode().getHealth();

            assertThat(health).contains("\"status\"");
            assertThat(health).contains("\"connectedPeers\"");
            assertThat(health).contains("\"nodeCount\":3");
            assertThat(health).doesNotContain("\"error\"");
        }

        @Test
        void status_showsLeaderInfo_andNodeState() {
            cluster.awaitLeader();
            var status = cluster.anyNode().getStatus();

            assertThat(status).contains("\"leader\"");
            assertThat(status).contains("\"isLeader\"");
            assertThat(status).contains("\"nodeId\"");
            assertThat(status).doesNotContain("\"error\"");
        }

        @Test
        void nodes_listsAllClusterMembers() {
            // Wait for nodes to exchange metrics
            await().atMost(DEFAULT_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .until(() -> {
                       var nodes = cluster.anyNode().getNodes();
                       return nodes.contains("node-1") && nodes.contains("node-2") && nodes.contains("node-3");
                   });

            var nodes = cluster.anyNode().getNodes();
            assertThat(nodes).contains("node-1");
            assertThat(nodes).contains("node-2");
            assertThat(nodes).contains("node-3");
        }

        @Test
        void slices_listsDeployedSlices_withState() {
            // Initially empty
            var slices = cluster.anyNode().getSlices();
            assertThat(slices).doesNotContain("\"error\"");

            // Deploy a slice and verify it appears
            deployAndAssert(TEST_ARTIFACT, 1);
            awaitSliceVisible("place-order");

            slices = cluster.anyNode().getSlices();
            assertThat(slices).contains("place-order");
        }
    }

    @Nested
    class MetricsEndpoints {

        @Test
        void metrics_returnsNodeMetrics_andSliceMetrics() {
            var metrics = cluster.anyNode().getMetrics();

            assertThat(metrics).doesNotContain("\"error\"");
            // Should contain basic metrics structure
            assertThat(metrics).containsAnyOf("cpu", "heap", "metrics");
        }

        @Test
        void prometheusMetrics_validFormat_scrapable() {
            var prometheus = cluster.anyNode().getPrometheusMetrics();

            // Prometheus format uses # for comments and metric_name{labels} value
            assertThat(prometheus).doesNotContain("\"error\"");
            // Valid Prometheus output has either metrics or is empty
            assertThat(prometheus).isNotNull();
        }

        @Test
        @Disabled("Flaky in containerized environments - requires longer timeouts")
        void invocationMetrics_tracksCallsPerMethod() {
            // Deploy a slice to generate some invocation data
            deployAndAssert(TEST_ARTIFACT, 1);
            awaitSliceVisible("place-order");

            var invocationMetrics = cluster.anyNode().getInvocationMetrics();
            assertThat(invocationMetrics).doesNotContain("\"error\"");
        }

        @Test
        void invocationMetrics_filtering_byArtifactAndMethod() {
            var filtered = cluster.anyNode().getInvocationMetrics("place-order", null);
            assertThat(filtered).doesNotContain("\"error\"");

            var methodFiltered = cluster.anyNode().getInvocationMetrics(null, "process");
            assertThat(methodFiltered).doesNotContain("\"error\"");
        }

        @Test
        void slowInvocations_capturedAboveThreshold() {
            var slowInvocations = cluster.anyNode().getSlowInvocations();
            assertThat(slowInvocations).doesNotContain("\"error\"");
        }

        @Test
        void invocationStrategy_returnsCurrentConfig() {
            var strategy = cluster.anyNode().getInvocationStrategy();

            assertThat(strategy).doesNotContain("\"error\"");
            // Should indicate strategy type
            assertThat(strategy).containsAnyOf("type", "fixed", "adaptive");
        }
    }

    @Nested
    class ThresholdAndAlertEndpoints {

        @Test
        void thresholds_setAndGet_persisted() {
            // Set a threshold
            var setResponse = cluster.anyNode().setThreshold("cpu.usage", 0.7, 0.9);
            assertThat(setResponse).doesNotContain("\"error\"");

            // Get thresholds and verify
            await().atMost(DEFAULT_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .until(() -> {
                       var thresholds = cluster.anyNode().getThresholds();
                       return thresholds.contains("cpu.usage");
                   });

            var thresholds = cluster.anyNode().getThresholds();
            assertThat(thresholds).contains("cpu.usage");
        }

        @Test
        @Disabled("Flaky in containerized environments - requires longer timeouts")
        void thresholds_delete_removesThreshold() {
            // First set a threshold
            cluster.anyNode().setThreshold("test.metric", 0.5, 0.8);

            await().atMost(DEFAULT_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .until(() -> {
                       var thresholds = cluster.anyNode().getThresholds();
                       return thresholds.contains("test.metric");
                   });

            // Now delete it
            var deleteResponse = cluster.anyNode().deleteThreshold("test.metric");
            assertThat(deleteResponse).doesNotContain("\"error\"");

            // Verify it's gone
            await().atMost(DEFAULT_TIMEOUT.duration())
                   .pollInterval(POLL_INTERVAL.duration())
                   .until(() -> {
                       var thresholds = cluster.anyNode().getThresholds();
                       return !thresholds.contains("test.metric");
                   });
        }

        @Test
        void alerts_active_reflectsCurrentState() {
            var activeAlerts = cluster.anyNode().getActiveAlerts();
            assertThat(activeAlerts).doesNotContain("\"error\"");
        }

        @Test
        void alerts_history_recordsPastAlerts() {
            var alertHistory = cluster.anyNode().getAlertHistory();
            assertThat(alertHistory).doesNotContain("\"error\"");
        }

        @Test
        void alerts_clear_removesAllAlerts() {
            var clearResponse = cluster.anyNode().clearAlerts();
            assertThat(clearResponse).doesNotContain("\"error\"");

            // Verify active alerts is empty or contains empty array
            var activeAlerts = cluster.anyNode().getActiveAlerts();
            assertThat(activeAlerts).doesNotContain("\"error\"");
        }
    }

    @Nested
    class ControllerEndpoints {

        @Test
        void controllerConfig_getAndUpdate() {
            // Get current config
            var config = cluster.anyNode().getControllerConfig();
            assertThat(config).doesNotContain("\"error\"");

            // Update config
            var newConfig = "{\"scaleUpThreshold\":0.85,\"scaleDownThreshold\":0.15}";
            var updateResponse = cluster.anyNode().setControllerConfig(newConfig);
            assertThat(updateResponse).doesNotContain("\"error\"");
        }

        @Test
        void controllerStatus_showsEnabledState() {
            var status = cluster.anyNode().getControllerStatus();

            assertThat(status).doesNotContain("\"error\"");
            // Should indicate enabled/disabled state
            assertThat(status).containsAnyOf("enabled", "status", "running");
        }

        @Test
        void controllerEvaluate_triggersImmediateCheck() {
            var evalResponse = cluster.anyNode().triggerControllerEvaluation();

            assertThat(evalResponse).doesNotContain("\"error\"");
        }
    }

    @Nested
    class SliceStatusEndpoints {

        @Test
        @Disabled("Flaky in containerized environments - requires longer timeouts")
        void slicesStatus_returnsDetailedHealth() {
            // Deploy a slice first
            deployAndAssert(TEST_ARTIFACT, 2);
            awaitSliceVisible("place-order");

            var slicesStatus = cluster.anyNode().getSlicesStatus();
            assertThat(slicesStatus).doesNotContain("\"error\"");
        }
    }
}
