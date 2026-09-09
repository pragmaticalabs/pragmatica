// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Disabled;
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


/// #957 — the PRODUCTION evaluation path, which no other test on this branch touches.
///
/// **Why this class exists.** Every other test of the redesign calls `AlertManager.checkThreshold`
/// directly. That is the right unit under test for hysteresis and emission, but it means the path an
/// actual node takes — `DashboardMetricsPublisher.publishMetrics()` → `checkAndBroadcastAlerts()` →
/// `checkThreshold` → `ThresholdBreached` — is exercised by NOTHING. The redesign's dependency on that
/// path being un-gated was disclosed; the absence of any test pinning it was not.
///
/// Without something here, nothing goes red if someone re-introduces the `connectedClients() == 0`
/// early return, and the un-gating this branch depends on would be protected by convention alone.
///
/// ## The two halves, and why one of them is deliberately disabled
///
/// On THIS branch `publishMetrics()` still returns early when no dashboard client is connected — the
/// un-gating lives on `fix/alerting-structure-2026-09-08`, which merges BEFORE this work. So the real
/// property cannot be asserted green here, and asserting it now would simply be a red test.
///
/// Rather than leave a `@Disabled` test that can be forgotten forever — which is this repo's most
/// repeated failure — the two halves are arranged so the merge CANNOT be completed silently:
///
/// - [#evaluationIsStillGatedOnADashboardClient_TRIPWIRE] is **enabled and green today**. It documents
///   the gate rather than endorsing it, and it **turns RED the moment the alerting fix merges**. Its
///   failure message says what to do.
/// - [#thresholdEvaluationReachesTheLogWithNoDashboardClient] and its control are **`@Disabled` until
///   that merge**, and are what actually pin the property afterwards.
///
/// A `@Disabled` test alone is silence. A tripwire that reddens forces the action.
class DashboardMetricsPublisherEvaluationPathTest {

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

    /// A node reporting exactly one metric for one peer — the shape `checkAndBroadcastAlerts` iterates
    /// (`node.metricsCollector().allMetrics()`, keyed by node id).
    private static ManageableNode nodeReporting(String metric, double value) {
        var collector = Mockito.mock(ClusterSyncCollector.class);
        var node = Mockito.mock(ManageableNode.class);

        Mockito.when(collector.allMetrics()).thenReturn(Map.of(NODE, Map.of(metric, value)));
        Mockito.when(node.metricsCollector()).thenReturn(collector);

        return node;
    }

    private static DashboardMetricsPublisher publisherFor(AlertManager alertManager, double value) {
        var node = nodeReporting(CPU, value);

        return DashboardMetricsPublisher.dashboardMetricsPublisher(() -> node, alertManager);
    }

    /// **TRIPWIRE — this test is expected to FAIL once `fix/alerting-structure-2026-09-08` merges.**
    ///
    /// It asserts the CURRENT behaviour, which is a defect: with no dashboard client connected,
    /// `publishMetrics()` returns before evaluating anything, so a metric far above its critical
    /// threshold raises nothing. It is here to make the merge visible, not because the behaviour is
    /// wanted.
    ///
    /// **When it goes red: DELETE this test and remove `@Disabled` from the two below.** That is the
    /// whole of the required action.
    @Test
    void evaluationIsStillGatedOnADashboardClient_TRIPWIRE() {
        var alertManager = manager();
        var sink = new RecordingSink();

        alertManager.bindEventSink(sink, HLC);

        assertThat(DashboardWebSocketHandler.connectedClients())
                .describedAs("precondition: the guard's condition must actually hold, or this test proves nothing")
                .isZero();

        for (int tick = 0; tick < 20; tick++) {
            publisherFor(alertManager, 0.95).publishMetrics();
        }

        assertThat(sink.breaches())
                .describedAs("TRIPWIRE (#957): evaluation is still gated on a dashboard client on THIS branch. "
                             + "If this assertion fails, fix/alerting-structure-2026-09-08 has merged and the gate "
                             + "is gone — which is correct. DELETE this test and remove @Disabled from "
                             + "thresholdEvaluationReachesTheLogWithNoDashboardClient and its control.")
                .isEmpty();
    }

    /// **The real pin.** A metric above its critical threshold must reach the cluster event log with
    /// NO dashboard client connected — a headless cluster is the normal production state, and alerting
    /// is an operator capability, not a UI feature.
    ///
    /// Also pins that a sustained breach emits ONCE across 20 ticks rather than once per tick, since
    /// this is the path where the 1 Hz repetition actually happens.
    @Disabled("RED until fix/alerting-structure-2026-09-08 merges and un-gates publishMetrics — see the "
              + "TRIPWIRE test above, which reddens at that merge and says to enable this one (#957)")
    @Test
    void thresholdEvaluationReachesTheLogWithNoDashboardClient() {
        var alertManager = manager();
        var sink = new RecordingSink();

        alertManager.bindEventSink(sink, HLC);

        assertThat(DashboardWebSocketHandler.connectedClients())
                .describedAs("precondition: zero clients — this test is worthless if one happened to be connected")
                .isZero();

        for (int tick = 0; tick < 20; tick++) {
            publisherFor(alertManager, 0.95).publishMetrics();
        }

        assertThat(sink.breaches())
                .describedAs("a metric above its critical threshold must reach the event log on a headless "
                             + "cluster, and a sustained breach must emit ONCE across 20 ticks")
                .hasSize(1);
    }

    /// **The control, and it is not optional.** Without it, the assertion above is satisfied by an
    /// implementation that emits on every evaluation regardless of value — the mirror-image defect.
    /// Same publisher, same zero connected clients, value below every threshold, nothing emitted.
    @Disabled("Enable together with thresholdEvaluationReachesTheLogWithNoDashboardClient — until the "
              + "gate is removed this passes VACUOUSLY, because nothing evaluates at all (#957)")
    @Test
    void valueBelowEveryThresholdEmitsNothingUnderTheSameConditions() {
        var alertManager = manager();
        var sink = new RecordingSink();

        alertManager.bindEventSink(sink, HLC);

        for (int tick = 0; tick < 20; tick++) {
            publisherFor(alertManager, 0.10).publishMetrics();
        }

        assertThat(sink.breaches())
                .describedAs("a sub-threshold metric must emit nothing under exactly the conditions that make "
                             + "the positive test pass")
                .isEmpty();
    }
}
