// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.io.TimeSpan;


/// rc6 §4.3: the ONE path by which every workflow, operator-started or autonomous, is admitted. Admission is one leader
/// transaction ([WorkflowLedger#create]) that creates the run and claims every conflict key, each expecting the key absent.
///
/// Guarantee: at most one admitted workflow holds a given conflict key at a time. Mechanism: the claim is a consensus-committed
/// record created only if absent, deleted only in the transaction that commits its run's end. A refused admission names the
/// holder: [AdmissionRefusal.ConflictKeyHeld] carries the key and the holding run's id, read back after the refusal; a refusal
/// whose keys are all free again by then (the holder ended in between, or leadership moved) is [AdmissionRefusal.NotAdmitted], and
/// the caller may retry.
///
/// Keyed admission (f2b §5): the run id is derived from the kind and the caller's request key ([#runId]), so a retry of one request
/// names the SAME run. A retry after a lost answer is refused by the applier (the run key is expected absent); the read-back then
/// finds the committed run and, when its kind and params equal the request's, answers THAT run as the success. The same request key
/// with a different kind or params is [AdmissionRefusal.RequestKeyReused]. A create left unanswered past `bound` is uncertain: the
/// same read-back resolves it when the run is already visible, and otherwise the refusal is [AdmissionRefusal.Unanswered], which the
/// caller resolves by retrying with the same request key. Bound: idempotency holds while the run is retained (not yet deleted), so
/// the retention of terminal runs must be at least the client's retry window.
public interface WorkflowAdmission {
    /// Admit a run of `kind` with `params`, claiming `conflictKeys`, at `now`, as the request `requestKey` (minted once by the caller
    /// and resent on every retry). The run's deadline is `now` plus the kind's elapsed-time budget.
    Promise<WorkflowRunValue> admit(String kind,
                                    Map<String, String> params,
                                    List<String> conflictKeys,
                                    long now,
                                    String requestKey);

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

        record InvalidRequestKey() implements AdmissionRefusal {
            @Override
            public String message() {
                return "A workflow admission needs a non-blank request key";
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

        /// `requestKey` already names the committed run `runId`, admitted for a different kind or different params.
        record RequestKeyReused(String requestKey, String runId) implements AdmissionRefusal {
            @Override
            public String message() {
                return "Request key " + requestKey
                     + " was already used to admit workflow " + runId
                     + " with a different kind or params; use a new request key for a new request";
            }
        }

        /// The admission of `runId` was not answered within the bound and the run is not visible yet: it may still be admitted.
        record Unanswered(String runId) implements AdmissionRefusal {
            @Override
            public String message() {
                return "The admission of workflow " + runId
                     + " was not answered in time; retry with the same request key, which answers the run if it was admitted";
            }
        }

        NotLeader NOT_LEADER = new NotLeader();
        InvalidRequestKey INVALID_REQUEST_KEY = new InvalidRequestKey();
    }

    /// The run id of the request `requestKey` for `kind`: `wf-` and the first 16 hex characters of SHA-256 over the kind, a NUL
    /// separator and the request key (the separator keeps (`ab`, `c`) and (`a`, `bc`) apart; a kind name holds no NUL).
    static Result<String> runId(String kind, String requestKey) {
        return Result.lift(() -> MessageDigest.getInstance("SHA-256"))
                     .map(digest -> digest.digest((kind + "\0" + requestKey).getBytes(StandardCharsets.UTF_8)))
                     .map(hash -> "wf-" + HexFormat.of()
                                                   .formatHex(hash)
                                                   .substring(0, 16));
    }

    /// Admission over `ledger` for the registered `kinds` (by name); a create unanswered for 30 s is resolved by the read-back.
    static WorkflowAdmission workflowAdmission(WorkflowLedger ledger, Map<String, WorkflowKind<?>> kinds) {
        return workflowAdmission(ledger,
                                 kinds,
                                 TimeSpan.timeSpan(30).seconds());
    }

    /// As [#workflowAdmission(WorkflowLedger, Map)]; `bound`: how long the create may stay unanswered before the read-back decides.
    static WorkflowAdmission workflowAdmission(WorkflowLedger ledger,
                                               Map<String, WorkflowKind<?>> kinds,
                                               TimeSpan bound) {
        record admission(WorkflowLedger ledger, Map<String, WorkflowKind<?>> kinds, TimeSpan bound) implements WorkflowAdmission {
            /// What the create's answer says: accepted, refused by the applier, or not known within the bound.
            private enum Created {
                ACCEPTED,
                REFUSED,
                UNANSWERED
            }

            @Override
            public Promise<WorkflowRunValue> admit(String kind,
                                                   Map<String, String> params,
                                                   List<String> conflictKeys,
                                                   long now,
                                                   String requestKey) {
                return admissible(kind, conflictKeys, requestKey).flatMap(found -> runId(found.name(),
                                                                                         requestKey).map(id -> WorkflowRunValue.admitted(id,
                                                                                                                                         found.name(),
                                                                                                                                         params,
                                                                                                                                         conflictKeys,
                                                                                                                                         now,
                                                                                                                                         now + found.budget()
                                                                                                                                                    .elapsed()
                                                                                                                                                    .millis())))
                                 .async()
                                 .flatMap(run -> create(run, requestKey));
            }

            private Result<WorkflowKind<?>> admissible(String kind, List<String> conflictKeys, String requestKey) {
                if (!ledger.leads()) {
                    return AdmissionRefusal.NOT_LEADER.result();
                }

                return Option.option(kinds.get(kind))
                             .toResult(new AdmissionRefusal.UnknownKind(kind))
                             .flatMap(found -> validKeys(conflictKeys).map(_ -> found))
                             .flatMap(found -> Verify.ensure(requestKey,
                                                             Verify.Is::notBlank,
                                                             AdmissionRefusal.INVALID_REQUEST_KEY).map(_ -> found));
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

            // Design-out: a create that failed or went unanswered within `bound` may still have been (or still be) applied, so its
            // outcome is never assumed; the keyed read-back decides. Guarantee: a retry of the same request never admits a second
            // run (its id is the same, and the applier refuses a run key that exists). The bound is armed on a DERIVED promise
            // (`map`), so a ledger that answers a shared promise does not have it failed for every subscriber.
            private Promise<WorkflowRunValue> create(WorkflowRunValue run, String requestKey) {
                return ledger.create(run)
                             .map(accepted -> accepted
                                              ? Created.ACCEPTED
                                              : Created.REFUSED)
                             .timeout(bound)
                             .recover(_ -> Created.UNANSWERED)
                             .flatMap(created -> created == Created.ACCEPTED
                                                 ? Promise.success(run)
                                                 : readBack(run, requestKey, created));
            }

            /// The run committed under this request's id decides; when there is none, the refusal is read back from the claims.
            private Promise<WorkflowRunValue> readBack(WorkflowRunValue run, String requestKey, Created created) {
                return ledger.run(run.id())
                             .fold(() -> notCreated(run, created),
                                   existing -> sameRequest(run, existing, requestKey));
            }

            private static Promise<WorkflowRunValue> sameRequest(WorkflowRunValue run,
                                                                 WorkflowRunValue existing,
                                                                 String requestKey) {
                return existing.kind()
                               .equals(run.kind()) && existing.params()
                                                              .equals(run.params())
                       ? Promise.success(existing)
                       : new AdmissionRefusal.RequestKeyReused(requestKey, run.id()).promise();
            }

            private Promise<WorkflowRunValue> notCreated(WorkflowRunValue run, Created created) {
                return created == Created.UNANSWERED
                       ? new AdmissionRefusal.Unanswered(run.id()).promise()
                       : refusal(run);
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

        return new admission(ledger, kinds, bound);
    }
}
