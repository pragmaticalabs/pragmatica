// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
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
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.ExpandedBlueprint;
import org.pragmatica.aether.slice.blueprint.ResolvedSlice;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
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

/// #2662: the autoscaler's read-modify-write of a slice target is correct whatever order consensus commits it in.
///
/// rc6 §3.1: the autoscaler now writes its `REACTIVE` requirement, never the target (the leader's combiner, not active in this
/// test, derives the target). The requirement write keeps #2662's fence: it witnesses the observed target and its owning blueprint,
/// so every race below leaves no requirement derived from a stale or orphaned target.
///
/// The autoscaler decides from the target it observed and writes the scaled value. The write is held here while a racing change
/// commits into the real `KVStore`, then the held write commits after it — the reverse of the order the two were decided in. A plain
/// put lands either way: after a blueprint delete it re-creates the target of the deleted blueprint (an orphan), and after a republish
/// it reverts the target to the version the decision was made from (the stale value wins). The fenced write is refused in both
/// orders, and its rebuild reads what committed.
class ControlLoopTargetOrderingTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:ordered-slice:1.0.0").unwrap();
    private static final Artifact V2 = Artifact.artifact("org.example:ordered-slice:2.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:ordered-app:1.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(V1.base());
    private static final AppBlueprintKey BLUEPRINT_KEY = AppBlueprintKey.appBlueprintKey(OWNER);
    private static final int WINDOW = 3;

    private KVStore<AetherKey, AetherValue> store;
    private HoldingClusterNode cluster;
    private ControlLoopContext ctx;

    @BeforeEach
    void leaderWithAnOwnedTarget() {
        store = FencedTargetWrites.fencedStore(SELF, OWNER);
        cluster = new HoldingClusterNode(store);
        // ONE value for the commit and the registration: `updatedAt` is stamped at construction, so two `targetOf` calls in different
        // milliseconds are unequal and the fenced write (which expects the registered value) would be refused for that alone.
        var initial = targetOf(V1, 2);

        commit(new KVCommand.Put<>(TARGET_KEY, initial));

        var controlLoop = buildControlLoop();

        ctx = ((ControlLoop.ControlLoopAdapter) controlLoop).ctx();
        ctx.setTopology(List.of(SELF,
                                NodeId.nodeId("n2").unwrap(),
                                NodeId.nodeId("n3").unwrap(),
                                NodeId.nodeId("n4").unwrap(),
                                NodeId.nodeId("n5").unwrap()));
        controlLoop.onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, initial), Option.none()));
    }

    /// Control of the instrument: with nothing racing, the scaling write commits as the REACTIVE requirement, and the target itself is
    /// not written by the autoscaler.
    @Test
    void applyScaling_nothingRaces_reactiveRequirementCommits() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);
        cluster.commitOldest();

        assertThat(committedReactive()).isEqualTo(Option.some(4));
        assertThat(committedTarget().map(SliceTargetValue::targetInstances)).as("the autoscaler never writes the target").isEqualTo(Option.some(2));
    }

    /// The blueprint is deleted and its target removed after the decision; the scaling write commits last. It must not re-create the target.
    @Test
    void applyScaling_deleteCommitsFirst_doesNotRecreateTheTarget() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        commit(new KVCommand.Remove<>(TARGET_KEY));
        cluster.commitAllAsTheyArrive();

        assertThat(committedTarget()).as("no target is re-created for a deleted blueprint").isEqualTo(Option.none());
        assertThat(committedReactive()).as("no requirement is written for a removed target").isEqualTo(Option.none());
    }

    /// A republish moves the target to v2 after the decision; the scaling write commits last. The v1 value it was derived from must not win,
    /// and the decision is dropped, not carried onto the moved target (#2662, CTO ruling): the next evaluation re-decides.
    @Test
    void applyScaling_republishCommitsFirst_doesNotRevertTheVersion() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Put<>(BLUEPRINT_KEY, blueprintOf(V2)));
        commit(new KVCommand.Put<>(TARGET_KEY, targetOf(V2, 2)));
        cluster.commitAllAsTheyArrive();

        var committed = committedTarget().unwrap();

        assertThat(committed.currentVersion()).as("the republished version is not reverted").isEqualTo(V2.version());
        assertThat(committed.targetInstances()).as("the stale decision is dropped, not carried onto the moved target").isEqualTo(2);
        assertThat(committedReactive()).as("the stale decision is dropped").isEqualTo(Option.none());
        assertThat(cluster.submitted()).as("one refused submission, no rebuild").isEqualTo(1);
    }

    /// Only the owning blueprint moves (the witness); the target stays equal to the observed one, so the decision is not stale: the
    /// refused write is resubmitted over the unchanged target and commits (the one rebuild that is kept).
    @Test
    void applyScaling_onlyTheBlueprintWitnessMoved_resubmitsAndCommits() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Put<>(BLUEPRINT_KEY, blueprintOf(V2)));
        cluster.commitAllAsTheyArrive();

        assertThat(committedReactive()).as("the requirement over the unchanged target commits on resubmission").isEqualTo(Option.some(4));
        assertThat(cluster.submitted()).as("one refused submission and one resubmission").isEqualTo(2);
    }

    /// An operator scale of the same version to 6 commits first; the decision made against 2 must not revert it.
    @Test
    void applyScaling_operatorScaleCommitsFirst_staleDecisionDoesNotWin() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Put<>(TARGET_KEY, targetOf(V1, 6)));
        cluster.commitAllAsTheyArrive();

        assertThat(committedTarget().map(SliceTargetValue::targetInstances)).as("the moved target stands").isEqualTo(Option.some(6));
        assertThat(committedReactive()).as("the decision made against 2 is dropped").isEqualTo(Option.none());
        assertThat(cluster.submitted()).as("one refused submission, no rebuild").isEqualTo(1);
    }

    /// A republish lowers the cap to 3 and commits first; the decision (4) was capped against the old cap and must never be written.
    @Test
    void applyScaling_republishLowersMax_decisionAboveTheNewCapIsNeverWritten() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Put<>(TARGET_KEY, targetWithMax(2, 3)));
        cluster.commitAllAsTheyArrive();

        assertThat(committedTarget().map(SliceTargetValue::targetInstances)).as("the new cap of 3 is never exceeded").isEqualTo(Option.some(2));
        assertThat(committedReactive()).as("the decision capped against the old cap is never written").isEqualTo(Option.none());
        assertThat(cluster.submitted()).as("one refused submission, no rebuild").isEqualTo(1);
    }

    /// The owner's blueprint is deleted while its target is still committed (the removal has not committed yet): the write is refused,
    /// and the rebuild refuses to write a target whose blueprint is gone.
    @Test
    void applyScaling_blueprintDeletedTargetStillCommitted_doesNotWriteTheOrphan() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        cluster.commitAllAsTheyArrive();

        assertThat(committedTarget().map(SliceTargetValue::targetInstances)).as("the orphan is not scaled").isEqualTo(Option.some(2));
        assertThat(committedReactive()).as("no requirement is written for an orphan").isEqualTo(Option.none());
        assertThat(cluster.submitted()).as("one refused submission, no rebuild written").isEqualTo(1);
    }

    /// A write that did not commit re-reads the registration from the committed target, so the next decision is made from committed state.
    @Test
    void applyScaling_notWritten_registrationIsTheCommittedTarget() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        cluster.commitAllAsTheyArrive();
        awaitRegistration(Option.some(2));

        assertThat(ctx.observedTarget(V1).map(SliceTargetValue::targetInstances)).isEqualTo(Option.some(2));
    }

    /// A write refused because the target is gone drops the registration: the autoscaler keeps no count for a target that is not
    /// committed.
    @Test
    void applyScaling_notWritten_targetGone_registrationDropped() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        commit(new KVCommand.Remove<>(TARGET_KEY));
        cluster.commitAllAsTheyArrive();
        awaitRegistration(Option.none());

        assertThat(ctx.observedTarget(V1)).isEqualTo(Option.none());
    }

    /// A write refused after the target moved to another version (and its blueprint was then deleted) does not register that version's
    /// value under the version the decision was made for.
    @Test
    void applyScaling_notWritten_targetMoved_registrationDropped() {
        ctx.runEvaluationCycle();
        cluster.awaitHeld(1);

        commit(new KVCommand.Put<>(BLUEPRINT_KEY, blueprintOf(V2)));
        commit(new KVCommand.Put<>(TARGET_KEY, targetOf(V2, 2)));
        commit(new KVCommand.Remove<>(BLUEPRINT_KEY));
        cluster.commitAllAsTheyArrive();
        awaitRegistration(Option.none());

        assertThat(ctx.observedTarget(V1)).isEqualTo(Option.none());
    }

    private void awaitRegistration(Option<Integer> instances) {
        var deadline = System.currentTimeMillis() + 2_000;

        while (!ctx.observedTarget(V1).map(SliceTargetValue::targetInstances).equals(instances) && System.currentTimeMillis() < deadline) {
            sleep();
        }
    }

    private Option<Integer> committedReactive() {
        return store.getTyped(SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.REACTIVE),
                              SliceRequirementValue.class)
                    .map(SliceRequirementValue::instances);
    }

    private Option<SliceTargetValue> committedTarget() {
        return store.get(TARGET_KEY).filter(SliceTargetValue.class::isInstance).map(SliceTargetValue.class::cast);
    }

    private void commit(KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static SliceTargetValue targetOf(Artifact slice, int instances) {
        return SliceTargetValue.sliceTargetValue(slice.version(), instances, 1, Option.some(OWNER), Option.some(8), Option.none(), Option.none());
    }

    private static SliceTargetValue targetWithMax(int instances, int max) {
        return SliceTargetValue.sliceTargetValue(V1.version(), instances, 1, Option.some(OWNER), Option.some(max), Option.none(), Option.none());
    }

    private static AppBlueprintValue blueprintOf(Artifact slice) {
        var resolved = ResolvedSlice.resolvedSlice(slice, 2, false).unwrap();

        return AppBlueprintValue.appBlueprintValue(ExpandedBlueprint.expandedBlueprint(OWNER, List.of(resolved)));
    }

    private ControlLoop buildControlLoop() {
        Consumer<ScalingEvent> sink = _ -> {};
        ClusterController controller = _ -> Promise.success(ControlDecisions.controlDecisions(new BlueprintChange.ScaleUp(V1, 2)));

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
            var deadline = System.currentTimeMillis() + 2_000;

            while (heldCount() < count && System.currentTimeMillis() < deadline) {
                sleep();
            }

            assertThat(heldCount()).as("submissions held").isGreaterThanOrEqualTo(count);
        }

        synchronized void commitOldest() {
            var entry = held.removeFirst();

            entry.promise().succeed(store.<Object> process(store.createBatch(entry.batch())));
        }

        /// Commits the held write, then each rebuild the refusal submits, until none arrives for a while.
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
