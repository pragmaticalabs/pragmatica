// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
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

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;


/// The leader-assigned admission epoch (spec MQ-4, R-4.3.0, J0 to J3) and the record's lifetime (R-4.3.5 narrowed): NT-88 (J3 epoch arm),
/// NT-124 (second cycle, leader change), NT-106 (record GC and the spot-worker exit), and the P3-Q7 partition-heal scenario of
/// `r-depfsm3-P3Q7ProbeTest`. Every admission is written by the leader against the committed record, never from membership.
class AdmissionEpochTest {
    private static final long TOKEN_2 = 0x2222L;
    private static final long TEN_MINUTES_MS = 600_000L;
    private static final NodeId W1 = node("worker-1");

    private MbWorld world() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);

        return w;
    }

    private static Option<NodeDepartureValue> written(ClusterEffect.Submit submit) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .filter(m -> m.key() instanceof NodeDepartureKey)
                     .findFirst()
                     .flatMap(m -> m.replacement()
                                    .stream()
                                    .findFirst())
                     .map(NodeDepartureValue.class::cast)
                     .map(Option::some)
                     .orElse(Option.none());
    }

    private static Option<Long> epochOf(MbWorld w, NodeId node) {
        return w.recordOf(node)
                .map(NodeDepartureValue::epoch);
    }

    private static Option<DepartureState> stateOf(MbWorld w, NodeId node) {
        return w.recordOf(node)
                .map(NodeDepartureValue::state);
    }

    // -- NT-88 J3, epoch arm ---------------------------------------------------------------------------------------
    // Red when: the ADMITTED written by J3 is not exactly the DEPARTED value's epoch + 1 (a core), or it expects anything but that DEPARTED value.
    @Test
    void j3_core_writesTheDepartedEpochPlusOne_expectingTheDepartedValue() {
        var w = world();

        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 7L, TOKEN_2, DepartureCause.NODE_REMOVED)
         .activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        var admission = w.h.submits(SubmitKind.ADMISSION);

        assertThat(admission).hasSize(1);
        assertThat(written(admission.getFirst()).map(NodeDepartureValue::state)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(written(admission.getFirst()).map(NodeDepartureValue::epoch)).as("DEPARTED(7) + 1")
                  .isEqualTo(Option.some(8L));
        assertThat(admission.getFirst().transaction().mutations().getFirst().expected().map(v -> ((NodeDepartureValue) v).epoch()))
                  .as("expects the DEPARTED value it read")
                  .isEqualTo(Option.some(7L));
        assertThat(epochOf(w, CORE_2)).isEqualTo(Option.some(8L));
        assertThat(w.counts(CORE_2)).isTrue();
    }

    // Red when: the ADMITTED written by J3 for a worker is not exactly the DEPARTED value's epoch + 1.
    @Test
    void j3_worker_writesTheDepartedEpochPlusOne() {
        var w = world();

        w.admit(W1, 0xBBBBL);
        w.directive(W1, Fixtures.worker("east-w-0"))
         .record(W1, DepartureState.DEPARTED, 4L, 0xBBBBL, DepartureCause.WORKER_LEAVE)
         .activate();
        w.h.clearLog();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admissionOf(W1)));

        assertThat(epochOf(w, W1)).as("DEPARTED(4) + 1").isEqualTo(Option.some(5L));
    }

    // Red when: J2 (a STALE record) writes anything but the DEPARTED value's epoch + 1.
    @Test
    void j2_staleRecord_isReplacedByTheNextEpoch() {
        var w = world();

        w.admit(W1, 0xBBBBL);
        w.directive(W1, Fixtures.worker("east-w-0"))
         .record(W1, DepartureState.DEPARTED, 0L, 0xBBBBL, DepartureCause.STALE)
         .activate();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admissionOf(W1)));

        assertThat(epochOf(w, W1)).isEqualTo(Option.some(1L));
    }

    // Red when: a first admission (no record of any epoch) is not ADMITTED(1), or the departure of a node with no record names an epoch but 0.
    @Test
    void firstDeparture_namesEpochZero_andTheFirstAdmissionIsEpochOne() {
        var w = world().activate();

        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(epochOf(w, CORE_2)).as("no record: epochOf = 0, a departure keeps it").isEqualTo(Option.some(0L));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(epochOf(w, CORE_2)).isEqualTo(Option.some(1L));
        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // -- the P3-Q7 probe (r-depfsm3-P3Q7ProbeTest), turned green --------------------------------------------------------
    // Red when: a same-process heal after NodeRemoved is not re-admitted within ten minutes, falls into J9 (join-unmatched), or the node is not counted again.
    private static void heal(long token) {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, token == 0 ? 0 : 0x1111L);
        w.admit(CORE_2, token);
        w.admit(CORE_3, token == 0 ? 0 : 0x3333L);
        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();

        w.refreshSeams(s -> s.withCountedCores(Set.of(CORE_1, CORE_3)).withReady(Set.of(CORE_1, CORE_3)));
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(stateOf(w, CORE_2)).as("control: C17 committed DEPARTED").isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(epochOf(w, CORE_2)).as("control: the departure epoch").isEqualTo(Option.some(0L));

        w.refreshSeams(s -> s.withCountedCores(Set.of(CORE_1, CORE_2, CORE_3)).withReady(Set.of(CORE_1, CORE_2, CORE_3)));
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        for (long t = 0; t < TEN_MINUTES_MS; t += w.h.settings.timing().tick()) {
            w.h.advance(w.h.settings.timing().tick() + 1);
        }

        assertThat(stateOf(w, CORE_2)).as("healed node re-admitted within 10 min").isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(epochOf(w, CORE_2)).isEqualTo(Option.some(1L));
        assertThat(w.counts(CORE_2)).as("healed node counts again").isTrue();
        assertThat(w.h.warns("join-unmatched")).as("never J9").isEmpty();
        assertThat(w.h.submits(SubmitKind.ADMISSION)).as("one admission write, no retry loop").hasSize(1);
    }

    @Test
    void probe_sameProcessHeal_tokensUnknown_isReadmitted() {
        heal(0L);
    }

    @Test
    void probe_sameProcessHeal_tokensKnown_isReadmitted() {
        heal(TOKEN_2);
    }

    // -- NT-124 ---------------------------------------------------------------------------------------------------------
    // Red when: the second cycle (DEPARTED(1) expecting ADMITTED(1), then a second heal) does not take the record to ADMITTED(2).
    @Test
    void secondCycle_departureKeepsTheEpoch_andTheSecondHealIsEpochTwo() {
        var w = world();

        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(epochOf(w, CORE_2)).as("first heal").isEqualTo(Option.some(1L));
        w.h.clearLog();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        var second = w.h.submits(SubmitKind.DEPARTURE).getFirst();

        assertThat(written(second).map(NodeDepartureValue::epoch)).as("a departure keeps the epoch of the admission it ends")
                  .isEqualTo(Option.some(1L));
        assertThat(second.transaction().mutations().getFirst().expected().map(v -> ((NodeDepartureValue) v).state()))
                  .as("expects ADMITTED(1)")
                  .isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(w.counts(CORE_2)).isFalse();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(stateOf(w, CORE_2)).isEqualTo(Option.some(DepartureState.ADMITTED));
        assertThat(epochOf(w, CORE_2)).as("second heal").isEqualTo(Option.some(2L));
        assertThat(w.counts(CORE_2)).isTrue();
    }

    // Red when: a new leader rebuilt from a committed DEPARTED(3) does not take the record to ADMITTED(4) on the heal: the epoch must
    // come from the committed record, not from any in-memory counter.
    @Test
    void leaderChange_healFromCommittedDepartedThree_isEpochFour() {
        var w = world();

        w.directive(CORE_2, ActivationDirectiveValue.core())
         .record(CORE_2, DepartureState.DEPARTED, 3L, TOKEN_2, DepartureCause.NODE_REMOVED)
         .activate();
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));

        assertThat(epochOf(w, CORE_2)).isEqualTo(Option.some(4L));
    }

    // Red when: a leader that is deposed and elected again (a fresh Rebuild, a fresh model) keeps an epoch of its own: the next admission
    // must read the committed one.
    @Test
    void leaderChange_twice_theEpochIsAlwaysTheCommittedOnePlusOne() {
        var w = world();

        w.directive(CORE_2, ActivationDirectiveValue.core()).activate();
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        w.h.deliver(new ClusterInput.Deactivate());
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        w.h.deliver(new ClusterInput.Activate());
        w.h.deliver(new ClusterInput.NodeRemoved(CORE_2));
        assertThat(epochOf(w, CORE_2)).as("departed again by the new incarnation: the committed ADMITTED(1) epoch is kept")
                  .isEqualTo(Option.some(1L));
        w.h.deliver(new ClusterInput.NodeJoined(CORE_2, w.admissionOf(CORE_2)));
        assertThat(epochOf(w, CORE_2)).isEqualTo(Option.some(2L));
    }

    // -- NT-106 ---------------------------------------------------------------------------------------------------------
    // Red when: any record is removed other than by R-4.3.3: a departed or admitted record with no directive and no rows, member or not, is kept.
    @Test
    void recordGc_noRecordIsRemoved_whateverItsResidueOrMembership() {
        var w = MbWorld.world(CORE_1, CORE_3);
        var seedDeparted = node("seed-departed");
        var seedAdmitted = node("seed-admitted");
        var strayDeparted = node("stray-departed");

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_3, 0x3333L);
        w.admit(seedAdmitted, 0x5555L);
        w.admit(seedDeparted, 0x4444L);
        w.record(seedDeparted, DepartureState.DEPARTED, 2L, 0x4444L, DepartureCause.NODE_REMOVED)
         .record(seedAdmitted, DepartureState.ADMITTED, 3L, 0x5555L, null)
         .record(strayDeparted, DepartureState.DEPARTED, 1L, 0x6666L, DepartureCause.STALE)
         .record(CORE_3, DepartureState.ADMITTED, 1L, 0x3333L, null)
         .activate();
        w.h.advance(w.h.settings.timing().tick() * 4);

        for (var node : Set.of(seedDeparted, seedAdmitted, strayDeparted, CORE_3)) {
            assertThat(w.recordOf(node).isPresent()).as("the record of " + node.id() + " is kept").isTrue();
        }

        assertThat(w.h.submits(SubmitKind.GC)).as("nothing is collected").isEmpty();
        assertThat(epochOf(w, seedDeparted)).as("and no record is rewritten without an admission").isEqualTo(Option.some(2L));
    }

    private MbWorld spotWorker() {
        var w = MbWorld.world(CORE_1);

        w.admit(W1, 0xBBBBL);
        w.community("east-w-0", 5, CommunityState.ACTIVE)
         .directive(W1, Fixtures.worker("east-w-0"))
         .target(ORDERS, 1, "WORKERS_ONLY")
         .instance(W1, ORDERS, SliceGoal.ACTIVE, SliceObservedState.ACTIVE, gen(1), 0xBBBBL)
         .activate();
        w.h.deliver(new ClusterInput.WorkerLeave(W1));

        return w;
    }

    // NT-106 spot-worker and community-retirement exit arm.
    // Red when: the record of a churned spot worker persists after its termination-confirmed directive removal (R-4.3.3), or the removal
    // does not take its rows with it in the same transaction.
    @Test
    void spotExit_theRecordGoesWithTheDirective_inOneTransaction() {
        var w = spotWorker();

        assertThat(stateOf(w, W1)).as("precondition: departed, record kept").isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.clearLog();
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();

        assertThat(w.recordOf(W1).isEmpty()).as("the record is removed through R-4.3.3").isTrue();
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).hasSize(1);
    }

    // Red when: an R-4.3.3 refused because a row moved is dropped, so the record of the terminated id persists (NT-106 spot-exit arm).
    @Test
    void spotExit_refusedBecauseARowMoved_isDerivedAgainAtOnce() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL).getFirst();

        w.h.held.clear();
        new NodeSim(w.h, W1, process(0xBBBBL)).writes(ORDERS, SliceObservedState.DEACTIVATING, gen(1));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.settleHeld(removal, w.h.refused(removal));

        assertThat(w.recordOf(W1).isEmpty()).as("derived again from the mirror, at the moved row's value").isTrue();
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isFalse();
    }

    // Red when: an R-4.3.3 submission that was lost (#2143) is never derived again, so the record of the terminated id persists.
    @Test
    void spotExit_lostSubmission_isDerivedAgainAfterTheExpiry() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        assertThat(w.recordOf(W1).isPresent()).as("precondition: the lost submission left the record").isTrue();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(w.h.settings.submissionExpiry() + w.h.settings.timing().tick());

        assertThat(w.recordOf(W1).isEmpty()).as("expired, parked and derived again by the next reconcile").isTrue();
    }

    // Red when: an R-4.3.3 submission that FAILED (a settlement failure, not a loss or a refusal) is never derived again, so the record of the
    // terminated id persists (spec R-4.3.3: "a lost or failed submission is parked"; NT-106). v-2190: V-ob-failed-noparkDR survived without it.
    @Test
    void spotExit_failedSubmission_isParkedAndDerivedAgain() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        assertThat(w.recordOf(W1).isPresent()).as("precondition: the failed submission left the record").isTrue();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).as("precondition: one R-4.3.3 was submitted").hasSize(1);
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(w.h.settings.timing().tick());

        assertThat(w.recordOf(W1).isEmpty()).as("parked on the failure and derived again by a later reconcile").isTrue();
    }

    // Red when: an R-4.3.3 that is derived again removes the record of a node that was re-admitted meanwhile (the record moved to ADMITTED).
    @Test
    void spotExit_derivedAgain_neverRemovesAReadmittedRecord() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.seed(new NodeDepartureKey(W1),
                 new NodeDepartureValue(DepartureState.ADMITTED, 1L, process(0xBBBBL), Option.none(), w.h.wallMs));
        w.h.seed(new ActivationDirectiveKey(W1), Fixtures.worker("east-w-0"));
        w.h.advance(w.h.settings.submissionExpiry() + w.h.settings.timing().tick());

        assertThat(stateOf(w, W1)).as("the record moved: the stale removal stops").isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // Red when: an R-4.3.3 that is derived again removes the record of a node that was re-admitted meanwhile without a directive (the record moved to ADMITTED).
    @Test
    void spotExit_derivedAgain_stopsWhenTheRecordMoved() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.seed(new NodeDepartureKey(W1),
                 new NodeDepartureValue(DepartureState.ADMITTED, 1L, process(0xBBBBL), Option.none(), w.h.wallMs));
        w.h.advance(w.h.settings.submissionExpiry() + w.h.settings.timing().tick());

        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.ADMITTED));
    }

    // Red when: an R-4.3.3 that is derived again removes the record although the directive is back (the id was minted a new directive).
    @Test
    void spotExit_derivedAgain_stopsWhenTheDirectiveIsBack() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.seed(new ActivationDirectiveKey(W1), Fixtures.worker("east-w-0"));
        w.h.advance(w.h.settings.submissionExpiry() + w.h.settings.timing().tick());

        assertThat(stateOf(w, W1)).isEqualTo(Option.some(DepartureState.DEPARTED));
    }

    // Red when: an R-4.3.3 refused more than the bound is dropped instead of being parked for the next reconcile, so the record of the terminated id persists.
    @Test
    void spotExit_pastTheBound_isParkedForTheNextReconcile() {
        var w = spotWorker();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        for (var round = 0; round < 8; round++) {
            var open = List.copyOf(w.h.held.values());

            w.h.held.clear();
            open.forEach(submit -> w.h.settleHeld(submit, w.h.refused(submit)));
        }

        assertThat(w.recordOf(W1).isPresent()).as("precondition: refused past the bound, still there").isTrue();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advance(w.h.settings.timing().tick() + 1);
        assertThat(w.recordOf(W1).isEmpty()).as("the next reconcile derives it again").isTrue();
    }

    // Red when: J2 (a STALE record) needs a directive: a worker wrongly recorded departed whose directive is gone is deferred instead of replaced.
    @Test
    void j2_staleRecordWithoutADirective_isStillReplaced() {
        var w = world();

        w.admit(W1, 0xBBBBL);
        w.record(W1, DepartureState.DEPARTED, 2L, 0xBBBBL, DepartureCause.STALE).activate();
        w.h.deliver(new ClusterInput.WorkerJoin(W1, w.admissionOf(W1)));

        assertThat(epochOf(w, W1)).isEqualTo(Option.some(3L));
    }

    // Red when: an R-4.3.3 decided while the FSM is suspended (the committed leader names another node) is dropped with the rest of the suspended
    // flush instead of parked, so the record of the terminated id is kept after the resume.
    @Test
    void spotExit_whileSuspended_isParkedAndReplayedOnTheResume() {
        var w = spotWorker();

        w.h.commitLeader(CORE_3, 50L);
        w.h.drain();
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        assertThat(w.recordOf(W1).isPresent()).as("nothing is submitted while suspended").isTrue();
        w.h.commitLeader(FsmHarness.SELF, 51L);
        w.h.drain();

        assertThat(w.recordOf(W1).isEmpty()).as("replayed by the reconcile the resume queues").isTrue();
        assertThat(w.present(new SliceObservedKey(W1, ORDERS))).isFalse();
    }

    // Red when: a parked R-4.3.3 is replayed without its guard: a node that was re-admitted (its directive back) during the suspension loses its record.
    @Test
    void spotExit_parkedAcrossASuspension_leavesAReadmittedNodeAlone() {
        var w = spotWorker();

        w.h.commitLeader(CORE_3, 50L);
        w.h.drain();
        w.h.seedRemove(new ActivationDirectiveKey(W1));
        w.h.drain();
        w.h.seed(new ActivationDirectiveKey(W1), Fixtures.worker("east-w-0"));
        w.h.drain();
        w.h.commitLeader(FsmHarness.SELF, 51L);
        w.h.drain();

        assertThat(stateOf(w, W1)).as("the directive is back: the record stays").isEqualTo(Option.some(DepartureState.DEPARTED));
    }
}
