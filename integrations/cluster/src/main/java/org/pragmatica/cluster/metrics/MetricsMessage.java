package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;

import java.util.Map;

/**
 * Messages for metrics exchange between nodes.
 * Uses Ping-Pong pattern: leader pings nodes with its metrics,
 * nodes respond with their metrics.
 */
public sealed interface MetricsMessage extends ProtocolMessage {
    /**
     * Metrics ping sent by leader to all nodes.
     * Contains sender's metrics, nodes respond with their own.
     */
    record MetricsPing(NodeId sender, Map<String, Double> metrics) implements MetricsMessage {}

    /**
     * Metrics pong sent by nodes in response to ping.
     * Contains responder's metrics.
     */
    record MetricsPong(NodeId sender, Map<String, Double> metrics) implements MetricsMessage {}
}
