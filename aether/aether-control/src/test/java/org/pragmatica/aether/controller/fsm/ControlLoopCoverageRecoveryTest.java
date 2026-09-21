// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.controller.fsm;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.DecisionTreeController;
import org.pragmatica.aether.controller.ScalingConfig;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.slice.SliceState;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.worker.metrics.CommunityMetricsSnapshot;
import org.pragmatica.aether.worker.metrics.PerSliceMetrics;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.Fsm;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Missing producers must not enter the scaling baseline as zero load.
class ControlLoopCoverageRecoveryTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final NodeId WORKER = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("worker-2").unwrap();
    private static final Artifact HOT = Artifact.artifact("org.test:hot:1.0.0").unwrap();
    private static final int WINDOW = 5; // ScalingConfig.forgeDefaults window
    private static final NodeId THIRD = NodeId.nodeId("worker-3").unwrap();

    @Test
    void steadyLoadAcrossACoverageGapMustNotScaleUpOnRecovery() {
        var cluster = new ControlLoopContextAttributionTest.CapturingClusterNode();
        var clock = new java.util.concurrent.atomic.AtomicLong(1_000_000);
        var ctx = buildContext(cluster, clock);

        ctx.putBlueprint(HOT, AetherValue.SliceTargetValue.sliceTargetValue(HOT.version(), 3, 1));
        ctx.setTopology(List.of(SELF, WORKER, OTHER, NodeId.nodeId("n4").unwrap(), NodeId.nodeId("n5").unwrap()));
        ctx.recordSliceState(new AetherKey.SliceNodeKey(HOT, WORKER), SliceState.ACTIVE);
        ctx.recordSliceState(new AetherKey.SliceNodeKey(HOT, OTHER), SliceState.ACTIVE);
        ctx.recordSliceState(new AetherKey.SliceNodeKey(HOT, THIRD), SliceState.ACTIVE);

        long seq = 1;
        // Phase 1: both producers fresh, steady 100 + 100. OTHER's samples are 29.4 s old so they expire soon.
        for (int i = 0; i < WINDOW + 1; i++, seq++) {
            ctx.storeCommunitySnapshot(snapshot(WORKER, 100, ctx.nowMs(), seq));
            ctx.storeCommunitySnapshot(snapshot(OTHER, 100, ctx.nowMs() - 29_400, seq));
            ctx.storeCommunitySnapshot(snapshot(THIRD, 100, ctx.nowMs() - 29_400, seq));
            assertThat(ctx.hasMetricCoverage(HOT)).describedAs("phase 1 cycle %d covered", i).isTrue();
            ctx.runEvaluationCycle();
        }
        assertThat(cluster.putBases()).describedAs("steady load with full coverage scales nothing").isEmpty();

        // Phase 2: OTHER's last report expires (placement still ACTIVE). Only WORKER reports.
        clock.addAndGet(1_000);
        for (int i = 0; i < WINDOW; i++, seq++) {
            ctx.storeCommunitySnapshot(snapshot(WORKER, 100, ctx.nowMs(), seq));
            assertThat(ctx.hasMetricCoverage(HOT)).describedAs("phase 2 cycle %d NOT covered", i).isFalse();
            ctx.runEvaluationCycle();
            assertThat(ctx.scalingDecisions().get(HOT).guard()).isEqualTo(ScalingDecisionRecord.Guard.METRICS_INCOMPLETE);
        }
        assertThat(cluster.putBases()).describedAs("held during the gap").isEmpty();

        // Phase 3: coverage returns with the SAME steady load (100 + 100 + 100 = 300). Let the 10 s slice cooldown (ControllerConfig.DEFAULT) lapse
        // first, as it would between 5 s production evaluation intervals.
        clock.addAndGet(10_500);
        ctx.storeCommunitySnapshot(snapshot(WORKER, 100, ctx.nowMs(), seq));
        ctx.storeCommunitySnapshot(snapshot(OTHER, 100, ctx.nowMs(), seq));
        ctx.storeCommunitySnapshot(snapshot(THIRD, 100, ctx.nowMs(), seq));
        assertThat(ctx.hasMetricCoverage(HOT)).isTrue();
        ctx.runEvaluationCycle();

        var decision = ctx.scalingDecisions().get(HOT);
        assertThat(decision.loadFactor()).isEqualTo(1.0);
        assertThat(cluster.putBases()).describedAs("unchanged load after a coverage gap must not scale up; decision=%s", decision)
                                     .doesNotContain(HOT.base());
    }

    private static CommunityMetricsSnapshot snapshot(NodeId producer, long active, long timestampMs, long sequence) {
        var slices = List.of(PerSliceMetrics.perSliceMetrics(HOT, active, 0.0, 0.0, active));

        return new CommunityMetricsSnapshot("community", producer, 1, slices, timestampMs, 1, sequence);
    }

    private static ControlLoopContext buildContext(ControlLoopContextAttributionTest.CapturingClusterNode cluster, java.util.concurrent.atomic.AtomicLong clock) {
        var config = ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig());
        var controller = DecisionTreeController.decisionTreeController(config);
        var holder = new AtomicReference<ControlLoopContext>();
        Function<Fsm<ControlLoopState, ClusterFsmEvent>, ControlLoopState> factory = fsm -> {
            var context = new ControlLoopContext(fsm,
                                                 SELF,
                                                 controller,
                                                 new ControlLoopContextAttributionTest.StubMetricsCollector(),
                                                 Option.none(),
                                                 cluster,
                                                 TimeSpan.timeSpan(5_000).millis(),
                                                 config,
                                                 _ -> {},
                                                 clock::get);

            context.setMetricsProducerEligibility(_ -> true);
            holder.set(context);

            return context.dormant();
        };

        Fsm.fsm("rev1390-probe", SELF.id(), factory);

        return holder.get();
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
