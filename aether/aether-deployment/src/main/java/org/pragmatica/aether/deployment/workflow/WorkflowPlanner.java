// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import org.pragmatica.aether.deployment.workflow.WorkflowStep.Readiness;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// rc6 §4: what one workflow kind does next. A kind supplies [#step], the step at the run's committed `stepIndex`; [#plan] turns
/// (run, observation, now) into the one thing the reconciler must do. Both are pure: nothing is remembered between calls, so a new
/// leader reading the same committed run and the same observation plans exactly what the old one would have (INV-06).
///
/// The order of the checks is the guarantee:
/// 1. the POSTCONDITION first: an effect that happened (even one whose call timed out, or one a previous leader issued) is adopted,
///    never re-issued (INV-05), and the run is `DONE` only when the last step's postcondition is observed (INV-07);
/// 2. the elapsed-time budget, then the step's settle window (an issued effect is given `settle` before anything is re-issued),
///    then the attempt and effect budgets; exhaustion ESCALATES and names the postcondition that was not observed (§4.4);
/// 3. the PRECONDITION last: ready issues the effect, not-yet holds, impossible FAILS the run.
public interface WorkflowPlanner<O> {
    /// The step at `run.stepIndex()`, or none past the last step.
    Option<WorkflowStep<O>> step(WorkflowRunValue run);

    /// What the reconciler must do.
    sealed interface Plan {
        /// Nothing this tick.
        record Hold() implements Plan {}

        /// Commit `next` by compare-and-set on the run read; no effect.
        record Commit(WorkflowRunValue next) implements Plan {}

        /// Commit `next` (the step's effect recorded as issued) by compare-and-set on the run read, and only when that commit is
        /// accepted, perform `action`. Recording the issue BEFORE the effect is what lets a new leader tell an issued effect from an
        /// unissued one, and what keeps two leaders from both issuing one attempt: only one compare-and-set on the run can win.
        record Issue(WorkflowRunValue next, Fn1<Promise<Unit>, WorkflowRunValue> action) implements Plan {}

        Hold HOLD = new Hold();
    }

    /// The plan for `run` under `budget`, given `observed` at `now`. Runs that are not `RUNNING` or `WAITING` are held.
    static <O> Plan plan(WorkflowPlanner<O> planner,
                         WorkflowBudget budget,
                         WorkflowRunValue run,
                         O observed,
                         long now) {
        if (!run.active()) {
            return Plan.HOLD;
        }

        return planner.step(run)
                      .fold(() -> new Plan.Commit(run.in(WorkflowState.FAILED,
                                                         "kind " + run.kind() + " has no step " + run.stepIndex())),
                            step -> planStep(planner, budget, run, step, observed, now));
    }

    private static <O> Plan planStep(WorkflowPlanner<O> planner,
                                     WorkflowBudget budget,
                                     WorkflowRunValue run,
                                     WorkflowStep<O> step,
                                     O observed,
                                     long now) {
        if (step.postcondition().apply(observed)) {
            return advance(planner, run, step);
        }

        if (now >= run.deadline()) {
            return escalate(run, step, "elapsed-time budget exhausted (deadline " + run.deadline() + ")");
        }

        if (settling(run, step, now)) {
            return Plan.HOLD;
        }

        if (run.attempts() >= budget.attempts()) {
            return escalate(run, step, "attempt budget of " + budget.attempts() + " exhausted");
        }

        if (run.effects() >= budget.effects()) {
            return escalate(run, step, "effect budget of " + budget.effects() + " exhausted");
        }

        return switch (step.precondition()
                           .apply(observed)) {
            case Readiness.Ready _ -> new Plan.Issue(run.issued(step.postconditionName(), now),
                                                     step.action());
            case Readiness.NotYet _ -> Plan.HOLD;
            case Readiness.Impossible impossible -> new Plan.Commit(run.in(WorkflowState.FAILED,
                                                                           "step '" + step.name() + "': " + impossible.why()));
        };
    }

    /// The step's postcondition is observed: the next step, or `DONE` when there is none.
    private static <O> Plan advance(WorkflowPlanner<O> planner, WorkflowRunValue run, WorkflowStep<O> step) {
        var next = run.advanced();

        return new Plan.Commit(planner.step(next)
                                      .isPresent()
                               ? next
                               : next.in(WorkflowState.DONE,
                                         "postcondition '" + step.postconditionName() + "' of the last step observed"));
    }

    /// An issued effect is still inside its settle window: re-check, never re-issue.
    private static <O> boolean settling(WorkflowRunValue run, WorkflowStep<O> step, long now) {
        return run.issuedAt() > 0 && now - run.issuedAt() < step.settle()
                                                                .millis();
    }

    private static <O> Plan escalate(WorkflowRunValue run, WorkflowStep<O> step, String budget) {
        return new Plan.Commit(run.in(WorkflowState.ESCALATED, incident(run, step, budget)));
    }

    /// The incident an escalation commits and announces: the budget, the postcondition not observed, and the recovery verbs.
    static <O> String incident(WorkflowRunValue run, WorkflowStep<O> step, String budget) {
        return "workflow " + run.id()
             + " (" + run.kind()
             + ") escalated: " + budget
             + "; postcondition '" + step.postconditionName()
             + "' of step " + run.stepIndex()
             + " '" + step.name()
             + "' not observed after " + run.attempts()
             + " attempt(s), " + run.effects()
             + " effect(s) in total. Recover: resume (re-arms the attempt and elapsed-time budgets; the postcondition"
             + " is re-checked before anything is re-issued) or abandon (FAILED; releases " + String.join(", ",
                                                                                                          run.conflictKeys())
             + ")";
    }
}
