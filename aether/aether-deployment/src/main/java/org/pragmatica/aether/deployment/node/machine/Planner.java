// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.lang.Cause;
import org.pragmatica.utility.warning.OperatorWarningCode;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Evaluating one pair (spec R-3.4.2, R-3.4.3): when the key is idle, compare the goal with the committed row and the local
/// store, and start the chain that closes the gap, park the start, or leave the pair alone. While a chain or a write is live,
/// a changed goal is only recorded and re-evaluated when the work ends (R-3.4.2).
sealed interface Planner {
    static Transition reconcile(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);

        if (!t.model().active()) {
            return t;
        }

        if (!rec.isIdle()) {
            return t.update(artifact, r -> r.withDesiredPending(true));
        }

        return rec.desired()
                  .fold(() -> t,
                        desired -> evaluate(fsm, t, artifact, rec, desired));
    }

    private static Transition evaluate(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       InstanceRecord rec,
                                       SliceDesiredValue desired) {
        return lagging(rec, desired)
               ? Redrive.redrive(fsm, t, artifact, 0)
               : enact(fsm,
                       t,
                       artifact,
                       desired,
                       Plans.decide(fsm, t.model(), artifact, rec, desired));
    }

    /// A re-issue of the generation already executed, while the committed row still lags the write the node submitted:
    /// R-6.0 instead of a new chain (spec R-3.4.2).
    private static boolean lagging(InstanceRecord rec, SliceDesiredValue desired) {
        return rec.unconfirmed()
                  .isPresent() && rec.chainGeneration()
                                     .filter(desired.generation()::equals)
                                     .isPresent();
    }

    private static Transition enact(NodeFsm fsm,
                                    Transition t,
                                    Artifact artifact,
                                    SliceDesiredValue desired,
                                    Plan plan) {
        var permitted = t.model().permits(artifact);

        return switch (plan) {
            case Plan.Settled _ -> settle(fsm, t, artifact);
            case Plan.Unknown _ -> t.emit(unknownLine(artifact));
            case Plan.Fail fail -> permitted
                                   ? failNow(fsm, t, artifact, desired, fail.cause())
                                   : park(fsm, t, artifact, desired);
            case Plan.Run run -> run.gated() && !permitted
                                 ? park(fsm, t, artifact, desired)
                                 : Chains.start(fsm, t, artifact, run.kind(), desired.generation(), run.steps());
        };
    }

    private static NodeFsmEffect unknownLine(Artifact artifact) {
        return new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                     "Observed state of " + artifact + " is UNKNOWN: no transition is driven");
    }

    /// The goal needs nothing more. `appliedGeneration` is not touched: only N0's same-process branch or a completed chain sets
    /// it (spec 3.4).
    private static Transition settle(NodeFsm fsm, Transition t, Artifact artifact) {
        return armTombstone(fsm, unpark(t, artifact, "the goal is already met"), artifact);
    }

    private static Transition failNow(NodeFsm fsm,
                                      Transition t,
                                      Artifact artifact,
                                      SliceDesiredValue desired,
                                      Cause cause) {
        var note = FailureNote.failureNote(cause.message(),
                                           SliceLoadingFailure.classify(cause, Unrecognised.RETRY).isFatal(),
                                           desired.generation());

        return Chains.failWith(fsm, t, artifact, note);
    }

    // ---------------------------------------------------------------- parking (N2)
    /// N2: a start the committed target does not permit is parked, never dropped; an identical re-delivery keeps the park and
    /// its `since` (B-155, B-159 changed).
    static Transition park(NodeFsm fsm, Transition t, Artifact artifact, SliceDesiredValue desired) {
        return t.instance(artifact)
                .parked()
                .filter(parked -> parked.matches(desired))
                .fold(() -> parkFresh(fsm,
                                      unpark(t, artifact, "a newer goal replaced it"),
                                      artifact,
                                      desired),
                      _ -> t);
    }

    private static Transition parkFresh(NodeFsm fsm, Transition t, Artifact artifact, SliceDesiredValue desired) {
        var since = fsm.now();
        var timer = TimerId.timerId(TimerId.TimerKind.PARKED_ESCALATION, artifact, t.incarnation(), since);

        return t.update(artifact,
                        rec -> rec.withParked(some(Parked.parked(since,
                                                                 desired.goal(),
                                                                 desired.generation()))))
                .emit(new NodeFsmEffect.Schedule(timer,
                                                 fsm.config().timings().parkedBoundMs()))
                .emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                            "Node " + fsm.config()
                                                         .self()
                                                         .id()
                                           + " defers the " + desired.goal()
                                           + " of " + artifact
                                           + ": no committed SliceTarget names this version; re-evaluated when a target arrives"));
    }

    /// Ends a park (target arrived, goal superseded or removed). The recovery event is raised only if the warning was.
    static Transition unpark(Transition t, Artifact artifact, String why) {
        return t.instance(artifact)
                .parked()
                .fold(() -> t,
                      parked -> ended(t, artifact, parked, why));
    }

    private static Transition ended(Transition t, Artifact artifact, Parked parked, String why) {
        var cleared = t.update(artifact, rec -> rec.withParked(none()));

        return parked.warned()
               ? cleared.emit(new NodeFsmEffect.WarnResolved(OperatorWarningCode.SLICE_START_DEFERRAL_ENDED.code(),
                                                             artifact.asString(),
                                                             "The deferred " + parked.goal()
                                                            + " of " + artifact
                                                            + " ended: " + why))
               : cleared;
    }

    /// Fires `parkedStartBound` after the first refusal: raises `slice-start-awaiting-target` once, leaves the park alone.
    static Transition escalate(NodeFsm fsm, Transition t, TimerId timer) {
        var artifact = timer.artifact();

        return t.instance(artifact)
                .parked()
                .filter(parked -> parked.since() == timer.token() && !parked.warned())
                .filter(parked -> fsm.now() - parked.since() >= fsm.config()
                                                                   .timings()
                                                                   .parkedBoundMs())
                .fold(() -> Transition.transition(t.model()).ignoring(),
                      parked -> raised(fsm, t, artifact, parked));
    }

    private static Transition raised(NodeFsm fsm, Transition t, Artifact artifact, Parked parked) {
        return t.update(artifact,
                        rec -> rec.withParked(some(parked.asWarned())))
                .emit(new NodeFsmEffect.Warn(OperatorWarningCode.SLICE_START_AWAITING_TARGET.code(),
                                             artifact.asString(),
                                             "Node " + fsm.config()
                                                          .self()
                                                          .id()
                                            + " has held the " + parked.goal()
                                            + " of " + artifact
                                            + " for " + (fsm.now() - parked.since())
                                            + " ms: no committed SliceTarget names this version"));
    }

    // ---------------------------------------------------------------- tombstone (R-1.6.3)
    /// A committed tombstone with no newer goal is Removed by the node after the grace. Arms the timer once per tombstone.
    static Transition armTombstone(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);
        var tombstone = Rows.current(fsm.config(), rec).filter(row -> row.state() == SliceObservedState.UNLOADED);

        return tombstone.filter(row -> row.version() != rec.tombstoneArmed() && allowsRemove(rec))
                        .fold(() -> t,
                              row -> t.update(artifact,
                                              r -> r.withTombstoneArmed(row.version()))
                                      .emit(new NodeFsmEffect.Schedule(tombstoneTimer(t,
                                                                                      artifact,
                                                                                      row.version()),
                                                                       fsm.config().timings().tombstoneGraceMs())));
    }

    /// No goal, or the UNLOADED goal: nothing newer asks for the pair (R-1.6.3).
    static boolean allowsRemove(InstanceRecord rec) {
        return rec.desired()
                  .filter(desired -> desired.goal() != SliceGoal.UNLOADED)
                  .isEmpty();
    }

    private static TimerId tombstoneTimer(Transition t, Artifact artifact, long version) {
        return TimerId.timerId(TimerId.TimerKind.TOMBSTONE_GRACE, artifact, t.incarnation(), version);
    }

    /// A newer goal cancels the pending Remove.
    static Transition cancelTombstone(Transition t, Artifact artifact) {
        var armed = t.instance(artifact).tombstoneArmed();

        return armed == 0L
               ? t
               : t.update(artifact,
                          rec -> rec.withTombstoneArmed(0L))
                  .emit(new NodeFsmEffect.Cancel(tombstoneTimer(t, artifact, armed)));
    }

    /// The tombstone grace passed: Remove the node's own tombstone, witnessed by its exact value, only with no goal asking
    /// otherwise and no own write in flight on the key (R-3.4.9 (2)).
    static Transition tombstoneExpired(NodeFsm fsm, Transition t, TimerId timer) {
        var artifact = timer.artifact();
        var rec = t.instance(artifact);

        return Rows.current(fsm.config(),
                            rec)
                   .filter(row -> row.state() == SliceObservedState.UNLOADED && row.version() == timer.token())
                   .filter(_ -> rec.isIdle() && allowsRemove(rec))
                   .fold(() -> Transition.transition(t.model()).ignoring(),
                         row -> Writes.submitTombstoneRemove(fsm,
                                                             t.update(artifact,
                                                                      r -> r.withTombstoneArmed(0L)),
                                                             artifact,
                                                             row));
    }

    /// The chain ended: arm the tombstone timer if it ended in a tombstone, then settle whatever waited.
    static Transition afterChain(NodeFsm fsm, Transition t, Artifact artifact) {
        return Writes.settle(fsm, armTombstone(fsm, t, artifact), artifact);
    }

    record unused() implements Planner {}
}
