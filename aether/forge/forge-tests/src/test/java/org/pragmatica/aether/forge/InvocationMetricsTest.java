package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;

import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Duration;

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
    private static final int BASE_PORT = 7000;
    private static final int BASE_MGMT_PORT = 7100;
    private static final int BASE_APP_HTTP_PORT = 7200;
    private static final int REQUEST_COUNT = 1000;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final String URL_SHORTENER_ARTIFACT = "org.pragmatica.aether.example:url-shortener-url-shortener:0.18.0";
    private static final String ANALYTICS_ARTIFACT = "org.pragmatica.aether.example:url-shortener-analytics:0.18.0";
    private static final String BLUEPRINT_ID = "forge.test:url-shortener-metrics:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private ForgeCluster cluster;
    private JdkHttpOperations httpOps;

    @BeforeAll
    void setUp() {
        cluster = forgeCluster(5, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "im");
        httpOps = jdkHttpOperations(Duration.ofSeconds(5), java.net.http.HttpClient.Redirect.NORMAL, org.pragmatica.lang.Option.empty());
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

        // Consensus stabilization
        sleep(Duration.ofSeconds(5));

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
    }

    @Test
    void invocationMetrics_clusterWideCounts_matchExpected() {
        var anyMgmtPort = cluster.status().nodes().getFirst().mgmtPort();
        var metricsJson = get(anyMgmtPort, "/api/invocation-metrics");

        assertThat(metricsJson).doesNotContain("\"error\"");
        assertThat(metricsJson).contains("shorten");
        assertThat(metricsJson).contains("resolve");
        assertThat(metricsJson).contains("recordClick");

        var shortenCount = extractMethodCount(metricsJson, "shorten", "count");
        var resolveCount = extractMethodCount(metricsJson, "resolve", "count");
        var recordClickCount = extractMethodCount(metricsJson, "recordClick", "count");

        assertThat(shortenCount).as("shorten invocation count").isGreaterThanOrEqualTo(REQUEST_COUNT);
        assertThat(resolveCount).as("resolve invocation count").isGreaterThanOrEqualTo(REQUEST_COUNT);
        assertThat(recordClickCount).as("recordClick invocation count").isGreaterThanOrEqualTo(REQUEST_COUNT);

        var shortenFailures = extractMethodCount(metricsJson, "shorten", "failureCount");
        var resolveFailures = extractMethodCount(metricsJson, "resolve", "failureCount");
        var recordClickFailures = extractMethodCount(metricsJson, "recordClick", "failureCount");

        assertThat(shortenFailures).as("shorten failure count").isEqualTo(0);
        assertThat(resolveFailures).as("resolve failure count").isEqualTo(0);
        assertThat(recordClickFailures).as("recordClick failure count").isEqualTo(0);
    }

    @Test
    void invocationMetrics_artifactFilter_returnsOnlyMatchingSlice() {
        var anyMgmtPort = cluster.status().nodes().getFirst().mgmtPort();
        var filtered = get(anyMgmtPort, "/api/invocation-metrics?artifact=url-shortener-url-shortener");

        assertThat(filtered).doesNotContain("\"error\"");
        assertThat(filtered).contains("shorten");
        assertThat(filtered).contains("resolve");
        assertThat(filtered).doesNotContain("recordClick");
        assertThat(filtered).doesNotContain("getStats");
    }

    @Test
    void metricsEndpoint_returnsDataOnAllNodes() {
        for (var node : cluster.status().nodes()) {
            var metrics = get(node.mgmtPort(), "/api/metrics");
            assertThat(metrics).as("Metrics from node %s", node.id())
                               .doesNotContain("\"error\"")
                               .isNotBlank();
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

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
