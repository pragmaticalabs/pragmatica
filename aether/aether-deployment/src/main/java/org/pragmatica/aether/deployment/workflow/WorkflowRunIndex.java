// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.workflow;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /// The announcer that turns each committed run transition into its operator events, on the node that owns the cluster-events
    /// partition ONLY: every node applies every run, and the events of one transition must be raised once. A replayed run announces
    /// nothing: it is committed history, not a transition. See [Announcer] for the events, the open-incident set and the seeding.
    public static Announcer announcer(WorkflowRunIndex index, BooleanSupplier ownsClusterEvents, OperatorWarningSink warnings) {
        return new Announcer(index, ownsClusterEvents, warnings);
    }

    /// The transition listener of [#announcer]. It keeps `openIncidents`, the run ids whose incident is open, on EVERY node (a replayed
    /// or non-owner put updates it and raises nothing), so a node that takes the cluster-events partition over already knows them.
    ///
    /// - [OperatorWarningCode#WORKFLOW_ESCALATED] when `after` is `ESCALATED` and the run id has no open incident. Re-escalating a run
    ///   whose incident is still open (a resume leaves it open) raises nothing: dedupe by run id (CTO ruling Q4, v2803c F1).
    /// - [OperatorWarningCode#WORKFLOW_RESOLVED] when the run id has an open incident and `after` is terminal (`DONE` or `FAILED`); the
    ///   message carries the [Outcome], `ABANDONED` when [WorkflowRunValue#isAbandoned] holds. It removes the id, so a redelivered
    ///   terminal commit raises nothing.
    /// - [OperatorWarningCode#WORKFLOW_RESUMED] (INFO, not a recovery: the incident stays open) when the run id has an open incident and
    ///   `after` is `RUNNING` or `WAITING`. Unreachable in f2b (no resume verb); tested by committing a `resumed(...)` value directly.
    ///
    /// Seeding (N13, v2803c F2). [#reseed] rebuilds `openIncidents` from the COMMITTED `ESCALATED` runs in the index. The owner wiring
    /// calls it when this node starts to own the events partition, on every owner change, and after a state restore (a restore applies
    /// no listener call, so a run that escalated before the snapshot was never seen). Without it a new owner would resolve nothing.
    /// It re-derives a RESOLVED whose terminal commit arrives after the takeover; a terminal commit applied BEFORE the takeover by a node
    /// that never raised its RESOLVED is not recoverable from committed state (the run no longer records that it escalated), and its
    /// ESCALATED stays open until the operator closes it.
    ///
    /// Guarantee per operation. RAISE: at most once per owner tenure; an owner hand-off can duplicate or lose it. Mechanism: the raise is
    /// derived from the applied run and the open-incident set, never from a commit's answer, so a slow or failed answer does not lose
    /// it. Within a tenure it is deduped by the owner gate (`ownsClusterEvents`), the replay bit, and the set (looked up by run id).
    /// Across a hand-off the gate is the live placement of the events partition read at each node's own apply time, not at a log
    /// index: the old owner can apply before it gives up and the new one after it took over (two raises, unless the new owner's set
    /// already holds the id, which it does when it saw the escalation), or the new one before and the old one after (none); before the
    /// controller is bound the gate reads false everywhere (#957); an owner dying between apply and raise loses it. The committed run
    /// keeps the incident as its `detail` in every case. `[design intent, unverified]` on a live cluster.
    public static final class Announcer implements TransitionListener {
        private final WorkflowRunIndex index;
        private final BooleanSupplier ownsClusterEvents;
        private final OperatorWarningSink warnings;
        private final Set<String> openIncidents = new HashSet<>();

        private Announcer(WorkflowRunIndex index, BooleanSupplier ownsClusterEvents, OperatorWarningSink warnings) {
            this.index = index;
            this.ownsClusterEvents = ownsClusterEvents;
            this.warnings = warnings;
        }

        @Override
        public Unit onTransition(Option<WorkflowRunValue> before, WorkflowRunValue after, boolean replayed) {
            var step = track(after);

            return !replayed && ownsClusterEvents.getAsBoolean()
                   ? raise(step, after)
                   : Unit.unit();
        }

        /// Rebuild the open incidents from the committed `ESCALATED` runs in the index (the seeding, N13).
        public synchronized Unit reseed() {
            openIncidents.clear();
            index.runs()
                 .stream()
                 .filter(WorkflowRunIndex::escalated)
                 .forEach(run -> openIncidents.add(run.id()));

            return Unit.unit();
        }

        private synchronized Step track(WorkflowRunValue after) {
            return switch (after.state()) {
                case ESCALATED -> openIncidents.add(after.id()) ? Step.ESCALATE : Step.NONE;
                case DONE, FAILED -> openIncidents.remove(after.id()) ? Step.RESOLVE : Step.NONE;
                case RUNNING, WAITING -> openIncidents.contains(after.id()) ? Step.RESUME : Step.NONE;
                default -> Step.NONE;
            };
        }

        private Unit raise(Step step, WorkflowRunValue after) {
            return switch (step) {
                case ESCALATE -> OperatorWarnings.raise(LOG,
                                                        warnings,
                                                        OperatorWarningCode.WORKFLOW_ESCALATED,
                                                        after.id(),
                                                        "{}",
                                                        after.detail());
                case RESOLVE -> OperatorWarnings.raise(LOG,
                                                       warnings,
                                                       OperatorWarningCode.WORKFLOW_RESOLVED,
                                                       after.id(),
                                                       "Workflow {} left ESCALATED with outcome {}",
                                                       after.id(),
                                                       outcomeOf(after));
                case RESUME -> OperatorWarnings.raise(LOG,
                                                      warnings,
                                                      OperatorWarningCode.WORKFLOW_RESUMED,
                                                      after.id(),
                                                      "Workflow {} was resumed from ESCALATED and is now {}",
                                                      after.id(),
                                                      after.state());
                case NONE -> Unit.unit();
            };
        }
    }

    private enum Step {
        NONE,
        ESCALATE,
        RESOLVE,
        RESUME
    }

    /// How an escalated run ended. `ABANDONED` is derived from the run's own terminal record ([WorkflowRunValue#isAbandoned]); no
    /// production verb commits one until L1b's abandon.
    public enum Outcome {
        DONE,
        FAILED,
        ABANDONED
    }

    private static Outcome outcomeOf(WorkflowRunValue terminal) {
        return terminal.isAbandoned()
               ? Outcome.ABANDONED
               : terminal.state() == WorkflowState.DONE
                 ? Outcome.DONE
                 : Outcome.FAILED;
    }

    private static boolean escalated(WorkflowRunValue run) {
        return run.state() == WorkflowState.ESCALATED;
    }
}
