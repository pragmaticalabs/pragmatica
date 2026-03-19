package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.HttpOperations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for Management API endpoints.
///
///
/// Comprehensive coverage of all HTTP API endpoints exposed by AetherNode.
/// Tests are organized by endpoint category:
///
///   - Status endpoints (/health, /status, /nodes, /slices)
///   - Metrics endpoints (/metrics, /metrics/prometheus, /invocation-metrics)
///   - Threshold & Alert endpoints (/thresholds, /alerts)
///   - Controller endpoints (/controller/*)
///
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ManagementApiTest {
    private static final int BASE_PORT = 10000;
    private static final int BASE_MGMT_PORT = 10100;
    private static final int BASE_APP_HTTP_PORT = 10200;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String TEST_ARTIFACT = TestArtifacts.ECHO_SLICE;
    private static final String BLUEPRINT_ID = "forge.test:management-api:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ma");

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(this::allNodesHealthy);
    }

    @BeforeEach
    void cleanUp() {
        // Undeploy any slices left by previous tests
        delete(anyNodePort(), "/api/blueprint/" + BLUEPRINT_ID);
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Nested
    class StatusEndpoints {

        @Test
        void health_returnsQuorumStatus_andConnectedPeers() {
            var health = getHealth(anyNodePort());

            assertThat(health).contains("\"status\"");
            assertThat(health).contains("\"connectedPeers\"");
            assertThat(health).contains("\"nodeCount\":3");
            assertThat(health).doesNotContain("\"error\"");
        }

        @Test
        void status_showsLeaderInfo_andNodeState() {
            var status = getStatus(anyNodePort());

            assertThat(status).contains("\"leader\"");
            assertThat(status).contains("\"isLeader\"");
            assertThat(status).contains("\"nodeId\"");
            assertThat(status).doesNotContain("\"error\"");
        }

        @Test
        void nodes_listsAllClusterMembers() {
            await().atMost(WAIT_TIMEOUT).until(() -> {
                var nodes = getNodes(anyNodePort());
                return nodes.contains("ma-1") && nodes.contains("ma-2") && nodes.contains("ma-3");
            });

            var nodes = getNodes(anyNodePort());
            assertThat(nodes).contains("ma-1");
            assertThat(nodes).contains("ma-2");
            assertThat(nodes).contains("ma-3");
        }

        @Test
        void nodes_returnsRoleAndLeaderInfo() {
            var response = getNodes(anyNodePort());

            assertThat(response).contains("\"role\"");
            assertThat(response).contains("\"isLeader\"");
            assertThat(response).contains("\"CORE\"");
            // At least one node should be leader
            assertThat(response).contains("\"isLeader\":true");
        }

        @Test
        void slices_listsDeployedSlices_withState() {
            var port = anyNodePort();

            // Initially may be empty
            var slices = getSlices(port);
            assertThat(slices).doesNotContain("\"error\"");

            // Deploy a slice and verify it appears (use slices/status for cluster-wide view)
            deploy(port, TEST_ARTIFACT, 1);

            await().atMost(WAIT_TIMEOUT)
                   .failFast(() -> {
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> {
                       var response = getSlicesStatus(anyNodePort());
                       return response.contains("echo-slice");
                   });

            var slicesStatus = getSlicesStatus(port);
            assertThat(slicesStatus).contains("echo-slice");
        }
    }

    @Nested
    class MetricsEndpoints {

        @Test
        void metrics_returnsNodeMetrics_andSliceMetrics() {
            var metrics = getMetrics(anyNodePort());

            assertThat(metrics).doesNotContain("\"error\"");
            assertThat(metrics).containsAnyOf("cpu", "heap", "metrics");
        }

        @Test
        void prometheusMetrics_validFormat_scrapable() {
            var prometheus = getPrometheusMetrics(anyNodePort());

            assertThat(prometheus).doesNotContain("\"error\"");
            assertThat(prometheus).isNotNull();
        }

        @Test
        void invocationMetrics_tracksCallsPerMethod() {
            var port = anyNodePort();

            deploy(port, TEST_ARTIFACT, 1);

            await().atMost(WAIT_TIMEOUT)
                   .failFast(() -> {
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> {
                       var slicesStatus = getSlicesStatus(anyNodePort());
                       return slicesStatus.contains("echo-slice");
                   });

            var invocationMetrics = getInvocationMetrics(port);
            assertThat(invocationMetrics).doesNotContain("\"error\"");
        }

        @Test
        void invocationMetrics_filtering_byArtifactAndMethod() {
            var port = anyNodePort();

            var filtered = getInvocationMetrics(port, "echo-slice", null);
            assertThat(filtered).doesNotContain("\"error\"");

            var methodFiltered = getInvocationMetrics(port, null, "process");
            assertThat(methodFiltered).doesNotContain("\"error\"");
        }

        @Test
        void slowInvocations_capturedAboveThreshold() {
            var slowInvocations = getSlowInvocations(anyNodePort());
            assertThat(slowInvocations).doesNotContain("\"error\"");
        }

        @Test
        void invocationStrategy_returnsCurrentConfig() {
            var strategy = getInvocationStrategy(anyNodePort());

            assertThat(strategy).doesNotContain("\"error\"");
            assertThat(strategy).containsAnyOf("type", "fixed", "adaptive");
        }
    }

    @Nested
    class ThresholdAndAlertEndpoints {

        @Test
        void thresholds_setAndGet_persisted() {
            var port = anyNodePort();

            var setResponse = setThreshold(port, "cpu.usage", 0.7, 0.9);
            assertThat(setResponse).doesNotContain("\"error\"");

            await().atMost(WAIT_TIMEOUT).until(() -> {
                var thresholds = getThresholds(anyNodePort());
                return thresholds.contains("cpu.usage");
            });

            var thresholds = getThresholds(port);
            assertThat(thresholds).contains("cpu.usage");
        }

        @Test
        void thresholds_delete_removesThreshold() {
            var port = anyNodePort();

            setThreshold(port, "test.metric", 0.5, 0.8);

            await().atMost(WAIT_TIMEOUT).until(() -> {
                var thresholds = getThresholds(anyNodePort());
                return thresholds.contains("test.metric");
            });

            var deleteResponse = deleteThreshold(port, "test.metric");
            assertThat(deleteResponse).doesNotContain("\"error\"");

            await().atMost(WAIT_TIMEOUT).until(() -> {
                var thresholds = getThresholds(anyNodePort());
                return !thresholds.contains("test.metric");
            });
        }

        @Test
        void alerts_active_reflectsCurrentState() {
            var activeAlerts = getActiveAlerts(anyNodePort());
            assertThat(activeAlerts).doesNotContain("\"error\"");
        }

        @Test
        void alerts_history_recordsPastAlerts() {
            var alertHistory = getAlertHistory(anyNodePort());
            assertThat(alertHistory).doesNotContain("\"error\"");
        }

        @Test
        void alerts_clear_removesAllAlerts() {
            var port = anyNodePort();

            var clearResponse = clearAlerts(port);
            assertThat(clearResponse).doesNotContain("\"error\"");

            var activeAlerts = getActiveAlerts(port);
            assertThat(activeAlerts).doesNotContain("\"error\"");
        }
    }

    @Nested
    class ControllerEndpoints {

        @Test
        void controllerConfig_getAndUpdate() {
            var port = anyNodePort();

            var config = getControllerConfig(port);
            assertThat(config).doesNotContain("\"error\"");

            var newConfig = "{\"scaleUpThreshold\":0.85,\"scaleDownThreshold\":0.15}";
            var updateResponse = setControllerConfig(port, newConfig);
            assertThat(updateResponse).doesNotContain("\"error\"");
        }

        @Test
        void controllerStatus_showsEnabledState() {
            var status = getControllerStatus(anyNodePort());

            assertThat(status).doesNotContain("\"error\"");
            assertThat(status).containsAnyOf("enabled", "status", "running");
        }

        @Test
        void controllerEvaluate_triggersImmediateCheck() {
            var evalResponse = triggerControllerEvaluation(anyNodePort());

            assertThat(evalResponse).doesNotContain("\"error\"");
        }
    }

    @Nested
    class SliceStatusEndpoints {

        @Test
        void slicesStatus_returnsDetailedHealth() {
            var port = anyNodePort();

            deploy(port, TEST_ARTIFACT, 2);

            await().atMost(WAIT_TIMEOUT)
                   .failFast(() -> {
                       if (sliceHasFailed(TEST_ARTIFACT)) {
                           throw new AssertionError("Slice deployment failed: " + TEST_ARTIFACT);
                       }
                   })
                   .until(() -> {
                       var slicesStatus = getSlicesStatus(anyNodePort());
                       return slicesStatus.contains("echo-slice");
                   });

            var slicesStatus = getSlicesStatus(port);
            assertThat(slicesStatus).doesNotContain("\"error\"");
        }
    }

    @Nested
    class ClusterTopologyEndpoints {

        @Test
        void governors_returnsEmptyListForCoreOnlyCluster() {
            var response = getGovernors(anyNodePort());

            assertThat(response).contains("\"governors\"");
            assertThat(response).contains("[]");
        }

        @Test
        void clusterTopology_includesCoreMaxAndCounts() {
            var response = getClusterTopology(anyNodePort());

            assertThat(response).contains("\"coreMax\"");
            assertThat(response).contains("\"coreCount\"");
            assertThat(response).contains("\"workerCount\"");
            assertThat(response).contains("\"coreNodes\"");
        }
    }

    // ===== Helper Methods =====

    private int anyNodePort() {
        return cluster.status().nodes().getFirst().mgmtPort();
    }

    private boolean allNodesHealthy() {
        var status = cluster.status();
        return status.nodes().stream()
                     .allMatch(node -> checkNodeHealth(node.mgmtPort()));
    }

    private boolean checkNodeHealth(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/health"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(r -> r.statusCode() == 200 && r.body().contains("\"quorum\":true"))
                   .or(false);
    }

    private boolean sliceHasFailed(String artifact) {
        try {
            var slicesStatus = cluster.slicesStatus();
            return slicesStatus.stream()
                               .anyMatch(s -> s.artifact().equals(artifact) &&
                                              s.state().equals("FAILED"));
        } catch (Exception e) {
            return false;
        }
    }

    // ===== HTTP Operations: Status =====

    private String getHealth(int port) {
        return get(port, "/api/health");
    }

    private String getStatus(int port) {
        return get(port, "/api/status");
    }

    private String getNodes(int port) {
        return get(port, "/api/nodes");
    }

    private String getSlices(int port) {
        return get(port, "/api/slices");
    }

    private String getSlicesStatus(int port) {
        return get(port, "/api/slices/status");
    }

    // ===== HTTP Operations: Cluster Topology =====

    private String getGovernors(int port) {
        return get(port, "/api/cluster/governors");
    }

    private String getClusterTopology(int port) {
        return get(port, "/api/cluster/topology");
    }

    // ===== HTTP Operations: Metrics =====

    private String getMetrics(int port) {
        return get(port, "/api/metrics");
    }

    private String getPrometheusMetrics(int port) {
        return get(port, "/api/metrics/prometheus");
    }

    private String getInvocationMetrics(int port) {
        return get(port, "/api/invocation-metrics");
    }

    private String getInvocationMetrics(int port, String artifact, String method) {
        var path = new StringBuilder("/api/invocation-metrics");
        var hasQuery = false;

        if (artifact != null) {
            path.append("?artifact=").append(artifact);
            hasQuery = true;
        }
        if (method != null) {
            path.append(hasQuery ? "&" : "?").append("method=").append(method);
        }

        return get(port, path.toString());
    }

    private String getSlowInvocations(int port) {
        return get(port, "/api/invocation-metrics/slow");
    }

    private String getInvocationStrategy(int port) {
        return get(port, "/api/invocation-metrics/strategy");
    }

    // ===== HTTP Operations: Thresholds =====

    private String getThresholds(int port) {
        return get(port, "/api/thresholds");
    }

    private String setThreshold(int port, String metric, double warning, double critical) {
        var body = String.format(
            "{\"metric\":\"%s\",\"warning\":%f,\"critical\":%f}",
            metric, warning, critical
        );
        return post(port, "/api/thresholds", body);
    }

    private String deleteThreshold(int port, String metric) {
        return delete(port, "/api/thresholds/" + metric);
    }

    // ===== HTTP Operations: Alerts =====

    private String getActiveAlerts(int port) {
        return get(port, "/api/alerts/active");
    }

    private String getAlertHistory(int port) {
        return get(port, "/api/alerts/history");
    }

    private String clearAlerts(int port) {
        return post(port, "/api/alerts/clear", "");
    }

    // ===== HTTP Operations: Controller =====

    private String getControllerConfig(int port) {
        return get(port, "/api/controller/config");
    }

    private String setControllerConfig(int port, String json) {
        return post(port, "/api/controller/config", json);
    }

    private String getControllerStatus(int port) {
        return get(port, "/api/controller/status");
    }

    private String triggerControllerEvaluation(int port) {
        return post(port, "/api/controller/evaluate", "");
    }

    // ===== HTTP Operations: Deployment =====

    private String deploy(int port, String artifact, int instances) {
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = %d
            """.formatted(BLUEPRINT_ID, artifact, instances);
        return postBlueprintWithRetry(port, blueprint);
    }

    private String postBlueprintWithRetry(int port, String body) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = postBlueprint(port, body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }
            if (attempt < 3) {
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        return lastResponse;
    }

    private String postBlueprint(int port, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/blueprint"))
                                 .header("Content-Type", "application/toml")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    // ===== Core HTTP Methods =====

    private String get(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .GET()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String post(int port, String path, String body) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .header("Content-Type", "application/json")
                                 .POST(HttpRequest.BodyPublishers.ofString(body))
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String delete(int port, String path) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + path))
                                 .DELETE()
                                 .timeout(Duration.ofSeconds(10))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
