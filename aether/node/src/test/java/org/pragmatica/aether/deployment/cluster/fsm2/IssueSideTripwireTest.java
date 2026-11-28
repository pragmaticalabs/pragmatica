// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput.Settlement;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3828 part B, the issue side (design section 6, the inputs that need the leader to WRITE the receipt beside the directive and to WITHHOLD a
/// directive that a standing receipt forbids). Neither exists at this head: a directive removed by the operator is written back by the next
/// join input, and no join writes a receipt. Each input is a pair:
/// - an ENABLED tripwire (`tripwire_...`) that asserts the behaviour of this head, with a failure message that names which twin to enable;
/// - the real assertion beside it, `@Disabled` until the issue side lands.
///
/// The harness is production's order: a join, the directive's commit, the operator's removal, then the re-drive. The fixtures that need a
/// standing receipt seed it beside the directive, "as the leader would have written it" (planted through `FsmHarness.seed`, the replica effect
/// writer, because the applier refuses a receipt put at this head). `DirectiveRemovedStaysRemovedTest` and `V3834aWindowTest` are not in this
/// branch (PR #3834 holds the first), so their inputs are restated here over the base harness.
///
/// Inputs of section 6 that do NOT need the issue side are not here: the retirement-only rows (a receipt seeded beside a record, retired by C17b,
/// R-4.3.3, R3 or TerminationRetirement) run today in `ReceiptRetirementTest`, `WorkerDepartureRetirementTest` and `TerminationRetirementTest`.
/// The rows that need a TerminationRetirement driver inside the FSM harness (a join under a bound id, withheld before a termination) and the
/// warning of design 4.4 (a core whose directive an operator removed) wait on the issue side's own counter and warning, which do not exist yet.
class IssueSideTripwireTest {
    private static final String ENABLE = "ENABLE the @Disabled twin `%s` and DELETE this tripwire when the issue side lands (#3828 part B, Directory.issue / mayIssue).";
    private static final long TOKEN = 0x4444L;
    private static final DirectiveReceiptValue WORKER_RECEIPT = DirectiveReceiptValue.directiveReceiptValue(ActivationDirectiveValue.WORKER);
    private static final DirectiveReceiptValue CORE_RECEIPT = DirectiveReceiptValue.directiveReceiptValue(ActivationDirectiveValue.CORE);
    private static final NodeId WORKER = node("issue-side-worker");
    private static final ActivationDirectiveKey KEY = new ActivationDirectiveKey(WORKER);
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(WORKER);

    // -- fixtures ----------------------------------------------------------------------------------------------------------------------------

    /// Three cores and one placeable worker, activated; nothing committed for the worker.
    private static MbWorld world() {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withMemberSource(Map.of(WORKER, "default")))
                      .activate();
    }

    /// The join issued the directive; the operator removed it; the log is clear.
    private static MbWorld directedThenRemoved(long token) {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, token)));
        assertThat(w.present(KEY)).as("precondition: the join committed the directive").isTrue();
        removeDirective(w);
        w.h.clearLog();

        return w;
    }

    private static void removeDirective(MbWorld w) {
        w.h.seedRemove(KEY);
        w.h.drain();
        assertThat(w.present(KEY)).as("precondition: the directive is removed").isFalse();
    }

    /// A join that carries the process the seam reports now. Built directly: the real registry refuses a second token for a bound node id, so a
    /// new process reaches the model as a join input that carries it.
    private static void redrive(MbWorld w, long token) {
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, new Admission(process(token))));
    }

    private static long directiveWrites(MbWorld w) {
        return w.h.mutations(ActivationDirectiveKey.class)
                  .stream()
                  .filter(mutation -> mutation.key().equals(KEY) && mutation.replacement().isPresent())
                  .count();
    }

    private static String enable(String twin) {
        return ENABLE.formatted(twin);
    }

    // -- the issue itself ------------------------------------------------------------------------------------------------------------------

    @Test
    void tripwire_workerJoin_writesTheDirectiveWithNoReceipt() {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));

        assertThat(w.present(KEY)).as("precondition: the join issued the directive").isTrue();
        assertThat(w.present(RECEIPT)).as(enable("workerJoin_writesTheDirectiveAndTheReceiptInOneTransaction")).isFalse();
    }

    @Disabled("#3828 part B: enable with the issue side (Directory.issue writes the receipt beside the directive)")
    @Test
    void workerJoin_writesTheDirectiveAndTheReceiptInOneTransaction() {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));

        assertThat(w.present(KEY)).as("the join issued the directive").isTrue();
        assertThat(w.stored(RECEIPT)).as("the receipt of the issue, beside the directive").isEqualTo(org.pragmatica.lang.Option.some(WORKER_RECEIPT));
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)
                    .stream()
                    .filter(submit -> submit.transaction()
                                            .mutations()
                                            .stream()
                                            .anyMatch(mutation -> mutation.key().equals(RECEIPT)))
                    .count()).as("one transaction carries both puts").isEqualTo(1L);
    }

    @Test
    void tripwire_coreJoin_writesTheDirectiveWithNoReceipt() {
        var w = world();
        var core = node("issue-side-core-4");

        w.h.deliver(new ClusterInput.NodeJoined(core, w.admit(core, TOKEN)));
        w.h.advanceBySeconds(10);

        assertThat(w.present(new ActivationDirectiveKey(core))).as("precondition: the core join issued its directive").isTrue();
        assertThat(w.present(new DirectiveReceiptKey(core))).as(enable("coreJoin_writesTheDirectiveAndTheReceiptInOneTransaction")).isFalse();
    }

    @Disabled("#3828 part B: enable with the issue side (Directory.assignCore writes the receipt beside the directive)")
    @Test
    void coreJoin_writesTheDirectiveAndTheReceiptInOneTransaction() {
        var w = world();
        var core = node("issue-side-core-4");

        w.h.deliver(new ClusterInput.NodeJoined(core, w.admit(core, TOKEN)));
        w.h.advanceBySeconds(10);

        assertThat(w.present(new ActivationDirectiveKey(core))).as("the core join issued its directive").isTrue();
        assertThat(w.stored(new DirectiveReceiptKey(core))).as("the receipt of the issue").isEqualTo(org.pragmatica.lang.Option.some(CORE_RECEIPT));
    }

    // -- the original #3828 cases ------------------------------------------------------------------------------------------------------------

    @Test
    void tripwire_placementRedriveAfterTheRemoval_writesTheDirectiveBack() {
        var w = directedThenRemoved(21L);

        redrive(w, 21L);

        assertThat(w.present(KEY)).as(enable("placementRedriveAfterTheRemoval_assignsNothing")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (mayIssue is false under a standing receipt)")
    @Test
    void placementRedriveAfterTheRemoval_assignsNothing() {
        var w = directedThenRemoved(21L);

        redrive(w, 21L);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("no directive transaction held or queued").isEmpty();
    }

    @Test
    void tripwire_reconcileTickAroundTheRedrive_writesTheDirectiveBack() {
        var w = directedThenRemoved(21L);

        w.h.advanceBySeconds(10);
        redrive(w, 21L);
        w.h.advanceBySeconds(10);

        assertThat(w.present(KEY)).as(enable("reconcileTickAroundTheRedrive_assignsNothing")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (the pending join is retried each pass and withheld each time)")
    @Test
    void reconcileTickAroundTheRedrive_assignsNothing() {
        var w = directedThenRemoved(21L);

        w.h.advanceBySeconds(10);
        redrive(w, 21L);
        w.h.advanceBySeconds(10);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
    }

    @Test
    void tripwire_joinWithoutAToken_writesTheDirectiveBack() {
        var w = directedThenRemoved(21L);

        redrive(w, 0L);

        assertThat(w.present(KEY)).as(enable("joinWithoutAToken_thenAnotherToken_endNotDirected")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (a tokenless join, the same token and another token all end not directed)")
    @Test
    void joinWithoutAToken_thenAnotherToken_endNotDirected() {
        var w = directedThenRemoved(21L);

        redrive(w, 0L);
        redrive(w, 21L);
        redrive(w, 22L);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
    }

    // -- the S2 inputs: another process under a bound id, a receipt issued without a token -----------------------------------------------------

    @Test
    void tripwire_anotherProcessUnderABoundId_isDirectedToday() {
        var w = directedThenRemoved(21L);

        redrive(w, 22L);

        assertThat(w.present(KEY)).as(enable("anotherProcessUnderABoundId_isNotDirected_untilTheIdIsRetired")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (the receipt is per node id; another process is not directed until the id is retired)")
    @Test
    void anotherProcessUnderABoundId_isNotDirected_untilTheIdIsRetired() {
        var w = directedThenRemoved(21L);

        redrive(w, 22L);
        assertThat(w.present(KEY)).as("another process under the bound id: refused by the committed receipt").isFalse();

        leaderChange(w);
        redrive(w, 23L);
        assertThat(w.present(KEY)).as("the same after a leader change: no decision reads leader-local state").isFalse();
    }

    @Test
    void tripwire_receiptIssuedWithoutAToken_laterTokenAndProcess_areDirectedToday() {
        var w = directedThenRemoved(0L);

        w.h.deliver(new ClusterInput.ProcessKnown(WORKER, 21L));
        redrive(w, 22L);

        assertThat(w.present(KEY)).as(enable("receiptIssuedWithoutAToken_laterTokenAndLaterProcess_areNotDirected")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (a receipt issued while the token was unknown still binds the id)")
    @Test
    void receiptIssuedWithoutAToken_laterTokenAndLaterProcess_areNotDirected() {
        var w = directedThenRemoved(0L);

        w.h.deliver(new ClusterInput.ProcessKnown(WORKER, 21L));
        redrive(w, 21L);
        redrive(w, 22L);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
    }

    @Test
    void tripwire_standingDirectiveHeldByAnotherProcess_thenRemoved_isDirectedAgainToday() {
        var w = standingWithReceipt();

        redrive(w, TOKEN + 1);
        removeDirective(w);
        redrive(w, TOKEN + 1);

        assertThat(w.present(KEY)).as(enable("standingDirective_heldByAnotherProcess_thenRemoved_isNotDirectedAgain")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (N1: a second arm, leader-local or committed, directs this input)")
    @Test
    void standingDirective_heldByAnotherProcess_thenRemoved_isNotDirectedAgain() {
        var w = standingWithReceipt();

        redrive(w, TOKEN + 1);
        removeDirective(w);
        w.h.clearLog();
        redrive(w, TOKEN + 1);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
    }

    // -- check F1: three arms of the window between the operator's removal and the join --------------------------------------------------------

    @Test
    void tripwire_j3Arm_admissionInFlight_removalIsWrittenBack() {
        var w = j3Arm();

        assertThat(w.present(KEY)).as(enable("j3Arm_admissionInFlight_operatorRemoval_staysRemoved")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (F1, J3 arm: the standing receipt withholds the join)")
    @Test
    void j3Arm_admissionInFlight_operatorRemoval_staysRemoved() {
        var w = j3Arm();

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("no directive transaction held or queued").isEmpty();
        assertThat(w.present(RECEIPT)).as("the receipt stands: the held C17b expects the DEPARTED record and is refused").isTrue();
    }

    @Test
    void tripwire_j2StaleArm_admissionInFlight_removalIsWrittenBack() {
        var w = j2StaleArm();

        assertThat(w.present(KEY)).as(enable("j2StaleArm_admissionInFlight_operatorRemoval_staysRemoved")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (F1, J2 STALE arm: ADMITTED accepted, the join stays pending, nothing drafted)")
    @Test
    void j2StaleArm_admissionInFlight_operatorRemoval_staysRemoved() {
        var w = j2StaleArm();

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("no directive transaction held or queued").isEmpty();
    }

    @Test
    void tripwire_departingArm_admissionInFlight_removalIsWrittenBack() {
        var w = departingArm();

        assertThat(w.present(KEY)).as(enable("departingArm_admissionInFlight_operatorRemoval_staysRemoved")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (F1, departing arm: the departure commits, the join stays pending, nothing drafted)")
    @Test
    void departingArm_admissionInFlight_operatorRemoval_staysRemoved() {
        var w = departingArm();

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("no directive transaction held or queued").isEmpty();
    }

    // -- checks F2 and F3: a leader change around the removal ---------------------------------------------------------------------------------------

    @Test
    void tripwire_leaderChangeAfterTheRemoval_redriveWritesTheDirectiveBack() {
        var w = standingWithReceipt();

        removeDirective(w);
        w.h.clearLog();
        leaderChange(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as(enable("leaderChangeAfterTheRemoval_rebuildMirrorsTheReceipt_redriveIsWithheld")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (F3: the new leader's Rebuild mirrors the receipt; the re-drive reaches assign; mayIssue is false)")
    @Test
    void leaderChangeAfterTheRemoval_rebuildMirrorsTheReceipt_redriveIsWithheld() {
        var w = standingWithReceipt();

        removeDirective(w);
        w.h.clearLog();
        leaderChange(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
        assertThat(w.h.submits(SubmitKind.DIRECTIVE)).as("no directive transaction held or queued").isEmpty();
        assertThat(w.h.model().receipts.get(WORKER).isPresent()).as("the new leader's model mirrors the receipt").isTrue();
    }

    @Test
    void tripwire_departedWorker_directiveRemovedThenLeaderChange_sameProcessRejoinIsDirectedWithNoReceiptWrite() {
        var w = departedWithReceipt();

        w.h.seedRemove(KEY);
        w.h.drain();
        leaderChange(w);
        w.h.loseKinds = Set.of();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));
        settle(w);

        assertThat(w.present(KEY)).as("precondition: the same process rejoin is directed at this head").isTrue();
        assertThat(w.present(RECEIPT)).as(enable("departedWorker_directiveRemovedThenLeaderChange_newLeadersSweepRetiresRecordAndReceipt_thenSameProcessIsIssued")
                                          + " (the sweep retired the seeded receipt; the issue writes no new one)").isFalse();
    }

    @Disabled("#3828 part B: enable with the issue side (F2: the receipt is retired with the record, then the same process is issued a NEW receipt)")
    @Test
    void departedWorker_directiveRemovedThenLeaderChange_newLeadersSweepRetiresRecordAndReceipt_thenSameProcessIsIssued() {
        var w = departedWithReceipt();

        w.h.seedRemove(KEY);
        w.h.drain();
        leaderChange(w);
        w.h.advanceBySeconds(60);
        assertThat(w.recordOf(WORKER).isPresent()).as("the sweep retired the record").isFalse();
        assertThat(w.present(RECEIPT)).as("the sweep retired the receipt with it").isFalse();

        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));
        settle(w);

        assertThat(w.present(KEY)).as("the same process is issued again").isTrue();
        assertThat(w.stored(RECEIPT)).as("with a new receipt").isEqualTo(org.pragmatica.lang.Option.some(WORKER_RECEIPT));
    }

    // -- round 3: (a) a live worker, (d) a re-admitted worker, (e) a retired id directed again ----------------------------------------------------

    @Test
    void tripwire_liveWorker_operatorRemoves_redriveWritesBack() {
        var w = standingWithReceipt();

        removeDirective(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as(enable("liveWorker_operatorRemovesTheDirective_redriveIsWithheld")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (round 3 (a): the receipt from its issue stands, asserted on the KV)")
    @Test
    void liveWorker_operatorRemovesTheDirective_redriveIsWithheld() {
        var w = standingWithReceipt();

        removeDirective(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
        assertThat(w.present(RECEIPT)).as("the receipt stands").isTrue();
    }

    @Test
    void tripwire_readmittedWorker_operatorRemoves_redriveWritesBack() {
        var w = readmittedWithReceipt();

        removeDirective(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as(enable("readmittedWorker_operatorRemovesTheDirective_redriveIsWithheld")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (round 3 (d): the receipt does not depend on the record)")
    @Test
    void readmittedWorker_operatorRemovesTheDirective_redriveIsWithheld() {
        var w = readmittedWithReceipt();

        removeDirective(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
        assertThat(w.present(RECEIPT)).as("the receipt stands").isTrue();
    }

    @Test
    void tripwire_retiredWorkerDirectedAgain_thenRemoved_redriveWritesBack() {
        var w = retiredThenDirectedAgain();

        removeDirective(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as(enable("retiredWorker_directedAgain_thenRemovedByTheOperator_isNotDirectedAgain")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (round 3 (e): the second issue wrote a new receipt)")
    @Test
    void retiredWorker_directedAgain_thenRemovedByTheOperator_isNotDirectedAgain() {
        var w = retiredThenDirectedAgain();

        assertThat(w.present(RECEIPT)).as("the second issue wrote a new receipt").isTrue();
        removeDirective(w);
        redrive(w, TOKEN);
        settle(w);

        assertThat(w.present(KEY)).as("no directive in the KV").isFalse();
    }

    // -- check F5-late-commit: an earlier draft of the directive committed after the operator's removal ---------------------------------------------

    @Test
    void tripwire_lateCommitOfAnEarlierDirectiveDraft_isAcceptedAndTheDirectiveIsBack() {
        var w = lateCommit();

        assertThat(w.present(KEY)).as(enable("lateCommitOfAnEarlierDirectiveDraft_isRefused")).isTrue();
    }

    @Disabled("#3828 part B: enable with the issue side (the draft that carries the receipt put expects the receipt absent, so the applier refuses it)")
    @Test
    void lateCommitOfAnEarlierDirectiveDraft_isRefused() {
        var w = lateCommit();

        assertThat(w.present(KEY)).as("the late draft is refused; the directive stays removed").isFalse();
    }

    // -- scenarios -----------------------------------------------------------------------------------------------------------------------------

    private static void settle(MbWorld w) {
        w.h.drain();
        w.h.advance(w.h.settings.timing().tick() * 3);
        w.h.drain();
    }

    private static void leaderChange(MbWorld w) {
        w.h.deliver(new ClusterInput.Deactivate());
        w.h.commitLeader(FsmHarness.SELF, 2L);
        w.h.drain();
        w.h.activate();
        settle(w);
    }

    /// A worker of an ACTIVE community with its directive and its receipt, as the issue leaves them; activated.
    private static MbWorld standingWithReceipt() {
        var w = MbWorld.world(CORE_1);

        w.admit(WORKER, TOKEN);
        w.community("c1", 5, CommunityState.ACTIVE).directive(WORKER, Fixtures.worker("c1"));
        w.h.seedQuietly(RECEIPT, WORKER_RECEIPT);

        return w.activate();
    }

    private static MbWorld readmittedWithReceipt() {
        var w = MbWorld.world(CORE_1);

        w.admit(WORKER, TOKEN);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(WORKER, Fixtures.worker("c1"))
         .record(WORKER, DepartureState.ADMITTED, 0L, TOKEN, null);
        w.h.seedQuietly(RECEIPT, WORKER_RECEIPT);

        return w.activate();
    }

    /// A DEPARTED worker whose record, directive and receipt stand: the C17b and R-4.3.3 submissions are lost, the operator has not acted.
    private static MbWorld departedWithReceipt() {
        var w = MbWorld.world(CORE_1);

        w.admit(WORKER, TOKEN);
        w.h.loseKinds = Set.of(SubmitKind.CLEANUP, SubmitKind.DIRECTIVE_REMOVAL);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(WORKER, Fixtures.worker("c1"))
         .record(WORKER, DepartureState.DEPARTED, 0L, TOKEN, DepartureCause.NODE_REMOVED);
        w.h.seedQuietly(RECEIPT, WORKER_RECEIPT);

        return w.activate();
    }

    /// Round 3 (e): the worker leaves, C17b retires it whole (record, directive, receipt), the same process joins and is directed again.
    private static MbWorld retiredThenDirectedAgain() {
        var w = standingWithReceipt();

        w.h.deliver(new ClusterInput.WorkerLeave(WORKER));
        w.h.drain();
        assertThat(w.present(KEY)).as("precondition: C17b retired the directive").isFalse();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));
        settle(w);
        assertThat(w.present(KEY)).as("precondition: the same process is directed again").isTrue();

        return w;
    }

    /// Commit every held submit of `kind` now, as a late commit, and report its settlement.
    private static List<String> commitHeld(MbWorld w, SubmitKind kind) {
        var verdicts = new ArrayList<String>();
        var due = w.h.held.values()
                    .stream()
                    .filter(submit -> submit.kind() == kind)
                    .toList();

        due.forEach(submit -> {
            w.h.held.remove(submit.correlationId());
            var settlement = w.h.applyTransaction(submit);

            verdicts.add(settlement.getClass().getSimpleName());
            w.h.settle(submit, settlement);
            w.h.drain();
        });

        return verdicts;
    }

    private static void releaseAndRedrive(MbWorld w) {
        w.h.holdKinds = Set.of();
        w.h.loseKinds = Set.of();
        settle(w);
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admissionOf(WORKER)));
        settle(w);
        w.h.advanceBySeconds(60);
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admissionOf(WORKER)));
        settle(w);
    }

    /// J3 arm: DEPARTED, same-process rejoin, its ADMITTED write (guarded on the directive) in flight, the operator removes the directive, then the
    /// admission settles and the re-drive runs.
    private static MbWorld j3Arm() {
        var w = standingWithReceipt();

        w.h.holdKinds = Set.of(SubmitKind.CLEANUP, SubmitKind.DIRECTIVE_REMOVAL, SubmitKind.ADMISSION);
        w.h.deliver(new ClusterInput.WorkerLeave(WORKER));
        w.h.drain();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));
        w.h.drain();
        assertThat(w.recordOf(WORKER).map(record -> record.state())).as("precondition: DEPARTED")
                                                                    .isEqualTo(org.pragmatica.lang.Option.some(DepartureState.DEPARTED));
        assertThat(w.h.held.values().stream().anyMatch(submit -> submit.kind() == SubmitKind.ADMISSION)).as("precondition: admission in flight").isTrue();
        w.h.seedRemove(KEY);
        w.h.drain();
        w.h.clearLog();
        commitHeld(w, SubmitKind.ADMISSION);
        commitHeld(w, SubmitKind.CLEANUP);
        releaseAndRedrive(w);

        return w;
    }

    /// J2 STALE arm: an inferred STALE DEPARTED record, same-process rejoin; its ADMITTED write carries no directive guard.
    private static MbWorld j2StaleArm() {
        var w = MbWorld.world(CORE_1);

        w.admit(WORKER, TOKEN);
        w.h.holdKinds = Set.of(SubmitKind.CLEANUP, SubmitKind.DIRECTIVE_REMOVAL, SubmitKind.ADMISSION);
        w.community("c1", 5, CommunityState.ACTIVE)
         .directive(WORKER, Fixtures.worker("c1"))
         .record(WORKER, DepartureState.DEPARTED, 0L, TOKEN, DepartureCause.STALE);
        w.h.seedQuietly(RECEIPT, WORKER_RECEIPT);
        w.activate();
        settle(w);
        assertThat(w.present(KEY)).as("precondition: the directive stands (C17b held)").isTrue();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));
        w.h.drain();
        assertThat(w.h.held.values().stream().anyMatch(submit -> submit.kind() == SubmitKind.ADMISSION)).as("precondition: admission in flight").isTrue();
        w.h.seedRemove(KEY);
        w.h.drain();
        w.h.clearLog();
        commitHeld(w, SubmitKind.ADMISSION);
        commitHeld(w, SubmitKind.CLEANUP);
        releaseAndRedrive(w);

        return w;
    }

    /// Departing arm: the departure write and the rejoin's admission both in flight; the departure commits, the operator removes the directive,
    /// then the admission settles.
    private static MbWorld departingArm() {
        var w = standingWithReceipt();

        w.h.holdKinds = Set.of(SubmitKind.DEPARTURE, SubmitKind.CLEANUP, SubmitKind.DIRECTIVE_REMOVAL, SubmitKind.ADMISSION);
        w.h.deliver(new ClusterInput.WorkerLeave(WORKER));
        w.h.drain();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, TOKEN)));
        w.h.drain();
        commitHeld(w, SubmitKind.DEPARTURE);
        w.h.seedRemove(KEY);
        w.h.drain();
        w.h.clearLog();
        commitHeld(w, SubmitKind.ADMISSION);
        commitHeld(w, SubmitKind.CLEANUP);
        releaseAndRedrive(w);

        return w;
    }

    /// A directive draft is settled as failed and held; the re-derived draft commits (stood in by a seed of the pair, directive and receipt, as
    /// the leader's issue writes them); the operator removes the directive; the held draft commits late. The held draft's only expectation at this
    /// head is "directive absent", which holds, so the applier accepts it.
    private static MbWorld lateCommit() {
        var w = world();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));
        var earlier = w.h.held.values()
                       .stream()
                       .filter(submit -> submit.kind() == SubmitKind.DIRECTIVE)
                       .findFirst()
                       .orElseThrow();

        w.h.held.remove(earlier.correlationId());
        w.h.settleHeld(earlier, new Settlement.Failed("injected"));
        w.h.held.clear();
        w.h.seed(KEY, ActivationDirectiveValue.worker("default-w-0", ""));
        w.h.seed(RECEIPT, WORKER_RECEIPT);
        w.h.drain();
        assertThat(w.present(KEY)).as("precondition: the re-derived commit wrote the directive").isTrue();
        w.h.seedRemove(KEY);
        w.h.drain();
        assertThat(w.present(KEY)).as("precondition: the directive is removed").isFalse();
        w.h.applyTransaction(earlier);
        w.h.drain();

        return w;
    }
}
