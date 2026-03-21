package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

/// Response from follower to governor with local metrics.
/// ~100 bytes per pong. Contains scaling-relevant metrics only.
///
/// @param sender            follower node ID
/// @param cpuUsage          CPU usage ratio (0.0-1.0)
/// @param heapUsage         heap usage ratio (0.0-1.0)
/// @param activeInvocations number of in-flight slice invocations
/// @param p95LatencyMs      estimated P95 latency in milliseconds
/// @param errorRate         error rate ratio (0.0-1.0)
/// @param timestampMs       when the pong was created
@Codec
public record WorkerMetricsPong(NodeId sender,
                                double cpuUsage,
                                double heapUsage,
                                long activeInvocations,
                                double p95LatencyMs,
                                double errorRate,
                                long timestampMs) implements Message.Wired {
    /// Factory with all fields.
    public static WorkerMetricsPong workerMetricsPong(NodeId sender,
                                                      double cpuUsage,
                                                      double heapUsage,
                                                      long activeInvocations,
                                                      double p95LatencyMs,
                                                      double errorRate,
                                                      long timestampMs) {
        return new WorkerMetricsPong(sender,
                                     cpuUsage,
                                     heapUsage,
                                     activeInvocations,
                                     p95LatencyMs,
                                     errorRate,
                                     timestampMs);
    }

    /// Factory with current timestamp.
    public static WorkerMetricsPong workerMetricsPong(NodeId sender,
                                                      double cpuUsage,
                                                      double heapUsage,
                                                      long activeInvocations,
                                                      double p95LatencyMs,
                                                      double errorRate) {
        return new WorkerMetricsPong(sender,
                                     cpuUsage,
                                     heapUsage,
                                     activeInvocations,
                                     p95LatencyMs,
                                     errorRate,
                                     System.currentTimeMillis());
    }
}
