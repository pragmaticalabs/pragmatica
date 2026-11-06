// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceDesiredKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.cluster.fsm2.Fixtures.*;
import static org.pragmatica.aether.deployment.cluster.fsm2.SlFixtures.*;
import static org.assertj.core.api.Assertions.assertThat;

/// CF-4 of the FSM replacement plan (#2759; legacy #2579 / #2581), G2-P1's ACTIVATE scenarios on the pure harness.
///
/// **ACTIVATE**: a LOADED, permitted instance whose ACTIVE goal write failed or was lost is re-issued, and the goal ACTIVE commits
/// within three ticks while consensus commits and the leader is unchanged. G2 named the legacy scenarios after a lost KV event;
/// the fsm2 mirror is fed by the ordered notification stream (design D3), so the fsm2 analogues of a lost event are a lost
/// submission and a LOADED row the FSM saw while it could not submit (a leader that was not the committed one).
class ActivateReissueTest {
    private static final long THREE_TICKS_SECONDS = 3 * Timing.DEFAULT.tick() / 1_000L;

    private static FsmHarness loadedOnCore1(NodeSim node, FsmHarness h) {
        seedTarget(h, ORDERS, 1, 1);
        h.activate();
        node.writes(ORDERS, SliceObservedState.LOADING);
        h.drain();

        return h;
    }

    private static Option<SliceGoal> committedGoal(FsmHarness h) {
        return h.store.get(new SliceDesiredKey(CORE_1, ORDERS))
                      .filter(SliceDesiredValue.class::isInstance)
                      .map(SliceDesiredValue.class::cast)
                      .map(SliceDesiredValue::goal);
    }

    private static long activeGoalSubmits(FsmHarness h) {
        return h.submits(SubmitKind.GOALS)
                .stream()
                .flatMap(submit -> submit.transaction()
                                         .mutations()
                                         .stream())
                .filter(mutation -> mutation.key()
                                            .equals(new SliceDesiredKey(CORE_1, ORDERS)))
                .filter(mutation -> mutation.replacement()
                                            .filter(SliceDesiredValue.class::isInstance)
                                            .map(SliceDesiredValue.class::cast)
                                            .map(goal -> goal.goal() == SliceGoal.ACTIVE)
                                            .or(false))
                .count();
    }

    // CF-4, G2-P1 activate_failedWriteLeaderUnchanged_activeWithinThreeTicks (#2579)
    // Red when: a failed ACTIVE goal transaction is not re-derived, so the LOADED instance never activates.
    @Test
    void activate_failedWriteLeaderUnchanged_activeWithinThreeTicks() {
        var h = harness(CORE_1);
        var node = node(h, CORE_1, 11);

        loadedOnCore1(node, h);
        h.policy = FsmHarness.SettlePolicy.FAIL_AND_HOLD;
        node.writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        h.held.clear();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        assertThat(activeGoalSubmits(h)).as("control: the ACTIVE goal was submitted once").isEqualTo(1L);
        assertThat(committedGoal(h)).as("control: the failed write did not commit").isEqualTo(Option.some(SliceGoal.LOADED));
        h.advanceBySeconds(THREE_TICKS_SECONDS);

        assertThat(committedGoal(h)).as("goal ACTIVE commits within three ticks").isEqualTo(Option.some(SliceGoal.ACTIVE));
        node.follows(ORDERS);
        h.drain();
        assertThat(stateOf(h, CORE_1, ORDERS)).isEqualTo(SliceObservedState.ACTIVE);
    }

    // CF-4, G2-P1 activate_lostEvent_activeWithinThreeTicks: the fsm2 loss is the submission itself
    // Red when: a lost ACTIVE goal submission is not expired and re-derived, so the LOADED instance never activates.
    @Test
    void activate_lostEvent_activeWithinThreeTicks() {
        var h = harness(CORE_1);
        var node = node(h, CORE_1, 11);

        loadedOnCore1(node, h);
        h.policy = FsmHarness.SettlePolicy.LOSE;
        node.writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        h.policy = FsmHarness.SettlePolicy.APPLY;
        assertThat(activeGoalSubmits(h)).as("control: the ACTIVE goal was submitted once, and lost").isEqualTo(1L);
        h.advanceBySeconds(THREE_TICKS_SECONDS);

        assertThat(committedGoal(h)).as("goal ACTIVE commits within three ticks").isEqualTo(Option.some(SliceGoal.ACTIVE));
    }

    // CF-4, the other fsm2 loss: the LOADED row commits while the FSM's committed leader names another node
    // Red when: an activation the FSM could not submit while suspended is not re-derived when the leader record names it again.
    @Test
    void activate_loadedSeenWhileSuspended_activeWithinThreeTicksOfTheResume() {
        var h = harness(CORE_1);
        var node = node(h, CORE_1, 11);

        loadedOnCore1(node, h);
        h.commitLeader(CORE_2, 2L);
        h.drain();
        node.writes(ORDERS, SliceObservedState.LOADED);
        h.drain();
        assertThat(activeGoalSubmits(h)).as("control: nothing is submitted while suspended").isZero();
        h.commitLeader(FsmHarness.SELF, 3L);
        h.drain();
        h.advanceBySeconds(THREE_TICKS_SECONDS);

        assertThat(committedGoal(h)).as("goal ACTIVE commits within three ticks of the resume")
                                    .isEqualTo(Option.some(SliceGoal.ACTIVE));
    }
}
