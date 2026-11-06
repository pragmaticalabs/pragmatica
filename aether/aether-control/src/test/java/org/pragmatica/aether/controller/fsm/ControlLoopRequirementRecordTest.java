// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControlLoop.ControlLoopAdapter;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.ScalingConfig;
import org.pragmatica.aether.controller.ScalingEvent;
import org.pragmatica.aether.controller.ScalingMetric;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// v2713 B2 (mutants x01, x02, x04, x07, x08, x09): what the autoscaler's decision writes. A scale-up from 2 to 4 writes exactly one
/// `REACTIVE` record (never an `OPERATOR` one) carrying the decided count, the scale-up reason and the committed revision it was
/// decided at, and announces exactly one `ScaledUp`; the same decision on the next cycle writes and announces nothing.
class ControlLoopRequirementRecordTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:recorded-slice:1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:recorded-app:1.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(V1.base());
    private static final SliceRequirementKey REACTIVE = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.REACTIVE);
    private static final SliceRequirementKey OPERATOR = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.OPERATOR);
    private static final long COMMITTED_REVISION = 42L;

    private final List<ScalingEvent> events = new CopyOnWriteArrayList<>();
    private KVStore<AetherKey, AetherValue> store;
    private ImmediateClusterNode cluster;
    private ControlLoopContext ctx;
    private ControlLoopAdapter adapter;
    private volatile BlueprintChange decision = new BlueprintChange.ScaleUp(V1, 2);

    @BeforeEach
    void loopOverAnOwnedTargetAtRevision42() {
        store = FencedTargetWrites.fencedStore(SELF, OWNER);
        cluster = new ImmediateClusterNode(SELF, store);

        var target = SliceTargetValue.sliceTargetValue(V1.version(), 2, 1, Option.some(OWNER), Option.some(20), Option.none(), Option.none());

        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(TARGET_KEY, target))));
        store.onPhaseCompleted(COMMITTED_REVISION);

        var loop = (ControlLoopAdapter) buildLoop();

        adapter = loop;
        ctx = loop.ctx();
        ctx.setTopology(List.of(SELF,
                                NodeId.nodeId("n2").unwrap(),
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
        loop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, target), Option.none()));
    }

    /// x04, x07, x08, x09: the first decision writes one REACTIVE record (x04: the key it reads and writes is REACTIVE's), with the
    /// scale-up reason (x08) and the revision committed when it was built (x07), and publishes the scaling event once (x09).
    @Test
    void scaleUp_writesOneReactiveRecordAndAnnouncesOnce() {
        ctx.runEvaluationCycle();

        assertThat(await(() -> store.get(REACTIVE).isPresent())).as("the decision was written").isTrue();

        var written = store.getTyped(REACTIVE, SliceRequirementValue.class).unwrap();

        assertThat(written.instances()).isEqualTo(4);
        assertThat(written.reason()).isEqualTo("autoscaler scale-up from 2");
        assertThat(written.baseTargetRevision()).as("the revision committed when the write was built").isEqualTo(COMMITTED_REVISION);
        assertThat(written.expiresAt().isEmpty()).as("REACTIVE does not expire").isTrue();
        assertThat(store.get(OPERATOR).isEmpty()).as("the autoscaler never writes the operator's record").isTrue();
        assertThat(cluster.submitted()).as("one transaction").isEqualTo(1);
        assertThat(events).as("one scaling event").containsExactly(ScalingEvent.ScaledUp.scaledUp(V1, 2, 4));
    }

    /// x12: the decision record carries the count the decision wrote (an uncapped scale-up requests and gets 4 from 2).
    @Test
    void scaleUp_recordsTheScaledCounts() {
        ctx.runEvaluationCycle();

        assertThat(await(() -> store.get(REACTIVE).isPresent())).as("the decision was written").isTrue();

        var record = ctx.scalingDecisions().get(V1);

        assertThat(record.outcome()).isEqualTo(ScalingDecisionRecord.Outcome.SCALED_UP);
        assertThat(record.guard()).isEqualTo(ScalingDecisionRecord.Guard.NONE);
        assertThat(record.currentInstances()).isEqualTo(2);
        assertThat(record.requestedInstances()).isEqualTo(4);
        assertThat(record.cappedInstances()).isEqualTo(4);
    }

    /// x08: a scale-up is capped at the cluster size (5 core nodes), not at the blueprint maximum (20).
    @Test
    void scaleUpBeyondTheClusterSize_isCappedAtTheClusterSize() {
        decision = new BlueprintChange.ScaleUp(V1, 10);

        ctx.runEvaluationCycle();

        assertThat(await(() -> store.get(REACTIVE).isPresent())).as("the decision was written").isTrue();
        assertThat(store.getTyped(REACTIVE, SliceRequirementValue.class).unwrap().instances()).isEqualTo(5);
        assertThat(ctx.scalingDecisions().get(V1).cappedInstances()).isEqualTo(5);
        assertThat(ctx.scalingDecisions().get(V1).guard()).isEqualTo(ScalingDecisionRecord.Guard.CLUSTER_CAP);
    }

    /// x05: whether the decision moves the target is judged at the count that will be written (the capped 5), not the requested 18.
    /// With an operator floor of 8 holding the target, REACTIVE 5 moves nothing, so nothing is announced; REACTIVE 18 would.
    @Test
    void cappedDecisionBelowAnOperatorFloor_isWrittenButNotAnnounced() {
        var target = SliceTargetValue.sliceTargetValue(V1.version(), 8, 1, Option.some(OWNER), Option.some(20), Option.none(), Option.none());

        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(TARGET_KEY, target),
                                                new KVCommand.Put<AetherKey, AetherValue>(OPERATOR,
                                                                                          SliceRequirementValue.sliceRequirementValue(8,
                                                                                                                                      "operator",
                                                                                                                                      Option.none(),
                                                                                                                                      0L)))));
        adapter.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, target), Option.none()));
        decision = new BlueprintChange.ScaleUp(V1, 10);

        ctx.runEvaluationCycle();

        assertThat(await(() -> store.get(REACTIVE).isPresent())).as("REACTIVE tracks the capped decision").isTrue();
        assertThat(store.getTyped(REACTIVE, SliceRequirementValue.class).unwrap().instances()).isEqualTo(5);
        assertThat(events).as("the floor holds the target, so no scaling is announced").isEmpty();
    }

    /// x01, x02, x04: the same decision again is not written and not announced again.
    @Test
    void unchangedDecision_writesAndAnnouncesNothing() {
        ctx.runEvaluationCycle();
        assertThat(await(() -> store.get(REACTIVE).isPresent())).as("the decision was written").isTrue();

        ctx.runEvaluationCycle();
        pause(300);

        assertThat(cluster.submitted()).as("an unchanged REACTIVE requirement is not re-written").isEqualTo(1);
        assertThat(events).as("and not announced again").hasSize(1);
    }

    private ControlLoop buildLoop() {
        ClusterController controller = _ -> Promise.success(ControlDecisions.controlDecisions(decision));

        return ControlLoop.controlLoop(SELF,
                                       controller,
                                       new ControlLoopContextAttributionTest.StubMetricsCollector(),
                                       Option.none(),
                                       cluster,
                                       store,
                                       TimeSpan.timeSpan(5_000).millis(),
                                       ControllerConfig.DEFAULT.withScalingConfig(smallWindowConfig()),
                                       events::add);
    }

    private static ScalingConfig smallWindowConfig() {
        var weights = new EnumMap<ScalingMetric, Double>(ScalingMetric.class);

        weights.put(ScalingMetric.CPU, 0.0);
        weights.put(ScalingMetric.ACTIVE_INVOCATIONS, 0.6);
        weights.put(ScalingMetric.P95_LATENCY, 0.4);
        weights.put(ScalingMetric.ERROR_RATE, 0.0);

        return ScalingConfig.scalingConfig(3, 5_000L, 1.5, 0.5, weights).unwrap();
    }

    private static boolean await(BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + 3_000;

        while (!condition.getAsBoolean() && System.currentTimeMillis() < deadline) {
            pause(10);
        }

        return condition.getAsBoolean();
    }

    private static void pause(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
