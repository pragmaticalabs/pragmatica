// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The node's observed writes (spec R-3.4.4, R-3.4.9, N3, N19): one version-fenced write in flight per key, confirmed by
/// the node's own notification, retried unchanged up to the attempt bound, and a FAILED row retried in spaced cycles.
sealed interface Writes {
    /// "W s" as a step of the live chain. The chain keeps its remaining steps; the write waits for its confirmation.
    static Transition submitChain(NodeFsm fsm, Transition t, Artifact artifact, SliceObservedState state) {
        var rec = t.instance(artifact);

        return rec.chain()
                  .fold(() -> t,
                        chain -> submitStep(fsm,
                                            t,
                                            artifact,
                                            chain,
                                            state,
                                            rec.lastVersion() + 1L));
    }

    private static Transition submitStep(NodeFsm fsm,
                                         Transition t,
                                         Artifact artifact,
                                         Chain chain,
                                         SliceObservedState state,
                                         long version) {
        var value = rowFor(fsm, chain, state, version);
        var commands = Batches.chainBatch(fsm, t.model(), artifact, chain, value);

        return submit(fsm,
                      t.update(artifact,
                               rec -> rec.withChain(some(chain.withoutHead().flushed()))),
                      artifact,
                      value,
                      commands,
                      InFlight.Purpose.CHAIN,
                      0,
                      none());
    }

    private static SliceObservedValue rowFor(NodeFsm fsm, Chain chain, SliceObservedState state, long version) {
        return state == SliceObservedState.ACTIVE
               ? Batches.active(fsm, version, chain.generation(), chain.methods())
               : Batches.observed(fsm, state, version, chain.generation());
    }

    /// A FAILED row at the next version (N3, N5, N6, N14). `cycle` counts the spaced retries of N19.
    static Transition issueFailed(NodeFsm fsm, Transition t, Artifact artifact, FailureNote note, int cycle) {
        var version = t.instance(artifact).lastVersion() + 1L;
        var value = Batches.failed(fsm, version, note);

        return submit(fsm,
                      t,
                      artifact,
                      value,
                      List.of(Batches.put(fsm, artifact, value)),
                      InFlight.Purpose.FAILED,
                      cycle,
                      some(note));
    }

    private static Transition submit(NodeFsm fsm,
                                     Transition t,
                                     Artifact artifact,
                                     SliceObservedValue value,
                                     List<KVCommand<AetherKey>> commands,
                                     InFlight.Purpose purpose,
                                     int cycle,
                                     Option<FailureNote> failure) {
        return t.issuing((next, correlation) -> submitWith(fsm,
                                                           next,
                                                           artifact,
                                                           InFlight.inFlight(correlation,
                                                                             value.version(),
                                                                             value.state(),
                                                                             commands,
                                                                             purpose,
                                                                             cycle),
                                                           Unconfirmed.unconfirmed(value.state(),
                                                                                   value.version(),
                                                                                   failure)));
    }

    private static Transition submitWith(NodeFsm fsm,
                                         Transition t,
                                         Artifact artifact,
                                         InFlight write,
                                         Unconfirmed unconfirmed) {
        return t.update(artifact,
                        rec -> rec.withWrite(some(write))
                                  .withUnconfirmed(some(unconfirmed)))
                .emit(new NodeFsmEffect.Submit(t.incarnation(),
                                               artifact,
                                               write.correlation(),
                                               1,
                                               write.commands()))
                .emit(confirmTimer(fsm,
                                   t,
                                   artifact,
                                   write.correlation()));
    }

    /// The witnessed Remove of the node's own tombstone (R-1.6.3): the exact committed value is the witness.
    static Transition submitTombstoneRemove(NodeFsm fsm,
                                            Transition t,
                                            Artifact artifact,
                                            SliceObservedValue tombstone) {
        var remove = new KVCommand.Remove<AetherKey>(Batches.observedKey(fsm, artifact), Option.<Object> some(tombstone));

        return t.issuing((next, correlation) -> submitRemoveWith(fsm,
                                                                 next,
                                                                 artifact,
                                                                 InFlight.inFlight(correlation,
                                                                                   tombstone.version(),
                                                                                   SliceObservedState.UNLOADED,
                                                                                   List.of(remove),
                                                                                   InFlight.Purpose.TOMBSTONE_REMOVE,
                                                                                   0)));
    }

    private static Transition submitRemoveWith(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return t.update(artifact,
                        rec -> rec.withWrite(some(write)))
                .emit(new NodeFsmEffect.Submit(t.incarnation(),
                                               artifact,
                                               write.correlation(),
                                               1,
                                               write.commands()))
                .emit(confirmTimer(fsm,
                                   t,
                                   artifact,
                                   write.correlation()));
    }

    private static NodeFsmEffect confirmTimer(NodeFsm fsm, Transition t, Artifact artifact, long correlation) {
        return new NodeFsmEffect.Schedule(writeTimer(t, artifact, correlation),
                                          fsm.config().timings().writeAttemptMs());
    }

    private static TimerId writeTimer(Transition t, Artifact artifact, long correlation) {
        return TimerId.timerId(TimerId.TimerKind.WRITE_CONFIRM, artifact, t.incarnation(), correlation);
    }

    /// One attempt did not confirm: its timer ran out or consensus reported a failure. A late apply the notification path
    /// missed counts as confirmation; otherwise the same version-fenced write is resubmitted, up to the attempt bound.
    static Transition attemptFailed(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);

        return rec.write()
                  .fold(() -> t,
                        write -> judgeAttempt(fsm, t, artifact, rec, write));
    }

    private static Transition judgeAttempt(NodeFsm fsm,
                                           Transition t,
                                           Artifact artifact,
                                           InstanceRecord rec,
                                           InFlight write) {
        if (appliedAlready(rec, write)) {
            return confirmed(fsm, t, artifact);
        }

        return write.attempt() < fsm.config()
                                    .timings()
                                    .writeAttempts()
               ? retry(fsm, t, artifact, write)
               : exhausted(fsm, t, artifact, write);
    }

    private static boolean appliedAlready(InstanceRecord rec, InFlight write) {
        return write.purpose() == InFlight.Purpose.TOMBSTONE_REMOVE
               ? rec.observed()
                    .isEmpty()
               : hasRowAtOrPast(rec, write.version());
    }

    private static boolean hasRowAtOrPast(InstanceRecord rec, long version) {
        return rec.observed()
                  .filter(row -> row.version() >= version)
                  .isPresent();
    }

    private static Transition retry(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return t.emit(new NodeFsmEffect.Cancel(writeTimer(t,
                                                          artifact,
                                                          write.correlation())))
                .issuing((next, correlation) -> resubmit(fsm,
                                                         next,
                                                         artifact,
                                                         write.retried(correlation)));
    }

    private static Transition resubmit(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return t.update(artifact,
                        rec -> rec.withWrite(some(write)))
                .emit(new NodeFsmEffect.Submit(t.incarnation(),
                                               artifact,
                                               write.correlation(),
                                               write.attempt(),
                                               write.commands()))
                .emit(confirmTimer(fsm,
                                   t,
                                   artifact,
                                   write.correlation()));
    }

    private static Transition exhausted(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        var cleared = t.update(artifact,
                               rec -> rec.withWrite(none()))
                       .emit(new NodeFsmEffect.Cancel(writeTimer(t,
                                                                 artifact,
                                                                 write.correlation())));

        return switch (write.purpose()) {
            case CHAIN -> cleared.instance(artifact).deferredFailure().fold(() -> Chains.failChain(fsm,
                                                                                                   cleared,
                                                                                                   artifact,
                                                                                                   NodeFsmError.WriteExhausted.FACTORY.apply(artifact,
                                                                                                                                             write.version(),
                                                                                                                                             write.attempt()),
                                                                                                   Unrecognised.RETRY),
                                                                            _ -> settle(fsm, cleared, artifact));
            case FAILED -> failedExhausted(fsm, cleared, artifact, write);
            // FER (degrade and continue): a tombstone Remove that never confirmed is logged and dropped. The guarantee earned:
            // none beyond the log line; the tombstone is harmless (a vacant pair, R-1.6.3) and the next goal for the pair
            // writes over it at version + 1. The mechanism is a single 3-attempt cycle with no re-arm of the grace timer.
            case TOMBSTONE_REMOVE -> cleared.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                                                        "Tombstone of " + artifact + " at version " + write.version() + " was not removed; it stays until the next goal"));
        };
    }

    /// N19: a FAILED write nobody confirmed is retried every 2 s, up to the cycle bound, then logged CRITICAL.
    private static Transition failedExhausted(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        var timings = fsm.config().timings();

        return write.cycle() + 1 < timings.failedCycles()
               ? t.emit(new NodeFsmEffect.Schedule(TimerId.timerId(TimerId.TimerKind.FAILED_RETRY,
                                                                   artifact,
                                                                   t.incarnation(),
                                                                   write.cycle() + 1L),
                                                   timings.failedRetryMs()))
               : t.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.CRITICAL,
                                              "Node " + fsm.config()
                                                           .self()
                                                           .id()
                                             + " could not record FAILED for " + artifact
                                             + " after " + timings.failedCycles()
                                             + " cycles; the row stays in its last committed state"));
    }

    /// The in-flight write is confirmed: its version is the committed one. A chain step goes on with the chain; anything
    /// else settles the key.
    static Transition confirmed(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);

        return rec.write()
                  .fold(() -> t,
                        write -> afterConfirm(fsm,
                                              clearWrite(t, artifact, write),
                                              artifact,
                                              write));
    }

    private static Transition clearWrite(Transition t, Artifact artifact, InFlight write) {
        return t.update(artifact,
                        rec -> rec.withWrite(none())
                                  .withUnconfirmed(none())
                                  .withLastVersion(Math.max(rec.lastVersion(),
                                                            write.version())))
                .emit(new NodeFsmEffect.Cancel(writeTimer(t,
                                                          artifact,
                                                          write.correlation())));
    }

    private static Transition afterConfirm(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return write.purpose() == InFlight.Purpose.CHAIN && t.instance(artifact)
                                                             .chain()
                                                             .isPresent()
               ? Chains.advance(fsm, t, artifact)
               : settle(fsm, t, artifact);
    }

    /// Nothing is in flight: write the failure that waited, or re-evaluate a goal that changed meanwhile.
    static Transition settle(NodeFsm fsm, Transition t, Artifact artifact) {
        var rec = t.instance(artifact);

        if (!rec.isIdle()) {
            return t;
        }

        return rec.deferredFailure()
                  .fold(() -> reevaluate(fsm,
                                         t,
                                         artifact,
                                         rec.desiredPending()),
                        note -> issueFailed(fsm,
                                            t.update(artifact,
                                                     r -> r.withDeferredFailure(none())),
                                            artifact,
                                            note,
                                            0));
    }

    private static Transition reevaluate(NodeFsm fsm, Transition t, Artifact artifact, boolean pending) {
        return pending
               ? Planner.reconcile(fsm,
                                   t.update(artifact, r -> r.withDesiredPending(false)),
                                   artifact)
               : t;
    }

    record unused() implements Writes {}
}
