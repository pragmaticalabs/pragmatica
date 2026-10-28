// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.Comparator;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Handling the KV notifications (spec NI-2 to NI-5): the node's own desired and observed keys, the replica index of other
/// nodes' observed keys, and the committed targets and routings the start gate reads.
sealed interface Observations {
    // ---------------------------------------------------------------- own desired key (NI-2)
    /// A goal arrived. A newer goal ends a park and cancels a pending tombstone Remove; a goal that arrives during a live
    /// chain is recorded and re-evaluated when the chain ends (R-3.4.2).
    static Transition desiredPut(NodeFsm fsm, Transition t, Artifact artifact, SliceDesiredValue value) {
        var replaced = replacesPark(t.instance(artifact), value)
                       ? Planner.unpark(t, artifact, "a newer goal replaced it")
                       : t;
        var recorded = replaced.update(artifact, rec -> rec.withDesired(some(value)));
        var cancelled = value.goal() == SliceGoal.UNLOADED
                        ? recorded
                        : Planner.cancelTombstone(recorded, artifact);

        return Planner.reconcile(fsm, cancelled, artifact);
    }

    private static boolean replacesPark(InstanceRecord rec, SliceDesiredValue value) {
        return rec.parked()
                  .filter(parked -> !parked.matches(value))
                  .isPresent();
    }

    /// N17: the goal is gone. Whatever the node still holds for the pair is cleaned up locally, and nothing is written: the
    /// leader owns the row (R-1.6.4). The chain, if any, is dropped (its running step's token dies with it).
    static Transition desiredRemove(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);
        var unparked = Planner.unpark(t, artifact, "the goal was removed");
        var forced = rec.chain().isPresent() || t.model().isLoaded(artifact)
                     ? unparked.emit(new NodeFsmEffect.LocalOp(t.incarnation(),
                                                               0L,
                                                               some(artifact),
                                                               LocalStep.FORCED_CLEANUP,
                                                               0L,
                                                               none()))
                     : unparked;
        var cleared = forced.update(artifact,
                                    r -> r.withDesired(none())
                                          .withDesiredPending(false)
                                          .withChain(none())
                                          .withDeferredFailure(none())
                                          .withUnconfirmed(none())
                                          .withServing(false)
                                          .withSuspended(false))
                            .loaded(artifact, false);

        return Planner.armTombstone(fsm, cleared, artifact);
    }

    // ---------------------------------------------------------------- observed keys (NI-3)
    static Transition observedPut(NodeFsm fsm, Transition t, NodeFsmInput.ObservedPut put) {
        return put.node()
                  .equals(fsm.config().self())
               ? ownObservedPut(fsm, t, put.artifact(), put.value())
               : t.withModel(t.model()
                              .withReplica(put.artifact(),
                                           put.node(),
                                           PeerRow.peerRow(put.value(),
                                                           put.knownToken())));
    }

    static Transition observedRemove(NodeFsm fsm, Transition t, NodeFsmInput.ObservedRemove remove) {
        return remove.node()
                     .equals(fsm.config().self())
               ? ownObservedRemove(fsm, t, remove.artifact())
               : t.withModel(t.model().withoutReplica(remove.artifact(), remove.node()));
    }

    private static Transition ownObservedPut(NodeFsm fsm, Transition t, Artifact artifact, SliceObservedValue value) {
        var rec = t.instance(artifact);

        return supersededBy(rec, value)
               ? Transition.transition(t.model()).ignoring()
               : afterOwnPut(fsm,
                             announce(fsm, mirror(t, artifact, value), artifact, previousState(rec, value), value),
                             artifact,
                             value);
    }

    /// A notification no newer than the mirror: an older row, or the same row again.
    private static boolean supersededBy(InstanceRecord rec, SliceObservedValue value) {
        return rec.observed()
                  .filter(known -> known.version() > value.version() || known.equals(value))
                  .isPresent();
    }

    private static SliceObservedState previousState(InstanceRecord rec, SliceObservedValue value) {
        return rec.observed()
                  .map(SliceObservedValue::state)
                  .or(value.state());
    }

    private static Transition mirror(Transition t, Artifact artifact, SliceObservedValue value) {
        return t.update(artifact, rec -> mirrored(rec, value));
    }

    private static InstanceRecord mirrored(InstanceRecord rec, SliceObservedValue value) {
        return rec.withObserved(some(value))
                  .withLastVersion(Math.max(rec.lastVersion(),
                                            value.version()))
                  .withUnconfirmed(rec.unconfirmed().filter(unconfirmed -> unconfirmed.version() > value.version()));
    }

    /// Routes the transition (B-118), the failure with its real reason (N15), the completion once per entry into ACTIVE (N10),
    /// and arms the 90 s check on a committed ACTIVATING row (N7).
    private static Transition announce(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       SliceObservedState previous,
                                       SliceObservedValue value) {
        var routed = t.emit(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.StateTransitioned(artifact,
                                                                                                    previous,
                                                                                                    value.state(),
                                                                                                    fsm.now())));

        return switch (value.state()) {
            case FAILED -> routed.emit(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.DeploymentFailed(artifact,
                                                                                                              previous,
                                                                                                              value.failureReason().or("Unknown failure"),
                                                                                                              fsm.now())));
            case ACTIVE -> previous != SliceObservedState.ACTIVE && t.model().isLoaded(artifact)
                           ? routed.emit(new NodeFsmEffect.Route(new NodeFsmEffect.RoutedEvent.DeploymentCompleted(artifact,
                                                                                                                   fsm.now())))
                           : routed;
            case ACTIVATING -> routed.emit(new NodeFsmEffect.Schedule(TimerId.timerId(TimerId.TimerKind.ACTIVATING_CHECK,
                                                                                      artifact,
                                                                                      t.incarnation(),
                                                                                      value.version()),
                                                                      fsm.config().timings().activatingCheckMs()));
            default -> routed;
        };
    }

    /// The put is the committed answer to the write in flight, a supersession of it, or news with no write waiting.
    private static Transition afterOwnPut(NodeFsm fsm, Transition t, Artifact artifact, SliceObservedValue value) {
        return t.instance(artifact)
                .write()
                .fold(() -> Planner.reconcile(fsm, t, artifact),
                      write -> answered(fsm, t, artifact, value, write));
    }

    private static Transition answered(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       SliceObservedValue value,
                                       InFlight write) {
        if (write.purpose() == InFlight.Purpose.TOMBSTONE_REMOVE || value.version() < write.version()) {
            return t;
        }

        return value.version() == write.version() && value.state() == write.state()
               ? Writes.confirmed(fsm, t, artifact)
               : superseded(fsm, t, artifact, write);
    }

    /// A row at the write's version that is not the write: someone else's commit won. The chain is dropped and the pair is
    /// evaluated again from the mirror.
    private static Transition superseded(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        var cleared = Writes.cancelTimers(t.update(artifact,
                                                   rec -> rec.withWrite(none())
                                                             .withChain(none())),
                                          artifact,
                                          write);

        return Planner.reconcile(fsm, cleared, artifact);
    }

    private static Transition ownObservedRemove(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);
        var cleared = t.update(artifact,
                               r -> r.withObserved(none())
                                     .withLastVersion(0L)
                                     .withUnconfirmed(none())
                                     .withTombstoneArmed(0L));

        return rec.write()
                  .filter(write -> write.purpose() == InFlight.Purpose.TOMBSTONE_REMOVE)
                  .fold(() -> Planner.reconcile(fsm, cleared, artifact),
                        write -> removed(fsm, cleared, artifact, write));
    }

    /// The tombstone Remove committed: the write is done, and a goal recorded behind it is evaluated now (R-3.4.2).
    private static Transition removed(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        var cleared = Writes.cancelTimers(t.update(artifact, rec -> rec.withWrite(none())),
                                          artifact,
                                          write);

        return Writes.settle(fsm, cleared, artifact);
    }

    // ---------------------------------------------------------------- targets and routings (NI-4)
    static Transition targetPut(NodeFsm fsm, Transition t, ArtifactBase base, Version version) {
        return redriveParked(fsm,
                             t.withModel(t.model().withTarget(base, version)),
                             base);
    }

    static Transition targetRemove(Transition t, ArtifactBase base) {
        return t.withModel(t.model().withoutTarget(base));
    }

    static Transition routingPut(NodeFsm fsm, Transition t, NodeFsmInput.RoutingPut put) {
        return redriveParked(fsm,
                             t.withModel(t.model()
                                          .withRouting(put.base(),
                                                       RoutingEntry.routingEntry(put.oldVersion(),
                                                                                 put.newVersion()))),
                             put.base());
    }

    static Transition routingRemove(Transition t, ArtifactBase base) {
        return t.withModel(t.model().withoutRouting(base));
    }

    /// A committed target or routing for `base` arrived: every start parked for that base goes through the gate again. Still
    /// refused, it stays parked with its original `since`.
    private static Transition redriveParked(NodeFsm fsm, Transition t, ArtifactBase base) {
        var parked = t.model()
                      .instances()
                      .keySet()
                      .stream()
                      .filter(artifact -> isParkedFor(t, artifact, base))
                      .sorted(Comparator.comparing(Artifact::asString))
                      .toList();

        return t.each(parked, (current, artifact) -> Planner.reconcile(fsm, current, artifact));
    }

    private static boolean isParkedFor(Transition t, Artifact artifact, ArtifactBase base) {
        return base.matches(artifact) && t.instance(artifact)
                                          .parked()
                                          .isPresent();
    }

    // ---------------------------------------------------------------- config (NI-5)
    static Transition configChanged(Transition t, String key) {
        return t.emit(new NodeFsmEffect.LocalOp(t.incarnation(), 0L, none(), LocalStep.NOTIFY_CONFIG, 0L, some(key)));
    }

    record unused() implements Observations {}
}
