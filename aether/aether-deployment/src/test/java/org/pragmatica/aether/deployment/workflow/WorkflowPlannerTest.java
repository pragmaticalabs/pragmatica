// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.workflow.WorkflowPlanner.Plan;
import org.pragmatica.aether.deployment.workflow.WorkflowStep.Readiness;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// rc6 §4: the pure plan, branch by branch. The order of the checks IS the guarantee (postcondition first, then the deadline, the
/// settle window, the budgets, and the precondition last), so each test pins one ordering against the check that would otherwise win.
class WorkflowPlannerTest {
    /// What the conditions read: whether each step's postcondition holds, and the precondition's answer.
    record Obs(boolean post0, boolean post1, Readiness pre) {}

    private static final WorkflowBudget BUDGET = new WorkflowBudget(2,
                                                                    TimeSpan.timeSpan(100).seconds(),
                                                                    3);
    private static final long SETTLE_MS = 10_000L;
    private static final WorkflowStep<Obs> STEP_0 = step("provision", Obs::post0, "node provisioned");
    private static final WorkflowStep<Obs> STEP_1 = step("verify", Obs::post1, "node verified");
    private static final List<WorkflowStep<Obs>> STEPS = List.of(STEP_0, STEP_1);

    private static final WorkflowPlanner<Obs> PLANNER = run -> run.stepIndex() < STEPS.size()
                                                               ? Option.some(STEPS.get(run.stepIndex()))
                                                               : Option.none();

    private static final Obs NOTHING_READY = new Obs(false, false, Readiness.READY);
    private static final long START = 1_000_000L;

    private static WorkflowStep<Obs> step(String name,
                                          org.pragmatica.lang.Functions.Fn1<Boolean, Obs> post,
                                          String postName) {
        return new WorkflowStep<>(name,
                                  Obs::pre,
                                  _ -> Promise.success(Unit.unit()),
                                  post,
                                  postName,
                                  TimeSpan.timeSpan(SETTLE_MS).millis());
    }

    private static WorkflowRunValue fresh() {
        return WorkflowRunValue.admitted("wf-1", "pilot", Map.of(), List.of("node:n1"), START, START + 100_000L);
    }

    private static Plan plan(WorkflowRunValue run, Obs obs, long now) {
        return WorkflowPlanner.plan(PLANNER, BUDGET, run, obs, now);
    }

    @Test
    void plan_freshRunWithReadyPrecondition_issuesTheEffect_recordingAttemptEffectAndIssueTime() {
        var plan = plan(fresh(), NOTHING_READY, START + 5);

        assertThat(plan).isInstanceOf(Plan.Issue.class);
        var next = ((Plan.Issue) plan).next();

        assertThat(next.state()).isEqualTo(WorkflowState.WAITING);
        assertThat(next.detail()).isEqualTo("node provisioned");
        assertThat(next.attempts()).isEqualTo(1);
        assertThat(next.effects()).isEqualTo(1);
        assertThat(next.issuedAt()).isEqualTo(START + 5);
        assertThat(next.stepIndex()).isZero();
        assertThat(((Plan.Issue) plan).action()).isSameAs(STEP_0.action());
    }

    @Test
    void plan_issuedEffectInsideItsSettleWindow_holds_neverReissues() {
        var waiting = fresh().issued("node provisioned", START);

        assertThat(plan(waiting, NOTHING_READY, START + SETTLE_MS - 1)).isEqualTo(Plan.HOLD);
    }

    @Test
    void plan_issuedEffectPastItsSettleWindow_reissues_countingTheAttempt() {
        var waiting = fresh().issued("node provisioned", START);
        var plan = plan(waiting, NOTHING_READY, START + SETTLE_MS);

        assertThat(plan).isInstanceOf(Plan.Issue.class);
        assertThat(((Plan.Issue) plan).next().attempts()).isEqualTo(2);
        assertThat(((Plan.Issue) plan).next().effects()).isEqualTo(2);
    }

    /// INV-05: an effect whose call failed but which happened is ADOPTED by its postcondition, even inside the settle window.
    @Test
    void plan_postconditionObservedWhileWaiting_advances_withoutReissuing() {
        var waiting = fresh().issued("node provisioned", START);
        var plan = plan(waiting, new Obs(true, false, Readiness.READY), START + 1);

        assertThat(plan).isEqualTo(new Plan.Commit(waiting.advanced()));
        assertThat(((Plan.Commit) plan).next().stepIndex()).isEqualTo(1);
        assertThat(((Plan.Commit) plan).next().attempts()).as("the attempt budget is per step").isZero();
        assertThat(((Plan.Commit) plan).next().effects()).as("the effect budget is the run's").isEqualTo(1);
        assertThat(((Plan.Commit) plan).next().issuedAt()).isZero();
    }

    /// INV-07: DONE only when the LAST step's postcondition is observed.
    @Test
    void plan_lastPostconditionObserved_isDone() {
        var onLast = fresh().advanced().issued("node verified", START);
        var plan = plan(onLast, new Obs(true, true, Readiness.READY), START + 1);

        assertThat(plan).isInstanceOf(Plan.Commit.class);
        var done = ((Plan.Commit) plan).next();

        assertThat(done.state()).isEqualTo(WorkflowState.DONE);
        assertThat(done.detail()).contains("node verified");
        assertThat(done.stepIndex()).isEqualTo(2);
    }

    @Test
    void plan_lastPostconditionNotObserved_isNeverDone() {
        var onLast = fresh().advanced();

        assertThat(plan(onLast, new Obs(true, false, new Readiness.NotYet("busy")), START + 1)).isEqualTo(Plan.HOLD);
    }

    /// The postcondition is checked before the deadline and the budgets: work that is done is DONE, not ESCALATED.
    @Test
    void plan_postconditionObservedPastTheDeadlineAndBudgets_stillAdvances() {
        var exhausted = fresh().issued("p", START).issued("p", START).issued("p", START);

        assertThat(plan(exhausted, new Obs(true, false, Readiness.READY), START + 1_000_000L)).isEqualTo(new Plan.Commit(exhausted.advanced()));
    }

    @Test
    void plan_pastTheDeadline_escalates_namingThePostcondition() {
        var plan = plan(fresh(), NOTHING_READY, START + 100_000L);

        assertThat(plan).isInstanceOf(Plan.Commit.class);
        var escalated = ((Plan.Commit) plan).next();

        assertThat(escalated.state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(escalated.detail()).contains("elapsed-time budget",
                                                "'node provisioned'",
                                                "resume",
                                                "abandon",
                                                "node:n1");
    }

    @Test
    void plan_attemptBudgetExhausted_escalates() {
        var twice = fresh().issued("node provisioned", START).issued("node provisioned", START);
        var plan = plan(twice, NOTHING_READY, START + SETTLE_MS);

        assertThat(plan).isInstanceOf(Plan.Commit.class);
        assertThat(((Plan.Commit) plan).next().state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(((Plan.Commit) plan).next().detail()).contains("attempt budget of 2", "'node provisioned'");
    }

    @Test
    void plan_effectBudgetExhausted_escalates() {
        var spent = fresh().issued("node provisioned", START)
                         .advanced()
                         .issued("node verified", START)
                         .issued("node verified", START)
                         .advanced();
        var onFirstAgain = new WorkflowRunValue(spent.id(),
                                                spent.kind(),
                                                spent.params(),
                                                1,
                                                0,
                                                spent.effects(),
                                                spent.startedAt(),
                                                spent.deadline(),
                                                0L,
                                                WorkflowState.RUNNING,
                                                "",
                                                spent.conflictKeys(),
                                                spent.epoch());
        var plan = plan(onFirstAgain, NOTHING_READY, START + 1);

        assertThat(onFirstAgain.effects()).isEqualTo(3);
        assertThat(plan).isInstanceOf(Plan.Commit.class);
        assertThat(((Plan.Commit) plan).next().state()).isEqualTo(WorkflowState.ESCALATED);
        assertThat(((Plan.Commit) plan).next().detail()).contains("effect budget of 3", "'node verified'");
    }

    @Test
    void plan_preconditionNotYet_holds() {
        assertThat(plan(fresh(), new Obs(false, false, new Readiness.NotYet("capacity")), START + 1)).isEqualTo(Plan.HOLD);
    }

    @Test
    void plan_preconditionImpossible_fails_withTheReason() {
        var plan = plan(fresh(), new Obs(false, false, new Readiness.Impossible("node is gone")), START + 1);

        assertThat(plan).isInstanceOf(Plan.Commit.class);
        assertThat(((Plan.Commit) plan).next().state()).isEqualTo(WorkflowState.FAILED);
        assertThat(((Plan.Commit) plan).next().detail()).contains("provision", "node is gone");
    }

    @Test
    void plan_noStepAtTheCommittedIndex_fails() {
        var past = fresh().advanced().advanced();
        var plan = plan(past, NOTHING_READY, START + 1);

        assertThat(plan).isInstanceOf(Plan.Commit.class);
        assertThat(((Plan.Commit) plan).next().state()).isEqualTo(WorkflowState.FAILED);
    }

    @Test
    void plan_inactiveStates_hold() {
        for (var state : List.of(WorkflowState.PAUSED,
                                 WorkflowState.DONE,
                                 WorkflowState.FAILED,
                                 WorkflowState.ESCALATED,
                                 WorkflowState.UNKNOWN)) {
            assertThat(plan(fresh().in(state, "x"),
                            NOTHING_READY,
                            START + 1_000_000L)).as(state.name())
                      .isEqualTo(Plan.HOLD);
        }
    }

    /// A new leader reading the same committed run and observation plans the same: nothing is remembered between calls.
    @Test
    void plan_isAPureFunctionOfRunObservationAndTime() {
        var waiting = fresh().issued("node provisioned", START);

        assertThat(plan(waiting, NOTHING_READY, START + SETTLE_MS)).isEqualTo(plan(waiting,
                                                                                   NOTHING_READY,
                                                                                   START + SETTLE_MS));
        assertThat(plan(fresh(), NOTHING_READY, START + 5)).isEqualTo(plan(fresh(), NOTHING_READY, START + 5));
    }

    /// Never-issued means `issuedAt == 0`, which is not a time: a `now` inside the settle window measured from 0 must not hold the run.
    @Test
    void plan_neverIssuedStep_withNowBelowTheSettleWindow_issues_notHolds() {
        assertThat(fresh().issuedAt()).isZero();
        assertThat(plan(fresh(), NOTHING_READY, SETTLE_MS - 1)).isInstanceOf(Plan.Issue.class);
    }

    /// After a resume or a step change `attempts` and `effects` differ; the incident reports each one's own count.
    @Test
    void plan_incident_reportsAttemptsAndEffectsSeparately_whenTheyDiffer() {
        var second = fresh().issued("node provisioned", START).advanced().issued("node verified", START).issued("node verified", START);
        var plan = plan(second, NOTHING_READY, START + SETTLE_MS);

        assertThat(second.attempts()).isEqualTo(2);
        assertThat(second.effects()).isEqualTo(3);
        assertThat(plan).isInstanceOf(Plan.Commit.class);
        assertThat(((Plan.Commit) plan).next().detail()).contains("not observed after 2 attempt(s), 3 effect(s) in total",
                                                                  "step 1 'verify'");
    }
}
