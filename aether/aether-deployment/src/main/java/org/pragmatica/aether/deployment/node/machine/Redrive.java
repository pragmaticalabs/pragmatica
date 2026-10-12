// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;


/// R-6.0, the generalised #2148 rule: re-drive a remedial, retried or re-issue-triggered observed write from the committed
/// row and the committed goal, never forward over a teardown or terminal row.
sealed interface Redrive {
    /// Spec 6.3 steps 1 to 6 for the last write the node submitted and has not seen committed.
    static Transition redrive(NodeFsm fsm, Transition t, Artifact artifact, int cycle) {
        var rec = t.instance(artifact);

        return rec.unconfirmed()
                  .fold(() -> t,
                        unconfirmed -> decide(fsm, t, artifact, rec, unconfirmed, cycle));
    }

    private static Transition decide(NodeFsm fsm,
                                     Transition t,
                                     Artifact artifact,
                                     InstanceRecord rec,
                                     Unconfirmed unconfirmed,
                                     int cycle) {
        var current = Rows.current(fsm.config(), rec);
        var described = t.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.INFO,
                                                     remediation(fsm, artifact, rec, unconfirmed, current)));

        return current.filter(row -> adopts(row, unconfirmed))
                      .fold(() -> rebuild(fsm, described, artifact, rec, unconfirmed, cycle),
                            _ -> described.update(artifact,
                                                  r -> r.withUnconfirmed(none())));
    }

    /// Steps 3 and 4: the committed row is at or past the write, or it is a teardown or terminal row a forward write must
    /// never overwrite (a FAILED write is itself terminal and yields only to rows that are already teardown or terminal).
    private static boolean adopts(SliceObservedValue row, Unconfirmed unconfirmed) {
        return row.version() >= unconfirmed.version() || isTeardownOrTerminal(row.state());
    }

    static boolean isTeardownOrTerminal(SliceObservedState state) {
        return switch (state) {
            case DEACTIVATING, FAILED, UNLOADING, UNLOADED, UNKNOWN -> true;
            case LOADING, LOADED, ACTIVATING, ACTIVE -> false;
        };
    }

    /// Step 5: the row is earlier in the chain and the goal still asks (a FAILED row is owed whatever the goal says).
    private static Transition rebuild(NodeFsm fsm,
                                      Transition t,
                                      Artifact artifact,
                                      InstanceRecord rec,
                                      Unconfirmed unconfirmed,
                                      int cycle) {
        if (unconfirmed.state() == SliceObservedState.FAILED) {
            return unconfirmed.failure()
                              .fold(() -> t,
                                    note -> Writes.issueFailed(fsm, t, artifact, note, cycle));
        }

        return rec.desired()
                  .fold(() -> t,
                        desired -> rebuildForward(fsm, t, artifact, desired, unconfirmed));
    }

    private static Transition rebuildForward(NodeFsm fsm,
                                             Transition t,
                                             Artifact artifact,
                                             SliceDesiredValue desired,
                                             Unconfirmed unconfirmed) {
        var steps = unconfirmed.state() == SliceObservedState.ACTIVE
                    ? Plans.redriveActiveSteps()
                    : List.of(Chain.write(unconfirmed.state()));
        var kind = unconfirmed.state() == SliceObservedState.ACTIVE
                   ? Chain.ChainKind.REDRIVE_ACTIVE
                   : Chain.ChainKind.SINGLE_WRITE;

        return ! t.model()
                  .permits(artifact)
               ? Planner.park(fsm, t, artifact, desired)
               : Chains.start(fsm, t, artifact, kind, generationOf(desired), steps);
    }

    private static Generation generationOf(SliceDesiredValue desired) {
        return desired.generation();
    }

    /// Step 6: the line names the node, the goal, the local state and the committed state.
    private static String remediation(NodeFsm fsm,
                                      Artifact artifact,
                                      InstanceRecord rec,
                                      Unconfirmed unconfirmed,
                                      Option<SliceObservedValue> current) {
        return "Node " + fsm.config()
                            .self()
                            .id()
             + " re-drives " + unconfirmed.state()
             + " of " + artifact
             + ": goal " + rec.desired()
                              .map(SliceDesiredValue::goal)
             + ", local write " + unconfirmed.state()
             + " at version " + unconfirmed.version()
             + ", committed " + current.map(row -> row.state() + " at version " + row.version());
    }

    /// N7, N9: 90 s after a committed ACTIVATING row, if it is still the committed row and nothing is in flight: serving
    /// means R-6.0 rebuilds the ACTIVE write; not serving warns and writes nothing (B-136).
    static Transition activatingCheck(NodeFsm fsm, Transition t, TimerId timer) {
        var artifact = timer.artifact();
        var rec = t.instance(artifact);

        return rec.observed()
                  .filter(row -> row.state() == SliceObservedState.ACTIVATING && row.version() == timer.token())
                  .filter(_ -> rec.isIdle())
                  .fold(() -> Transition.transition(t.model()).ignoring(),
                        row -> checkServing(fsm, t, artifact, rec, row));
    }

    private static Transition checkServing(NodeFsm fsm,
                                           Transition t,
                                           Artifact artifact,
                                           InstanceRecord rec,
                                           SliceObservedValue row) {
        if (!rec.serving()) {
            return t.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN, stuck(fsm, artifact, rec, row)));
        }

        return rec.desired()
                  .filter(desired -> desired.goal() == SliceGoal.ACTIVE)
                  .fold(() -> t,
                        desired -> rebuildForward(fsm,
                                                  t.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.INFO,
                                                                               stuck(fsm, artifact, rec, row))),
                                                  artifact,
                                                  desired,
                                                  Unconfirmed.unconfirmed(SliceObservedState.ACTIVE,
                                                                          row.version() + 1L,
                                                                          none())));
    }

    private static String stuck(NodeFsm fsm, Artifact artifact, InstanceRecord rec, SliceObservedValue row) {
        return "Node " + fsm.config()
                            .self()
                            .id()
             + " still ACTIVATING " + artifact
             + " after the check: goal " + rec.desired()
                                              .map(SliceDesiredValue::goal)
             + ", serving " + rec.serving()
             + ", committed " + row.state()
             + " at version " + row.version();
    }

    record unused() implements Redrive {}
}
