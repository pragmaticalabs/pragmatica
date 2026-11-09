// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.DeadSet;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import java.util.function.UnaryOperator;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// NT-168 (deployment-fsm-spec draft 8.1, C17r, #2937): a DEAD verdict this node reached as a follower, whose removal input was
/// dropped while the cluster FSM was Dormant, is applied on election: `Rebuild` synthesises one removal input per id in local
/// membership's current DEAD set, and C17 runs on it unchanged (its guard and the R-4.3.0 fence).
class RebuildDeadSetTest {
    private static final long TOKEN_2 = 0x2222L;

    /// core-2 holds an ACTIVE instance of ORDERS (goal and row at gen 1), and routes for it.
    private static MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w.target(ORDERS, 1, "CORE_ONLY")
                .directive(CORE_2,
                           ActivationDirectiveValue.core())
                .instance(CORE_2,
                          ORDERS,
                          SliceGoal.ACTIVE,
                          SliceObservedState.ACTIVE,
                          gen(1),
                          TOKEN_2)
                .routes(CORE_2, ORDERS);
    }

    private static DeadSet deadCores(NodeId... cores) {
        return DeadSet.deadSet(Set.of(cores), Set.of());
    }

    /// The `NodeRemoved` inputs the Dormant FSM dropped (the set-up's own admissions are dropped too, so the input is named).
    private static long droppedDormant(MbWorld w) {
        return w.h.metricTotal("aether_deployment_fsm_inputs_total",
                               tags -> "dropped_dormant".equals(tags.get("result")) && "NodeRemoved".equals(tags.get("input")));
    }

    private static Option<DepartureState> stateOf(MbWorld w, NodeId node) {
        return w.recordOf(node)
                .map(NodeDepartureValue::state);
    }

    // NT-168 (core arm)
    // Red when: a DEAD verdict reached as a follower (the NodeRemoved dropped while Dormant) is not recorded DEPARTED by the
    // Rebuild that follows the election, i.e. it waits for C17s after B_rm.
    @Test
    void rebuild_coreDeadAsFollower_isDepartedWithinTheActivationDrain() {
        var w = world();
        var droppedBefore = droppedDormant(w);

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(droppedDormant(w) - droppedBefore).as("precondition: the follower dropped the removal input")
                                                       .isEqualTo(1L);
        w.h.dead = deadCores(CORE_2);
        w.activate();

        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.recordOf(CORE_2)
                    .flatMap(NodeDepartureValue::cause)).isEqualTo(Option.some(DepartureCause.NODE_REMOVED));
        assertThat(w.present(new SliceDesiredKey(CORE_2, ORDERS))).as("C17 removed the departed node's goal").isFalse();
    }

    // NT-168 (control)
    // Red when: a Rebuild with an empty DEAD set departs anyone (the synthesis must come from the DEAD set alone).
    @Test
    void rebuild_emptyDeadSet_synthesisesNoDeparture() {
        var w = world();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.activate();

        assertThat(w.h.submits(SubmitKind.DEPARTURE)).isEmpty();
        assertThat(w.recordOf(CORE_2)).isEqualTo(Option.none());
    }

    // NT-168 (worker arm)
    // Red when: a DEAD worker is not departed by Rebuild, or is departed as a core (cause other than WORKER_LEAVE).
    @Test
    void rebuild_workerDeadAsFollower_isDepartedAsWorkerLeave() {
        var w = MbWorld.world(CORE_1);
        var worker = node("worker-1");

        w.admit(worker, 0x4444L);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(worker,
                    Fixtures.worker("c1"));
        w.h.dead = DeadSet.deadSet(Set.of(), Set.of(worker));
        w.activate();

        assertThat(stateOf(w, worker)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.recordOf(worker)
                    .flatMap(NodeDepartureValue::cause)).isEqualTo(Option.some(DepartureCause.WORKER_LEAVE));
    }

    // NT-168 (guard arm)
    // Red when: C17r writes for an n already in `departed` (C17's guard must refuse the synthesised input).
    @Test
    void rebuild_deadNodeAlreadyDeparted_writesNothing() {
        var w = world().record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED);

        w.h.dead = deadCores(CORE_2);
        w.activate();

        assertThat(w.h.submits(SubmitKind.DEPARTURE)).isEmpty();
    }

    // NT-168 (fence arm)
    // Red when: C17r's departure of a node that the quorum re-admitted before the write commits (ADMITTED at a higher epoch)
    // is not refused by the R-4.3.0 fence, or is resubmitted against the new record.
    @Test
    void rebuild_deadLocallyButReadmittedOnTheQuorum_isRefusedByTheFence() {
        var w = world().record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null);

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.dead = deadCores(CORE_2);
        w.activate();
        var departure = w.h.submits(SubmitKind.DEPARTURE)
                           .getFirst();

        assertThat(departure.transaction()
                            .mutations()
                            .getFirst()
                            .expected()
                            .map(value -> ((NodeDepartureValue) value).epoch()))
                  .as("the synthesised departure expects the record it observed")
                  .isEqualTo(Option.some(1L));
        w.h.seed(new NodeDepartureKey(CORE_2),
                 new NodeDepartureValue(DepartureState.ADMITTED, 2L, process(TOKEN_2), Option.none(), FsmHarness.START_WALL));
        w.h.drain();
        var verdict = w.h.applyHeld(departure.correlationId());

        assertThat(verdict).isInstanceOf(Settlement.Refused.class);
        w.h.clearLog();
        w.h.settleHeld(departure, verdict);

        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("an obsolete departure is not resubmitted").isEmpty();
        assertThat(w.recordOf(CORE_2)
                    .map(record -> record.state() + "@" + record.epoch())).isEqualTo(Option.some("ADMITTED@2"));
    }

    // NT-168 (re-Rebuild arm)
    // Red when: a Rebuild delivered while Active (a fresh incarnation) does not synthesise the removals of its DEAD set.
    @Test
    void rebuildWhileActive_coreDead_isDeparted() {
        var w = world().activate();

        w.h.dead = deadCores(CORE_2);
        w.h.deliver(new ClusterInput.Rebuild(w.h.snapshot()));

        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    /// The three cores admitted, and nothing else committed: no target, no directive, no rows, no record.
    private static MbWorld bareWorld() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w;
    }

    private static Option<DepartureState> departedWithOnly(UnaryOperator<MbWorld> residue) {
        var w = residue.apply(bareWorld());

        w.h.dead = deadCores(CORE_2);
        w.activate();

        return stateOf(w, CORE_2);
    }

    // NT-169 (draft 8.1a residue guard)
    // Red when: a DEAD id whose departure was fully GC'd (no record, no directive, no desired or observed rows) gets any
    // departure write from a Rebuild, or a NodeDepartureKey exists after two successive Rebuilds.
    @Test
    void rebuild_ghostDeadIdAfterGc_writesNothingOverTwoRebuilds() {
        var w = bareWorld();

        w.h.dead = deadCores(CORE_2);
        w.activate();
        w.h.deliver(new ClusterInput.Rebuild(w.h.snapshot()));

        assertThat(w.h.submits(SubmitKind.DEPARTURE)).isEmpty();
        assertThat(w.present(new NodeDepartureKey(CORE_2))).isFalse();
    }

    // NT-169 (positive control: orphan observed row without a record)
    // Red when: a DEAD id whose only residue is one observed row is not departed by C17r.
    @Test
    void rebuild_deadWithOnlyAnObservedRow_isDeparted() {
        assertThat(departedWithOnly(w -> seeded(w,
                                                new SliceObservedKey(CORE_2, ORDERS),
                                                row(SliceObservedState.ACTIVE, 1L, gen(1), process(TOKEN_2)))))
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // NT-169 (positive control: orphan desired row without a record)
    // Red when: a DEAD id whose only residue is one desired row is not departed by C17r.
    @Test
    void rebuild_deadWithOnlyADesiredRow_isDeparted() {
        assertThat(departedWithOnly(w -> seeded(w,
                                                new SliceDesiredKey(CORE_2, ORDERS),
                                                desired(SliceGoal.ACTIVE, gen(1)))))
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // NT-169 (positive control: directive only)
    // Red when: a DEAD id whose only residue is its directive is not departed by C17r.
    @Test
    void rebuild_deadWithOnlyADirective_isDeparted() {
        assertThat(departedWithOnly(w -> w.directive(CORE_2, ActivationDirectiveValue.core())))
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // NT-169 (positive control: committed record only)
    // Red when: a DEAD id whose only residue is a committed ADMITTED record is not departed by C17r.
    @Test
    void rebuild_deadWithOnlyAnAdmittedRecord_isDeparted() {
        assertThat(departedWithOnly(w -> w.record(CORE_2, DepartureState.ADMITTED, 1L, TOKEN_2, null)))
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    private static MbWorld seeded(MbWorld w, AetherKey key, AetherValue value) {
        w.h.seedQuietly(key, value);

        return w;
    }
}
