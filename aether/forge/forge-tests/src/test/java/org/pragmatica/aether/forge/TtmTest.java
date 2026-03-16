package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Tag;
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

/// Tests for TTM (Tiny Time Mixers) predictive scaling.
///
///
/// Tests cover:
///
///   - TTM status API endpoint
///   - TTM disabled state (no model file)
///   - TTM state consistency across cluster
///   - TTM leader-only behavior
///
///
///
/// Note: These tests verify TTM infrastructure, not prediction accuracy.
/// Prediction accuracy testing requires a trained ONNX model and is better
/// suited for unit tests with mocked predictors.
@Tag("Heavy")
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TtmTest {
    private static final int BASE_PORT = 11500;
    private static final int BASE_MGMT_PORT = 11600;
    private static final int BASE_APP_HTTP_PORT = 11700;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(60);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "tm");

        cluster.start()
               .await()
               .onFailure(cause -> {
                   throw new AssertionError("Cluster start failed: " + cause.message());
               });

        await().atMost(WAIT_TIMEOUT)
               .pollInterval(POLL_INTERVAL)
               .until(() -> cluster.currentLeader().isPresent());
    }

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    @Nested
    class TtmStatusEndpoint {

        @Test
        void ttmStatus_returnsValidJson() {
            var status = getTtmStatusFromAnyNode();

            assertThat(status).doesNotContain("\"error\"");
            assertThat(status).contains("\"enabled\":");
            assertThat(status).contains("\"state\":");
        }

        @Test
        void ttmStatus_showsDisabledByDefault() {
            var status = getTtmStatusFromAnyNode();

            // TTM is disabled by default (no model file)
            assertThat(status).contains("\"enabled\":false");
            assertThat(status).contains("\"state\":\"STOPPED\"");
        }

        @Test
        void ttmStatus_includesConfigurationDetails() {
            var status = getTtmStatusFromAnyNode();

            assertThat(status).contains("\"inputWindowMinutes\":");
            assertThat(status).contains("\"evaluationIntervalMs\":");
            assertThat(status).contains("\"confidenceThreshold\":");
        }
    }

    @Nested
    class TtmClusterBehavior {

        @Test
        void ttmStatus_availableOnAllNodes() {
            // All nodes should expose TTM status endpoint
            for (var node : cluster.status().nodes()) {
                var status = getTtmStatus(node.mgmtPort());
                assertThat(status).doesNotContain("\"error\"");
                assertThat(status).contains("\"state\":");
            }
        }

        @Test
        void ttmStatus_consistentAcrossCluster() {
            // All nodes should report consistent TTM state
            var statuses = cluster.status().nodes().stream()
                                  .map(node -> getTtmStatus(node.mgmtPort()))
                                  .toList();

            // All should show disabled (no model)
            for (var status : statuses) {
                assertThat(status).contains("\"enabled\":false");
            }
        }

    }

    @Nested
    class TtmNoForecastWhenDisabled {

        @Test
        void ttmStatus_showsNoForecastWhenDisabled() {
            var status = getTtmStatusFromAnyNode();

            // When disabled, no forecast should be present
            assertThat(status).contains("\"hasForecast\":false");
            assertThat(status).doesNotContain("\"lastForecast\"");
        }
    }

    private String getTtmStatusFromAnyNode() {
        var nodes = cluster.status().nodes();
        if (nodes.isEmpty()) {
            return "";
        }
        return getTtmStatus(nodes.getFirst().mgmtPort());
    }

    private String getTtmStatus(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/ttm/status"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }
}
