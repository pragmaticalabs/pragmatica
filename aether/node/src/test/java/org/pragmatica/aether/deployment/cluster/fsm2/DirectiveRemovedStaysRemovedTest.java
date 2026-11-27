// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.consensus.NodeId;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;

/// #3828: a directive that was COMMITTED and then REMOVED is not written back for that node's process incarnation, by any join input (the B11
/// placement re-drive is a `WorkerJoin`; the reconcile tick re-evaluates pending joins). A directive that was lost or refused, never
/// committed, is still derived again (the B11 heal), and a join that is positive evidence of another process is assigned.
///
/// The input order is production's: the join, the directive's commit and settlement, the removal, then the re-drive.
class DirectiveRemovedStaysRemovedTest {
    private static final NodeId WORKER = node("removed-stays-removed-worker");
    private static final ActivationDirectiveKey KEY = new ActivationDirectiveKey(WORKER);

    private static MbWorld world() {
        return MbWorld.world(CORE_1, CORE_2, CORE_3)
                      .seams(seams -> seams.withMemberSource(Map.of(WORKER, "default")))
                      .activate();
    }

    private static MbWorld directedThenRemoved(long token) {
        var w = world();

        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, token)));
        assertThat(w.present(KEY)).as("precondition: the join committed the directive").isTrue();
        w.h.seedRemove(KEY);
        w.h.drain();
        assertThat(w.present(KEY)).as("precondition: the directive is removed").isFalse();
        w.h.clearLog();

        return w;
    }

    /// The join carries the process the seam reports now. Built directly: the real registry refuses a second token for a bound node id, so
    /// a new incarnation reaches the model as a join input that carries it.
    private static void redrive(MbWorld w, long token) {
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, Admission.admission(Fixtures.process(token))));
    }

    private static long directiveWrites(MbWorld w) {
        return w.h.mutations(ActivationDirectiveKey.class)
                  .stream()
                  .filter(mutation -> mutation.key().equals(KEY))
                  .count();
    }

    // Red when: a NodePlacement put re-drive (WorkerJoin) after the removal writes the directive back.
    @Test
    void directiveCommittedThenRemoved_placementRedriveAssignsNothing() {
        var w = directedThenRemoved(21L);

        redrive(w, 21L);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
    }

    // Red when: a reconcile tick between the removal and the re-drive, or after it, lets the re-drive write the directive back.
    @Test
    void directiveCommittedThenRemoved_reconcileTickAroundTheRedriveAssignsNothing() {
        var w = directedThenRemoved(21L);

        w.h.advanceBySeconds(10);
        redrive(w, 21L);
        w.h.advanceBySeconds(10);

        assertThat(directiveWrites(w)).as("no directive write after the removal").isZero();
        assertThat(w.present(KEY)).as("the directive stays removed").isFalse();
    }

    // Red when: a refused write of the removed directive's draft is resubmitted after the removal (a stale derivation).
    @Test
    void directiveCommittedThenRemoved_refusedStaleDraftIsNotResubmitted() {
        var w = world();

        w.h.policy = FsmHarness.SettlePolicy.HOLD;
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));
        var stale = w.h.held.values()
                         .stream()
                         .findFirst()
                         .orElseThrow();

        w.h.held.remove(stale.correlationId());
        w.h.seed(KEY, ActivationDirectiveValue.worker("default-w-0", ""));
        w.h.drain();
        w.h.seedRemove(KEY);
        w.h.drain();
        w.h.clearLog();
        w.h.settleHeld(stale, w.h.refused(stale));

        assertThat(directiveWrites(w)).as("the stale draft is not resubmitted behind the removal").isZero();
        assertThat(w.h.held.values()
                           .stream()
                           .filter(submit -> submit.transaction()
                                                   .mutations()
                                                   .stream()
                                                   .anyMatch(mutation -> mutation.key().equals(KEY)))).as("no directive write is held for resubmission")
                                                                                                      .isEmpty();
    }

    // Green at the base and after: B11 heal. A directive write that was lost, never committed, is derived again by a re-drive.
    @Test
    void directiveLostNeverCommitted_redriveAssigns() {
        var w = world();

        w.h.policy = FsmHarness.SettlePolicy.LOSE;
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));
        assertThat(w.present(KEY)).as("precondition: the write was lost").isFalse();
        w.h.policy = FsmHarness.SettlePolicy.APPLY;
        w.h.advanceBySeconds(600);
        redrive(w, 21L);
        w.h.advanceBySeconds(10);

        assertThat(w.present(KEY)).as("the lost directive is derived again").isTrue();
    }

    // Green at the base and after: a new process incarnation of the node (positive evidence: both tokens known, unequal) is assigned.
    @Test
    void directiveRemoved_newIncarnationJoinAssigns() {
        var w = directedThenRemoved(21L);

        redrive(w, 22L);

        assertThat(w.present(KEY)).as("the new incarnation gets a directive").isTrue();
    }

    // Red when: a token learned after the removal is not adopted by the mark, so a later different token is never positive evidence.
    @Test
    void directiveRemovedWhileTokenUnknown_learnedTokenIsAdoptedThenNewIncarnationAssigns() {
        var w = directedThenRemoved(0L);

        w.h.deliver(new ClusterInput.ProcessKnown(WORKER, 21L));
        redrive(w, 21L);
        assertThat(directiveWrites(w)).as("learning the token is not a new incarnation").isZero();

        redrive(w, 22L);

        assertThat(w.present(KEY)).as("a different known token is a new incarnation").isTrue();
    }

    // Red when: `onProcessKnown` does not adopt the learned token into the mark (the join input that follows would adopt it instead).
    @Test
    void directiveRemovedWhileTokenUnknown_processKnownAdoptsTheTokenBeforeAnyJoin() {
        var w = directedThenRemoved(0L);

        w.h.deliver(new ClusterInput.ProcessKnown(WORKER, 21L));
        redrive(w, 22L);

        assertThat(w.present(KEY)).as("22 differs from the adopted 21: a new incarnation").isTrue();
    }

    // Red when: the removal is not remembered per node: another worker's removal must not block this worker.
    @Test
    void directiveRemovedOfAnotherWorker_doesNotBlockThisWorker() {
        var other = node("removed-stays-removed-other");
        var w = MbWorld.world(CORE_1, CORE_2, CORE_3)
                       .seams(seams -> seams.withMemberSource(Map.of(WORKER, "default", other, "default")))
                       .activate();

        w.h.deliver(new ClusterInput.WorkerJoin(other, w.admit(other, 31L)));
        w.h.seedRemove(new ActivationDirectiveKey(other));
        w.h.drain();
        w.h.deliver(new ClusterInput.WorkerJoin(WORKER, w.admit(WORKER, 21L)));

        assertThat(w.present(KEY)).as("this worker is assigned").isTrue();
        assertThat(w.present(new ActivationDirectiveKey(other))).as("the removed worker stays removed").isFalse();
    }
}
