// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// CF-8 (#2759), G2 P3 (membership writes: D05, D06, D16) ported to fsm2. Mechanism: fsm2 has no independent directive Remove (R-4.3.1):
/// C17 is ONE fenced transaction (the DEPARTED record with the footprint), the directive goes only on source-confirmed termination, and
/// R-4.3.3 then removes the record with the rows in one transaction. The durable record is the departure intent (G2 Q2), so a new leader
/// reads it instead of re-registering the ghost, and a same-process rejoin supersedes it. The CORE and WORKER directives are
/// re-derived from the mirror until they commit (J5, the failed or lost write is derived again).
///
/// No fsm2 analogue: the legacy "directive Remove issued by the leader" arm of `departure_directiveRemoveFailed_removed` (the leader never
/// removes a directive; the analogue is the failed R-4.3.3 that follows the confirmed removal), and the legacy M17 independence of the
/// three writes of `handleNodeRemoval` (the record and the footprint are one transaction, so they cannot diverge).
class G2MembershipScenariosTest {
    private static final NodeId W1 = node("worker-1");
    private static final long TOKEN = 0xBBBBL;

    /// A worker world in which C17b is lost for the whole test: for tests that observe a DEPARTED record and its directive standing
    /// together or that drive R-4.3.3 through a seeded directive removal.
    private static MbWorld workerWorld() {
        return workerWorldBase().c17bLost()
                                .activate();
    }

    /// A worker world with the real path: C17b commits right after DEPARTED, and retires the directive.
    private static MbWorld liveWorkerWorld() {
        return workerWorldBase().activate();
    }

    private static MbWorld workerWorldBase() {
        var w = MbWorld.world(CORE_1);

        w.admit(W1, TOKEN);
        w.community("east-w-0", 5, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker("east-w-0"))
         .target(ORDERS, 1, "WORKERS_ONLY")
         .instance(W1,
                   ORDERS,
                   SliceGoal.ACTIVE,
                   SliceObservedState.ACTIVE,
                   gen(1),
                   TOKEN);

        return w;
    }

    private static Option<DepartureState> stateOf(MbWorld w) {
        return w.recordOf(W1)
                .map(NodeDepartureValue::state);
    }

    private static boolean footprintPresent(MbWorld w) {
        return w.present(new SliceDesiredKey(W1, ORDERS));
    }

    // G2 P3 departure_footprintBatchFailed_directiveRemoved
    // Red when: a failed departure transaction is not derived again, so the footprint and the record never land and the
    // termination-confirmed directive removal leaves rows or the record of the terminated id behind.
    @Test
    void departure_footprintBatchFailed_directiveRemoved() {
        var w = workerWorld();

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        w.h.held.clear();
        assertThat(w.recordOf(W1).isPresent()).as("control: the departure write failed").isFalse();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(30_000L);
        assertThat(stateOf(w)).as("the departure is re-derived").isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        assertThat(w.present(new ActivationDirectiveKey(W1))).isFalse();
        assertThat(w.recordOf(W1).isEmpty()).as("no record of the terminated id").isTrue();
        assertThat(footprintPresent(w)).isFalse();
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isFalse();
    }

    // G2 P3 departure_directiveRemoveFailed_removed (the analogue: the R-4.3.3 that follows the confirmed directive removal)
    // Red when: a failed R-4.3.3 is not derived again, so the record and the rows of a node whose directive is already gone persist.
    @Test
    void departure_directiveRemoveFailed_removed() {
        var w = workerWorld();

        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        assertThat(stateOf(w)).as("precondition: departed").isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        w.h.held.clear();
        assertThat(w.recordOf(W1).isPresent()).as("control: the removal write failed").isTrue();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(w.h.settings.timing().tick());
        assertThat(w.recordOf(W1).isEmpty()).isTrue();
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isFalse();
        assertThat(footprintPresent(w)).isFalse();
    }

    // G2 P3 newLeader_departedWorker_notReRegistered
    // Red when: a new leader that reads a DEPARTED record whose directive is still present counts the worker again, or writes an
    // admission or a directive for it (the ghost of restoreWorkerNode).
    @Test
    void newLeader_departedWorker_notReRegistered() {
        var w = MbWorld.world(CORE_1);

        w.admit(W1, TOKEN);
        w.community("east-w-0", 5, CommunityState.ACTIVE)
         .directive(W1,
                    Fixtures.worker("east-w-0"))
         .record(W1, DepartureState.DEPARTED, 1L, TOKEN, DepartureCause.WORKER_LEAVE)
         .c17bLost()
         .activate();
        w.h.advance(120_000L);
        assertThat(w.counts(W1)).as("the departed worker is not counted").isFalse();
        assertThat(stateOf(w)).isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("no re-registration").isEmpty();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).isEmpty();
    }

    // G2 P3 departure_recordPutFailed_footprintAndDirectiveStillIssued (the analogue: record and footprint are one transaction)
    // Red when: a failed departure transaction applies part of itself (the footprint without the record), is not derived again, or
    // the re-derived departure is not followed by C17b retiring the directive (#3193) and R-4.3.3 collecting the record.
    @Test
    void departure_recordPutFailed_footprintAndDirectiveStillIssued() {
        var w = liveWorkerWorld();

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        w.h.held.clear();
        assertThat(footprintPresent(w)).as("control: nothing of the failed transaction applied").isTrue();
        assertThat(w.recordOf(W1).isPresent()).isFalse();
        assertThat(w.present(new ActivationDirectiveKey(W1))).as("control: the directive stands until C17b").isTrue();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(30_000L);
        assertThat(footprintPresent(w)).as("the footprint goes with the record").isFalse();
        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("C17b was issued after the re-derived departure").isNotEmpty();
        assertThat(w.present(new ActivationDirectiveKey(W1))).as("C17b retired the directive").isFalse();
        assertThat(w.recordOf(W1).isEmpty()).as("R-4.3.3 collected the record").isTrue();
    }

    // G2 P3 departure_sameIdRejoinsBeforeRetire_recordSuperseded_workerRegistered
    // Red when: the same process rejoining while C17b is in flight stays uncounted, keeps a DEPARTED record, or loses its directive or rows
    // to the late C17b (the record's exact-value removal must refuse it), or a later R-4.3.3 removes its rows.
    @Test
    void departure_sameIdRejoinsBeforeRetire_recordSuperseded_workerRegistered() {
        var w = liveWorkerWorld();

        w.h.holdKinds = Set.of(SubmitKind.CLEANUP);
        w.h.deliver(new ClusterInput.WorkerLeave(W1));
        assertThat(w.counts(W1)).as("precondition: departed").isFalse();
        var c17b = w.h.held.values().stream().filter(s -> s.kind() == SubmitKind.CLEANUP).findFirst();

        assertThat(c17b.isPresent()).as("control: C17b is in flight").isTrue();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admit(W1, TOKEN)));
        w.h.advance(30_000L);
        assertThat(stateOf(w)).as("the record is superseded").isEqualTo(Option.some(DepartureState.ADMITTED));
        var late = w.h.applyHeld(c17b.get().correlationId());

        assertThat(late).as("the late C17b is refused whole").isInstanceOf(ClusterInput.Settlement.Refused.class);
        w.h.holdKinds = Set.of();
        w.h.advance(30_000L);
        assertThat(stateOf(w)).as("the record stays superseded").isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.counts(W1)).as("registered again").isTrue();
        assertThat(w.present(new ActivationDirectiveKey(W1))).as("the late C17b did not take the directive").isTrue();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).isEmpty();
    }

    // G2 P3 coreJoin_directiveWriteFailed_written
    // Red when: the CORE directive of a joined non-seed core, once its write failed, is not derived again.
    @Test
    void coreJoin_directiveWriteFailed_written() {
        var w = MbWorld.worldWithSeeds(Set.of(CORE_1), CORE_1, CORE_2);

        w.activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_1, w.admit(CORE_1, 0x1111L)));
        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admit(CORE_2, 0xAAAAL)));
        w.h.held.clear();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("control: the directive write was issued").isNotEmpty();
        assertThat(w.present(new ActivationDirectiveKey(CORE_2))).as("control: and failed").isFalse();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(120_000L);
        assertThat(w.present(new ActivationDirectiveKey(CORE_2))).isTrue();
    }

    // G2 P3 workerJoin_commitLost_assigned
    // Red when: a worker's directive write whose outcome was lost (no settlement) is not derived again after the submission expires.
    @Test
    void workerJoin_commitLost_assigned() {
        var worker = node("worker-2");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withCommunityPolicies(Map.of())
                                            .withMemberSource(Map.of(worker, "east")))
                       .activate();

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, 21L)));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("control: the write was issued").isNotEmpty();
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("control: and lost").isFalse();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(w.h.settings.submissionExpiry() + 3 * w.h.settings.timing().tick());
        assertThat(w.present(new ActivationDirectiveKey(worker))).as("assigned after the expiry").isTrue();
    }
}
