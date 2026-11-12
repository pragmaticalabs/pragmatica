// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.NodeReplacementService;
import org.pragmatica.aether.deployment.cluster.UpgradeRunAnnouncements;
import org.pragmatica.aether.deployment.cluster.UpgradeRunIndex;
import org.pragmatica.aether.deployment.cluster.UpgradeRunPlanner;
import org.pragmatica.aether.deployment.cluster.UpgradeRunReconciler;
import org.pragmatica.aether.deployment.cluster.UpgradeRunService.Refusal;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.assertj.core.api.Assertions.assertThat;

/// #1543 F — the operator operations on the committed run, driven through the REAL service with a recording commit. Each assertion is
/// about what the service COMMITS (and what the committed transition announces), never a hand-fed transition: the abort of a paused
/// run used to commit PAUSED -> RUNNING -> ABORTED and announce a resume that never happened.
class UpgradeRunServiceTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");

    private final UpgradeRunIndex index = UpgradeRunIndex.upgradeRunIndex();
    private final List<UpgradeRunValue> committed = new ArrayList<>();
    private final List<UpgradeRunValue> expectations = new ArrayList<>();
    /// Each entry is a reconciler progress commit that lands between the request's read and its compare-and-set: the request's
    /// commit is rejected and the index (as the delivered notification would) moves to the winner before the reject is answered.
    private final Deque<UpgradeRunValue> racingProgress = new ArrayDeque<>();
    private boolean progressForever = false;
    private boolean leaderLostOnRace = false;
    private boolean commitFails = false;
    private Map<NodeId, NodeReplacementValue> records = Map.of();
    private boolean leader = true;

    private final UpgradeRunReconciler.Environment commits = new UpgradeRunReconciler.Environment() {
        @Override
        public boolean isLeader() {
            return leader;
        }

        @Override
        public Option<UpgradeRunValue> run() {
            return index.run();
        }

        @Override
        public Promise<Boolean> commit(UpgradeRunValue expected, UpgradeRunValue next) {
            expectations.add(expected);

            if (commitFails) {
                return Causes.cause("outcome unknown").<Boolean> promise();
            }

            if (progressForever) {
                index.put(expected.with(expected.index(), expected.inFlight(), expected.state(), expected.stop(), expected.reason(), expected.updatedAtMs() + 1));

                return Promise.success(false);
            }

            if (!racingProgress.isEmpty()) {
                index.put(racingProgress.removeFirst());
                leader = !leaderLostOnRace;

                return Promise.success(false);
            }

            committed.add(next);

            return Promise.success(true);
        }

        @Override
        public UpgradeRunPlanner.Observation observe() {
            return new UpgradeRunPlanner.Observation(Map.of(), records);
        }

        @Override
        public Promise<UpgradeRunReconciler.BeginResult> begin(NodeId node, String targetVersion) {
            return Promise.success(new UpgradeRunReconciler.BeginResult.Started());
        }

        @Override
        public long now() {
            return 7L;
        }
    };

    private final NodeReplacementService replacements = new NodeReplacementService() {
        @Override
        public Promise<NodeReplacementValue> begin(NodeId original, String targetVersion) {
            return new Refusal.Unavailable().promise();
        }

        @Override
        public Promise<NodeReplacementValue> beginExternal(NodeId original, NodeId replacement, String targetVersion) {
            return new Refusal.Unavailable().promise();
        }

        @Override
        public Option<NodeReplacementValue> status(NodeId original) {
            return Option.option(records.get(original));
        }

        @Override
        public Map<NodeId, NodeReplacementValue> all() {
            return records;
        }

        @Override
        public Promise<Unit> settle(NodeId original, Settlement settlement) {
            return new Refusal.Unavailable().promise();
        }
    };

    private UpgradeRunWiring.Service service() {
        var inputs = new UpgradeRunWiring.Inputs(new NodeId("self"), () -> leader, null, null, index, () -> null, _ -> "", replacements, () -> 7L);

        return new UpgradeRunWiring.Service(inputs, commits);
    }

    private static UpgradeRunValue run(UpgradeRunState state, UpgradeStop stop, String inFlight) {
        return new UpgradeRunValue("2.0.0", List.of(A, B), 1, inFlight, state, stop, "why", 1L, 1L, 1L);
    }

    private static NodeReplacementValue record(NodeReplacementPhase phase) {
        return new NodeReplacementValue(new NodeId("a2"), "core", phase, 0L, "", "2.0.0", "CTM", 1, "r", 1L);
    }

    @Test
    void abortOfAPausedRun_commitsAbortedDirectly_andAnnouncesTheEndOfThePause_notAResume() {
        var paused = run(UpgradeRunState.PAUSED, UpgradeStop.NONE, "");

        index.put(paused);
        service().abort().await();

        assertThat(committed).as("one commit, straight to ABORTED").hasSize(1);
        assertThat(committed.getFirst().state()).isEqualTo(UpgradeRunState.ABORTED);
        assertThat(UpgradeRunAnnouncements.upgradeRunAnnouncements(Option.some(paused), committed.getFirst()).stream().map(UpgradeRunAnnouncements.Announcement::code).toList())
            .as("the committed transition, not a hand-fed one")
            .containsExactly(OperatorWarningCode.UPGRADE_PAUSE_ENDED, OperatorWarningCode.UPGRADE_ABORTED);
    }

    @Test
    void abortOfARunningRun_isARequest_thatLeavesTheReplacementInFlightAlone() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a"));
        service().abort().await();

        assertThat(committed.getFirst().state()).isEqualTo(UpgradeRunState.RUNNING);
        assertThat(committed.getFirst().stop()).isEqualTo(UpgradeStop.ABORT);
        assertThat(committed.getFirst().inFlight()).isEqualTo("a");
    }

    @Test
    void aPauseNeverDowngradesAPendingAbort() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.ABORT, "a"));

        var result = service().pause().await();

        assertThat(result.isFailure()).isTrue();
        assertThat(committed).isEmpty();
    }

    @Test
    void aPauseOnAPlainRunningRun_isRecorded() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a"));
        service().pause().await();

        assertThat(committed.getFirst().stop()).isEqualTo(UpgradeStop.PAUSE);
    }

    @Test
    void resume_ofARolledBackNode_clearsInFlight_soTheNodeIsTriedAgain() {
        records = Map.of(A, record(NodeReplacementPhase.ROLLED_BACK));
        index.put(run(UpgradeRunState.PAUSED, UpgradeStop.NONE, "a"));
        service().resume().await();

        assertThat(committed.getFirst().state()).isEqualTo(UpgradeRunState.RUNNING);
        assertThat(committed.getFirst().inFlight()).as("cleared: the planner would otherwise judge the old ROLLED_BACK record and pause again").isEmpty();
    }

    @Test
    void resume_ofARecordThatIsGone_alsoClearsInFlight() {
        index.put(run(UpgradeRunState.PAUSED, UpgradeStop.NONE, "a"));
        service().resume().await();

        assertThat(committed.getFirst().inFlight()).isEmpty();
    }

    @Test
    void resume_ofAKeptBothNode_keepsInFlight_soTheRunPausesAgainUntilItIsSettled() {
        records = Map.of(A, record(NodeReplacementPhase.FAILED_KEPT_BOTH));
        index.put(run(UpgradeRunState.PAUSED, UpgradeStop.NONE, "a"));
        service().resume().await();

        assertThat(committed.getFirst().inFlight()).isEqualTo("a");
    }

    @Test
    void operationsOnTheWrongState_orWithNoRun_areRefused_andCommitNothing() {
        assertThat(service().resume().await().isFailure()).as("no run").isTrue();
        assertThat(service().abort().await().isFailure()).as("no run").isTrue();

        index.put(run(UpgradeRunState.COMPLETED, UpgradeStop.NONE, ""));

        assertThat(service().resume().await().isFailure()).as("not paused").isTrue();
        assertThat(service().abort().await().isFailure()).as("already ended").isTrue();
        assertThat(committed).isEmpty();
    }

    /// #2737: the run's own progress commit (the replacement in flight advancing the run) landed between the abort's read and its
    /// compare-and-set, and the operator got "The upgrade run changed concurrently; retry". The abort is re-decided against the run
    /// that won and lands on it.
    @Test
    void abortThatLosesTheCompareAndSetToTheRunsProgress_isReDecidedAgainstTheWinner_andLands() {
        var before = run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a");
        var progressed = new UpgradeRunValue("2.0.0", List.of(A, B), 1, "b", UpgradeRunState.RUNNING, UpgradeStop.NONE, "why", 1L, 2L, 1L);

        index.put(before);
        racingProgress.add(progressed);

        var result = service().abort().await();

        assertThat(result.isSuccess()).as("abort answered: %s", result).isTrue();
        assertThat(expectations).as("the second attempt compares against the run that won, not the one first read").containsExactly(before, progressed);
        assertThat(committed).hasSize(1);
        assertThat(committed.getFirst().stop()).isEqualTo(UpgradeStop.ABORT);
        assertThat(committed.getFirst().inFlight()).as("built from the winner: its replacement in flight is kept").isEqualTo("b");
    }

    @Test
    void pauseThatLosesTheCompareAndSetToTheRunsProgress_isReDecidedAgainstTheWinner_andLands() {
        var before = run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a");
        var progressed = new UpgradeRunValue("2.0.0", List.of(A, B), 1, "b", UpgradeRunState.RUNNING, UpgradeStop.NONE, "why", 1L, 2L, 1L);

        index.put(before);
        racingProgress.add(progressed);

        var result = service().pause().await();

        assertThat(result.isSuccess()).as("pause answered: %s", result).isTrue();
        assertThat(expectations).containsExactly(before, progressed);
        assertThat(committed.getFirst().stop()).isEqualTo(UpgradeStop.PAUSE);
        assertThat(committed.getFirst().inFlight()).isEqualTo("b");
    }

    @Test
    void resumeThatLosesTheCompareAndSet_isReDecidedAgainstTheWinner_andLands() {
        var before = run(UpgradeRunState.PAUSED, UpgradeStop.NONE, "");
        var rewritten = new UpgradeRunValue("2.0.0", List.of(A, B), 1, "", UpgradeRunState.PAUSED, UpgradeStop.NONE, "why", 1L, 2L, 1L);

        index.put(before);
        racingProgress.add(rewritten);

        var result = service().resume().await();

        assertThat(result.isSuccess()).as("resume answered: %s", result).isTrue();
        assertThat(expectations).containsExactly(before, rewritten);
        assertThat(committed.getFirst().state()).isEqualTo(UpgradeRunState.RUNNING);
    }

    @Test
    void abortThatLosesTheCompareAndSetToTheRunsEnd_isRefusedForTheEndedRun_notAnsweredWithRetry() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "b"));
        racingProgress.add(new UpgradeRunValue("2.0.0", List.of(A, B), 2, "", UpgradeRunState.COMPLETED, UpgradeStop.NONE, "", 1L, 2L, 1L));

        var result = service().abort().await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.NotApplicable.class));
        assertThat(committed).isEmpty();
    }

    @Test
    void aRequestThatLosesEveryCompareAndSet_answersConflict_afterABoundedNumberOfAttempts() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a"));
        progressForever = true;

        var result = service().abort().await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.Conflict.class));
        assertThat(expectations).as("one compare-and-set per attempt, and no more").hasSize(UpgradeRunWiring.Service.REQUEST_ATTEMPTS);
        assertThat(committed).isEmpty();
    }

    @Test
    void requestThatLosesTheCompareAndSetAndThenLeadership_isRefusedNotLeader_andDoesNotLoop() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a"));
        racingProgress.add(new UpgradeRunValue("2.0.0", List.of(A, B), 1, "b", UpgradeRunState.RUNNING, UpgradeStop.NONE, "why", 1L, 2L, 1L));
        leaderLostOnRace = true;

        var result = service().abort().await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.NotLeader.class));
        assertThat(expectations).hasSize(1);
        assertThat(committed).isEmpty();
    }

    @Test
    void pauseThatLosesTheCompareAndSetToAnAbort_isRefusedForTheAbortPending() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a"));
        racingProgress.add(new UpgradeRunValue("2.0.0", List.of(A, B), 1, "a", UpgradeRunState.RUNNING, UpgradeStop.ABORT, "why", 1L, 2L, 1L));

        var result = service().pause().await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.NotApplicable.class));
        assertThat(committed).isEmpty();
    }

    @Test
    void resumeThatLosesTheCompareAndSetToARunningRun_isRefusedNotApplicable() {
        index.put(run(UpgradeRunState.PAUSED, UpgradeStop.NONE, ""));
        racingProgress.add(new UpgradeRunValue("2.0.0", List.of(A, B), 1, "a", UpgradeRunState.RUNNING, UpgradeStop.NONE, "why", 1L, 2L, 1L));

        var result = service().resume().await();

        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isInstanceOf(Refusal.NotApplicable.class));
        assertThat(committed).isEmpty();
    }

    @Test
    void anApplyThatFails_isNotRetried_itsOutcomeIsUnknown() {
        index.put(run(UpgradeRunState.RUNNING, UpgradeStop.NONE, "a"));
        commitFails = true;

        var result = service().abort().await();

        assertThat(result.isFailure()).isTrue();
        assertThat(expectations).hasSize(1);
    }

    @Test
    void aNodeThatIsNotTheLeader_changesNothing() {
        leader = false;
        index.put(run(UpgradeRunState.PAUSED, UpgradeStop.NONE, ""));

        assertThat(service().abort().await().isFailure()).isTrue();
        assertThat(committed).isEmpty();
    }
}
