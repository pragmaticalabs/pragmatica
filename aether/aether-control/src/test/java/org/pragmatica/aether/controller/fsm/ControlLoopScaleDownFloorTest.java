// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// #936 — the autoscaler could not scale down after its first scale-up, and said nothing about it.
///
/// `applyScaling` wrote `newInstances` into **both** `targetInstances` and `minInstances` of the
/// durable `SliceTargetValue`. The resulting Put fed back through `ControlLoop.onSliceTargetPut`,
/// the sole feeder of the autoscaler's own model, so the operator's floor was replaced by the count
/// the slice had just scaled to. `computeRequestedInstances` floors a scale-down at that minimum, so
/// from the first scale-up onwards `max(minInstances, instances - reduceBy)` evaluated to
/// `instances` for any `reduceBy`, the caller saw a no-op and emitted nothing at all.
///
/// **The feedback step in these tests is load-bearing, not ceremony.** In the same method the
/// in-memory registration was written with the *correct* floor while the durable record was written
/// with the wrong one, so a test that stops at the emitted value and never lets it return through
/// the feeder observes a model that still holds the operator's minimum and passes against the
/// defect. The disagreement between the two writes was the defect, and the durable record wins on
/// the next notification. Every sequence below therefore returns the autoscaler's own output through
/// `onSliceTargetPut` exactly as consensus does.
///
/// A ratchet is invisible in any single observation — the scaled-up write looks correct on its own,
/// and so does every evaluation after it. It appears only across the sequence.
class ControlLoopScaleDownFloorTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:scaled-slice:1.0.0").unwrap();
    private static final int WINDOW = 3;

    private ControlLoopOwnerPreservationTest.CapturingClusterNode cluster;
    private ControlLoop controlLoop;
    private ControlLoopContext ctx;
    private final AtomicReference<ControlDecisions> nextDecision = new AtomicReference<>(ControlDecisions.none());

    @BeforeEach
    void setUp() {
        cluster = new ControlLoopOwnerPreservationTest.CapturingClusterNode();
        controlLoop = buildControlLoop();
        ctx = ((ControlLoop.ControlLoopAdapter) controlLoop).ctx();
        ctx.setTopology(List.of(SELF,
                                NodeId.nodeId("worker-1").unwrap(),
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
    }

    @Nested
    class RatchetAcrossTheFeedbackLoop {
        /// The headline sequence: a real scale-up, the autoscaler's own Put returning through the
        /// production feeder, then a scale-down that has to actually reduce the count.
        @Test
        void scaleUpThenScaleDown_actuallyReducesTheInstanceCount() {
            deploy(2, 1);

            var scaledUp = evaluate(new BlueprintChange.ScaleUp(SLICE, 2));

            assertThat(scaledUp.targetInstances()).as("the scale-up must have happened, or the scale-down below is vacuous")
                                                  .isEqualTo(4);

            observe(scaledUp);

            var scaledDown = evaluate(new BlueprintChange.ScaleDown(SLICE, 2));

            assertThat(scaledDown.targetInstances()).as("#936: the floor must not have ratcheted up to the scaled count")
                                                    .isEqualTo(2);
        }

        /// The producer half of the same defect, asserted directly on the emitted record: the
        /// operator's floor is what the autoscaler writes back, never the count it just chose.
        @Test
        void scaleUp_leavesTheOperatorFloorUntouched() {
            deploy(2, 1);

            assertThat(evaluate(new BlueprintChange.ScaleUp(SLICE, 2)).minInstances())
                    .as("#936: minInstances is the operator's policy, not the autoscaler's new count")
                    .isEqualTo(1);
        }

        /// Opposite polarity, and the reason the fix is not "stop consulting `minInstances`". A
        /// genuine operator floor must still refuse a scale-down through it — without this, an
        /// implementation that dropped the floor entirely would pass the two tests above.
        @Test
        void scaleDownThroughAGenuineFloor_emitsNothing() {
            deploy(3, 3);

            evaluateExpectingNoCommand(new BlueprintChange.ScaleDown(SLICE, 2));

            assertThat(cluster.sliceTargets()).as("#936: a real floor must still bind")
                                              .isEmpty();
        }
    }

    /// #936 acceptance 3. A scale-down the floor reduced to a no-op used to be indistinguishable
    /// from an evaluation with nothing to do: same absent command, same absent event, same `HELD`
    /// baseline in the decision snapshot. An operator saw an autoscaler that appeared idle.
    @Nested
    class FlooredScaleDownIsVisible {
        @Test
        void flooredScaleDown_recordsTheFloorGuardAndThePreFloorRequest() {
            deploy(2, 2);

            evaluateExpectingNoCommand(new BlueprintChange.ScaleDown(SLICE, 1));

            var decision = ctx.scalingDecisions().get(SLICE);

            assertThat(decision.guard()).as("#936: the floor must name itself on the #425 surface")
                                        .isEqualTo(ScalingDecisionRecord.Guard.MIN_INSTANCES);
            assertThat(decision.outcome()).isEqualTo(ScalingDecisionRecord.Outcome.HELD);
            assertThat(decision.requestedInstances()).as("the count that was wanted, not the floored one")
                                                     .isEqualTo(1);
            assertThat(decision.cappedInstances()).isEqualTo(2);
        }

        /// Opposite polarity: a scale-down the floor does not touch must not claim the floor fired,
        /// or the guard above would be a constant rather than a diagnostic.
        @Test
        void unflooredScaleDown_recordsNoGuard() {
            deploy(3, 1);

            evaluate(new BlueprintChange.ScaleDown(SLICE, 1));

            var decision = ctx.scalingDecisions().get(SLICE);

            assertThat(decision.guard()).isEqualTo(ScalingDecisionRecord.Guard.NONE);
            assertThat(decision.outcome()).isEqualTo(ScalingDecisionRecord.Outcome.SCALED_DOWN);
        }
    }

    // --- sequence helpers ---

    /// Registers the slice the way a deploy does — a `SliceTargetValue` arriving at the production
    /// feeder, which is the only route by which the autoscaler learns a slice exists at all.
    private void deploy(int instances, int minInstances) {
        observe(SliceTargetValue.sliceTargetValue(SLICE.version(), instances, minInstances));
    }

    /// Delivers a `SliceTargetValue` through `ControlLoop.onSliceTargetPut`, the notification every
    /// committed Put raises — including the autoscaler's own.
    private void observe(SliceTargetValue value) {
        controlLoop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()),
                                                                        value),
                                                    Option.none()));
    }

    /// Runs one evaluation cycle for `change` and returns the single `SliceTargetValue` it wrote.
    private SliceTargetValue evaluate(BlueprintChange change) {
        var before = cluster.sliceTargets().size();

        nextDecision.set(ControlDecisions.controlDecisions(change));
        ctx.runEvaluationCycle();

        var emitted = cluster.sliceTargets();

        assertThat(emitted).as("the evaluation cycle must have emitted exactly one SliceTarget Put")
                           .hasSize(before + 1);

        return emitted.getLast();
    }

    private void evaluateExpectingNoCommand(BlueprintChange change) {
        var before = cluster.sliceTargets().size();

        nextDecision.set(ControlDecisions.controlDecisions(change));
        ctx.runEvaluationCycle();

        assertThat(cluster.sliceTargets()).as("this evaluation must emit no SliceTarget Put")
                                          .hasSize(before);
    }

    // --- control-loop fixtures (mirrors ControlLoopOwnerPreservationTest's) ---

    /// Assembled through the production factory, which builds the context, the FSM and the adapter
    /// the same way a node does. The controller replays whatever the current test scripted, so the
    /// scaling arithmetic under test is isolated from metric-window composite scoring — the only
    /// producer of `SliceTarget` Puts here is the autoscaler itself.
    private ControlLoop buildControlLoop() {
        Consumer<ScalingEvent> sink = _ -> {};
        ClusterController controller = _ -> Promise.success(nextDecision.get());

        return ControlLoop.controlLoop(SELF,
                                       controller,
                                       new ControlLoopContextAttributionTest.StubMetricsCollector(),
                                       Option.none(),
                                       cluster,
                                       TimeSpan.timeSpan(5_000).millis(),
                                       ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig()),
                                       sink);
    }

    private static ScalingConfig smallWindowConfig() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);

        weights.put(ScalingMetric.CPU, 0.0);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.6);
        weights.put(ScalingMetric.P95_LATENCY, 0.4);
        weights.put(ScalingMetric.ERROR_RATE, 0.0);

        return ScalingConfig.scalingConfig(WINDOW, 5_000L, 1.5, 0.5, weights).unwrap();
    }
}
