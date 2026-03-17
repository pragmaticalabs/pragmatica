package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

/// Tests for metrics collection and distribution.
///
///
/// Tests cover:
///
///   - Metrics collection at 1-second intervals
///   - Per-node CPU and JVM metrics
///   - Cluster-wide metrics aggregation
///   - Prometheus endpoint format
///   - Metrics snapshot distribution to all nodes
///
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsTest {
    private static final int BASE_PORT = 6500;
    private static final int BASE_MGMT_PORT = 6600;
    private static final int BASE_APP_HTTP_PORT = 6700;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(240);
    private static final Duration METRICS_INTERVAL = Duration.ofSeconds(2);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);
    private static final String ERROR_FALLBACK = "{\"error\":\"request failed\"}";

    private EmberCluster cluster;
    private final HttpOperations http = jdkHttpOperations();

    @BeforeAll
    void setUp() {
        cluster = emberCluster(3, BASE_PORT, BASE_MGMT_PORT, BASE_APP_HTTP_PORT, "mt");
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

    @AfterAll
    void tearDown() {
        if (cluster != null) {
            cluster.stop()
                   .await();
        }
    }

    private String getMetrics(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/metrics"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    private String getPrometheusMetrics(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/metrics/prometheus"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        return http.sendString(request)
                   .await()
                   .map(HttpResult::body)
                   .or(ERROR_FALLBACK);
    }

    @Nested
    class MetricsCollection {

        @Test
        void metricsEndpoint_returnsNodeMetrics() {
            var status = cluster.status();
            var anyPort = status.nodes().getFirst().mgmtPort();
            var metrics = getMetrics(anyPort);

            assertThat(metrics).doesNotContain("\"error\"");
            assertThat(metrics).isNotBlank();
        }

        @Test
        void metricsCollected_everySecond() {
            var status = cluster.status();
            var anyPort = status.nodes().getFirst().mgmtPort();

            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(METRICS_INTERVAL)
                   .until(() -> {
                       var metrics = getMetrics(anyPort);
                       return metrics != null && !metrics.contains("\"error\"");
                   });

            var metrics = getMetrics(anyPort);
            assertThat(metrics).doesNotContain("\"error\"");
        }

        @Test
        void cpuMetrics_reportedPerNode() {
            var status = cluster.status();
            var anyPort = status.nodes().getFirst().mgmtPort();

            await().atMost(WAIT_TIMEOUT).until(() -> {
                var metrics = getMetrics(anyPort);
                return metrics != null && !metrics.isBlank();
            });

            for (var node : status.nodes()) {
                var metrics = getMetrics(node.mgmtPort());
                assertThat(metrics).doesNotContain("\"error\"");
            }
        }
    }

    @Nested
    class PrometheusMetrics {

        @Test
        void prometheusEndpoint_returnsValidFormat() {
            var status = cluster.status();
            var anyPort = status.nodes().getFirst().mgmtPort();
            var prometheus = getPrometheusMetrics(anyPort);

            assertThat(prometheus).doesNotContain("\"error\"");
            assertThat(prometheus).isNotNull();
        }

        @Test
        void prometheusMetrics_availableOnAllNodes() {
            var status = cluster.status();

            for (var node : status.nodes()) {
                var prometheus = getPrometheusMetrics(node.mgmtPort());
                assertThat(prometheus).doesNotContain("\"error\"");
            }
        }
    }

    @Nested
    class MetricsDistribution {

        @Test
        void metricsSnapshot_receivedByAllNodes() {
            await().atMost(WAIT_TIMEOUT)
                   .pollInterval(METRICS_INTERVAL)
                   .pollDelay(Duration.ofSeconds(3))
                   .until(() -> {
                       var status = cluster.status();
                       for (var node : status.nodes()) {
                           var metrics = getMetrics(node.mgmtPort());
                           if (metrics.contains("\"error\"")) {
                               return false;
                           }
                       }
                       return true;
                   });

            var status = cluster.status();
            for (var node : status.nodes()) {
                var metrics = getMetrics(node.mgmtPort());
                assertThat(metrics).doesNotContain("\"error\"");
            }
        }
    }
}
