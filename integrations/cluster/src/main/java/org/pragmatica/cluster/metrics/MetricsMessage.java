package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;

import java.util.Map;

/// Messages for metrics exchange between nodes.
/// Uses Ping-Pong pattern: leader pings nodes with aggregated cluster metrics,
/// nodes respond with their own metrics.
public sealed interface MetricsMessage extends ProtocolMessage {
    /// Metrics ping sent by leader to all nodes.
    /// Contains the full aggregated cluster metrics map so every node
    /// gets a complete picture. Nodes respond with their own metrics.
    record MetricsPing(NodeId sender, Map<NodeId, Map<String, Double>> allMetrics) implements MetricsMessage {}

    /// Metrics pong sent by nodes in response to ping.
    /// Contains responder's metrics.
    record MetricsPong(NodeId sender, Map<String, Double> metrics) implements MetricsMessage {}
}
