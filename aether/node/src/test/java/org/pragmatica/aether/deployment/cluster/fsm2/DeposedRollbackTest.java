// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Set;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.AppBlueprintKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DeploymentOutcomeKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.AppBlueprintValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.LcSupport.*;
import static org.assertj.core.api.Assertions.assertThat;

/// CF-1 of the FSM replacement plan (#2759; legacy #2614 G2 P0 and #2592 / #2593), the rollback scenarios ported to fsm2.
///
/// **Every leader deployment write** commits only if the committed `LeaderValue` at the commit position equals the witness the
/// writer read, and a node whose committed leader record names another node, or whose term ended, submits nothing. Mechanism:
/// every `Outbox` transaction is a `LeaderTransaction` (the applier refuses a stale leader witness), and the FSM submits only while
/// the committed leader names it (R-5.7.1 (a)). The legacy `onAbandoned` (the abandoned rollback releases its bookkeeping) is the
/// deposed arm of R-5.7.2: the refused rollback is dropped, not resubmitted, and is no longer in flight.
class DeposedRollbackTest {
    private static final String APP = "org.example:app:1.0.0";
    private static final AppBlueprintValue FIRST = blueprint(APP, false, "attempt-1", slice(ORDERS, 1, 1, Set.of()));
    private static final AppBlueprintValue SECOND = blueprint(APP, false, "attempt-2", slice(ORDERS_V2, 1, 1, Set.of()));

    /// FIRST deployed and settled, SECOND published over it with FIRST as its previous value, the node loading SECOND.
    private static NodeSim republished(FsmHarness h) {
        var node = node(h, CORE_1, 11);

        publish(h, FIRST);
        node.follows(ORDERS);
        h.drain();
        node.follows(ORDERS);
        h.drain();
        publish(h, SECOND, Option.some(FIRST));
        node.writes(ORDERS_V2, SliceObservedState.LOADING);
        h.drain();

        return node;
    }

    private static Option<AppBlueprintValue> committedBlueprint(FsmHarness h) {
        return h.store.get(new AppBlueprintKey(blueprintId(APP)))
                      .filter(AppBlueprintValue.class::isInstance)
                      .map(AppBlueprintValue.class::cast);
    }

    private static Option<DeploymentOutcomeStatus> outcomeStatus(FsmHarness h) {
        return h.store.get(new DeploymentOutcomeKey(blueprintId(APP)))
                      .filter(DeploymentOutcomeValue.class::isInstance)
                      .map(DeploymentOutcomeValue.class::cast)
                      .map(DeploymentOutcomeValue::status);
    }

    private static void assertNothingRolledBack(FsmHarness h) {
        assertThat(committedBlueprint(h)).as("the republish is left committed").isEqualTo(Option.some(SECOND));
        assertThat(outcomeStatus(h)).as("the outcome is unchanged").isEqualTo(Option.some(DeploymentOutcomeStatus.IN_PROGRESS));
    }

    // CF-1, control: the scenario reaches a rollback while this node leads
    // Red when: the fixture no longer drives a fatal failure into a rollback, so the deposed arms below would pass vacuously.
    @Test
    void control_aFatalFailureWhileLeading_rollsBack() {
        var h = active();

        republished(h).fails(ORDERS_V2, true);
        h.drain();

        assertThat(h.submits(SubmitKind.ROLLBACK)).hasSize(1);
        assertThat(committedBlueprint(h).map(value -> value.blueprint()
                                                           .loadOrder())).as("the previous blueprint is restored")
                  .isEqualTo(Option.some(FIRST.blueprint()
                                              .loadOrder()));
    }

    // CF-1, #2593 committedLeaderRecordNamesAnotherNode_noRollbackIsSubmitted
    // Red when: a node whose committed leader record names another node still submits the rollback.
    @Test
    void committedLeaderRecordNamesAnotherNode_noRollbackIsSubmitted() {
        var h = active();
        var node = republished(h);

        h.commitLeader(CORE_2, 2L);
        h.drain();
        node.fails(ORDERS_V2, true);
        h.drain();
        h.advance(60_000L);

        assertThat(h.submits(SubmitKind.ROLLBACK)).as("no rollback carrying another node's leader record").isEmpty();
        assertNothingRolledBack(h);
    }

    // CF-1, AttemptBoundOutcomeTest withoutACommittedLeaderRecord_theRollbackIsAbandoned_notApplied: the fsm2 analogue of "no record"
    // is the interpreter's self-check finding that the committed record does not name this node (WitnessStale)
    // Red when: the FSM submits after its self-check cleared the leader witness.
    @Test
    void withoutALeaderRecordNamingSelf_theRollbackIsNotSubmitted() {
        var h = active();
        var node = republished(h);

        h.deliver(new ClusterInput.WitnessStale());
        node.fails(ORDERS_V2, true);
        h.drain();

        assertThat(h.submits(SubmitKind.ROLLBACK)).isEmpty();
        assertNothingRolledBack(h);
    }

    // CF-1, #2593 committedLeaderRecordNamesSelfButTermHasEnded_noRollbackIsSubmitted
    // Red when: a deactivated FSM (its term ended) still submits.
    @Test
    void termHasEnded_noRollbackIsSubmitted() {
        var h = active();
        var node = republished(h);

        h.deliver(new ClusterInput.Deactivate());
        h.clearLog();
        node.fails(ORDERS_V2, true);
        h.drain();
        h.advance(60_000L);

        assertThat(h.submits()).as("an ended term submits nothing").isEmpty();
        assertNothingRolledBack(h);
    }

    // CF-1, #2593 committedLeaderRecordNamesAnotherNode_abandonedRestoreReleasesItsBookkeeping: the rollback was sent, the
    // leader changed before it committed
    // Red when: the late rollback commits under the ended term, or the refused rollback is resubmitted or stays in flight.
    @Test
    void rollbackInFlightWhenDeposed_isRefused_droppedAndNoLongerInFlight() {
        var h = active();
        var node = republished(h);

        h.policy = FsmHarness.SettlePolicy.HOLD;
        node.fails(ORDERS_V2, true);
        h.drain();
        var rollback = h.submits(SubmitKind.ROLLBACK).getFirst();

        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.commitLeader(CORE_2, 2L);
        h.drain();
        var late = h.applyTransaction(rollback);

        h.settle(rollback, late);
        h.drain();
        h.advance(60_000L);

        assertThat(late).as("the applier refuses the ended term's rollback").isInstanceOf(ClusterInput.Settlement.Refused.class);
        assertThat(h.submits(SubmitKind.ROLLBACK)).as("not resubmitted").hasSize(1);
        assertThat(h.model().submitted.values()
                                      .stream()
                                      .flatMap(submitted -> submitted.drafts()
                                                                     .stream())
                                      .noneMatch(draft -> draft.kind() == SubmitKind.ROLLBACK)).as("no longer in flight")
                  .isTrue();
        assertNothingRolledBack(h);
    }
}
