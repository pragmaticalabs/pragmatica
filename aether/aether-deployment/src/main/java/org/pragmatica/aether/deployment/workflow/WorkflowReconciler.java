// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// rc6 §4: the leader-side driver of every committed workflow run. One tick reads the committed runs and, for each `RUNNING` or
/// `WAITING` run, asks its kind's [WorkflowPlanner] what to do and performs at most ONE effect, committing by compare-and-set on
/// the EXACT run it read. Nothing is remembered between ticks: a new leader (or a restarted reconciler) reads the same runs and
/// continues from the committed `stepIndex`, attempts, effects and deadline, so failover resets nothing (INV-06). Called from a
/// scheduler thread, never from an FSM listener.
///
/// An effect is performed only after the commit recording it as issued was accepted ([WorkflowPlanner.Plan.Issue]). An escalation
/// is announced as [OperatorWarningCode#WORKFLOW_ESCALATED] once its commit is accepted, by the leader that committed it: at most
/// once (a leader that dies between the accept and the raise loses the event; the committed run keeps the incident as its detail).
/// A run whose kind this node does not know (a newer node admitted it) is held, never failed.
public interface WorkflowReconciler {
    Promise<Unit> reconcile();

    static WorkflowReconciler workflowReconciler(WorkflowLedger ledger,
                                                 Map<String, WorkflowKind<?>> kinds,
                                                 LongSupplier clock,
                                                 OperatorWarningSink warnings) {
        return workflowReconciler(ledger,
                                  kinds,
                                  clock,
                                  warnings,
                                  TimeSpan.timeSpan(30).seconds());
    }

    /// `bound`: how long a commit or an effect call may stay unanswered before the tick stops waiting for it; the next tick re-reads
    /// the committed run.
    static WorkflowReconciler workflowReconciler(WorkflowLedger ledger,
                                                 Map<String, WorkflowKind<?>> kinds,
                                                 LongSupplier clock,
                                                 OperatorWarningSink warnings,
                                                 TimeSpan bound) {
        record reconciler(WorkflowLedger ledger,
                          Map<String, WorkflowKind<?>> kinds,
                          LongSupplier clock,
                          OperatorWarningSink warnings,
                          TimeSpan bound,
                          AtomicBoolean running) implements WorkflowReconciler {
            private static final Logger LOG = LoggerFactory.getLogger(WorkflowReconciler.class);

            @Override
            public Promise<Unit> reconcile() {
                if (!ledger.leads() || !running.compareAndSet(false, true)) {
                    return Promise.unitPromise();
                }
                // A tick that throws before it has a promise must still release the guard, or no later tick would run.
                return Result.lift(this::tick).fold(this::released,
                                                    promise -> promise.withResult(_ -> running.set(false)));
            }

            private Promise<Unit> released(Cause cause) {
                running.set(false);

                return cause.promise();
            }

            private Promise<Unit> tick() {
                return advanceFrom(ledger.runs().stream().filter(WorkflowRunValue::active).toList(),
                                   0);
            }

            private Promise<Unit> advanceFrom(List<WorkflowRunValue> runs, int index) {
                return index >= runs.size()
                       ? Promise.unitPromise()
                       : advance(runs.get(index)).flatMap(_ -> advanceFrom(runs, index + 1));
            }

            // FER: one run's failure (an observation that threw, a refused commit) must not stop the other runs of the tick; the run
            // is re-read and re-planned on the next tick, so nothing is lost but the tick.
            private Promise<Unit> advance(WorkflowRunValue run) {
                return Option.option(kinds.get(run.kind()))
                             .fold(Promise::unitPromise,
                                   kind -> planned(kind, run))
                             .recover(_ -> Unit.unit());
            }

            private Promise<Unit> planned(WorkflowKind<?> kind, WorkflowRunValue run) {
                return Result.lift(() -> kind.plan(run,
                                                   clock.getAsLong()))
                             .async()
                             .flatMap(plan -> execute(run, plan));
            }

            private Promise<Unit> execute(WorkflowRunValue run, WorkflowPlanner.Plan plan) {
                return switch (plan) {
                    case WorkflowPlanner.Plan.Hold _ -> Promise.unitPromise();
                    case WorkflowPlanner.Plan.Commit commit -> commit(run, commit.next()).flatMap(accepted -> accepted
                                                                                                              ? announced(commit.next())
                                                                                                              : Promise.unitPromise());
                    case WorkflowPlanner.Plan.Issue issue -> commit(run, issue.next()).flatMap(accepted -> accepted
                                                                                                           ? performed(issue)
                                                                                                           : Promise.unitPromise());
                };
            }

            // Design-out: a commit that timed out or failed may still have been applied, so its outcome is never assumed; the
            // next tick re-reads the committed run and plans from whatever it is.
            private Promise<Boolean> commit(WorkflowRunValue before, WorkflowRunValue next) {
                return ledger.commit(before, next)
                             .timeout(bound)
                             .recover(_ -> false);
            }

            // Design-out (INV-05): an effect call that failed or went unanswered is UNCERTAIN, not failed. The run is already
            // committed as WAITING with the issue recorded, so later ticks decide by the postcondition, and the effect is issued
            // again only after the step's settle window, against the attempt and effect budgets.
            private Promise<Unit> performed(WorkflowPlanner.Plan.Issue issue) {
                return Result.lift(() -> issue.action()
                                              .apply(issue.next()))
                             .fold(Cause::<Unit> promise, promise -> promise)
                             .timeout(bound)
                             .recover(_ -> Unit.unit());
            }

            private Promise<Unit> announced(WorkflowRunValue next) {
                return next.state() == WorkflowState.ESCALATED
                       ? Promise.success(OperatorWarnings.raise(LOG,
                                                                warnings,
                                                                OperatorWarningCode.WORKFLOW_ESCALATED,
                                                                next.id(),
                                                                "{}",
                                                                next.detail()))
                       : Promise.unitPromise();
            }
        }

        return new reconciler(ledger, kinds, clock, warnings, bound, new AtomicBoolean());
    }
}
