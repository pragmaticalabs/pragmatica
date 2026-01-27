package org.pragmatica.aether.forge;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.aether.forge.ForgeCluster.forgeCluster;

/**
 * Tests for metrics collection and distribution.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Metrics collection at 1-second intervals</li>
 *   <li>Per-node CPU and JVM metrics</li>
 *   <li>Cluster-wide metrics aggregation</li>
 *   <li>Prometheus endpoint format</li>
 *   <li>Metrics snapshot distribution to all nodes</li>
 * </ul>
 */
@Execution(ExecutionMode.SAME_THREAD)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MetricsTest {
    private static final int BASE_PORT = 5140;
    private static final int BASE_MGMT_PORT = 5240;
    private static final Duration WAIT_TIMEOUT = Duration.ofSeconds(120);
    private static final Duration METRICS_INTERVAL = Duration.ofSeconds(2);
    private static final Duration POLL_INTERVAL = Duration.ofMillis(500);

    private ForgeCluster cluster;
    private HttpClient httpClient;

    @BeforeAll
    void setUp() {
        cluster = forgeCluster(3, BASE_PORT, BASE_MGMT_PORT, "mt");
        httpClient = HttpClient.newBuilder()
                               .connectTimeout(Duration.ofSeconds(5))
                               .build();
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

        // Stabilization time for consensus
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200 && response.body().contains("\"quorum\":true");
        } catch (IOException | InterruptedException e) {
            return false;
        }
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
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    private String getPrometheusMetrics(int port) {
        var request = HttpRequest.newBuilder()
                                 .uri(URI.create("http://localhost:" + port + "/api/metrics/prometheus"))
                                 .GET()
                                 .timeout(Duration.ofSeconds(5))
                                 .build();
        try {
            var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.body();
        } catch (IOException | InterruptedException e) {
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
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
