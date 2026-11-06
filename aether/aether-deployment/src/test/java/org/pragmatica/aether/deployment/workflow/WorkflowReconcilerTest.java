// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.deployment.workflow.WorkflowStep.Readiness;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.deployment.workflow.WorkflowTestCluster.CORE_1;
import static org.assertj.core.api.Assertions.assertThat;


/// rc6 §4: the reconciler's contract around the ledger and the effect, one failure arm per test. The ledger is the real one over a
/// real applier, wrapped so a test can refuse, lose, delay or fail a commit, or change what the node believes about leadership.
/// Where the wrapper answers instead of the applier, the test says so.
class WorkflowReconcilerTest {
    private static final Cause BOOM = Causes.cause("boom");
    private static final long T0 = 1_000_000L;
    private static final TimeSpan BOUND = TimeSpan.timeSpan(150).millis();
    private static final TimeSpan LONG_BOUND = TimeSpan.timeSpan(60).seconds();
    private static final TimeSpan WAIT = TimeSpan.timeSpan(10).seconds();

    private WorkflowTestCluster cluster;
    private WarningProbe probe;
    private AtomicInteger actions;
    private AtomicInteger observations;
    private volatile Fn0<Promise<Unit>> effect;
    private volatile Fn0<Boolean> leads;
    private volatile Fn0<List<WorkflowRunValue>> runs;
    private volatile Fn2<Promise<Boolean>, WorkflowRunValue, WorkflowRunValue> commits;
    private WorkflowLedger real;
    private WorkflowKind<Boolean> kind;

    @BeforeEach
    void setUp() {
        cluster = new WorkflowTestCluster();
        probe = new WarningProbe();
        actions = new AtomicInteger();
        observations = new AtomicInteger();
        real = cluster.ledger(CORE_1);
        effect = Promise::unitPromise;
        leads = () -> real.leads();
        runs = () -> real.runs();
        commits = (before, next) -> real.commit(before, next);
        var step = new WorkflowStep<Boolean>("only",
                                             _ -> Readiness.READY,
                                             this::act,
                                             observed -> observed,
                                             "done",
                                             TimeSpan.timeSpan(10).seconds());

        kind = new WorkflowKind<>("k",
                                  new WorkflowBudget(3, TimeSpan.timeSpan(100).seconds(), 5),
                                  _ -> Option.some(step),
                                  this::observe);
    }

    private Promise<Unit> act(WorkflowRunValue run) {
        actions.incrementAndGet();

        return effect.apply();
    }

    private Boolean observe() {
        observations.incrementAndGet();

        return false;
    }

    private WorkflowLedger ledger() {
        return new WorkflowLedger() {
            @Override
            public List<WorkflowRunValue> runs() {
                return runs.apply();
            }

            @Override
            public Option<org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowClaimValue> claim(String conflictKey) {
                return real.claim(conflictKey);
            }

            @Override
            public boolean leads() {
                return leads.apply();
            }

            @Override
            public Promise<Boolean> create(WorkflowRunValue run) {
                return real.create(run);
            }

            @Override
            public Promise<Boolean> commit(WorkflowRunValue expected, WorkflowRunValue next) {
                return commits.apply(expected, next);
            }
        };
    }

    private WorkflowReconciler reconciler(TimeSpan bound) {
        return WorkflowReconciler.workflowReconciler(ledger(), Map.of("k", kind), () -> T0, probe.sink(), bound);
    }

    private WorkflowRunValue admit(String id, String key) {
        return WorkflowAdmission.workflowAdmission(real, Map.of("k", kind), () -> id)
                                .admit("k", Map.of(), List.of(key), T0)
                                .await()
                                .unwrap();
    }

    private WorkflowRunValue committed(String id) {
        return cluster.run(id).unwrap();
    }

    private static void tick(WorkflowReconciler reconciler) {
        assertThat(reconciler.reconcile().await(WAIT).isSuccess()).as("the tick ends").isTrue();
    }

    //--- D4: an effect is performed only after its Issue commit was accepted (R11-R13)

    @Test
    void tick_acceptedIssueCommit_performsTheEffectOnce() {
        admit("wf-1", "a");
        tick(reconciler(BOUND));
        assertThat(actions.get()).isEqualTo(1);
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.WAITING);
    }

    @Test
    void tick_refusedIssueCommit_performsNoEffect() {
        admit("wf-1", "a");
        commits = (_, _) -> Promise.success(false);
        tick(reconciler(BOUND));
        assertThat(actions.get()).isZero();
    }

    @Test
    void tick_issueCommitWhoseAnswerIsLost_performsNoEffect_andTheTickEndsAtTheBound() {
        admit("wf-1", "a");
        commits = (_, _) -> Promise.promise();
        tick(reconciler(BOUND));
        assertThat(actions.get()).isZero();
    }

    @Test
    void tick_issueCommitThatFails_performsNoEffect() {
        admit("wf-1", "a");
        commits = (_, _) -> BOOM.promise();
        tick(reconciler(BOUND));
        assertThat(actions.get()).isZero();
    }

    //--- the effect's own arms (R14, R15): uncertain, never failed

    @Test
    void tick_effectThatNeverAnswers_endsAtTheBound_leavingTheRunWaiting() {
        admit("wf-1", "a");
        effect = Promise::promise;
        tick(reconciler(BOUND));
        assertThat(actions.get()).isEqualTo(1);
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.WAITING);
        assertThat(probe.settled()).isEmpty();
    }

    @Test
    void tick_effectThatFails_leavesTheRunWaiting() {
        admit("wf-1", "a");
        effect = () -> BOOM.promise();
        tick(reconciler(BOUND));
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.WAITING);
        assertThat(committed("wf-1").attempts()).isEqualTo(1);
    }

    @Test
    void tick_effectThatThrows_leavesTheRunWaiting() {
        admit("wf-1", "a");
        effect = () -> {
            throw new IllegalStateException("executor blew up");
        };
        tick(reconciler(BOUND));
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.WAITING);
    }

    /// Promise.timeout fails the promise it is called on. The bound must be armed on a derived promise, so an action that returns a
    /// shared promise (a cached or in-flight one from an existing mechanism) does not get it failed for every other subscriber.
    @Test
    void tick_effectReturningASharedPromise_doesNotFailItWithTheRunsBound() {
        var shared = Promise.<Unit> promise();

        admit("wf-1", "a");
        admit("wf-2", "b");
        effect = () -> shared;
        tick(reconciler(BOUND));
        assertThat(actions.get()).as("both runs issued and waited out their bound").isEqualTo(2);
        assertThat(shared.isResolved()).as("the shared promise is not resolved by a run's timeout").isFalse();
    }

    //--- the leader fence immediately before the effect (D4/D10)

    @Test
    void tick_leaderDeposedAfterTheIssueCommit_burnsTheAttempt_andPerformsNoEffect() {
        admit("wf-1", "a");
        var checks = new AtomicInteger();

        leads = () -> checks.incrementAndGet() == 1;
        tick(reconciler(BOUND));
        assertThat(checks.get()).as("once at the tick, once immediately before the effect").isEqualTo(2);
        assertThat(actions.get()).isZero();
        assertThat(committed("wf-1").state()).as("the attempt is recorded as issued").isEqualTo(WorkflowState.WAITING);
        assertThat(committed("wf-1").attempts()).isEqualTo(1);
    }

    //--- the tick guard (R2, R3) and what a tick observes (R1, R5)

    @Test
    void reconcile_whileATickIsInFlight_isSkipped_andRunsAgainOnceItEnded() {
        var inFlight = Promise.<Unit> promise();

        admit("wf-1", "a");
        effect = () -> inFlight;
        var reconciler = reconciler(LONG_BOUND);
        var first = reconciler.reconcile();

        assertThat(first.isResolved()).as("the first tick waits for its effect").isFalse();
        tick(reconciler);
        assertThat(observations.get()).as("the overlapping tick observed nothing").isEqualTo(1);
        assertThat(actions.get()).isEqualTo(1);
        inFlight.succeed(Unit.unit());
        assertThat(first.await(WAIT).isSuccess()).isTrue();
        tick(reconciler);
        assertThat(observations.get()).as("the guard was released").isEqualTo(2);
    }

    @Test
    void reconcile_whenTheTickThrowsBeforeHavingAPromise_failsOnce_andReleasesTheGuard() {
        admit("wf-1", "a");
        var reads = new AtomicInteger();

        runs = () -> reads.incrementAndGet() == 1 ? failingRead() : real.runs();
        var reconciler = reconciler(BOUND);

        assertThat(reconciler.reconcile().await(WAIT).isFailure()).isTrue();
        tick(reconciler);
        assertThat(observations.get()).as("the next tick ran").isEqualTo(1);
    }

    private static List<WorkflowRunValue> failingRead() {
        throw new IllegalStateException("store read failed");
    }

    @Test
    void reconcile_onANodeThatDoesNotLead_observesNothing_andDoesNothing() {
        admit("wf-1", "a");
        leads = () -> false;
        tick(reconciler(BOUND));
        assertThat(observations.get()).isZero();
        assertThat(actions.get()).isZero();
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.RUNNING);
    }

    @Test
    void reconcile_inactiveRuns_areNeitherObservedNorPlanned() {
        admit("wf-1", "a");
        var run = committed("wf-1");

        for (var state : List.of(WorkflowState.PAUSED, WorkflowState.DONE, WorkflowState.FAILED, WorkflowState.ESCALATED, WorkflowState.UNKNOWN)) {
            cluster.putRaw(run.in(state, "x"));
            tick(reconciler(BOUND));
        }
        assertThat(observations.get()).isZero();
        assertThat(actions.get()).isZero();
    }

    //--- the at-most-once incident (A3): a commit applied but not seen to be accepted never raises

    private WorkflowRunValue pastDeadline() {
        var run = admit("wf-1", "a");

        cluster.putRaw(new WorkflowRunValue(run.id(), run.kind(), run.params(), 0, 0, 0, T0, T0 - 1, 0L, WorkflowState.RUNNING, "", run.conflictKeys(), run.epoch()));

        return committed("wf-1");
    }

    @Test
    void tick_escalationAcceptedAndAnnounced_raisesExactlyOneIncident() {
        pastDeadline();
        var reconciler = reconciler(BOUND);

        tick(reconciler);
        tick(reconciler);
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(probe.settled()).hasSize(1);
    }

    @Test
    void tick_escalationCommitAppliedButAnsweredLate_isNeverAnnounced_yetStaysCommitted() {
        pastDeadline();

        commits = (expected, next) -> appliedButUnanswered(expected, next, Promise.promise());
        var reconciler = reconciler(BOUND);

        tick(reconciler);
        tick(reconciler);
        assertThat(committed("wf-1").state()).as("the commit was applied").isEqualTo(WorkflowState.ESCALATED);
        assertThat(committed("wf-1").detail()).as("the incident survives as the run's detail").contains("elapsed-time budget");
        assertThat(probe.settled()).as("at most once: nothing re-raises it").isEmpty();
    }

    @Test
    void tick_escalationCommitAppliedButAnswerFailed_isNeverAnnounced() {
        pastDeadline();
        commits = (expected, next) -> appliedButUnanswered(expected, next, BOOM.promise());
        tick(reconciler(BOUND));
        assertThat(committed("wf-1").state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(probe.settled()).isEmpty();
    }

    /// The wrapper, not the applier, answers: the REAL commit is applied, and the caller is handed `answer` instead of its result.
    private Promise<Boolean> appliedButUnanswered(WorkflowRunValue expected, WorkflowRunValue next, Promise<Boolean> answer) {
        real.commit(expected, next).await();

        return answer;
    }
}
