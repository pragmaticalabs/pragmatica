// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.BlueprintChange;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.RequirementCombiner;
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
import org.pragmatica.aether.slice.kvstore.SliceRequirements;
import org.pragmatica.aether.slice.kvstore.SliceTargetFence;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// rc6 §3.1: the autoscaler and the operator write separate requirement records, and the leader's combiner is the only writer of the
/// target count. Every write here is held by the test and committed into a real `KVStore` in an order the test chooses, including the
/// reverse of the order the writes were decided in. Whatever the order, both requirements survive and the target equals their max.
class RequirementCombinerOrderingTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:combined-slice:1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:combined-app:1.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(V1.base());
    private static final SliceRequirementKey REACTIVE = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.REACTIVE);
    private static final SliceRequirementKey OPERATOR = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.OPERATOR);
    private static final SliceRequirementKey SCHEDULED = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.SCHEDULED);
    private static final AetherKey.AbTestKey AB_TEST = new AetherKey.AbTestKey("t1");
    private static final int WINDOW = 3;
    private static final int CAPACITY = 9;

    private KVStore<AetherKey, AetherValue> store;
    private HoldingClusterNode cluster;
    private ControlLoopContext ctx;
    private RequirementCombiner combiner;

    @BeforeEach
    void leaderWithAnOwnedTarget() {
        store = FencedTargetWrites.fencedStore(SELF, OWNER);
        cluster = new HoldingClusterNode(store);

        var initial = targetOf(2);

        commit(new KVCommand.Put<>(TARGET_KEY, initial));

        var controlLoop = buildControlLoop();

        ctx = ((ControlLoop.ControlLoopAdapter) controlLoop).ctx();
        ctx.setTopology(List.of(SELF,
                                NodeId.nodeId("n2").unwrap(),
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
        controlLoop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, initial), Option.none()));
        combiner = RequirementCombiner.requirementCombiner(store, cluster::apply, System::currentTimeMillis, () -> CAPACITY);
        combiner.activate();
    }

    @AfterEach
    void stopCombiner() {
        combiner.deactivate();
    }

    /// The autoscaler decides 4 (from 2) and the operator asks for 6, both against target 2. The operator commits first, then the
    /// autoscaler: neither refuses the other (separate keys), and the combined target is the max.
    @Test
    void operatorCommitsFirst_bothRequirementsSurvive_targetIsTheMax() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);
        submitOperator(6);
        cluster.awaitHeld(2);

        cluster.commitNewest();
        cluster.commitOldest();
        combineNow();

        assertBothSurviveAtMax();
    }

    /// The same two writes, committed in the order they were decided: the result is identical.
    @Test
    void autoscalerCommitsFirst_bothRequirementsSurvive_targetIsTheMax() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);
        submitOperator(6);
        cluster.awaitHeld(2);

        cluster.commitOldest();
        cluster.commitOldest();
        combineNow();

        assertBothSurviveAtMax();
    }

    /// The combiner commits a target of 4 from the autoscaler's requirement while the operator's write (decided against target 2) is
    /// still in flight. The operator's write is refused by the moved target, rebuilt from the committed state and commits; the next
    /// combination raises the target to the operator's 6. The autoscaler's requirement survives the whole sequence.
    @Test
    void combinerMovesTheTargetUnderTheOperator_operatorRebuildsAndBothSurvive() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);
        submitOperator(6);
        cluster.awaitHeld(2);

        cluster.commitOldest();
        combiner.onRequirementPut(REACTIVE);
        cluster.awaitHeld(2);
        cluster.commitNewest();

        assertThat(committedTargetCount()).as("the combiner committed the reactive requirement").isEqualTo(Option.some(4));

        cluster.commitAllAsTheyArrive();
        combineNow();

        assertBothSurviveAtMax();
    }

    /// The combiner reads {REACTIVE 4} and builds target 4; the operator's 6 commits before the combiner's write. The combiner's witness
    /// on the operator requirement refuses its stale write, and its rebuild combines the committed requirements: the target is 6 with
    /// no further trigger.
    @Test
    void requirementCommitsUnderTheCombiner_staleCombinationIsRefusedAndRebuilt() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);
        cluster.commitOldest();
        combiner.onRequirementPut(REACTIVE);
        cluster.awaitHeld(1);
        submitOperator(6);
        cluster.awaitHeld(2);

        cluster.commitNewest();
        cluster.commitAllAsTheyArrive();

        assertThat(committedTargetCount()).as("no combination of stale requirements commits").isEqualTo(Option.some(6));
    }

    /// The autoscaler's requirement falls below the operator's: the target holds at the operator's floor.
    @Test
    void reactiveFallsBelowOperator_targetHoldsAtTheOperatorFloor() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(6)));
        commit(new KVCommand.Put<>(REACTIVE, requirement(3)));
        combineNow();

        assertThat(committedTargetCount()).isEqualTo(Option.some(6));
    }

    /// v2713 item 2 (spec §3.1): a release never commands a scale-down. Releasing the operator's 6 over a reactive 4 re-bases REACTIVE to
    /// the committed 6 in the same transaction, so the target holds at 6; only REACTIVE falling (the autoscaler) then lowers it.
    @Test
    void operatorReleased_targetHoldsUntilReactiveFalls() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(6)));
        commit(new KVCommand.Put<>(REACTIVE, requirement(4)));
        combineNow();
        assertThat(committedTargetCount()).isEqualTo(Option.some(6));

        releaseOperator();
        cluster.awaitHeld(1);
        cluster.commitAllAsTheyArrive();
        combineNow();

        assertThat(store.get(OPERATOR).isEmpty()).as("the operator requirement is released").isTrue();
        assertThat(requirementCount(REACTIVE)).as("REACTIVE is re-based to the held count").isEqualTo(Option.some(6));
        assertThat(committedTargetCount()).as("a release does not scale down").isEqualTo(Option.some(6));

        commit(new KVCommand.Put<>(REACTIVE, requirement(5)));
        combineNow();

        assertThat(committedTargetCount()).as("REACTIVE falling scales down").isEqualTo(Option.some(5));
    }

    /// A lowered operator floor is a partial release: it does not scale down either.
    @Test
    void operatorLowered_targetHolds() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(8)));
        commit(new KVCommand.Put<>(REACTIVE, requirement(4)));
        combineNow();
        submitOperator(5);
        cluster.awaitHeld(1);
        cluster.commitAllAsTheyArrive();
        combineNow();

        assertThat(requirementCount(OPERATOR)).isEqualTo(Option.some(5));
        assertThat(committedTargetCount()).as("a lowered floor does not scale down").isEqualTo(Option.some(8));
    }

    /// The placeable capacity bounds the combined target.
    @Test
    void requirementAboveCapacity_targetIsClampedToCapacity() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(8)));
        commit(new KVCommand.Put<>(REACTIVE, requirement(15)));
        combineNow();

        assertThat(committedTargetCount()).isEqualTo(Option.some(CAPACITY));
    }

    /// v2713 item 2: a requirement's expiry re-combines without any write to trigger it, and releases its claim without commanding a
    /// scale-down: the scheduled claim of 7 lapses, the combiner removes the expired record and re-bases REACTIVE to 7 in the same
    /// transaction, and the target holds at 7.
    @Test
    void scheduledRequirementExpires_targetHoldsAtTheExpiry() {
        commit(new KVCommand.Put<>(REACTIVE, requirement(4)));
        commit(new KVCommand.Put<>(SCHEDULED,
                                   SliceRequirementValue.sliceRequirementValue(7,
                                                                               "test",
                                                                               Option.some(System.currentTimeMillis() + 1_000),
                                                                               0L)));
        combineNow();

        assertThat(committedTargetCount()).isEqualTo(Option.some(7));

        cluster.awaitHeld(1, 3_000);
        cluster.commitAllAsTheyArrive();

        assertThat(store.get(SCHEDULED).isEmpty()).as("the expired record is removed").isTrue();
        assertThat(requirementCount(REACTIVE)).as("REACTIVE is re-based to the held count").isEqualTo(Option.some(7));
        assertThat(committedTargetCount()).as("an expiry does not scale down").isEqualTo(Option.some(7));
    }

    /// v2713 item 1, probe P1 (production wiring): the control loop's own combiner runs with unbounded placeable capacity, so an
    /// operator requirement of 7 on a cluster of 3 core nodes commits 7, not 3.
    @Test
    void productionCombiner_operatorAboveCoreCount_commitsTheRequirement() {
        var controlLoop = buildControlLoop();
        var adapter = (ControlLoop.ControlLoopAdapter) controlLoop;

        adapter.ctx().setTopology(List.of(SELF, NodeId.nodeId("n2").unwrap(), NodeId.nodeId("n3").unwrap()));
        combiner.deactivate();
        combiner = adapter.combiner();
        combiner.activate();
        commit(new KVCommand.Put<>(OPERATOR, requirement(7)));
        combineNow();

        assertThat(committedTargetCount()).as("the core count is not a placeable-capacity stand-in").isEqualTo(Option.some(7));
    }

    /// v2713 item 1: a topology change re-runs the combiner for every slice holding a requirement.
    @Test
    void topologyChange_reCombinesEverySliceHoldingARequirement() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(6)));
        cluster.awaitQuiet();
        assertThat(cluster.submitted()).as("a commit without a notification triggers nothing").isZero();

        combiner.onTopologyChange();
        cluster.awaitHeld(1);
        cluster.commitAllAsTheyArrive();

        assertThat(committedTargetCount()).isEqualTo(Option.some(6));
    }

    /// v2713 item 7 (owner ruling): while an A/B test runs its canary, the combiner never scales the variant; the test's terminal put
    /// re-runs the combination.
    @Test
    void canaryRunning_targetIsNotCombined_untilTheTestEnds() {
        commit(new KVCommand.Put<>(AB_TEST, abTest("ACTIVE")));
        commit(new KVCommand.Put<>(OPERATOR, requirement(6)));
        combiner.onRequirementPut(OPERATOR);
        cluster.awaitQuiet();

        assertThat(cluster.submitted()).as("no combination while the canary runs").isZero();
        assertThat(committedTargetCount()).isEqualTo(Option.some(2));

        commit(new KVCommand.Put<>(AB_TEST, abTest("COMPLETED")));
        combiner.onAbTestPut(V1.base());
        cluster.awaitHeld(1);
        cluster.commitAllAsTheyArrive();

        assertThat(committedTargetCount()).as("the ended test re-runs the combination").isEqualTo(Option.some(6));
    }

    /// v2713 item 3: an orphan removal whose apply fails is retried (backoff from 1 s), not only logged.
    @Test
    void orphanRemovalApplyFails_isRetried() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(6)));
        commit(new KVCommand.Remove<>(TARGET_KEY, store.get(TARGET_KEY).map(Object.class::cast)));
        combiner.onSliceTargetRemove(TARGET_KEY);
        cluster.awaitHeld(1);
        cluster.failOldest();
        cluster.awaitHeld(1, 3_000);
        cluster.commitAllAsTheyArrive();

        assertThat(store.get(OPERATOR).isEmpty()).as("the retried removal applied").isTrue();
    }

    /// v2713 item 4, probe P3: with an operator floor of 10 and REACTIVE already 9, the autoscaler's repeated scale-down decision to
    /// 9 announces nothing and writes nothing; a decision that writes REACTIVE without moving the target announces nothing either.
    @Test
    void floorHoldsTheTarget_noPhantomScalingEvents() {
        var events = new ArrayList<ScalingEvent>();
        var loop = buildControlLoop(new BlueprintChange.ScaleDown(V1, 1), events::add);
        var loopCtx = ((ControlLoop.ControlLoopAdapter) loop).ctx();
        var held = targetOf(10);

        commit(new KVCommand.Put<>(TARGET_KEY, held));
        commit(new KVCommand.Put<>(OPERATOR, requirement(10)));
        commit(new KVCommand.Put<>(REACTIVE, requirement(9)));
        loopCtx.setTopology(List.of(SELF, NodeId.nodeId("n2").unwrap(), NodeId.nodeId("n3").unwrap()));
        loop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, held), Option.none()));

        for (var cycle = 0; cycle < 3; cycle++) {
            loopCtx.runEvaluationCycle();
        }
        cluster.awaitQuiet();

        assertThat(events).as("no ScaledDown for a target the floor holds").isEmpty();
        assertThat(cluster.submitted()).as("an unchanged REACTIVE is not re-written").isZero();

        commit(new KVCommand.Put<>(REACTIVE, requirement(8)));
        loopCtx.runEvaluationCycle();
        cluster.awaitHeld(1);

        assertThat(events).as("REACTIVE 8 -> 9 under a floor of 10 moves no target").isEmpty();
    }

    /// Positive control for the test above: without the floor the same decision moves the target and is announced once.
    @Test
    void noFloor_scaleDownMovesTheTarget_isAnnounced() {
        var events = new ArrayList<ScalingEvent>();
        var loop = buildControlLoop(new BlueprintChange.ScaleDown(V1, 1), events::add);
        var loopCtx = ((ControlLoop.ControlLoopAdapter) loop).ctx();
        var held = targetOf(10);

        commit(new KVCommand.Put<>(TARGET_KEY, held));
        commit(new KVCommand.Put<>(REACTIVE, requirement(10)));
        loopCtx.setTopology(List.of(SELF, NodeId.nodeId("n2").unwrap(), NodeId.nodeId("n3").unwrap()));
        loop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, held), Option.none()));
        loopCtx.runEvaluationCycle();
        cluster.awaitHeld(1);

        assertThat(events).hasSize(1);
        assertThat(events.getFirst()).isInstanceOf(ScalingEvent.ScaledDown.class);

        cluster.commitAllAsTheyArrive();

        assertThat(requirementCount(REACTIVE)).as("a scale-down is not capped at the 3 core nodes").isEqualTo(Option.some(9));
    }

    /// The target is removed (its blueprint deleted): the combiner removes the slice's requirements with it.
    @Test
    void targetRemoved_requirementsAreRemovedWithIt() {
        commit(new KVCommand.Put<>(OPERATOR, requirement(6)));
        commit(new KVCommand.Put<>(REACTIVE, requirement(4)));
        commit(new KVCommand.Remove<>(TARGET_KEY, store.get(TARGET_KEY).map(Object.class::cast)));
        combiner.onSliceTargetRemove(TARGET_KEY);
        cluster.awaitHeld(1);
        cluster.commitAllAsTheyArrive();

        assertThat(store.get(OPERATOR).isEmpty()).isTrue();
        assertThat(store.get(REACTIVE).isEmpty()).isTrue();
    }

    /// While an operator floor holds the target above the autoscaler's decision, the autoscaler re-decides the same count every cycle;
    /// an unchanged requirement is not re-written.
    @Test
    void unchangedReactiveDecision_isNotRewritten() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);
        cluster.commitOldest();
        var submitted = cluster.submitted();

        ctx.runEvaluationCycle();
        cluster.awaitQuiet();

        assertThat(cluster.submitted()).as("no write for an unchanged REACTIVE requirement").isEqualTo(submitted);
    }

    private void assertBothSurviveAtMax() {
        assertThat(requirementCount(REACTIVE)).as("the autoscaler's requirement survives").isEqualTo(Option.some(4));
        assertThat(requirementCount(OPERATOR)).as("the operator's requirement survives").isEqualTo(Option.some(6));
        assertThat(committedTargetCount()).as("the target is the max of the requirements").isEqualTo(Option.some(6));
    }

    /// One combination of the committed state: trigger, then commit what it (and any rebuild) submits.
    private void combineNow() {
        combiner.onRequirementPut(REACTIVE);
        cluster.awaitHeld(1);
        cluster.commitAllAsTheyArrive();
    }

    private void releaseOperator() {
        SliceTargetFence.submit("release",
                                cluster::apply,
                                () -> SliceTargetFence.presentTarget(store, TARGET_KEY)
                                                      .flatMap(read -> SliceRequirements.requirementTransaction(store,
                                                                                                                read,
                                                                                                                OPERATOR,
                                                                                                                Option.none())));
    }

    private static AetherValue.AbTestValue abTest(String state) {
        return AetherValue.AbTestValue.abTestValue("t1", V1.base(), V1.version(), "{}", state, "{}", 50, 50, OWNER.asString(), 0L, 0L);
    }

    private void submitOperator(int instances) {
        SliceTargetFence.submit("operator",
                                cluster::apply,
                                () -> SliceTargetFence.presentTarget(store, TARGET_KEY)
                                                      .flatMap(read -> SliceRequirements.requirementTransaction(store,
                                                                                                                read,
                                                                                                                OPERATOR,
                                                                                                                Option.some(requirement(instances)))));
    }

    private static SliceRequirementValue requirement(int instances) {
        return SliceRequirementValue.sliceRequirementValue(instances, "test", Option.none(), 0L);
    }

    private Option<Integer> requirementCount(SliceRequirementKey key) {
        return store.getTyped(key, SliceRequirementValue.class).map(SliceRequirementValue::instances);
    }

    private Option<Integer> committedTargetCount() {
        return store.getTyped(TARGET_KEY, SliceTargetValue.class).map(SliceTargetValue::targetInstances);
    }

    private void commit(KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static SliceTargetValue targetOf(int instances) {
        return SliceTargetValue.sliceTargetValue(V1.version(), instances, 1, Option.some(OWNER), Option.some(20), Option.none(), Option.none());
    }

    private ControlLoop buildControlLoop() {
        return buildControlLoop(new BlueprintChange.ScaleUp(V1, 2), _ -> {});
    }

    private ControlLoop buildControlLoop(BlueprintChange decision, Consumer<ScalingEvent> sink) {
        ClusterController controller = _ -> Promise.success(ControlDecisions.controlDecisions(decision));

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

        return ScalingConfig.scalingConfig(WINDOW, 5_000L, 1.5, 0.5, weights).unwrap();
    }

    private static void sleep() {
        try {
            Thread.sleep(10);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Holds every submitted batch until the test commits it into the real store; the promise then resolves with the applier's results.
    private static final class HoldingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private record Held(List<KVCommand<AetherKey>> batch, Promise<List<Object>> promise) {}

        private final KVStore<AetherKey, AetherValue> store;
        private final List<Held> held = new ArrayList<>();
        private int submitted;

        HoldingClusterNode(KVStore<AetherKey, AetherValue> store) {
            this.store = store;
        }

        @Override
        @SuppressWarnings("unchecked")
        public synchronized <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            var promise = Promise.<List<Object>> promise();

            held.add(new Held(List.copyOf(batch), promise));
            submitted++;

            return (Promise<List<R>>) (Promise<?>) promise;
        }

        synchronized int submitted() {
            return submitted;
        }

        private synchronized int heldCount() {
            return held.size();
        }

        void awaitHeld(int count) {
            awaitHeld(count, 2_000);
        }

        void awaitHeld(int count, long timeoutMs) {
            var deadline = System.currentTimeMillis() + timeoutMs;

            while (heldCount() < count && System.currentTimeMillis() < deadline) {
                sleep();
            }

            assertThat(heldCount()).as("submissions held").isGreaterThanOrEqualTo(count);
        }

        void awaitQuiet() {
            var until = System.currentTimeMillis() + 300;

            while (System.currentTimeMillis() < until) {
                sleep();
            }
        }

        void commitOldest() {
            commit(true);
        }

        /// Fails the oldest held write as a consensus apply failure would.
        void failOldest() {
            Held entry;

            synchronized (this) {
                entry = held.removeFirst();
            }

            entry.promise().fail(org.pragmatica.lang.utils.Causes.cause("apply failed (test)"));
        }

        void commitNewest() {
            commit(false);
        }

        private void commit(boolean oldest) {
            Held entry;

            synchronized (this) {
                entry = oldest
                        ? held.removeFirst()
                        : held.removeLast();
            }

            entry.promise().succeed(store.<Object> process(store.createBatch(entry.batch())));
        }

        /// Commits every held write, oldest first, then each rebuild a refusal submits, until none arrives for a while.
        void commitAllAsTheyArrive() {
            var quietUntil = System.currentTimeMillis() + 300;

            while (System.currentTimeMillis() < quietUntil) {
                if (heldCount() > 0) {
                    commitOldest();
                    quietUntil = System.currentTimeMillis() + 300;
                } else {
                    sleep();
                }
            }
        }

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {throw new UnsupportedOperationException("unused");}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
    }
}
