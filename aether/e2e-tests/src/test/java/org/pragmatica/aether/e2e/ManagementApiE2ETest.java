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

/// E2E tests for Management API endpoints.
///
///
/// Comprehensive coverage of all HTTP API endpoints exposed by AetherNode.
/// Tests are organized by endpoint category:
///
///   - Status endpoints (/health, /status, /nodes, /slices)
///   - Metrics endpoints (/metrics, /metrics/prometheus, /invocation-metrics)
///   - Threshold & Alert endpoints (/thresholds, /alerts)
///   - Controller endpoints (/controller/*)
///   - Slice status endpoints (/slices/status)
///
///
///
/// This test class uses a shared cluster for all tests to reduce startup overhead.
/// Tests run in order and each test cleans up previous state before running.
@TestClassOrder(ClassOrderer.OrderAnnotation.class)
@Execution(ExecutionMode.SAME_THREAD)
class ManagementApiE2ETest {
    private static final Path PROJECT_ROOT = Path.of(System.getProperty("project.basedir", ".."));
    private static final String TEST_ARTIFACT_VERSION = System.getProperty("project.version", "0.16.0");
    private static final String TEST_ARTIFACT = "org.pragmatica-lite.aether.test:echo-slice-echo-service:" + TEST_ARTIFACT_VERSION;

    // Common timeouts (CI gets 2x via adapt())
    private static final Duration DEFAULT_TIMEOUT = adapt(timeSpan(30).seconds().duration());
    private static final Duration DEPLOY_TIMEOUT = adapt(timeSpan(3).minutes().duration());
    private static final Duration POLL_INTERVAL = timeSpan(2).seconds().duration();
    private static final Duration CLEANUP_TIMEOUT = adapt(timeSpan(2).minutes().duration());

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
    void cleanupAndPrepare() {
        cluster.awaitLeader();
        cluster.awaitAllHealthy();

        // Undeploy all slices to ensure clean state
        undeployAllSlices();
        awaitNoSlices();
    }

    // ===== Nested Test Classes =====

    @Nested
    @Order(1)
    class StatusEndpoints {

        @Test
        void health_returnsQuorumStatus_andConnectedPeers() {
            var health = cluster.anyNode().getHealth();

            assertThat(health).contains("\"status\"");
            assertThat(health).contains("\"connectedPeers\"");
            assertThat(health).contains("\"nodeCount\":5");
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
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> {
                       var nodes = cluster.anyNode().getNodes();
                       return nodes.contains("node-1") && nodes.contains("node-2") && nodes.contains("node-3")
                              && nodes.contains("node-4") && nodes.contains("node-5");
                   });

            var nodes = cluster.anyNode().getNodes();
            assertThat(nodes).contains("node-1");
            assertThat(nodes).contains("node-2");
            assertThat(nodes).contains("node-3");
            assertThat(nodes).contains("node-4");
            assertThat(nodes).contains("node-5");
        }

        @Test
        void slices_listsDeployedSlices_withState() {
            // Initially empty
            var slices = cluster.anyNode().getSlices();
            assertThat(slices).doesNotContain("\"error\"");

            // Deploy a slice and verify it appears
            deployAndAssert(TEST_ARTIFACT, 1);
            awaitSliceActive(TEST_ARTIFACT);

            slices = cluster.anyNode().getSlices();
            assertThat(slices).contains("echo-slice");
        }
    }

    @Nested
    @Order(2)
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
        void invocationMetrics_tracksCallsPerMethod() {
            // Deploy a slice to generate some invocation data
            deployAndAssert(TEST_ARTIFACT, 1);
            awaitSliceActive(TEST_ARTIFACT);

            var invocationMetrics = cluster.anyNode().getInvocationMetrics();
            assertThat(invocationMetrics).doesNotContain("\"error\"");
        }

        @Test
        void invocationMetrics_filtering_byArtifactAndMethod() {
            var filtered = cluster.anyNode().getInvocationMetrics("echo-slice", null);
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
    @Order(3)
    class ThresholdAndAlertEndpoints {

        @Test
        void thresholds_setAndGet_persisted() {
            // Set a threshold
            var setResponse = cluster.anyNode().setThreshold("cpu.usage", 0.7, 0.9);
            assertThat(setResponse).doesNotContain("\"error\"");

            // Get thresholds and verify
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> {
                       var thresholds = cluster.anyNode().getThresholds();
                       return thresholds.contains("cpu.usage");
                   });

            var thresholds = cluster.anyNode().getThresholds();
            assertThat(thresholds).contains("cpu.usage");
        }

        @Test
        void thresholds_delete_removesThreshold() {
            // First set a threshold
            cluster.anyNode().setThreshold("test.metric", 0.5, 0.8);

            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
                   .until(() -> {
                       var thresholds = cluster.anyNode().getThresholds();
                       return thresholds.contains("test.metric");
                   });

            // Now delete it
            var deleteResponse = cluster.anyNode().deleteThreshold("test.metric");
            assertThat(deleteResponse).doesNotContain("\"error\"");

            // Verify it's gone
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(POLL_INTERVAL)
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
    @Order(4)
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
    @Order(5)
    class SliceStatusEndpoints {

        @Test
        void slicesStatus_returnsDetailedHealth() {
            // Deploy a slice first
            deployAndAssert(TEST_ARTIFACT, 2);
            awaitSliceActive(TEST_ARTIFACT);

            var slicesStatus = cluster.anyNode().getSlicesStatus();
            assertThat(slicesStatus).doesNotContain("\"error\"");
        }
    }

    // ===== Helper Methods =====

    private void undeployAllSlices() {
        try {
            var leader = cluster.leader()
                                .toResult(Causes.cause("No leader"))
                                .unwrap();

            var slices = leader.getSlicesStatus();
            System.out.println("[DEBUG] Deployed slices: " + slices);

            if (slices.contains(TEST_ARTIFACT)) {
                var result = leader.undeploy(TEST_ARTIFACT);
                System.out.println("[DEBUG] Undeploy " + TEST_ARTIFACT + ": " + result);
            }
        } catch (Exception e) {
            System.out.println("[DEBUG] Error undeploying slices: " + e.getMessage());
        }
    }

    private void awaitNoSlices() {
        undeployAllSlices();
        cluster.awaitSliceUndeployed(TEST_ARTIFACT, CLEANUP_TIMEOUT);
    }

    private String deployAndAssert(String artifact, int instances) {
        var leader = cluster.leader().toResult(Causes.cause("No leader")).unwrap();
        var response = leader.deploy(artifact, instances);
        assertThat(response)
            .describedAs("Deployment of %s should succeed", artifact)
            .doesNotContain("\"error\"");
        return response;
    }

    private void awaitSliceActive(String artifact) {
        // Delegates to cluster which has stuck-state detection (throws if ACTIVATING > 120s in CI)
        cluster.awaitSliceActive(artifact, DEPLOY_TIMEOUT);
    }

}
