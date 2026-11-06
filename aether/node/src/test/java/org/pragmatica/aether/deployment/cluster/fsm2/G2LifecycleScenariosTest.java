// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// CF-8 (#2759), G2 P1 (slice lifecycle writes) ported to fsm2: every instance write the leader issues converges when the write
/// fails, is lost or is applied after its timeout. Mechanism: the reconcile re-derives each goal from the model (Failures.sweep,
/// Divergence, Gc), and an unknown-outcome goal is held by the in-flight record until its settlement, so a late commit is
/// observed instead of answered with a second placement.
///
/// The three activation scenarios of P1 (`activate_lostEvent_*`, `activate_failedWriteLeaderUnchanged_*`) are in
/// `ActivateReissueTest` (CF-4).
class G2LifecycleScenariosTest {
    private static final long TICK_SECONDS = Timing.DEFAULT.tick() / 1_000L;

    private static long desiredRows(FsmHarness h) {
        return h.model().desired.keys().stream().filter(pair -> pair.artifact().equals(ORDERS)).count();
    }

    // G2 P1 load_appliedThenTimedOut_noSecondInstance (with the notification control)
    // Red when: a goal write whose settlement failed (it may still commit) is answered with a second placement, so a late commit
    // leaves two instances of a one-instance target.
    @Test
    void load_appliedThenTimedOut_noSecondInstance() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        h.activate();
        var first = h.submits(SubmitKind.GOALS).getFirst();

        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.applyHeld(first.correlationId());
        h.drain();
        assertThat(desiredRows(h)).as("control: the late commit reached the FSM as a notification").isEqualTo(1L);
        h.advanceBySeconds(TICK_SECONDS * 3);

        assertThat(hosts(h, ORDERS)).as("one instance of a one-instance target").hasSize(1);
        assertThat(desiredRows(h)).isEqualTo(1L);
    }

    // G2 P1 sliceFailure_unloadFailed_unloadedByReconcile
    // Red when: the UNLOADED goal of a failed instance, once its write failed, is not derived again from the FAILED row.
    @Test
    void sliceFailure_unloadFailed_unloadedByReconcile() {
        var h = harness(CORE_1);
        var node = node(h, CORE_1, 11);

        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node.follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.fails(ORDERS, true);
        h.drain();
        h.held.clear();
        assertThat(goalOf(h, CORE_1, ORDERS)).as("control: the failed write did not commit").isNotEqualTo(Option.some(SliceGoal.UNLOADED));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 3);

        assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
    }

    // G2 P1 stuckReset_unloadFailed_reissued: a stuck instance is retracted (UNLOADED at a fresh generation) after 3 x T
    // Red when: the retraction of a stuck instance, once its write failed, is not derived again.
    @Test
    void stuckReset_unloadFailed_reissued() {
        var h = harness(CORE_1);
        var node = node(h, CORE_1, 11);
        var stuckFor = 3 * DivergenceBounds.DEFAULT.load();

        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();
        h.advance(stuckFor - 10_000L);
        assertThat(goalOf(h, CORE_1, ORDERS)).as("control: not yet retracted").isNotEqualTo(Option.some(SliceGoal.UNLOADED));
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        h.advance(20_000L);
        h.held.clear();
        assertThat(goalOf(h, CORE_1, ORDERS)).as("control: the retraction write failed").isNotEqualTo(Option.some(SliceGoal.UNLOADED));
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 3);

        assertThat(goalOf(h, CORE_1, ORDERS)).isEqualTo(Option.some(SliceGoal.UNLOADED));
    }

    // G2 P1 stuckRemove_removeFailed_reissued: the desired key of a finished unload is removed by the collect pass
    // Red when: a failed removal of a finished unload's desired key is not derived again, so the key stays forever.
    @Test
    void stuckRemove_removeFailed_reissued() {
        var h = harness(CORE_1);
        var node = node(h, CORE_1, 11);

        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node.follows(ORDERS);
        h.drain();
        h.seedRemove(new SliceTargetKey(ORDERS.base()));
        h.drain();
        assertThat(goalOf(h, CORE_1, ORDERS)).as("control: the removed target unloads the instance").isEqualTo(Option.some(SliceGoal.UNLOADED));
        node.follows(ORDERS);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        h.seedRemove(new SliceObservedKey(CORE_1, ORDERS));
        h.drain();
        h.held.clear();
        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS)).isPresent()).as("control: the removal write failed").isTrue();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        h.advanceBySeconds(TICK_SECONDS * 3);

        assertThat(h.store.get(new SliceDesiredKey(CORE_1, ORDERS))).isEqualTo(Option.none());
    }

    // G2 P1 activeEntry_firstWritesIssuedOnceLeaderValueNamesSelf, expected green: the entry writes follow the committed leader record
    // Red when: an entry write is issued twice, or carries a leader value that does not name this node and its term.
    @Test
    void activeEntry_firstWritesIssuedOnceLeaderValueNamesSelf() {
        var h = threeCores();

        seedTarget(h, ORDERS, 1, 1);
        h.activate();

        assertThat(h.submits()).as("control: the entry issued writes").isNotEmpty();
        assertThat(h.submits()).allSatisfy(submit -> assertThat(submit.transaction().leader())
                                                                                      .isEqualTo(new LeaderValue(FsmHarness.SELF, 1L)));
        assertThat(h.submits().stream().map(submit -> submit.correlationId()).distinct().count())
                  .as("each write is issued once")
                  .isEqualTo((long) h.submits().size());
        assertThat(h.submits(SubmitKind.GOALS)).as("one goal transaction for the one placement").hasSize(1);
    }
}
