// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;

import static org.assertj.core.api.Assertions.assertThat;


/// #957 — the PRODUCTION evaluation path, which no other test touches.
///
/// **Why this class exists.** Every other test of the alerting redesign calls
/// `AlertManager.checkThreshold` directly. That is the right unit under test for hysteresis and
/// emission, but it means the path an actual node takes — `AlertThresholdEvaluator.evaluateThresholds()`
/// → `checkThreshold` → `ThresholdBreached` — would be exercised by NOTHING.
///
/// Evaluation used to sit behind the dashboard publisher's `connectedClients() == 0` early return, so a
/// headless cluster never evaluated a threshold. The dashboard is gone and so is the guard; this class
/// pins that evaluation reaches the cluster event log with nothing connected, so the guard cannot come
/// back unnoticed in whatever feed is built next.
class AlertThresholdEvaluatorEvaluationPathTest {

    private static final HlcClock HLC = HlcClock.hlcClock(new NodeId("test-node"));
    private static final NodeId NODE = new NodeId("node-1");
    private static final String CPU = "cpu.usage";

    private static final class RecordingSink implements AlertManager.EventSink {
        private final List<ClusterEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(ClusterEvent event) {
            events.add(event);
        }

        List<ClusterEvent> breaches() {
            return events.stream().filter(e -> e instanceof ClusterEvent.ThresholdBreached).toList();
        }
    }

    @SuppressWarnings("unchecked")
    private static AlertManager manager() {
        return AlertManager.alertManager(Mockito.mock(org.pragmatica.cluster.node.rabia.RabiaNode.class),
                                         (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    /// A node reporting exactly one metric for one peer — the shape `evaluateAllMetrics` iterates
    /// (`node.metricsCollector().allMetrics()`, keyed by node id).
    private static ManageableNode nodeReporting(String metric, double value) {
        var collector = Mockito.mock(ClusterSyncCollector.class);
        var node = Mockito.mock(ManageableNode.class);
        Mockito.when(node.hasCompleteClusterView()).thenReturn(true);

        Mockito.when(collector.allMetrics()).thenReturn(Map.of(NODE, Map.of(metric, value)));
        Mockito.when(node.metricsCollector()).thenReturn(collector);

        return node;
    }

    private static AlertThresholdEvaluator evaluatorFor(AlertManager alertManager, double value) {
        var node = nodeReporting(CPU, value);

        return AlertThresholdEvaluator.alertThresholdEvaluator(() -> node, alertManager);
    }

    /// **The real pin.** A metric above its critical threshold must reach the cluster event log with
    /// NOTHING connected to the node — a headless cluster is the normal production state, and alerting
    /// is an operator capability, not a UI feature.
    ///
    /// Also pins that a sustained breach emits ONCE across 20 ticks rather than once per tick, since
    /// this is the path where the 1 Hz repetition actually happens.
    @Test
    void thresholdEvaluationReachesTheLogOnAHeadlessNode() {
        var alertManager = manager();
        var sink = new RecordingSink();

        alertManager.bindEventSink(sink, HLC);

        for (int tick = 0; tick < 20; tick++) {
            evaluatorFor(alertManager, 0.95).evaluateThresholds();
        }

        assertThat(sink.breaches())
                .describedAs("a metric above its critical threshold must reach the event log on a headless "
                             + "node, and a sustained breach must emit ONCE across 20 ticks")
                .hasSize(1);
    }

    /// **The control, and it is not optional.** Without it, the assertion above is satisfied by an
    /// implementation that emits on every evaluation regardless of value — the mirror-image defect.
    /// Same evaluator, same headless node, value below every threshold, nothing emitted.
    @Test
    void valueBelowEveryThresholdEmitsNothingUnderTheSameConditions() {
        var alertManager = manager();
        var sink = new RecordingSink();

        alertManager.bindEventSink(sink, HLC);

        for (int tick = 0; tick < 20; tick++) {
            evaluatorFor(alertManager, 0.10).evaluateThresholds();
        }

        assertThat(sink.breaches())
                .describedAs("a sub-threshold metric must emit nothing under exactly the conditions that make "
                             + "the positive test pass")
                .isEmpty();
    }
}
