// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.config.AlertConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AlertThresholdKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AlertThresholdValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;


/// #957 / #969 — threshold alerting on the cluster event log, and the hysteresis that damps it.
///
/// **What each group is actually pinning**, because "the tests pass" is not a claim:
/// - [Hysteresis] pins the CLEAR and DOWNGRADE edges against the margin, and pins that the RAISE and
///   UPGRADE edges are NOT damped. The last two matter most: a margin that delayed first detection or
///   swallowed an escalation would be a regression wearing the shape of a feature.
/// - [Clamp] pins the one property that makes the margin's VALUE a tunable rather than a correctness
///   constant. Its threshold pair is deliberately closer together than the margin, which is the only
///   configuration where clamping is observable at all.
/// - [Emission] pins that the two new sealed variants actually reach the sink, WITH a negative control
///   — an implementation that emitted unconditionally would pass the positives alone.
/// - [HistoryProjection] pins that history now comes from the log, including the bound.
/// - [DerivedView] pins the property the whole redesign rests on: "what is firing now" is re-derived
///   from live metrics and therefore survives losing all in-memory state WITHOUT replaying the log.
///
/// Uses the real `alertManager` factory (not `readOnly`) so `ensureDefaultThresholds` seeds the SHIPPED
/// defaults — `cpu.usage` 0.7/0.9 — since the clamp arithmetic is only meaningful against real numbers.
class AlertManagerThresholdEventTest {

    private static final HlcClock HLC = HlcClock.hlcClock(new NodeId("test-node"));
    private static final NodeId NODE = new NodeId("node-1");
    private static final String CPU = "cpu.usage";

    /// Shipped defaults for `cpu.usage`, and the clear points they imply at the 5% default margin:
    /// CRITICAL clears below `max(0.9 * 0.95, 0.7)` = **0.855**; WARNING clears below `0.7 * 0.95` =
    /// **0.665**. Every literal in [Hysteresis] is chosen relative to these two numbers.
    private static final double CRITICAL_CLEAR_POINT = 0.855;
    private static final double WARNING_CLEAR_POINT = 0.665;

    @SuppressWarnings("unchecked")
    private static AlertManager newManager() {
        return AlertManager.alertManager(Mockito.mock(org.pragmatica.cluster.node.rabia.RabiaNode.class),
                                         (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class));
    }

    /// Recording sink standing in for `ClusterEventAggregator::emit`. It records unconditionally — it
    /// does NOT re-implement the owner gate — so these tests pin what `AlertManager` decides to emit,
    /// never what the aggregator decides to publish. Those are separate properties and conflating them
    /// in one double is how a test ends up asserting its own fixture.
    private static final class RecordingSink implements AlertManager.EventSink {
        private final List<ClusterEvent> events = new CopyOnWriteArrayList<>();

        @Override
        public void emit(ClusterEvent event) {
            events.add(event);
        }

        List<ClusterEvent> breaches() {
            return events.stream().filter(e -> e instanceof ClusterEvent.ThresholdBreached).toList();
        }

        List<ClusterEvent> clears() {
            return events.stream().filter(e -> e instanceof ClusterEvent.ThresholdCleared).toList();
        }
    }

    private static RecordingSink sinkOn(AlertManager manager) {
        var sink = new RecordingSink();

        manager.bindEventSink(sink, HLC);

        return sink;
    }

    /// Install a threshold through the CLUSTER-REPLICATION path (`@MessageReceiver onAlertThresholdPut`)
    /// rather than by reaching into the map. That is how a threshold really arrives on a node after a
    /// consensus Put, so a test using it exercises the same entry point production does.
    private static void putThreshold(AlertManager manager, String metric, double warning, double critical) {
        manager.onAlertThresholdPut(new KVStoreNotification.ValuePut<>(new KVCommand.Put<>(new AlertThresholdKey(metric),
                                                                                          AlertThresholdValue.alertThresholdValue(metric,
                                                                                                                                  warning,
                                                                                                                                  critical)),
                                                                       Option.empty()));
    }

    @Nested
    class Hysteresis {

        @Test
        void sustainedBreach_raisesOnceNotOncePerEvaluation() {
            var manager = newManager();
            var sink = sinkOn(manager);

            for (int i = 0; i < 20; i++) {
                manager.checkThreshold(CPU, NODE, 0.95);
            }

            assertThat(sink.breaches())
                    .describedAs("a sustained breach must fire once, not once per evaluation tick")
                    .hasSize(1);
        }

        /// The core #969 behaviour. 0.87 sits BELOW the critical threshold (0.9) but ABOVE the critical
        /// clear point (0.855), so the alert holds and nothing new is emitted. Without the margin this
        /// value would downgrade to WARNING and emit on every crossing.
        @Test
        void valueOscillatingInsideTheMargin_doesNotReFire() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold(CPU, NODE, 0.95);
            assertThat(sink.breaches()).hasSize(1);

            for (int i = 0; i < 10; i++) {
                manager.checkThreshold(CPU, NODE, 0.87);
                manager.checkThreshold(CPU, NODE, 0.95);
            }

            assertThat(sink.breaches())
                    .describedAs("a value oscillating between 0.87 and 0.95 stays above the %s clear point,"
                                 + " so it must not re-fire", CRITICAL_CLEAR_POINT)
                    .hasSize(1);
            assertThat(sink.clears())
                    .describedAs("and it must not clear either")
                    .isEmpty();
        }

        /// The complement, and the control for the test above: below the clear point it DOES resolve.
        /// Without this, "does not re-fire" would also be satisfied by an implementation that never
        /// fires or never clears at all.
        @Test
        void valueBelowWarningClearPoint_clearsTheAlert() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold(CPU, NODE, 0.95);
            manager.checkThreshold(CPU, NODE, 0.5);

            assertThat(sink.clears())
                    .describedAs("0.5 is below the WARNING clear point %s, so the alert must clear", WARNING_CLEAR_POINT)
                    .hasSize(1);
            assertThat(manager.activeAlertCount())
                    .describedAs("and it must leave the derived view")
                    .isZero();
        }

        /// A WARNING alert whose metric crosses into CRITICAL must escalate on the spot. Damping exists
        /// to suppress repeated notification of the SAME condition, never to hide a worsening one — and
        /// the naive implementation gets this wrong, because the WARNING clear point (0.665) sits far
        /// below any CRITICAL value, so a hold-first branch would swallow every escalation.
        @Test
        void warningEscalatingToCritical_isNotDamped() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold(CPU, NODE, 0.75);
            manager.checkThreshold(CPU, NODE, 0.95);

            assertThat(sink.breaches())
                    .describedAs("WARNING then CRITICAL must produce two breach events, not one held at WARNING")
                    .hasSize(2);
            assertThat(sink.breaches().getLast().details())
                    .containsEntry("alertSeverity", "CRITICAL");
        }

        /// The raise edge is undamped: the very first crossing fires immediately rather than waiting for
        /// the value to exceed the threshold by the margin. A margin applied to the raise edge would
        /// delay first detection, which is the regression this asserts against.
        @Test
        void firstCrossing_firesWithoutWaitingForTheMargin() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold(CPU, NODE, 0.9);

            assertThat(sink.breaches())
                    .describedAs("exactly at the critical threshold must fire, undamped")
                    .hasSize(1);
        }
    }

    @Nested
    class Clamp {

        /// The clamp is only observable when warning and critical are closer together than the margin.
        /// 0.80/0.82 at a 5% margin gives an unclamped clear point of `0.82 * 0.95` = **0.779**, which
        /// is BELOW the warning threshold of 0.80.
        ///
        /// At 0.795 the metric is below WARNING, so no alert should be active at all. Unclamped, 0.795
        /// >= 0.779 would HOLD the alert at CRITICAL — a CRITICAL alert persisting while the metric sits
        /// beneath even the warning line. The clamp raises the clear point to 0.80 and the alert clears.
        @Test
        void criticalAlert_doesNotPersistBelowItsWarningThreshold() {
            var manager = newManager();
            var sink = sinkOn(manager);

            putThreshold(manager, "tight.metric", 0.80, 0.82);
            manager.checkThreshold("tight.metric", NODE, 0.85);
            assertThat(sink.breaches()).hasSize(1);

            manager.checkThreshold("tight.metric", NODE, 0.795);

            assertThat(sink.clears())
                    .describedAs("0.795 is below the WARNING threshold 0.80; unclamped it would sit above"
                                 + " the raw clear point 0.779 and hold CRITICAL")
                    .hasSize(1);
            assertThat(manager.activeAlertCount()).isZero();
        }
    }

    @Nested
    class Emission {

        @Test
        void breach_emitsThresholdBreachedNamingTheNodeWhoseMetricCrossed() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold(CPU, NODE, 0.95);

            assertThat(sink.breaches()).hasSize(1);

            var details = sink.breaches().getFirst().details();

            assertThat(details).containsEntry("metric", CPU)
                               .containsEntry("nodeId", NODE.id())
                               .containsEntry("alertSeverity", "CRITICAL");
            assertThat(details.get("value")).isEqualTo("0.95");
        }

        @Test
        void clear_emitsThresholdClearedCarryingTheClearPoint() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold(CPU, NODE, 0.95);
            manager.checkThreshold(CPU, NODE, 0.1);

            assertThat(sink.clears()).hasSize(1);
            assertThat(sink.clears().getFirst().details())
                    .containsEntry("metric", CPU)
                    .containsEntry("nodeId", NODE.id())
                    .containsEntry("clearedFrom", "CRITICAL")
                    .containsKey("clearPoint");
        }

        /// The negative control. Without it, an implementation that emitted a breach on EVERY evaluation
        /// regardless of value would satisfy both positives above.
        @Test
        void valueBelowEveryThreshold_emitsNothing() {
            var manager = newManager();
            var sink = sinkOn(manager);

            for (int i = 0; i < 10; i++) {
                manager.checkThreshold(CPU, NODE, 0.1);
            }

            assertThat(sink.breaches()).isEmpty();
            assertThat(sink.clears()).isEmpty();
        }

        /// A metric with no configured threshold must not emit. Guards against a future refactor that
        /// defaults a missing threshold to zero, which would alert on every metric in the cluster.
        @Test
        void metricWithNoThreshold_emitsNothing() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.checkThreshold("no.such.metric", NODE, 999.0);

            assertThat(sink.breaches()).isEmpty();
        }
    }

    @Nested
    class HistoryProjection {

        @Test
        void history_projectsBreachAsTriggeredAndClearAsResolved() {
            var manager = newManager();

            manager.bindClusterEventsSource(() -> Promise.success(List.of(breachEvent(), clearEvent())));

            var history = manager.alertHistoryAsList().await().or(List.of());

            assertThat(history).hasSize(2);
            assertThat(history.getFirst().status()).isEqualTo("TRIGGERED");
            assertThat(history.getFirst().metric()).isEqualTo(CPU);
            assertThat(history.getFirst().nodeId()).isEqualTo(NODE.id());
            assertThat(history.getLast().status()).isEqualTo("RESOLVED");
        }

        /// Non-alert cluster events must not leak into the alert history surface.
        @Test
        void history_ignoresUnrelatedClusterEvents() {
            var manager = newManager();
            var unrelated = new ClusterEvent.LeaderElected(HLC.now(),
                                                           ClusterEvent.Severity.INFO,
                                                           "unrelated",
                                                           Map.of("leaderId", "n1"));

            manager.bindClusterEventsSource(() -> Promise.success(List.of(unrelated, breachEvent())));

            var history = manager.alertHistoryAsList().await().or(List.of());

            assertThat(history).hasSize(1);
            assertThat(history.getFirst().status()).isEqualTo("TRIGGERED");
        }

        /// The dashboard polls this every 2 seconds, so an unbounded projection would scan the full
        /// retained window on every poll. Keeps the NEWEST entries.
        @Test
        void history_isBoundedAndKeepsTheNewest() {
            var manager = newManager();
            var many = new java.util.ArrayList<ClusterEvent>();

            for (int i = 0; i < 250; i++) {
                many.add(breachEventFor("metric-" + i));
            }

            manager.bindClusterEventsSource(() -> Promise.success(List.copyOf(many)));

            var bounded = manager.alertHistoryAsList(10).await().or(List.of());

            assertThat(bounded).hasSize(10);
            assertThat(bounded.getLast().metric())
                    .describedAs("the bound must keep the most recent entries, not the oldest")
                    .isEqualTo("metric-249");
        }

        /// Bootstrap window: no source bound yet. Empty, not a failure — a node that cannot yet read the
        /// log must still answer the endpoint.
        @Test
        void history_withNoBoundSource_isEmptyRatherThanFailing() {
            assertThat(newManager().alertHistoryAsList().await().or(List.of())).isEmpty();
        }

        private static ClusterEvent breachEvent() {
            return breachEventFor(CPU);
        }

        private static ClusterEvent breachEventFor(String metric) {
            return new ClusterEvent.ThresholdBreached(HLC.now(),
                                                      ClusterEvent.Severity.CRITICAL,
                                                      "breach",
                                                      Map.of("metric",
                                                             metric,
                                                             "nodeId",
                                                             NODE.id(),
                                                             "value",
                                                             "0.95",
                                                             "alertSeverity",
                                                             "CRITICAL"));
        }

        private static ClusterEvent clearEvent() {
            return new ClusterEvent.ThresholdCleared(HLC.now(),
                                                     ClusterEvent.Severity.INFO,
                                                     "clear",
                                                     Map.of("metric",
                                                            CPU,
                                                            "nodeId",
                                                            NODE.id(),
                                                            "value",
                                                            "0.1",
                                                            "clearedFrom",
                                                            "CRITICAL",
                                                            "clearPoint",
                                                            "0.855"));
        }
    }

    @Nested
    class DerivedView {

        /// **The property the whole redesign rests on.** A fresh manager models a node that restarted or
        /// an owner that just took over: no in-memory alert state, and — crucially — NO cluster events
        /// bound, so nothing can be replayed. One evaluation tick against the live metric value is
        /// enough to restore "what is firing now".
        ///
        /// This is what makes the derived view different in kind from the accumulated map it replaces,
        /// and it is why a breach whose `ThresholdBreached` was evicted by retention is still reported
        /// as firing.
        @Test
        void freshManagerWithNoLogAccess_reDerivesFiringStateFromLiveMetrics() {
            var restarted = newManager();

            assertThat(restarted.activeAlertCount())
                    .describedAs("precondition: a fresh manager starts with nothing firing")
                    .isZero();

            restarted.checkThreshold(CPU, NODE, 0.95);

            assertThat(restarted.activeAlertCount())
                    .describedAs("one evaluation tick restores the firing state with no log replay")
                    .isEqualTo(1);
        }

        /// The view is keyed by (metric, node), so one node's breach does not mask another's.
        @Test
        void breachesOnDifferentNodes_areTrackedSeparately() {
            var manager = newManager();

            manager.checkThreshold(CPU, NODE, 0.95);
            manager.checkThreshold(CPU, new NodeId("node-2"), 0.95);

            assertThat(manager.activeAlertCount()).isEqualTo(2);
        }
    }

    @Nested
    class ConfiguredMargin {

        /// A zero margin disables damping — the clear point collapses onto the breach point, which is
        /// the pre-#969 behaviour. Pins that the margin is genuinely read from config rather than
        /// hard-coded: with damping off, 0.87 (below critical, above the default clear point) downgrades
        /// instead of holding.
        @Test
        void zeroMargin_restoresUndampedClearing() {
            var manager = newManager();
            var sink = sinkOn(manager);

            manager.bindAlertConfig(AlertConfig.alertConfig(true,
                                                            AlertConfig.WebhookConfig.webhookConfig(),
                                                            AlertConfig.EventConfig.eventConfig(),
                                                            0.0)
                                               .unwrap());

            manager.checkThreshold(CPU, NODE, 0.95);
            manager.checkThreshold(CPU, NODE, 0.87);

            assertThat(sink.breaches())
                    .describedAs("with the margin at 0, 0.87 is below critical and must downgrade to"
                                 + " WARNING rather than holding CRITICAL")
                    .hasSize(2);
            assertThat(sink.breaches().getLast().details()).containsEntry("alertSeverity", "WARNING");
        }
    }
}
