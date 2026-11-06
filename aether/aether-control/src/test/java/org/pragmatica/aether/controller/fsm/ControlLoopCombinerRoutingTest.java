// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.controller.ClusterController;
import org.pragmatica.aether.controller.ClusterController.ControlDecisions;
import org.pragmatica.aether.controller.ControlLoop;
import org.pragmatica.aether.controller.ControlLoop.ControlLoopAdapter;
import org.pragmatica.aether.controller.ControllerConfig;
import org.pragmatica.aether.controller.RequirementCombiner;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.AbTestKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceRequirementKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.RequirementKind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceRequirementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipDecision;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;

/// v2713 B2 (mutants c01-c08): the control loop routes leadership and every KV event of the F1 model to its requirement combiner, and
/// wires the combiner to the loop's own clock. A spy combiner records the routing (c01-c06); a combiner built by the loop itself is
/// driven over a real store to pin the clock (c07). The capacity wiring (c08) is `UNBOUNDED_CAPACITY` since fix1 and is pinned by
/// `RequirementCombinerOrderingTest#productionCombiner_operatorAboveCoreCount_commitsTheRequirement`.
class ControlLoopCombinerRoutingTest {
    private static final NodeId SELF = NodeId.nodeId("leader").unwrap();
    private static final NodeId PEER = NodeId.nodeId("peer").unwrap();
    private static final Artifact V1 = Artifact.artifact("org.example:routed-slice:1.0.0").unwrap();
    private static final BlueprintId OWNER = BlueprintId.blueprintId("org.example:routed-app:1.0.0").unwrap();
    private static final SliceTargetKey TARGET_KEY = SliceTargetKey.sliceTargetKey(V1.base());
    private static final SliceRequirementKey REACTIVE = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.REACTIVE);
    private static final SliceRequirementKey SCHEDULED = SliceRequirementKey.sliceRequirementKey(V1.base(), RequirementKind.SCHEDULED);

    private KVStore<AetherKey, AetherValue> store;
    private ControlLoopAdapter loop;
    private final SpyCombiner spy = new SpyCombiner();

    @BeforeEach
    void loopOverAnOwnedTarget() {
        store = FencedTargetWrites.fencedStore(SELF, OWNER);
        commit(new KVCommand.Put<>(TARGET_KEY, targetOf(2)));
        loop = (ControlLoopAdapter) buildLoop();
    }

    @AfterEach
    void stopLoop() {
        loop.combiner().deactivate();
        loop.deactivate();
    }

    /// c01
    @Test
    void activate_activatesTheCombiner() {
        var spied = spiedLoop();

        spied.activate();

        assertThat(spy.events()).containsExactly("activate");
    }

    /// c02
    @Test
    void deactivate_deactivatesTheCombiner() {
        var spied = spiedLoop();

        spied.activate();
        spied.deactivate();

        assertThat(spy.events()).containsExactly("activate", "deactivate");
    }

    /// c03
    @Test
    void sliceTargetPut_reachesTheCombiner() {
        spiedLoop().onSliceTargetPut(new ValuePut<>(new KVCommand.Put<>(TARGET_KEY, targetOf(2)), Option.none()));

        assertThat(spy.events()).containsExactly("targetPut " + TARGET_KEY.artifactBase().asString());
    }

    /// c04
    @Test
    void sliceTargetRemove_reachesTheCombiner() {
        spiedLoop().onSliceTargetRemove(new ValueRemove<>(new KVCommand.Remove<>(TARGET_KEY, Option.none()), Option.none()));

        assertThat(spy.events()).containsExactly("targetRemove " + TARGET_KEY.artifactBase().asString());
    }

    /// c05
    @Test
    void requirementPut_reachesTheCombiner() {
        spiedLoop().onSliceRequirementPut(new ValuePut<>(new KVCommand.Put<>(REACTIVE, requirement(4, Option.none())), Option.none()));

        assertThat(spy.events()).containsExactly("requirementPut " + REACTIVE.asString());
    }

    /// c06
    @Test
    void requirementRemove_reachesTheCombiner() {
        spiedLoop().onSliceRequirementRemove(new ValueRemove<>(new KVCommand.Remove<>(REACTIVE, Option.none()), Option.none()));

        assertThat(spy.events()).containsExactly("requirementRemove " + REACTIVE.asString());
    }

    /// k01
    @Test
    void nodeJoined_reachesTheCombiner() {
        spiedLoop().onMembershipDecision(MembershipDecision.nodeJoined(PEER, List.of(SELF, PEER)));

        assertThat(spy.events()).containsExactly("topology");
    }

    /// k02
    @Test
    void nodeRemoved_reachesTheCombiner() {
        spiedLoop().onMembershipDecision(MembershipDecision.nodeRemoved(PEER, List.of(SELF)));

        assertThat(spy.events()).containsExactly("topology");
    }

    /// k03
    @Test
    void nodeDecommissioned_reachesTheCombiner() {
        spiedLoop().onMembershipDecision(MembershipDecision.nodeDecommissioned(PEER, List.of(SELF)));

        assertThat(spy.events()).containsExactly("topology");
    }

    /// k04: a canary that started or ended changes whether the slice is combined, so the A/B test put reaches the combiner.
    @Test
    void abTestPut_reachesTheCombinerWithTheSliceBase() {
        var test = AetherValue.AbTestValue.abTestValue("t1", V1.base(), V1.version(), "{}", "ACTIVE", "{}", 50, 50, OWNER.asString(), 0L, 0L);

        spiedLoop().onAbTestPut(new ValuePut<>(new KVCommand.Put<>(new AbTestKey("t1"), test), Option.none()));

        assertThat(spy.events()).containsExactly("abTest " + V1.base().asString());
    }

    /// c07: the loop's own combiner reads the loop's clock. A scheduled claim of 7 that expired ten seconds ago is not combined, so
    /// the target follows the autoscaler's 4; with the clock frozen at zero the lapsed claim would still count and the target would be 7.
    @Test
    void productionCombiner_usesTheLoopsClock_anExpiredRequirementIsIgnored() {
        loop.combiner().activate();
        commit(new KVCommand.Put<>(REACTIVE, requirement(4, Option.none())));
        commit(new KVCommand.Put<>(SCHEDULED, requirement(7, Option.some(System.currentTimeMillis() - 10_000))));

        loop.onSliceRequirementPut(new ValuePut<>(new KVCommand.Put<>(REACTIVE, requirement(4, Option.none())), Option.none()));

        assertThat(await(() -> committedTarget() == 4)).as("the lapsed claim is ignored, REACTIVE holds the target").isTrue();
        pause(300);
        assertThat(committedTarget()).as("the target stays at the unexpired claim").isEqualTo(4);
    }

    private ControlLoopAdapter spiedLoop() {
        return new ControlLoopAdapter(loop.ctx(), loop.fsm(), spy);
    }

    private ControlLoop buildLoop() {
        ClusterController controller = _ -> Promise.success(ControlDecisions.controlDecisions());

        return ControlLoop.controlLoop(SELF,
                                       controller,
                                       new ControlLoopContextAttributionTest.StubMetricsCollector(),
                                       Option.none(),
                                       new ImmediateClusterNode(SELF, store),
                                       store,
                                       TimeSpan.timeSpan(5_000).millis(),
                                       ControllerConfig.DEFAULT,
                                       _ -> {});
    }

    private int committedTarget() {
        return store.getTyped(TARGET_KEY, SliceTargetValue.class).map(SliceTargetValue::targetInstances).or(-1);
    }

    private void commit(KVCommand<AetherKey> command) {
        store.process(store.createBatch(List.of(command)));
    }

    private static SliceRequirementValue requirement(int instances, Option<Long> expiresAt) {
        return SliceRequirementValue.sliceRequirementValue(instances, "test", expiresAt, 0L);
    }

    private static SliceTargetValue targetOf(int instances) {
        return SliceTargetValue.sliceTargetValue(V1.version(), instances, 1, Option.some(OWNER), Option.some(20), Option.none(), Option.none());
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

    /// Records which combiner entry point the loop called and for which key.
    private static final class SpyCombiner implements RequirementCombiner {
        private final List<String> events = new CopyOnWriteArrayList<>();

        List<String> events() {
            return List.copyOf(events);
        }

        @Override public Unit activate() {return record("activate");}

        @Override public Unit deactivate() {return record("deactivate");}

        @Override public Unit onRequirementPut(SliceRequirementKey key) {return record("requirementPut " + key.asString());}

        @Override public Unit onRequirementRemove(SliceRequirementKey key) {return record("requirementRemove " + key.asString());}

        @Override public Unit onSliceTargetPut(SliceTargetKey key) {return record("targetPut " + key.artifactBase().asString());}

        @Override public Unit onSliceTargetRemove(SliceTargetKey key) {return record("targetRemove " + key.artifactBase().asString());}

        @Override public Unit onTopologyChange() {return record("topology");}

        @Override public Unit onAbTestPut(ArtifactBase base) {return record("abTest " + base.asString());}

        private Unit record(String event) {
            events.add(event);

            return Unit.unit();
        }
    }
}
