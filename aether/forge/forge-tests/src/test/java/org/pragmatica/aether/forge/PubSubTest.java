package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.config.ConfigurationProvider;
import org.pragmatica.config.source.MapConfigSource;
import org.pragmatica.http.HttpResult;
import org.pragmatica.http.JdkHttpOperations;
import org.pragmatica.lang.Option;

import java.net.URI;
import java.net.http.HttpClient.Redirect;
import java.net.http.HttpRequest;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import org.pragmatica.aether.ember.EmberCluster;
import org.pragmatica.aether.ember.EmberH2Config;
import org.pragmatica.aether.ember.EmberH2Server;
import static org.pragmatica.aether.ember.EmberCluster.emberCluster;
import static org.pragmatica.http.JdkHttpOperations.jdkHttpOperations;

/// Tests for cross-node pub-sub message delivery.
///
///
/// Deploys the url-shortener example (two slices: url-shortener + analytics),
/// verifies that resolving a short URL publishes a ClickEvent which the analytics
/// slice receives and records in the database -- including cross-node delivery
/// via SliceInvoker RPC.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PubSubTest extends ForgeTestBase {
    private static final int BASE_PORT = 14000;
    private static final int BASE_MGMT_PORT = 14100;
    private static final int BASE_APP_HTTP_PORT = 14200;
    private static final int H2_PORT = 14300;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration DEPLOY_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);

    private static final String URL_SHORTENER_ARTIFACT = TestArtifacts.URL_SHORTENER;
    private static final String ANALYTICS_ARTIFACT = TestArtifacts.ANALYTICS;
    private static final String BLUEPRINT_ID = "forge.test:pubsub:1.0.0";
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberH2Server h2Server;
    private EmberCluster cluster;
    private JdkHttpOperations httpOps;

    @BeforeAll
    void setUp() {
        var schemaPath = Path.of("").toAbsolutePath()
                             .resolve("../../../examples/url-shortener/schema/url-shortener.sql")
                             .normalize()
                             .toString();
        var h2Config = EmberH2Config.emberH2Config(true, H2_PORT, "forge", false, Option.some(schemaPath));
        h2Server = EmberH2Server.emberH2Server(h2Config);
        h2Server.start()
                .await()
                .onFailure(cause -> {
                    throw new AssertionError("H2 start failed: " + cause.message());
                });

        var configProvider = buildConfigurationProvider(h2Server);
        cluster = emberCluster(5, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "ps", Option.some(configProvider));
        httpOps = jdkHttpOperations(Duration.ofSeconds(5), Redirect.NORMAL, Option.empty());
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
               .until(() -> allNodesHealthy(cluster));

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

    @Test
    void clickEvent_deliveredCrossNode() {
        var port = firstAvailableAppPort();

        var shortCode = shortenUrl(port, "https://example.com/pubsub-single");
        assertThat(shortCode).as("Short code from creation").isNotNull().isNotEmpty();

        resolveShortCode(port, shortCode);

        // Wait for pub-sub delivery and database write
        awaitClickCount(port, shortCode, 1);
    }

    @Test
    void multipleClicks_allDelivered() {
        var port = firstAvailableAppPort();
        var clickCount = 10;

        var shortCode = shortenUrl(port, "https://example.com/pubsub-multi");
        assertThat(shortCode).as("Short code from creation").isNotNull().isNotEmpty();

        for (int i = 0; i < clickCount; i++) {
            var ports = cluster.getAvailableAppHttpPorts();
            var resolvePort = ports.get(i % ports.size());
            resolveShortCode(resolvePort, shortCode);
        }

        awaitClickCount(port, shortCode, clickCount);
    }

    // ===== Domain Helpers =====

    private String shortenUrl(int port, String url) {
        var body = "{\"url\":\"" + url + "\"}";
        var response = postJson(port, "/api/v1/urls/", body);
        return extractStringField(response, "shortCode");
    }

    private void resolveShortCode(int port, String shortCode) {
        var response = get(port, "/api/v1/urls/" + shortCode);
        assertThat(response).as("Resolve response for %s", shortCode)
                             .doesNotContain("\"error\"");
    }

    private void awaitClickCount(int port, String shortCode, int expectedCount) {
        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .untilAsserted(() -> {
                   var stats = get(port, "/api/v1/analytics/" + shortCode);
                   assertThat(stats).as("Analytics stats for %s", shortCode)
                                    .doesNotContain("\"error\"");
                   var clickCount = extractNumericField(stats, "clickCount");
                   assertThat(clickCount).as("Click count for %s", shortCode)
                                         .isGreaterThanOrEqualTo(expectedCount);
               });
    }

    private int firstAvailableAppPort() {
        var ports = cluster.getAvailableAppHttpPorts();
        assertThat(ports).as("Available app HTTP ports").isNotEmpty();
        return ports.getFirst();
    }

    // ===== HTTP Helpers =====

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

    // ===== Infrastructure Helpers =====

    private static ConfigurationProvider buildConfigurationProvider(EmberH2Server server) {
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

    private boolean allNodesOnDuty(int mgmtPort) {
        var lifecycles = get(mgmtPort, "/api/nodes/lifecycle");
        if (lifecycles.contains("\"error\"")) {
            return false;
        }
        int onDutyCount = 0;
        int idx = 0;
        while ((idx = lifecycles.indexOf("\"ON_DUTY\"", idx)) != -1) {
            onDutyCount++;
            idx += 9;
        }
        return onDutyCount >= 5;
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

    private static long parseLongSafe(String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
