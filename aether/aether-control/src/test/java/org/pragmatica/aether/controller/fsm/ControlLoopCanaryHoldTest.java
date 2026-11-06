// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AbTestValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// v2713c F2 (CTO ruling 2026-11-06), survivor x10: the autoscaler's decision for a slice is held while an A/B test runs its canary,
/// and released otherwise. One test per A/B test state. Held: `DEPLOYING_VARIANTS`, `ACTIVE`, `ROLLING_BACK`. Not held: `PENDING`,
/// `CONCLUDING` (a refused conclude can sit there indefinitely; known limit: the count may flap once while the conclude write lands),
/// `COMPLETED`, `ROLLED_BACK`, `FAILED`. Each test also runs the opposite arm of the same fixture (no test), so "no Put" cannot come
/// from a loop that never scales.
class ControlLoopCanaryHoldTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact SLICE = Artifact.artifact("org.example:canary-slice:1.0.0").unwrap();

    @Test
    void noTest_scaleUpIsEmitted() {
        assertThat(scaleUpPuts(Option.none())).isEqualTo(1);
    }

    @Test
    void pending_scaleUpIsEmitted() {
        assertThat(scaleUpPuts(Option.some("PENDING"))).isEqualTo(1);
    }

    @Test
    void deployingVariants_scaleUpIsHeld() {
        assertThat(scaleUpPuts(Option.some("DEPLOYING_VARIANTS"))).isZero();
    }

    @Test
    void active_scaleUpIsHeld() {
        assertThat(scaleUpPuts(Option.some("ACTIVE"))).isZero();
    }

    @Test
    void concluding_scaleUpIsEmitted() {
        assertThat(scaleUpPuts(Option.some("CONCLUDING"))).isEqualTo(1);
    }

    @Test
    void rollingBack_scaleUpIsHeld() {
        assertThat(scaleUpPuts(Option.some("ROLLING_BACK"))).isZero();
    }

    @Test
    void completed_scaleUpIsEmitted() {
        assertThat(scaleUpPuts(Option.some("COMPLETED"))).isEqualTo(1);
    }

    @Test
    void rolledBack_scaleUpIsEmitted() {
        assertThat(scaleUpPuts(Option.some("ROLLED_BACK"))).isEqualTo(1);
    }

    @Test
    void failed_scaleUpIsEmitted() {
        assertThat(scaleUpPuts(Option.some("FAILED"))).isEqualTo(1);
    }

    /// Runs one ScaleUp evaluation over a slice at 3 instances with the A/B test (if any) in `abState`; returns the SliceTarget Puts.
    private static int scaleUpPuts(Option<String> abState) {
        var cluster = new ControlLoopOwnerPreservationTest.CapturingClusterNode();
        var store = FencedTargetWrites.fencedStore(SELF);

        abState.onPresent(state -> commit(store, state));

        var nextDecision = new AtomicReference<>(ControlDecisions.none());
        var loop = buildControlLoop(cluster, store, nextDecision);
        var ctx = ((ControlLoop.ControlLoopAdapter) loop).ctx();

        cluster.combineOver(_ -> ctx.observedTarget(SLICE));
        ctx.setTopology(List.of(SELF,
                                NodeId.nodeId("worker-1").unwrap(),
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
        loop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(SliceTargetKey.sliceTargetKey(SLICE.base()),
                                                                 SliceTargetValue.sliceTargetValue(SLICE.version(), 3, 2)),
                                             Option.none()));
        nextDecision.set(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(SLICE, 2)));
        ctx.runEvaluationCycle();

        return cluster.sliceTargets().size();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void commit(KVStore<AetherKey, AetherValue> store, String state) {
        var test = AbTestValue.abTestValue("t1", SLICE.base(), SLICE.version(), "{}", state, "{}", 50, 50, "owner", 0L, 0L);

        store.process(store.createBatch((List) List.of(new KVCommand.Put(new AbTestKey("t1"), test))));
    }

    private static ControlLoop buildControlLoop(ControlLoopOwnerPreservationTest.CapturingClusterNode cluster,
                                                KVStore<AetherKey, AetherValue> store,
                                                AtomicReference<ControlDecisions> nextDecision) {
        Consumer<ScalingEvent> sink = _ -> {};
        ClusterController controller = _ -> Promise.success(nextDecision.get());

        return ControlLoop.controlLoop(SELF,
                                       controller,
                                       new ControlLoopContextAttributionTest.StubMetricsCollector(),
                                       Option.none(),
                                       cluster,
                                       store,
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

        return ScalingConfig.scalingConfig(3, 5_000L, 1.5, 0.5, weights).unwrap();
    }
}
