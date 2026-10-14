// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// The write probe (spec R-3.4.4, O1): while an observed write is in flight, a `WRITE_PROBE` timer fires at the offsets of
/// `NodeFsmTimings.probeOffsetsMs` after the attempt's submission. A probe re-reads the applied mirror; if the write's row is
/// absent it rebuilds the batch from the current mirror (the same builder steps the batch came from) and resubmits it at the same
/// version under its own correlation, **without** consuming an attempt. The batch built at first submission is never replayed
/// (CTO ruling on L-20). `WRITE_CONFIRM` stays the give-up bound.
///
/// Absorption, named: a probe resubmission's settlement, and a failed rebuild, are absorbed rather than reported. Strategy FER
/// (degrade and continue): the attempt's own settlement and its `WRITE_CONFIRM` still judge the write, so the guarantee earned
/// is only "the window closes sooner when the first submission was dropped". The mechanism is the next probe, then the 35 s bound.
sealed interface Probes {
    String PROBE_LINE = "write-probe-resubmit";
    String CONFIRM_LINE = "write-confirm-timeout";

    static TimerId probeTimer(Transition t, Artifact artifact, long correlation) {
        return TimerId.timerId(TimerId.TimerKind.WRITE_PROBE, artifact, t.incarnation(), correlation);
    }

    /// Arms the first probe of the attempt that was just submitted.
    static Transition arm(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return fsm.config()
                  .timings()
                  .probeDelayAfter(0)
                  .fold(() -> t,
                        delay -> t.emit(new NodeFsmEffect.Schedule(probeTimer(t,
                                                                              artifact,
                                                                              write.correlation()),
                                                                   delay)));
    }

    /// A probe timer fired. A probe whose correlation is no longer the in-flight write is dropped (R-3.1.3).
    static Transition fired(NodeFsm fsm, Transition t, TimerId timer) {
        var artifact = timer.artifact();
        var rec = t.instance(artifact);

        return rec.write()
                  .filter(write -> write.correlation() == timer.token())
                  .fold(() -> Transition.transition(t.model()).ignoring(),
                        write -> probe(fsm, t, artifact, rec, write));
    }

    private static Transition probe(NodeFsm fsm, Transition t, Artifact artifact, InstanceRecord rec, InFlight write) {
        if (Writes.isApplied(rec, write)) {
            return Writes.confirmed(fsm, t, artifact);
        }

        var probed = write.withProbing(write.probing().probed());
        var counted = t.update(artifact, r -> r.withWrite(some(probed)));

        return rearm(fsm, rebuild(fsm, counted, artifact, probed), artifact, probed);
    }

    /// Arms the next probe of the series, if any is left (R-3.4.4 (3)).
    private static Transition rearm(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return fsm.config()
                  .timings()
                  .probeDelayAfter(write.probing().fired())
                  .fold(() -> t,
                        delay -> t.emit(new NodeFsmEffect.Schedule(probeTimer(t,
                                                                              artifact,
                                                                              write.correlation()),
                                                                   delay)));
    }

    // ------------------------------------------------------------------ the rebuild
    /// Starts the rebuild, unless one is already under way or the write has nothing to rebuild from: a chain write whose chain is
    /// gone (the chain failed while its write was in flight) is not resubmitted, and only the mirror re-read and the attempt bound
    /// judge it [guess: that case has no spec text].
    private static Transition rebuild(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        if (write.probing().rebuild().isPresent() || isOrphanChainWrite(t, artifact, write)) {
            return t;
        }

        var steps = write.probing().builders();

        return steps.isEmpty()
               ? resubmit(fsm, t, artifact, write, List.of(), none())
               : startStep(fsm, t, artifact, write, InFlight.Rebuild.rebuild(steps));
    }

    private static boolean isOrphanChainWrite(Transition t, Artifact artifact, InFlight write) {
        return write.purpose() == InFlight.Purpose.CHAIN && t.instance(artifact)
                                                             .chain()
                                                             .isEmpty();
    }

    private static Transition startStep(NodeFsm fsm,
                                        Transition t,
                                        Artifact artifact,
                                        InFlight write,
                                        InFlight.Rebuild rebuild) {
        var step = rebuild.remaining().getFirst();

        return t.issuing((next, token) -> next.update(artifact,
                                                      rec -> rec.withWrite(some(write.withProbing(write.probing()
                                                                                                       .rebuilding(rebuild.next(token))))))
                                              .emit(new NodeFsmEffect.LocalOp(next.incarnation(),
                                                                              token,
                                                                              some(artifact),
                                                                              step,
                                                                              fsm.config().timings().boundOf(step),
                                                                              none())));
    }

    /// Whether a local step's settlement belongs to a probe's rebuild.
    static boolean owns(Transition t, NodeFsmInput.LocalOpSettled settled) {
        return settled.incarnation() == t.incarnation() && rebuildOf(t,
                                                                     settled.artifact()).filter(rebuild -> rebuild.token() == settled.token())
                                                                    .isPresent();
    }

    private static Option<InFlight.Rebuild> rebuildOf(Transition t, Artifact artifact) {
        return t.instance(artifact)
                .write()
                .flatMap(write -> write.probing()
                                       .rebuild());
    }

    /// A step of a probe's rebuild ended.
    static Transition localSettled(NodeFsm fsm, Transition t, NodeFsmInput.LocalOpSettled settled) {
        var artifact = settled.artifact();
        var rec = t.instance(artifact);

        return rec.write()
                  .fold(() -> Transition.transition(t.model()).ignoring(),
                        write -> settledStep(fsm,
                                             t,
                                             artifact,
                                             write,
                                             settled.outcome()));
    }

    private static Transition settledStep(NodeFsm fsm,
                                          Transition t,
                                          Artifact artifact,
                                          InFlight write,
                                          LocalOutcome outcome) {
        return write.probing()
                    .rebuild()
                    .fold(() -> Transition.transition(t.model()).ignoring(),
                          rebuild -> settledRebuild(fsm, t, artifact, write, rebuild, outcome));
    }

    private static Transition settledRebuild(NodeFsm fsm,
                                             Transition t,
                                             Artifact artifact,
                                             InFlight write,
                                             InFlight.Rebuild rebuild,
                                             LocalOutcome outcome) {
        return switch (outcome) {
            case LocalOutcome.Done done -> stepDone(fsm,
                                                    t,
                                                    artifact,
                                                    write,
                                                    rebuild.collecting(done.commands()));
            case LocalOutcome.Failed failed -> abandoned(t,
                                                         artifact,
                                                         write,
                                                         failed.cause().message());
            case LocalOutcome.TimedOut _ -> abandoned(t, artifact, write, "timed out");
        };
    }

    private static Transition stepDone(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       InFlight write,
                                       InFlight.Rebuild rebuild) {
        return rebuild.remaining()
                      .isEmpty()
               ? finished(fsm, t, artifact, write, rebuild)
               : startStep(fsm, t, artifact, write, rebuild);
    }

    /// The builders ran. The mirror is re-checked first: a row that appeared meanwhile confirms the write and the rebuilt batch is
    /// discarded (R-3.4.4 (2)).
    private static Transition finished(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       InFlight write,
                                       InFlight.Rebuild rebuild) {
        var rec = t.instance(artifact);

        return Writes.isApplied(rec, write)
               ? Writes.confirmed(fsm, t, artifact)
               : resubmit(fsm, t, artifact, write, rebuild.collected(), rec.chain());
    }

    /// FER (degrade and continue): a rebuild step that failed or timed out leaves the write to the next probe and to the attempt
    /// bound. The line is a WARN so a run that hits it is read, not absorbed silently.
    private static Transition abandoned(Transition t, Artifact artifact, InFlight write, String reason) {
        return t.update(artifact,
                        rec -> rec.withWrite(some(write.withProbing(write.probing().notRebuilding()))))
                .emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                            "Write probe of " + artifact
                                           + " at version " + write.version()
                                           + " could not rebuild its batch: " + reason));
    }

    // ------------------------------------------------------------------ the resubmission and its sensor
    /// Resubmits the rebuilt batch at the same version under its own correlation. The attempt counter, the version and
    /// `WRITE_CONFIRM` are untouched.
    private static Transition resubmit(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       InFlight write,
                                       List<LegCommand> legs,
                                       Option<Chain> chain) {
        var batch = batchOf(fsm, t, artifact, write, legs, chain);

        return t.issuing((next, correlation) -> submitted(fsm, next, artifact, write, batch, correlation));
    }

    private static List<KVCommand<AetherKey>> batchOf(NodeFsm fsm,
                                                      Transition t,
                                                      Artifact artifact,
                                                      InFlight write,
                                                      List<LegCommand> legs,
                                                      Option<Chain> chain) {
        return chain.fold(() -> List.of(write.observedCommand()),
                          live -> Batches.assembled(fsm,
                                                    t.model(),
                                                    artifact,
                                                    live,
                                                    write.state(),
                                                    write.observedCommand(),
                                                    legs));
    }

    private static Transition submitted(NodeFsm fsm,
                                        Transition t,
                                        Artifact artifact,
                                        InFlight write,
                                        List<KVCommand<AetherKey>> batch,
                                        long correlation) {
        var recorded = write.withProbing(write.probing().resubmitted(correlation));

        return t.update(artifact,
                        rec -> rec.withWrite(some(recorded)))
                .emit(new NodeFsmEffect.Submit(t.incarnation(),
                                               artifact,
                                               correlation,
                                               write.attempt(),
                                               write.probing().fired(),
                                               batch))
                .emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.INFO,
                                            line(fsm, PROBE_LINE, artifact, write)))
                .emit(NodeFsm.submission("probe_resubmit"));
    }

    /// The attempt's `WRITE_CONFIRM` ran out: one line and one metric arm, whether or not the expiry then confirms from the mirror.
    static Transition confirmTimeout(NodeFsm fsm, Transition t, Artifact artifact, InFlight write) {
        return t.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                            line(fsm, CONFIRM_LINE, artifact, write)))
                .emit(NodeFsm.submission("confirm_timeout"));
    }

    /// Names the node, artifact, state, version, attempt, probe index and elapsed time since the attempt's submission.
    private static String line(NodeFsm fsm, String code, Artifact artifact, InFlight write) {
        return code
             + ": node " + fsm.config()
                              .self()
                              .id()
             + " artifact " + artifact
             + " state " + write.state()
             + " version " + write.version()
             + " attempt " + write.attempt()
             + " probe " + write.probing()
                                .fired()
             + " elapsed_ms " + (fsm.now() - write.probing()
                                                  .submittedAtMs());
    }

    /// Whether `correlation` is one of the attempt's probe resubmissions.
    static boolean isResubmission(InFlight write, long correlation) {
        return write.probing()
                    .resubmissions()
                    .contains(correlation);
    }

    record unused() implements Probes {}
}
