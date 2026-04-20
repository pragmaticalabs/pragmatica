// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.metrics;

import org.pragmatica.aether.metrics.invocation.InvocationMetricsCollector;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.cluster.metrics.CommunityReport;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPing;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.ClusterSyncPong;
import org.pragmatica.cluster.metrics.ClusterSyncMessage.SnapshotPayload;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;


/// Inert `ClusterSyncCollector` for tests that don't exercise metrics collection.
final class NoopClusterSyncCollector implements ClusterSyncCollector {
    @Override public Map<String, Double> collectLocal() {return Map.of();}
    @Override public void recordCall(MethodName method, long durationMs) {}
    @Override public void recordCustom(String name, double value) {}
    @Override public void setInvocationMetricsProvider(InvocationMetricsCollector provider) {}
    @Override public Map<NodeId, Map<String, Double>> allMetrics() {return Map.of();}
    @Override public Map<String, Double> metricsFor(NodeId nodeId) {return Map.of();}
    @Override public Map<NodeId, List<MetricsSnapshot>> historicalMetrics() {return Map.of();}
    @Override public void removeNode(NodeId nodeId) {}
    @Override public void onTopologyChange(TopologyChangeNotification topologyChange) {}
    @Override public void onClusterSyncPing(ClusterSyncPing ping) {}
    @Override public void onClusterSyncPong(ClusterSyncPong pong) {}
    @Override public long observedRabiaTerm() {return 0L;}
    @Override public Epoch observedEpoch() {return Epoch.ZERO;}
    @Override public Option<SnapshotPayload> lastObservedSnapshot() {return Option.none();}
    @Override public String currentLifecycleState() {return "ON_DUTY";}
    @Override public List<CommunityReport> collectCommunityReports() {return List.of();}
    @Override public void setLifecycleStateSupplier(Supplier<String> supplier) {}
    @Override public void setCommunityReportSupplier(Supplier<List<CommunityReport>> supplier) {}
    @Override public void addPongListener(Consumer<ClusterSyncPong> listener) {}
    @Override public void setPongSignalFan(ClusterSyncPongSignalFan fan) {}
}
