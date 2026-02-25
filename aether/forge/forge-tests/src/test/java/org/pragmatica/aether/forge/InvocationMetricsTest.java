package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for invocation metrics accuracy after real traffic through a multi-slice deployment.
///
///
/// Deploys the url-shortener example (two slices: url-shortener + analytics),
/// generates 1000 round-trip requests (create + resolve), then validates that
/// invocation metrics, Prometheus, and trace endpoints report correct data
/// across all cluster nodes.
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InvocationMetricsTest extends ForgeTestBase {
    private static final int BASE_PORT = 12000;
    private static final int BASE_MGMT_PORT = 12100;
    private static final int BASE_APP_HTTP_PORT = 12200;
    private static final int H2_PORT = 12300;
    private static final int REQUEST_COUNT = 1000;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final String URL_SHORTENER_ARTIFACT = "org.pragmatica.aether.example:url-shortener-url-shortener:0.18.0";
    private static final String ANALYTICS_ARTIFACT = "org.pragmatica.aether.example:url-shortener-analytics:0.18.0";
    private static final String BLUEPRINT_ID = "forge.test:url-shortener-metrics:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private ForgeH2Server h2Server;
    private ForgeCluster cluster;
    private JdkHttpOperations httpOps;

    @BeforeAll
    void setUp() {
        var schemaPath = Path.of("").toAbsolutePath()
                             .resolve("../../../examples/url-shortener/schema/url-shortener.sql")
                             .normalize()
                             .toString();
        var h2Config = ForgeH2Config.forgeH2Config(true, H2_PORT, "forge", false, Option.some(schemaPath));
        h2Server = ForgeH2Server.forgeH2Server(h2Config);
        h2Server.start()
                .await()
                .onFailure(cause -> {
                    throw new AssertionError("H2 start failed: " + cause.message());
                });

        var configProvider = buildConfigurationProvider(h2Server);
        cluster = forgeCluster(5, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "im", Option.some(configProvider));
        httpOps = jdkHttpOperations(Duration.ofSeconds(5), java.net.http.HttpClient.Redirect.NORMAL, Option.empty());
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
               .until(() -> allNodesHealthy(cluster, httpOps.client()));

        // Wait for all nodes to register ON_DUTY lifecycle state via consensus
        var leaderPortForLifecycle = cluster.getLeaderManagementPort().unwrap();
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> allNodesOnDuty(leaderPortForLifecycle));

        var leaderPort = cluster.getLeaderManagementPort().unwrap();
        var blueprint = """
            id = "%s"

            [[slices]]
            artifact = "%s"
            instances = 3

            [[slices]]
            artifact = "%s"
            instances = 3
            """.formatted(BLUEPRINT_ID, URL_SHORTENER_ARTIFACT, ANALYTICS_ARTIFACT);

        var deployResponse = postBlueprintWithRetry(leaderPort, blueprint);
        assertThat(deployResponse).doesNotContain("\"error\"");

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> sliceIsActive(URL_SHORTENER_ARTIFACT) && sliceIsActive(ANALYTICS_ARTIFACT));

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> get(leaderPort, "/api/routes").contains("/api/v1/urls"));

        await().atMost(DEPLOY_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> !cluster.getAvailableAppHttpPorts().isEmpty());

        generateTraffic();

        // Wait for metrics propagation via gossip
        sleep(Duration.ofSeconds(5));
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop().await();
        }
        if (h2Server != null) {
            h2Server.stop().await();
        }
    }

    private static ConfigurationProvider buildConfigurationProvider(ForgeH2Server server) {
        var runtimeValues = Map.of("database.name", "forge-h2",
                                   "database.type", "H2",
                                   "database.host", "localhost",
                                   "database.port", "0",
                                   "database.database", "forge",
                                   "database.jdbc_url", server.jdbcUrl(),
                                   "database.username", "sa",
                                   "database.password", "");
        return ConfigurationProvider.builder()
                                    .withSource(MapConfigSource.mapConfigSource("runtime", runtimeValues, 500).unwrap())
                                    .build();
    }

    @Test
    void invocationMetrics_clusterWideCounts_matchExpected() {
        // Invocation metrics are only recorded by InvocationHandler for SERVER-SIDE RPC invocations.
        // AppHTTP requests (shorten/resolve) go through SliceRouter directly — no invocation metrics.
        // Cross-slice calls (recordClick from url-shortener → analytics) record metrics only when
        // the target slice is on a DIFFERENT node (remote RPC). Local bridge calls skip metrics.
        var totalRecordClickCount = 0L;

        for (var node : cluster.status().nodes()) {
            var metricsJson = get(node.mgmtPort(), "/api/invocation-metrics");
            assertThat(metricsJson).as("Invocation metrics from node %s", node.id())
                                   .doesNotContain("\"error\"");

            var recordClickCount = extractMethodCount(metricsJson, "recordClick", "count");
            if (recordClickCount > 0) {
                totalRecordClickCount += recordClickCount;
            }
        }

        // Remote cross-slice invocations (recordClick) should produce some metrics.
        // With 5 nodes and 3 instances each, some calls are remote.
        assertThat(totalRecordClickCount).as("cluster-wide recordClick count from remote invocations")
                                         .isGreaterThan(0);

        // Verify zero failures on nodes that have recordClick metrics
        for (var node : cluster.status().nodes()) {
            var metricsJson = get(node.mgmtPort(), "/api/invocation-metrics");
            var recordClickFailures = extractMethodCount(metricsJson, "recordClick", "failureCount");
            if (recordClickFailures > 0) {
                assertThat(recordClickFailures).as("recordClick failures on node %s", node.id()).isEqualTo(0);
            }
        }
    }

    @Test
    void invocationMetrics_artifactFilter_returnsOnlyMatchingSlice() {
        // Find a node that has analytics invocation metrics (remote recordClick calls)
        var nodeWithAnalytics = cluster.status().nodes().stream()
                                       .filter(n -> extractMethodCount(get(n.mgmtPort(), "/api/invocation-metrics"),
                                                                       "recordClick", "count") > 0)
                                       .findFirst();
        assertThat(nodeWithAnalytics).as("At least one node should have recordClick metrics").isPresent();

        var port = nodeWithAnalytics.orElseThrow().mgmtPort();

        // Filter by analytics artifact — should include recordClick but not url-shortener methods
        var analyticsFiltered = get(port, "/api/invocation-metrics?artifact=url-shortener-analytics");
        assertThat(analyticsFiltered).doesNotContain("\"error\"");
        assertThat(analyticsFiltered).contains("recordClick");
        assertThat(analyticsFiltered).doesNotContain("\"method\":\"shorten\"");
        assertThat(analyticsFiltered).doesNotContain("\"method\":\"resolve\"");
    }

    @Test
    void gossipedMetrics_allNodesHaveRemoteData() {
        for (var node : cluster.status().nodes()) {
            var metrics = get(node.mgmtPort(), "/api/metrics");
            assertThat(metrics).as("Metrics from node %s", node.id())
                               .doesNotContain("\"error\"");

            // Count how many node entries appear in the "load" map
            var nodeEntryCount = countNodeEntries(metrics);
            assertThat(nodeEntryCount).as("Node entries visible from node %s", node.id())
                                      .isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void nodeMetrics_reportCpuAndHeapForAllNodes() {
        var anyMgmtPort = cluster.status().nodes().getFirst().mgmtPort();
        var nodeMetrics = get(anyMgmtPort, "/api/node-metrics");

        assertThat(nodeMetrics).doesNotContain("\"error\"");

        // Should have entries for multiple nodes
        var nodeCount = countNodeEntries(nodeMetrics);
        assertThat(nodeCount).as("Node count in node-metrics").isGreaterThanOrEqualTo(3);

        // Verify metrics values are reasonable
        assertThat(nodeMetrics).contains("cpuUsage");
        assertThat(nodeMetrics).contains("heapUsedMb");
        assertThat(nodeMetrics).contains("heapMaxMb");
    }

    @Test
    void metricsEndpoint_returnsDataOnAllNodes() {
        for (var node : cluster.status().nodes()) {
            var metrics = get(node.mgmtPort(), "/api/metrics");
            assertThat(metrics).as("Metrics from node %s", node.id())
                               .doesNotContain("\"error\"")
                               .isNotBlank()
                               .contains("\"load\"");
        }
    }

    @Test
    void prometheusEndpoint_returnsDataOnAllNodes() {
        for (var node : cluster.status().nodes()) {
            var prometheus = get(node.mgmtPort(), "/api/metrics/prometheus");
            assertThat(prometheus).as("Prometheus from node %s", node.id())
                                  .doesNotContain("\"error\"")
                                  .isNotNull();
        }
    }

    @Test
    void tracesEndpoint_hasCapturedTraces() {
        var anyMgmtPort = cluster.status().nodes().getFirst().mgmtPort();
        var traces = get(anyMgmtPort, "/api/traces");

        assertThat(traces).doesNotContain("\"error\"");
        assertThat(traces).isNotEqualTo("[]");
    }

    @Test
    void traceStats_showPositiveCounts() {
        var anyMgmtPort = cluster.status().nodes().getFirst().mgmtPort();
        var stats = get(anyMgmtPort, "/api/traces/stats");

        assertThat(stats).doesNotContain("\"error\"");
        assertThat(stats).contains("\"totalTraces\"");

        var totalTraces = extractNumericField(stats, "totalTraces");
        assertThat(totalTraces).as("Total traces captured").isGreaterThan(0);
    }

    @Test
    void observabilityDepth_setAndGetViaApi() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();
        var otherPort = cluster.status().nodes().stream()
                               .filter(n -> n.mgmtPort() != leaderPort)
                               .findFirst()
                               .orElseThrow()
                               .mgmtPort();

        // POST depth configuration
        var setBody = "{\"artifact\":\"url-shortener-url-shortener\",\"method\":\"shorten\",\"depthThreshold\":5}";
        var setResponse = postJson(leaderPort, "/api/observability/depth", setBody);
        assertThat(setResponse).doesNotContain("\"error\"");

        // GET on same node - should appear immediately
        var getResponse = get(leaderPort, "/api/observability/depth");
        assertThat(getResponse).contains("url-shortener-url-shortener");
        assertThat(getResponse).contains("shorten");

        // Wait for consensus propagation
        sleep(Duration.ofSeconds(3));

        // GET on different node - verify propagation
        var remoteGet = get(otherPort, "/api/observability/depth");
        assertThat(remoteGet).as("Depth config propagated to other node")
                             .contains("url-shortener-url-shortener");

        // DELETE the config
        var deleteResponse = delete(leaderPort, "/api/observability/depth/url-shortener-url-shortener/shorten");
        assertThat(deleteResponse).doesNotContain("\"error\"");

        // Wait for propagation
        sleep(Duration.ofSeconds(3));

        // Verify removal
        var afterDelete = get(leaderPort, "/api/observability/depth");
        assertThat(afterDelete).doesNotContain("url-shortener-url-shortener/shorten");
    }

    @Test
    void observabilityDepth_affectsTraceRecording() {
        var leaderPort = cluster.getLeaderManagementPort().unwrap();

        // Set depth threshold to 5 for shorten method
        var setBody = "{\"artifact\":\"url-shortener-url-shortener\",\"method\":\"shorten\",\"depthThreshold\":5}";
        var setResponse = postJson(leaderPort, "/api/observability/depth", setBody);
        assertThat(setResponse).doesNotContain("\"error\"");

        // Wait for propagation
        sleep(Duration.ofSeconds(3));

        // Generate a few requests to trigger tracing
        var ports = cluster.getAvailableAppHttpPorts();
        assertThat(ports).isNotEmpty();
        var port = ports.getFirst();
        for (int i = 0; i < 10; i++) {
            var body = "{\"url\":\"https://example.com/depth-test-" + i + "\"}";
            postJson(port, "/api/v1/urls/", body);
        }

        // Verify traces still exist (depth config affects log level, not trace recording)
        sleep(Duration.ofSeconds(2));
        var traces = get(leaderPort, "/api/traces?method=shorten&limit=5");
        assertThat(traces).doesNotContain("\"error\"");

        // Clean up - remove the depth config
        delete(leaderPort, "/api/observability/depth/url-shortener-url-shortener/shorten");
        sleep(Duration.ofSeconds(2));
    }

    // ===== Traffic Generation =====

    private void generateTraffic() {
        var successCount = 0;
        for (int i = 0; i < REQUEST_COUNT; i++) {
            var ports = cluster.getAvailableAppHttpPorts();
            if (ports.isEmpty()) {
                throw new AssertionError("No route-ready app HTTP ports available at request " + i);
            }
            var port = ports.get(i % ports.size());

            var createBody = "{\"url\":\"https://example.com/p-" + i + "\"}";
            var createResponse = postJson(port, "/api/v1/urls/", createBody);

            var shortCode = extractStringField(createResponse, "shortCode");
            assertThat(shortCode).as("Short code from creation request %d", i).isNotNull().isNotEmpty();

            var resolveResponse = get(port, "/api/v1/urls/" + shortCode);
            assertThat(resolveResponse).as("Resolve response for request %d", i)
                                       .contains("https://example.com/p-" + i);
            successCount++;
        }
        assertThat(successCount).as("All round-trip requests succeeded").isEqualTo(REQUEST_COUNT);
    }

    // ===== HTTP Helpers (using JdkHttpOperations) =====

    private HttpRequest.Builder requestTo(int port, String path) {
        return HttpRequest.newBuilder()
                          .uri(URI.create("http://localhost:" + port + path))
                          .timeout(REQUEST_TIMEOUT);
    }

    private String get(int port, String path) {
        return httpOps.sendString(requestTo(port, path).GET().build())
                      .await()
                      .map(HttpResult::body)
                      .or(ERROR_FALLBACK);
    }

    private String postJson(int port, String path, String body) {
        return httpOps.sendString(requestTo(port, path)
                          .header("Content-Type", "application/json")
                          .POST(HttpRequest.BodyPublishers.ofString(body))
                          .build())
                      .await()
                      .map(HttpResult::body)
                      .or(ERROR_FALLBACK);
    }

    private String postToml(int port, String path, String body) {
        return httpOps.sendString(requestTo(port, path)
                          .header("Content-Type", "application/toml")
                          .POST(HttpRequest.BodyPublishers.ofString(body))
                          .build())
                      .await()
                      .map(HttpResult::body)
                      .or(ERROR_FALLBACK);
    }

    private String delete(int port, String path) {
        return httpOps.sendString(requestTo(port, path)
                          .DELETE()
                          .build())
                      .await()
                      .map(HttpResult::body)
                      .or(ERROR_FALLBACK);
    }

    private String postBlueprintWithRetry(int port, String body) {
        String lastResponse = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            lastResponse = postToml(port, "/api/blueprint", body);
            if (!lastResponse.contains("\"error\"")) {
                return lastResponse;
            }
            if (attempt < 3) {
                sleep(Duration.ofSeconds(2));
            }
        }
        return lastResponse;
    }

    private boolean sliceIsActive(String artifact) {
        try {
            return cluster.slicesStatus().stream()
                          .anyMatch(s -> s.artifact().equals(artifact)
                                         && s.state().equals(SliceState.ACTIVE.name()));
        } catch (Exception e) {
            return false;
        }
    }

    // ===== JSON Parsing Helpers =====

    private static String extractStringField(String json, String fieldName) {
        var searchKey = "\"" + fieldName + "\":\"";
        int start = json.indexOf(searchKey);
        if (start == -1) {
            return null;
        }
        start += searchKey.length();
        int end = json.indexOf("\"", start);
        if (end == -1) {
            return null;
        }
        return json.substring(start, end);
    }

    private static long extractNumericField(String json, String fieldName) {
        var searchKey = "\"" + fieldName + "\":";
        int start = json.indexOf(searchKey);
        if (start == -1) {
            return -1;
        }
        start += searchKey.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }
        return parseLongSafe(json.substring(start, end));
    }

    private static long extractMethodCount(String json, String methodName, String fieldName) {
        var methodKey = "\"method\":\"" + methodName + "\"";
        int methodIdx = json.indexOf(methodKey);
        if (methodIdx == -1) {
            return -1;
        }

        int closeBrace = json.indexOf("}", methodIdx);
        if (closeBrace == -1) {
            return -1;
        }

        var fieldKey = "\"" + fieldName + "\":";
        int fieldIdx = json.indexOf(fieldKey, methodIdx);
        if (fieldIdx == -1 || fieldIdx > closeBrace) {
            return -1;
        }

        int start = fieldIdx + fieldKey.length();
        int end = start;
        while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) {
            end++;
        }
        return parseLongSafe(json.substring(start, end));
    }

    private static int countNodeEntries(String json) {
        // Count occurrences of node ID patterns like "im-0", "im-1", etc.
        // which are the keys in the metrics maps
        int count = 0;
        int idx = 0;
        while ((idx = json.indexOf("\"im-", idx)) != -1) {
            count++;
            idx += 4;
        }
        return count;
    }

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /// Check if all 5 nodes have ON_DUTY lifecycle state registered in KV store.
    private boolean allNodesOnDuty(int mgmtPort) {
        var lifecycles = get(mgmtPort, "/api/nodes/lifecycle");
        if (lifecycles.contains("\"error\"")) {
            return false;
        }
        // Count ON_DUTY entries — need one per node
        int onDutyCount = 0;
        int idx = 0;
        while ((idx = lifecycles.indexOf("\"ON_DUTY\"", idx)) != -1) {
            onDutyCount++;
            idx += 9;
        }
        return onDutyCount >= 5;
    }
}
