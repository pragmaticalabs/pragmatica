// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;


/// rc6 §4.3: the ONE path by which every workflow, operator-started or autonomous, is admitted. Admission is one leader
/// transaction ([WorkflowLedger#create]) that creates the run and claims every conflict key, each expecting the key absent.
///
/// Guarantee: at most one admitted workflow holds a given conflict key at a time. Mechanism: the claim is a consensus-committed
/// record created only if absent, deleted only in the transaction that commits its run's end. A refused admission names the
/// holder: [AdmissionRefusal.ConflictKeyHeld] carries the key and the holding run's id, read back after the refusal; a refusal
/// whose keys are all free again by then (the holder ended in between, or leadership moved) is [AdmissionRefusal.NotAdmitted], and
/// the caller may retry.
public interface WorkflowAdmission {
    /// Admit a run of `kind` with `params`, claiming `conflictKeys`, at `now`. The run's deadline is `now` plus the kind's
    /// elapsed-time budget.
    Promise<WorkflowRunValue> admit(String kind, Map<String, String> params, List<String> conflictKeys, long now);

    /// Why an admission was refused.
    sealed interface AdmissionRefusal extends Cause {
        /// `key` is held by the workflow `holderRunId`: a visible refusal, never a silent merge.
        record ConflictKeyHeld(String key, String holderRunId) implements AdmissionRefusal {
            @Override
            public String message() {
                return "Conflict key " + key + " is held by workflow " + holderRunId;
            }
        }

        record UnknownKind(String kind) implements AdmissionRefusal {
            @Override
            public String message() {
                return "There is no workflow kind " + kind;
            }
        }

        record InvalidConflictKeys(String why) implements AdmissionRefusal {
            @Override
            public String message() {
                return "A workflow claims one or more distinct, non-blank conflict keys: " + why;
            }
        }

        record NotLeader() implements AdmissionRefusal {
            @Override
            public String message() {
                return "Only the leader admits a workflow";
            }
        }

        /// The transaction was refused, but no key of the run was held when it was read back: retry.
        record NotAdmitted(String runId) implements AdmissionRefusal {
            @Override
            public String message() {
                return "Workflow " + runId + " was not admitted (its claims or leadership changed concurrently); retry";
            }
        }

        NotLeader NOT_LEADER = new NotLeader();
    }

    /// Admission over `ledger` for the registered `kinds` (by name), with `wf-<uuid>` run ids.
    static WorkflowAdmission workflowAdmission(WorkflowLedger ledger, Map<String, WorkflowKind<?>> kinds) {
        return workflowAdmission(ledger, kinds, () -> "wf-" + UUID.randomUUID());
    }

    /// As [#workflowAdmission(WorkflowLedger, Map)], with run ids from `ids`.
    static WorkflowAdmission workflowAdmission(WorkflowLedger ledger,
                                               Map<String, WorkflowKind<?>> kinds,
                                               Fn0<String> ids) {
        record admission(WorkflowLedger ledger, Map<String, WorkflowKind<?>> kinds, Fn0<String> ids) implements WorkflowAdmission {
            @Override
            public Promise<WorkflowRunValue> admit(String kind,
                                                   Map<String, String> params,
                                                   List<String> conflictKeys,
                                                   long now) {
                return admissible(kind, conflictKeys).async()
                                 .flatMap(found -> create(WorkflowRunValue.admitted(ids.apply(),
                                                                                    found.name(),
                                                                                    params,
                                                                                    conflictKeys,
                                                                                    now,
                                                                                    now + found.budget()
                                                                                               .elapsed()
                                                                                               .toMillis())));
            }

            private Result<WorkflowKind<?>> admissible(String kind, List<String> conflictKeys) {
                if (!ledger.leads()) {
                    return AdmissionRefusal.NOT_LEADER.result();
                }

                return Option.option(kinds.get(kind))
                             .toResult(new AdmissionRefusal.UnknownKind(kind))
                             .flatMap(found -> validKeys(conflictKeys).map(_ -> found));
            }

            private static Result<Unit> validKeys(List<String> conflictKeys) {
                if (conflictKeys.isEmpty()) {
                    return new AdmissionRefusal.InvalidConflictKeys("none given").result();
                }

                if (conflictKeys.stream().anyMatch(String::isBlank)) {
                    return new AdmissionRefusal.InvalidConflictKeys("a blank key").result();
                }

                return new HashSet<>(conflictKeys).size() == conflictKeys.size()
                       ? Result.unitResult()
                       : new AdmissionRefusal.InvalidConflictKeys("a key given twice in " + conflictKeys).result();
            }

            private Promise<WorkflowRunValue> create(WorkflowRunValue run) {
                return ledger.create(run)
                             .flatMap(accepted -> accepted
                                                  ? Promise.success(run)
                                                  : refusal(run));
            }

            /// The refusal of `run`, read back from the committed claims: the first of its keys held by another run.
            private Promise<WorkflowRunValue> refusal(WorkflowRunValue run) {
                return Option.from(run.conflictKeys().stream().flatMap(key -> heldBy(key).stream()).findFirst()).fold(() -> new AdmissionRefusal.NotAdmitted(run.id()).<WorkflowRunValue> promise(),
                                                                                                                      held -> held.<WorkflowRunValue> promise());
            }

            private Option<AdmissionRefusal.ConflictKeyHeld> heldBy(String key) {
                return ledger.claim(key)
                             .map(claim -> new AdmissionRefusal.ConflictKeyHeld(key,
                                                                                claim.holderRunId()));
            }
        }

        return new admission(ledger, kinds, ids);
    }
}
