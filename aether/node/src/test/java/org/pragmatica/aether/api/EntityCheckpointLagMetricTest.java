// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.EntityCheckpointLagMetric;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.hlc.HlcClock;

import static org.assertj.core.api.Assertions.assertThat;


/// #1330 — the node-side binding of the durable-entity checkpoint-lag metric, pinned against the REAL
/// collector the alert path reads rather than by reachability alone.
///
/// A sink reachable from production but writing under the wrong name computes every lag and can never
/// alert, and a bytecode reachability pin stays green for it (review mutation W1). So the name is asserted
/// here, as the literal the alert threshold is seeded under, and the whole path — `recordCustom` through
/// `DashboardMetricsPublisher.publishMetrics` to `ThresholdBreached` and `ThresholdCleared` — is driven
/// end to end.
class EntityCheckpointLagMetricTest {
    private static final HlcClock HLC = HlcClock.hlcClock(new NodeId("test-node"));
    private static final NodeId SELF = new NodeId("node-1");
    private static final String LAG_METRIC = "entity.checkpoint.lag.max";

    @Test
    void sinkFor_recordsTheLag_underTheExactAlertMetricName() {
        var collector = collector();

        EntityCheckpointLagMetric.sinkFor(collector).report(7);

        assertThat(collector.collectLocal()).describedAs("the alert threshold is seeded under exactly this name")
                                            .containsEntry(LAG_METRIC, 7.0);
    }

    /// The lag from the sink to the alert and back: a lag past the default CRITICAL threshold raises
    /// through the production evaluation loop, and a lag far below WARNING clears it.
    @Test
    void reportedLag_raisesAndClearsTheAlert_throughTheProductionEvaluationLoop() {
        var collector = collector();
        var sink = EntityCheckpointLagMetric.sinkFor(collector);
        var alertManager = alertManager();
        var events = new RecordingSink();
        var publisher = DashboardMetricsPublisher.dashboardMetricsPublisher(() -> nodeWith(collector), alertManager);

        alertManager.bindEventSink(events, HLC);
        alertManager.bindAlertConfig(AlertConfig.alertConfig());

        sink.report(12_000);
        publisher.publishMetrics();

        assertThat(events.breaches()).describedAs("a lag past the default CRITICAL threshold must raise").hasSize(1);

        sink.report(100);
        publisher.publishMetrics();

        assertThat(events.clears()).describedAs("a lag far below WARNING must clear the alert").hasSize(1);
    }

    private static ClusterSyncCollector collector() {
        return ClusterSyncCollector.clusterSyncCollector(SELF, Mockito.mock(ClusterNetwork.class), 60_000);
    }

    private static ManageableNode nodeWith(ClusterSyncCollector collector) {
        var node = Mockito.mock(ManageableNode.class);

        Mockito.when(node.metricsCollector()).thenReturn(collector);

        return node;
    }

    @SuppressWarnings("unchecked")
    private static AlertManager alertManager() {
        return AlertManager.alertManager(Mockito.mock(org.pragmatica.cluster.node.rabia.RabiaNode.class),
                                         (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    private static final class RecordingSink implements AlertManager.EventSink {
        private final List<ClusterEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(ClusterEvent event) {
            events.add(event);
        }

        /// Filtered to the LAG metric: the real collector also reports cpu and heap, which breach their
        /// own thresholds on a loaded box, and an unfiltered count would be satisfied — or broken — by
        /// something this test says nothing about.
        List<ClusterEvent> breaches() {
            return lagEvents(ClusterEvent.ThresholdBreached.class);
        }

        List<ClusterEvent> clears() {
            return lagEvents(ClusterEvent.ThresholdCleared.class);
        }

        private List<ClusterEvent> lagEvents(Class<? extends ClusterEvent> variant) {
            return events.stream()
                         .filter(variant::isInstance)
                         .filter(RecordingSink::aboutTheLagMetric)
                         .toList();
        }

        private static boolean aboutTheLagMetric(ClusterEvent event) {
            return LAG_METRIC.equals(event.details().get("metric"));
        }
    }
}
