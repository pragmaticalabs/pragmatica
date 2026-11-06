// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.pragmatica.aether.slice.kvstore.AetherKey.WorkflowRunKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.WorkflowState;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.utility.warning.OperatorWarnings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// rc6 f2b §5: every node's view of the committed workflow runs, fed by the KV put/remove route of `WorkflowRunKey` (the same route
/// shape as the node replacement index: [#absorbPut] and [#absorbRemove]), and restored from a KV snapshot on state restore
/// ([#restore]). [WorkflowLedger#runs] reads it, so a reconciler tick costs the retained runs, not a copy of the whole store.
///
/// It is also the one place a run's transition is seen exactly as the cluster committed it, on every node: the listener gets
/// (before, after, replayed) for every applied put, which is where the operator events of a transition are derived ([#announcer]).
public final class WorkflowRunIndex {
    private static final Logger LOG = LoggerFactory.getLogger(WorkflowRunIndex.class);

    private volatile Map<String, WorkflowRunValue> runs = Map.of();

    private WorkflowRunIndex() {}

    public static WorkflowRunIndex workflowRunIndex() {
        return new WorkflowRunIndex();
    }

    /// Told of every committed run as it is applied, with the run it replaced. `replayed` is the replay bit the KV handler captured
    /// when it absorbed the put (KVStore dispatcher design §3.5 R4): a replayed run is committed history, not a transition.
    @FunctionalInterface
    public interface TransitionListener {
        Unit onTransition(Option<WorkflowRunValue> before, WorkflowRunValue after, boolean replayed);
    }

    private final AtomicReference<TransitionListener> listener = new AtomicReference<>((_, _, _) -> Unit.unit());

    /// Replaces the transition listener. It runs outside the index's lock.
    public Unit onTransition(TransitionListener transitionListener) {
        listener.set(transitionListener);

        return Unit.unit();
    }

    /// A run applied live (never in a replay burst).
    public Unit put(WorkflowRunValue value) {
        return put(value, false);
    }

    /// A run as the KV handler absorbed it, with the replay bit it captured on the delivering thread.
    public Unit put(WorkflowRunValue value, boolean replayed) {
        return listener.get()
                       .onTransition(swap(value),
                                     value,
                                     replayed);
    }

    private synchronized Option<WorkflowRunValue> swap(WorkflowRunValue value) {
        var before = Option.option(runs.get(value.id()));
        var updated = new HashMap<>(runs);

        updated.put(value.id(), value);
        runs = Map.copyOf(updated);

        return before;
    }

    /// A run deleted from the store. No event is raised: only a put is a transition.
    public synchronized Unit remove(String runId) {
        var updated = new HashMap<>(runs);

        updated.remove(runId);
        runs = Map.copyOf(updated);

        return Unit.unit();
    }

    /// Replace the whole view from a KV snapshot (state restore): any entry that is not a run is ignored. No event is raised: a
    /// restore is not a transition. The snapshot is already taken, so a put absorbed between taking it and this call is overwritten;
    /// a caller that can race live puts uses [#restoreFrom].
    public synchronized Unit restore(Map<?, ?> snapshot) {
        var updated = new HashMap<String, WorkflowRunValue>();

        snapshot.forEach((key, value) -> collectRun(updated, key, value));
        runs = Map.copyOf(updated);

        return Unit.unit();
    }

    /// As [#restore], but the snapshot is taken INSIDE the index's lock: a put absorbed meanwhile waits for the lock and is applied
    /// after the restore, so a restore never overwrites a newer put (v2803 F4).
    public synchronized Unit restoreFrom(Supplier<Map<?, ?>> snapshot) {
        return restore(snapshot.get());
    }

    private static Unit collectRun(Map<String, WorkflowRunValue> target, Object key, Object value) {
        if (key instanceof WorkflowRunKey && value instanceof WorkflowRunValue run) {
            target.put(run.id(), run);
        }

        return Unit.unit();
    }

    /// Every run in the view, oldest first (by start time, then by id).
    public List<WorkflowRunValue> runs() {
        return runs.values()
                   .stream()
                   .sorted(Comparator.comparingLong(WorkflowRunValue::startedAt).thenComparing(WorkflowRunValue::id))
                   .toList();
    }

    /// The KV handler of a committed run (the put route of `WorkflowRunKey`): captures the replay bit at absorb, on the delivering
    /// thread, and passes it with the run (design §3.5 R4); `isReplaying()` read anywhere else reads false.
    public static Unit absorbPut(WorkflowRunIndex index,
                                 BooleanSupplier replaying,
                                 ValuePut<WorkflowRunKey, WorkflowRunValue> put) {
        return index.put(put.cause().value(),
                         replaying.getAsBoolean());
    }

    /// The KV handler of a deleted run (the remove route of `WorkflowRunKey`).
    public static Unit absorbRemove(WorkflowRunIndex index, ValueRemove<WorkflowRunKey, WorkflowRunValue> remove) {
        return index.remove(remove.cause().key().id());
    }

    /// The listener that turns each committed run transition into its operator events, on the node that owns the cluster-events
    /// partition ONLY: every node applies every run, and the events of one transition must be raised once. A replayed run announces
    /// nothing: it is committed history, not a transition.
    ///
    /// - [OperatorWarningCode#WORKFLOW_ESCALATED] when `after` is `ESCALATED` and `before` was not; the message is the incident.
    /// - [OperatorWarningCode#WORKFLOW_RESOLVED] ONLY when `before` was `ESCALATED` and `after` is terminal (`DONE` or `FAILED`); the
    ///   message carries the [Outcome]. At most one per run per ESCALATED episode: the raise is derived from the applied (before, after)
    ///   pair, and the index holds the terminal run afterwards, so a redelivered terminal commit has a terminal `before` and raises nothing.
    /// - [OperatorWarningCode#WORKFLOW_RESUMED] (INFO, not a recovery: the incident stays open) when `before` was `ESCALATED` and `after`
    ///   is `RUNNING` or `WAITING`. A run that escalates again after a resume opens a new incident.
    ///
    /// Guarantee per operation. RAISE: at most once per owner tenure; an owner hand-off can duplicate or lose it. Mechanism: the raise is
    /// derived from the applied (before, after) pair, never from a commit's answer, so a slow or failed answer does not lose it. Within a
    /// tenure it is deduped by the owner gate (`ownsClusterEvents`), the replay bit, and the (before, after) pair looked up by run id (the
    /// index holds the new run afterwards, so a redelivered identical transition raises nothing). Across a hand-off the gate is the live
    /// placement of the events partition read at each node's own apply time, not at a log index: the old owner can apply before it gives
    /// up and the new one after it took over (two raises), or the new one before and the old one after (none); before the controller is
    /// bound the gate reads false everywhere (#957); an owner dying between apply and raise loses it. The committed run keeps the incident
    /// as its `detail` in every case. `[design intent, unverified]` on a live cluster.
    public static TransitionListener announcer(BooleanSupplier ownsClusterEvents, OperatorWarningSink warnings) {
        return (before, after, replayed) -> !replayed && ownsClusterEvents.getAsBoolean()
                                            ? announce(warnings, before, after)
                                            : Unit.unit();
    }

    private static Unit announce(OperatorWarningSink warnings,
                                 Option<WorkflowRunValue> before,
                                 WorkflowRunValue after) {
        var wasEscalated = before.filter(WorkflowRunIndex::escalated).isPresent();

        if (escalated(after) && !wasEscalated) {
            return OperatorWarnings.raise(LOG,
                                          warnings,
                                          OperatorWarningCode.WORKFLOW_ESCALATED,
                                          after.id(),
                                          "{}",
                                          after.detail());
        }

        return wasEscalated
               ? announceLeft(warnings, after)
               : Unit.unit();
    }

    private static Unit announceLeft(OperatorWarningSink warnings, WorkflowRunValue after) {
        return outcomeOf(after.state()).fold(() -> announceResume(warnings, after),
                                             outcome -> OperatorWarnings.raise(LOG,
                                                                               warnings,
                                                                               OperatorWarningCode.WORKFLOW_RESOLVED,
                                                                               after.id(),
                                                                               "Workflow {} left ESCALATED with outcome {}",
                                                                               after.id(),
                                                                               outcome));
    }

    private static Unit announceResume(OperatorWarningSink warnings, WorkflowRunValue after) {
        return after.state() == WorkflowState.RUNNING || after.state() == WorkflowState.WAITING
               ? OperatorWarnings.raise(LOG,
                                        warnings,
                                        OperatorWarningCode.WORKFLOW_RESUMED,
                                        after.id(),
                                        "Workflow {} was resumed from ESCALATED and is now {}",
                                        after.id(),
                                        after.state())
               : Unit.unit();
    }

    /// How an escalated run ended. `ABANDONED` is RESERVED: the ledger has no abandon operation yet, so a committed abandon is
    /// indistinguishable from a `FAILED` commit and is announced as `FAILED`; the lane that adds the abandon verb derives it.
    public enum Outcome {
        DONE,
        FAILED,
        ABANDONED
    }

    private static Option<Outcome> outcomeOf(WorkflowState state) {
        return switch (state) {
            case DONE -> Option.some(Outcome.DONE);
            case FAILED -> Option.some(Outcome.FAILED);
            default -> Option.none();
        };
    }

    private static boolean escalated(WorkflowRunValue run) {
        return run.state() == WorkflowState.ESCALATED;
    }
}
