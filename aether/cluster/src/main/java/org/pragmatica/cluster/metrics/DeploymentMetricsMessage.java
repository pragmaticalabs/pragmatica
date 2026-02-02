package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;

import java.util.List;
import java.util.Map;

/**
 * Messages for deployment metrics exchange between nodes.
 * Uses Ping-Pong pattern: leader pings nodes with aggregated metrics,
 * nodes respond with their local metrics.
 */
public sealed interface DeploymentMetricsMessage extends ProtocolMessage {
    /**
     * Single deployment metrics entry using primitive types for serialization.
     */
    record DeploymentMetricsEntry(String artifact,
                                  // Artifact as string
    String nodeId,
                                  // NodeId as string
    long startTime,
                                  // T0: Blueprint change
    long loadTime,
                                  // T1: LOAD received
    long loadedTime,
                                  // T2: LOADED committed
    long activateTime,
                                  // T3: ACTIVATE committed
    long activeTime,
                                  // T4: ACTIVE committed
    String status) {}

    /**
     * Deployment metrics ping sent by leader to all nodes.
     * Contains aggregated deployment metrics from all known deployments.
     */
    record DeploymentMetricsPing(NodeId sender,
                                 Map<String, List<DeploymentMetricsEntry>> metrics) implements DeploymentMetricsMessage {}

    /**
     * Deployment metrics pong sent by nodes in response to ping.
     * Contains local deployment metrics.
     */
    record DeploymentMetricsPong(NodeId sender,
                                 Map<String, List<DeploymentMetricsEntry>> metrics) implements DeploymentMetricsMessage {}
}
