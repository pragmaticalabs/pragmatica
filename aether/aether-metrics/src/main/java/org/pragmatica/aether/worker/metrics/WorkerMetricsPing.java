package org.pragmatica.aether.worker.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;
import org.pragmatica.serialization.Codec;

/// Ping from governor to followers requesting metrics.
///
/// @param sender      governor node ID
/// @param timestampMs when the ping was sent
@Codec public record WorkerMetricsPing( NodeId sender,
                                        long timestampMs) implements Message.Wired {
    /// Factory with explicit timestamp.
    public static WorkerMetricsPing workerMetricsPing(NodeId sender, long timestampMs) {
        return new WorkerMetricsPing(sender, timestampMs);
    }

    /// Factory with current timestamp.
    public static WorkerMetricsPing workerMetricsPing(NodeId sender) {
        return new WorkerMetricsPing(sender, System.currentTimeMillis());
    }
}
