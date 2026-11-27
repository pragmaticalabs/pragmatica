// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.DeadSet;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// #3828 lane 3 (design 3.4, 6, 8): the retire side of the directive receipt. A bound id is released only by a committed transaction that
/// carries an end verdict for it, and one function builds the triple {record, directive, receipt} for every retirer: each present member
/// is removed at its exact value, each absent member is witnessed absent. Nothing writes a receipt in this lane, so every receipt here is
/// a fixture committed as the leader would. The assertions read the committed KV and the submitted transactions, never the retirement unit
/// (the unit is opaque to its callers, and a test that asks it asks the code under test). Every drive is in production order: a write
/// that the FSM settles late is held, then applied as the applier would.
class ReceiptRetirementTest {
    private static final long TOKEN = 0x4444L;
    private static final long TOKEN_2 = 0x2222L;
    private static final DirectiveReceiptValue WORKER_RECEIPT = DirectiveReceiptValue.directiveReceiptValue(ActivationDirectiveValue.WORKER);
    private static final DirectiveReceiptValue CORE_RECEIPT = DirectiveReceiptValue.directiveReceiptValue(ActivationDirectiveValue.CORE);

    private final NodeId worker = node("worker-1");
    private final DirectiveReceiptKey receiptKey = DirectiveReceiptKey.directiveReceiptKey(worker);
    private final ActivationDirectiveKey directiveKey = new ActivationDirectiveKey(worker);
    private final NodeDepartureKey recordKey = new NodeDepartureKey(worker);

    // -- set-ups ---------------------------------------------------------------------------------------------------

    /// One core, one admitted worker of an ACTIVE community, nothing else committed; not activated.
    private MbWorld bare() {
        var w = MbWorld.world(CORE_1);

        w.admit(worker, TOKEN);

        return w.community("c1", 5, CommunityState.ACTIVE);
    }

    /// A live worker with its directive and its receipt, as the directive's issue leaves them.
    private MbWorld directedWithReceipt() {
        var w = bare().directive(worker, Fixtures.worker("c1"));

        w.h.seedQuietly(receiptKey, WORKER_RECEIPT);

        return w.activate();
    }

    /// A live worker with its directive and no receipt: a directive committed before receipts existed.
    private MbWorld directedWithoutReceipt() {
        return bare().directive(worker, Fixtures.worker("c1"))
                     .activate();
    }

    /// A worker whose directive was removed and whose departure was recorded: a DEPARTED record and a receipt, no directive, no rows.
    private MbWorld departedWithReceiptOnly() {
        var w = bare();

        w.record(worker, DepartureState.DEPARTED, 1L, TOKEN, DepartureCause.WORKER_LEAVE);
        w.h.seedQuietly(receiptKey, WORKER_RECEIPT);

        return w;
    }

    private static void settle(MbWorld w) {
        w.h.drain();
        w.h.advance(w.h.settings.timing().tick() * 3);
        w.h.drain();
    }

    /// The worker leaves, its DEPARTURE commits as the applier would, and the C17b that follows is held, not applied.
    private ClusterEffect.Submit departedAndC17bHeld(MbWorld w) {
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.applyHeld(w.h.submits(SubmitKind.DEPARTURE).getFirst().correlationId());
        w.h.drain();
        settle(w);

        return w.h.submits(SubmitKind.CLEANUP).getLast();
    }

    // -- readers ---------------------------------------------------------------------------------------------------

    private static boolean removesAt(ClusterEffect.Submit submit, AetherKey key, AetherValue value) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> mutation.key().equals(key) && mutation.replacement().isEmpty() && mutation.expected().equals(Option.some(value)));
    }

    private static boolean removes(ClusterEffect.Submit submit, AetherKey key) {
        return submit.transaction()
                     .mutations()
                     .stream()
                     .anyMatch(mutation -> mutation.key().equals(key));
    }

    private NodeDepartureValue recordNow(MbWorld w) {
        assertThat(w.recordOf(worker).isPresent()).as("precondition: the worker has a committed record").isTrue();

        return w.recordOf(worker).or((NodeDepartureValue) null);
    }

    private void assertNothingOfTheIdIsLeft(MbWorld w) {
        assertThat(w.present(recordKey)).as("the record is retired").isFalse();
        assertThat(w.present(directiveKey)).as("the directive is retired").isFalse();
        assertThat(w.present(receiptKey)).as("the receipt is retired").isFalse();
    }

    /// F4: the SAME process is admitted again under the retired id and is directed again as a WORKER.
    private void assertTheSameProcessIsDirectedAgain(MbWorld w) {
        w.h.loseKinds = Set.of();
        w.h.holdKinds = Set.of();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.deliver(new ClusterInput.WorkerJoin(worker, w.admit(worker, TOKEN)));
        settle(w);

        assertThat(w.stored(directiveKey).map(value -> ((ActivationDirectiveValue) value).role()))
                  .as("the same process (TOKEN) is directed as WORKER once the id is retired")
                  .isEqualTo(Option.some(ActivationDirectiveValue.WORKER));
    }

    private List<ClusterEffect.Submit> cleanupsAfter(MbWorld w, ClusterEffect.Submit refused) {
        var all = w.h.submits(SubmitKind.CLEANUP);

        return all.subList(all.indexOf(refused) + 1, all.size());
    }

    // -- the retirement, per retirer, without and with a leader change ---------------------------------------------

    // Red when: C17b leaves the receipt behind (its members are the record and the directive only), removes a member at other than its
    // exact value, or removes the record after the directive (notifications follow mutation order, the record leaves `departed` first).
    @Test
    void c17b_retiresRecordDirectiveAndReceipt_atTheirExactValues_andTheSameProcessIsDirectedAgain() {
        var w = directedWithReceipt();
        var cleanup = departedAndC17bHeld(w);
        var record = recordNow(w);

        assertThat(removesAt(cleanup, recordKey, record)).as("the C17b removes the record at its exact value").isTrue();
        assertThat(removesAt(cleanup, directiveKey, Fixtures.worker("c1"))).as("the C17b removes the directive at its exact value").isTrue();
        assertThat(removesAt(cleanup, receiptKey, WORKER_RECEIPT)).as("the C17b removes the receipt at its exact value").isTrue();
        assertThat(cleanup.transaction().mutations().getFirst().key()).as("the record goes first").isEqualTo(recordKey);

        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        assertThat(w.h.applyHeld(cleanup.correlationId())).as("the C17b commits").isInstanceOf(Settlement.Accepted.class);
        w.h.drain();

        assertNothingOfTheIdIsLeft(w);
        assertTheSameProcessIsDirectedAgain(w);
    }

    // Red when: the successor of a deposed leader (its mirror filled by `Rebuild`, receipt included) derives a C17b that leaves the receipt
    // behind, or leaves the departed record for a sweep that never comes.
    @Test
    void c17b_afterALeaderChange_successorRetiresRecordDirectiveAndReceipt_andTheSameProcessIsDirectedAgain() {
        var w = directedWithReceipt();
        var cleanup = departedAndC17bHeld(w);

        assertThat(removesAt(cleanup, receiptKey, WORKER_RECEIPT)).as("precondition: the deposed leader's C17b carries the receipt").isTrue();
        w.h.deliver(new ClusterInput.Deactivate());
        w.h.held.clear();
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.drain();
        assertThat(w.recordOf(worker).map(NodeDepartureValue::state)).as("precondition: the lost C17b left the DEPARTED record")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.activate();
        settle(w);

        assertThat(w.h.model().receipts.get(worker)).as("the successor's mirror dropped the receipt with the committed retirement").isEqualTo(Option.none());
        assertNothingOfTheIdIsLeft(w);
        assertTheSameProcessIsDirectedAgain(w);
    }

    // Red when: R-4.3.3 (the operator removes a departed worker's directive) removes the record and leaves the receipt, or removes either at
    // other than its exact value. C17b is lost so that R-4.3.3 is the only retirer.
    @Test
    void r433_retiresRecordAndReceipt_atTheirExactValues_andTheSameProcessIsDirectedAgain() {
        var w = directedWithReceipt();

        w.h.loseKinds = Set.of(SubmitKind.CLEANUP);
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.drain();
        var record = recordNow(w);

        assertThat(w.present(directiveKey)).as("precondition: the directive stands").isTrue();
        w.h.seedRemove(directiveKey);
        w.h.drain();
        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL).getLast();

        assertThat(removesAt(removal, recordKey, record)).as("R-4.3.3 removes the record at its exact value").isTrue();
        assertThat(removesAt(removal, receiptKey, WORKER_RECEIPT)).as("R-4.3.3 removes the receipt at its exact value").isTrue();
        assertThat(removal.transaction().mutations().getFirst().key()).as("the record goes first").isEqualTo(recordKey);
        assertNothingOfTheIdIsLeft(w);
        assertTheSameProcessIsDirectedAgain(w);
    }

    // Red when: an R-4.3.3 that the deposed leader never committed (the operator's removal was observed, the record stands, the directive is
    // gone) is left for good: no remover is left, so the id is deferred or refused for ever (R3: the receipt's WORKER role is the sweep's
    // evidence). The successor's sweep must retire record and receipt.
    @Test
    void r433Lost_afterALeaderChange_successorSweepRetiresRecordAndReceipt_andTheSameProcessIsDirectedAgain() {
        var w = directedWithReceipt();

        w.h.loseKinds = Set.of(SubmitKind.CLEANUP);
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.drain();
        w.h.holdKinds = Set.of(SubmitKind.DIRECTIVE_REMOVAL);
        w.h.seedRemove(directiveKey);
        w.h.drain();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE_REMOVAL)).as("precondition: R-4.3.3 was submitted, and it is held").isNotEmpty();
        w.h.deliver(new ClusterInput.Deactivate());
        w.h.held.clear();
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.drain();
        assertThat(w.recordOf(worker).map(NodeDepartureValue::state)).as("precondition: the record stands DEPARTED")
                  .isEqualTo(Option.some(DepartureState.DEPARTED));
        assertThat(w.present(directiveKey)).as("precondition: the directive is gone").isFalse();
        w.h.loseKinds = Set.of();
        w.h.holdKinds = Set.of();
        w.h.activate();
        settle(w);

        assertNothingOfTheIdIsLeft(w);
        assertTheSameProcessIsDirectedAgain(w);
    }

    // R3 — Red when: the sweep does not retire the record of a departed worker that has a receipt and no directive (C17b removes a record only
    // together with a WORKER directive, "otherwise never"), so nothing ever releases the id.
    @Test
    void sweep_departedWorkerWithAReceiptAndNoDirective_isRetired_andTheSameProcessIsDirectedAgain() {
        var w = departedWithReceiptOnly().activate();
        var record = recordNow(w);

        settle(w);

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("a C17b is submitted").isNotEmpty();
        assertThat(w.h.submits(SubmitKind.CLEANUP).stream().anyMatch(submit -> removesAt(submit, recordKey, record) && removesAt(submit, receiptKey, WORKER_RECEIPT)))
                  .as("one C17b removes the record and the receipt, each at its exact value").isTrue();
        assertNothingOfTheIdIsLeft(w);
        assertTheSameProcessIsDirectedAgain(w);
    }

    // M8 pin (R3 widened to CORE) — Red when: the sweep retires a departed CORE on the evidence of a CORE receipt. Nothing in the FSM carries
    // a core's recorded verdict (3.4, "Who carries a recorded verdict"); a core is released by a termination. Green at the base: it pins
    // behaviour that is kept.
    @Test
    void sweep_departedCoreWithACoreReceiptAndNoDirective_isNotRetired() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED);
        var coreReceipt = DirectiveReceiptKey.directiveReceiptKey(CORE_2);

        w.h.seedQuietly(coreReceipt, CORE_RECEIPT);
        w.activate();
        settle(w);

        assertThat(w.h.submits(SubmitKind.CLEANUP)).as("no C17b touches the departed core's record").noneMatch(submit -> removes(submit, new NodeDepartureKey(CORE_2)));
        assertThat(w.present(new NodeDepartureKey(CORE_2))).as("the core's record stands").isTrue();
        assertThat(w.present(coreReceipt)).as("the core's receipt stands").isTrue();
    }

    // -- the witnesses: every absent member is witnessed absent, every present member is removed at its exact value ---

    // M5 (a witness dropped: the directive) — Red when: a C17b of an R3 unit (record and receipt, no directive) commits although a directive
    // was committed after it was derived: the transaction would retire the record under a live directive.
    @Test
    void c17bOfAnR3Unit_directiveCreatedBeforeCommit_refusesTheCleanup() {
        var w = departedWithReceiptOnly();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.activate();
        settle(w);
        var cleanups = w.h.submits(SubmitKind.CLEANUP);

        assertThat(cleanups.stream().filter(submit -> removes(submit, recordKey))).as("precondition: the sweep derived a C17b that retires the record").isNotEmpty();
        var cleanup = cleanups.stream().filter(submit -> removes(submit, recordKey)).findFirst().orElseThrow();

        w.h.seed(directiveKey, Fixtures.worker("c1"));
        w.h.drain();
        var verdict = w.h.applyHeld(cleanup.correlationId());

        assertThat(verdict).as("the directive committed after the derivation refuses the whole unit").isInstanceOf(Settlement.Refused.class);
        assertThat(w.present(recordKey)).as("the record stands").isTrue();
        assertThat(w.present(receiptKey)).as("the receipt stands").isTrue();
    }

    // M5 (a witness dropped: the directive, R-4.3.3's form) — Red when: R-4.3.3 commits although the directive it answered came back before it
    // committed (its guards are empty today), which retires the record under a live directive.
    @Test
    void r433_directiveCreatedBeforeCommit_refusesTheRemoval() {
        var w = directedWithReceipt();

        w.h.loseKinds = Set.of(SubmitKind.CLEANUP);
        w.h.deliver(new ClusterInput.WorkerLeave(worker));
        w.h.drain();
        w.h.holdKinds = Set.of(SubmitKind.DIRECTIVE_REMOVAL);
        w.h.seedRemove(directiveKey);
        w.h.drain();
        var removal = w.h.submits(SubmitKind.DIRECTIVE_REMOVAL).getLast();

        w.h.seed(directiveKey, Fixtures.worker("c1"));
        w.h.drain();
        var verdict = w.h.applyHeld(removal.correlationId());

        assertThat(verdict).as("the directive that came back refuses R-4.3.3").isInstanceOf(Settlement.Refused.class);
        assertThat(w.present(recordKey)).as("the record stands under the live directive").isTrue();
        assertThat(w.present(receiptKey)).as("the receipt stands").isTrue();
    }

    /// A worker with a directive and no receipt whose C17b is held; then a receipt is committed (the directive was issued again, or a late
    /// write landed). Returns the held C17b.
    private ClusterEffect.Submit c17bHeldThenAReceiptCommits(MbWorld w) {
        var cleanup = departedAndC17bHeld(w);

        assertThat(removes(cleanup, receiptKey)).as("precondition: the C17b was derived with no receipt").isFalse();
        assertThat(removesAt(cleanup, directiveKey, Fixtures.worker("c1"))).as("precondition: it retires the directive").isTrue();
        w.h.seed(receiptKey, WORKER_RECEIPT);
        w.h.drain();

        return cleanup;
    }

    // M6 (a witness dropped: the receipt) — Red when: a C17b derived with no receipt commits although a receipt was committed meanwhile: it
    // would retire the record and the directive and leave a receipt that nothing can release (check F5).
    @Test
    void c17b_receiptCommittedAfterDerivation_refusesTheCleanup() {
        var w = directedWithoutReceipt();
        var cleanup = c17bHeldThenAReceiptCommits(w);
        var verdict = w.h.applyHeld(cleanup.correlationId());

        assertThat(verdict).as("the receipt committed after the derivation refuses the whole unit").isInstanceOf(Settlement.Refused.class);
        assertThat(w.present(recordKey)).as("the record never leaves alone").isTrue();
        assertThat(w.present(directiveKey)).as("the directive stands").isTrue();
        assertThat(w.present(receiptKey)).as("the receipt stands").isTrue();
    }

    // M7 (the unit filtered instead of recomputed at resubmission) — Red when: the resubmission after that refusal is the old unit filtered
    // (no receipt member, and still the "receipt absent" witness), so it is refused again; or no resubmission follows. The next C17b must
    // be the unit recomputed from the mirror, which now holds the receipt.
    @Test
    void c17b_receiptCommittedAfterDerivation_nextCleanupRemovesRecordDirectiveAndReceipt_atTheirExactValues() {
        var w = directedWithoutReceipt();
        var cleanup = c17bHeldThenAReceiptCommits(w);
        var record = recordNow(w);
        var verdict = w.h.applyHeld(cleanup.correlationId());

        w.h.settleHeld(cleanup, verdict);
        var next = cleanupsAfter(w, cleanup);

        assertThat(next).as("a C17b follows the refusal").isNotEmpty();
        assertThat(removesAt(next.getFirst(), recordKey, record)).as("the next C17b removes the record at its exact value").isTrue();
        assertThat(removesAt(next.getFirst(), directiveKey, Fixtures.worker("c1"))).as("the next C17b removes the directive at its exact value").isTrue();
        assertThat(removesAt(next.getFirst(), receiptKey, WORKER_RECEIPT)).as("the next C17b removes the receipt at its exact value").isTrue();
    }

    // M4 pin (the record witness of the guarding form) — Red when: a C17b of a CORE (rows only, never the record) is no longer guarded by the
    // record it was derived from, so it removes the rows of a process that was admitted over that record meanwhile (W3'). Green at the base:
    // it pins behaviour that is kept.
    @Test
    void coreCleanup_recordMovedBeforeCommit_refusesTheCleanup() {
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3);
        var rowKey = new SliceObservedKey(CORE_2, ORDERS);

        w.admit(CORE_1, 0x1111L);
        w.admit(CORE_2, TOKEN_2);
        w.admit(CORE_3, 0x3333L);
        w.record(CORE_2, DepartureState.DEPARTED, 1L, TOKEN_2, DepartureCause.NODE_REMOVED);
        w.h.seedQuietly(rowKey, row(SliceObservedState.ACTIVE, 1L, gen(1), process(TOKEN_2)));
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.activate();
        settle(w);
        var cleanup = w.h.submits(SubmitKind.CLEANUP).getLast();

        assertThat(removes(cleanup, rowKey)).as("precondition: the C17b removes the departed core's row").isTrue();
        w.h.seed(new NodeDepartureKey(CORE_2),
                 new NodeDepartureValue(DepartureState.ADMITTED, 2L, process(TOKEN_2 + 1), Option.none(), FsmHarness.START_WALL));
        w.h.drain();
        var verdict = w.h.applyHeld(cleanup.correlationId());

        assertThat(verdict).as("the moved record refuses the C17b").isInstanceOf(Settlement.Refused.class);
        assertThat(w.present(rowKey)).as("the row of the re-admitted process stands").isTrue();
    }

    // -- G4: a refused R3 unit is resubmitted at once ------------------------------------------------------------------

    // Check G4 (S-r2: a unit witness read as the record witness must go red) — Red when: a refused C17b of an R3 unit (a receipt, no directive,
    // so a "directive absent" witness) is dropped to the next sweep instead of resubmitted at once, because the record-unchanged question reads
    // every witness of the draft against the DEPARTED record (the two never match). A count of CLEANUP submits proves nothing (check H6): the
    // cause of the refusal requests a reconcile, and a fresh sweep in the same drain adds one too. The next CLEANUP is read by content.
    @Test
    void refusedR3Unit_withAMovedRow_isResubmittedAtOnce_withoutThatRow() {
        var w = departedWithReceiptOnly();
        var rowKey = new SliceObservedKey(worker, ORDERS);

        w.h.seedQuietly(rowKey, row(SliceObservedState.DEACTIVATING, 1L, gen(1), process(TOKEN)));
        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.activate();
        settle(w);
        var record = recordNow(w);
        var cleanup = w.h.submits(SubmitKind.CLEANUP).getLast();

        assertThat(removesAt(cleanup, recordKey, record) && removesAt(cleanup, receiptKey, WORKER_RECEIPT) && removes(cleanup, rowKey))
                  .as("precondition: the C17b under test retires the record, the receipt and the row together").isTrue();
        w.h.seed(rowKey, row(SliceObservedState.ACTIVE, 2L, gen(2), process(TOKEN)));
        w.h.drain();
        var verdict = w.h.applyHeld(cleanup.correlationId());

        assertThat(verdict).as("the moved row refuses the whole C17b").isInstanceOf(Settlement.Refused.class);
        w.h.settleHeld(cleanup, verdict);
        var next = cleanupsAfter(w, cleanup);

        assertThat(next).as("a C17b follows the refusal").isNotEmpty();
        assertThat(removes(next.getFirst(), rowKey)).as("nextCleanup_omitsTheMovedRow").isFalse();
        assertThat(removesAt(next.getFirst(), recordKey, record) && removesAt(next.getFirst(), receiptKey, WORKER_RECEIPT))
                  .as("nextCleanup_removesRecordAndReceipt_atTheirExactValues").isTrue();
    }

    // -- G3: the receipt alone is residue ------------------------------------------------------------------------------

    // Check G3 (the receipt dropped from `hasResidue` must go red) — Red when: a leader change between a worker's halt and its recorded
    // departure leaves the id with a receipt and nothing else, `Rebuild` synthesises no `WorkerLeave` for it (the receipt is not residue), no
    // DEPARTED record is written, and no verdict ever releases the receipt. The next Rebuild must find no residue (no ghost loop, NT-169).
    @Test
    void leaderChangeBetweenHaltAndDeparture_receiptAloneIsResidue_andTheIdIsRetired() {
        var w = bare();

        w.h.seedQuietly(receiptKey, WORKER_RECEIPT);
        w.h.dead = DeadSet.deadSet(Set.of(), Set.of(worker));
        var snapshot = w.h.snapshot();
        var keys = snapshot.entries().stream().map(ClusterInput.KvPut::key).toList();

        assertThat(snapshot.dead().workers()).as("atRebuild_wIsDead_andHasNoRecordNoDirectiveNoRows: W is in the dead workers").contains(worker);
        assertThat(keys).as("atRebuild_wIsDead_andHasNoRecordNoDirectiveNoRows: no record, no directive").doesNotContain(recordKey, directiveKey);
        assertThat(keys.stream().filter(key -> key instanceof SliceDesiredKey desired && desired.nodeId().equals(worker)
                                               || key instanceof SliceObservedKey observed && observed.nodeId().equals(worker)))
                  .as("atRebuild_wIsDead_andHasNoRecordNoDirectiveNoRows: no desired or observed rows").isEmpty();
        assertThat(keys).as("the receipt is the only residue of W").contains(receiptKey);
        w.activate();
        settle(w);

        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("Rebuild synthesised the departure of W").isNotEmpty();
        assertNothingOfTheIdIsLeft(w);
        w.h.clearLog();
        w.h.deliver(new ClusterInput.Rebuild(w.h.snapshot()));
        settle(w);
        assertThat(w.h.submits(SubmitKind.DEPARTURE)).as("the next Rebuild finds no residue: no ghost loop").isEmpty();
        assertTheSameProcessIsDirectedAgain(w);
    }
}
