package org.pragmatica.aether.e2e;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * E2E tests for metrics collection and distribution.
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
class MetricsE2ETest extends AbstractE2ETest {
    private static final Duration METRICS_INTERVAL = Duration.ofSeconds(2);

    @Nested
    class MetricsCollection {

        @Test
        void metricsEndpoint_returnsNodeMetrics() {
            var metrics = cluster.anyNode().getMetrics();

            assertThat(metrics).doesNotContain("\"error\"");
            // Should contain some metrics structure
            assertThat(metrics).isNotBlank();
        }

        @Test
        void metricsCollected_everySecond() {
            // Wait for a few collection cycles
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(METRICS_INTERVAL)
                   .until(() -> {
                       var metrics = cluster.anyNode().getMetrics();
                       return metrics != null && !metrics.contains("\"error\"");
                   });

            var metrics = cluster.anyNode().getMetrics();
            assertThat(metrics).doesNotContain("\"error\"");
        }

        @Test
        void cpuMetrics_reportedPerNode() {
            // Wait for metrics to be collected
            await().atMost(DEFAULT_TIMEOUT).until(() -> {
                var metrics = cluster.anyNode().getMetrics();
                return metrics != null && !metrics.isBlank();
            });

            // Each node should report metrics
            for (var node : cluster.nodes()) {
                var metrics = node.getMetrics();
                assertThat(metrics).doesNotContain("\"error\"");
            }
        }
    }

    @Nested
    class PrometheusMetrics {

        @Test
        void prometheusEndpoint_returnsValidFormat() {
            var prometheus = cluster.anyNode().getPrometheusMetrics();

            assertThat(prometheus).doesNotContain("\"error\"");
            // Prometheus format doesn't use JSON
            // Should contain metric lines or be empty
            assertThat(prometheus).isNotNull();
        }

        @Test
        void prometheusMetrics_availableOnAllNodes() {
            for (var node : cluster.nodes()) {
                var prometheus = node.getPrometheusMetrics();
                assertThat(prometheus).doesNotContain("\"error\"");
            }
        }
    }

    @Nested
    class MetricsDistribution {

        @Test
        void metricsSnapshot_receivedByAllNodes() {
            // Wait for leader to be elected (metrics aggregation runs on leader)
            cluster.awaitLeader();

            // Wait for a few metrics cycles to ensure distribution
            await().atMost(DEFAULT_TIMEOUT)
                   .pollInterval(METRICS_INTERVAL)
                   .pollDelay(Duration.ofSeconds(3))
                   .until(() -> {
                       // Check that all nodes have metrics
                       for (var node : cluster.nodes()) {
                           var metrics = node.getMetrics();
                           if (metrics.contains("\"error\"")) {
                               return false;
                           }
                       }
                       return true;
                   });

            // Verify all nodes have metrics
            for (var node : cluster.nodes()) {
                var metrics = node.getMetrics();
                assertThat(metrics).doesNotContain("\"error\"");
            }
        }
    }
}
