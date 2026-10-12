// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.SliceLoadingFailure;
import org.pragmatica.aether.slice.SliceLoadingFailure.Intermittent;
import org.pragmatica.aether.slice.SliceLoadingFailure.Unrecognised;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Running a chain (spec R-3.4.2, R-3.4.3): one chain per key; its steps run one at a time; a local step ends with a
/// `LocalOpSettled`, a write with the node's own notification. A step that ends after its chain was dropped carries a
/// dead token and is ignored (B-142).
sealed interface Chains {
    /// Starts a chain for the goal generation `generation`: ends any park, disarms a pending tombstone Remove (a newer goal
    /// cancels it, R-1.6.3), bounds activating chains, and runs the first step.
    static Transition start(NodeFsm fsm,
                            Transition t,
                            Artifact artifact,
                            Chain.ChainKind kind,
                            Generation generation,
                            List<Chain.Step> steps) {
        return t.issuing((next, id) -> run(fsm,
                                           begin(fsm, next, artifact, Chain.chain(id, kind, generation, steps)),
                                           artifact));
    }

    private static Transition begin(NodeFsm fsm, Transition t, Artifact artifact, Chain chain) {
        var bounded = chain.kind().isBounded()
                      ? t.emit(new NodeFsmEffect.Schedule(boundTimer(t, artifact, chain.id()),
                                                          fsm.config().timings().chainBoundMs()))
                      : t;

        return Planner.cancelTombstone(Planner.unpark(bounded, artifact, "the start proceeds"),
                                       artifact)
                      .update(artifact,
                              rec -> rec.withChain(some(chain))
                                        .withChainGeneration(some(chain.generation()))
                                        .withDesiredPending(false));
    }

    private static Transition run(NodeFsm fsm, Transition t, Artifact artifact) {
        return advance(fsm, t, artifact);
    }

    private static TimerId boundTimer(Transition t, Artifact artifact, long chainId) {
        return TimerId.timerId(TimerId.TimerKind.CHAIN_BOUND, artifact, t.incarnation(), chainId);
    }

    /// Runs the next step of the live chain, or finishes it.
    static Transition advance(NodeFsm fsm, Transition t, Artifact artifact) {
        return t.instance(artifact)
                .chain()
                .fold(() -> t,
                      chain -> step(fsm, t, artifact, chain));
    }

    private static Transition step(NodeFsm fsm, Transition t, Artifact artifact, Chain chain) {
        if (!chain.hasSteps()) {
            return finish(fsm, t, artifact, chain);
        }

        return switch (chain.head()) {
            case Chain.Step.Local local -> runLocal(fsm, t, artifact, chain, local.step());
            case Chain.Step.Write write -> Writes.submitChain(fsm, t, artifact, write.state());
        };
    }

    private static Transition runLocal(NodeFsm fsm, Transition t, Artifact artifact, Chain chain, LocalStep step) {
        return t.issuing((next, token) -> next.update(artifact,
                                                      rec -> rec.withChain(some(chain.started(step, token))))
                                              .emit(new NodeFsmEffect.LocalOp(next.incarnation(),
                                                                              token,
                                                                              some(artifact),
                                                                              step,
                                                                              fsm.config().timings().boundOf(step),
                                                                              none())));
    }

    /// The chain ran out of steps: its generation is applied; a goal that changed meanwhile is re-evaluated.
    private static Transition finish(NodeFsm fsm, Transition t, Artifact artifact, Chain chain) {
        var done = t.update(artifact,
                            rec -> rec.withChain(none())
                                      .withAppliedGeneration(some(chain.generation())));
        var unbound = chain.kind().isBounded()
                      ? done.emit(new NodeFsmEffect.Cancel(boundTimer(t, artifact, chain.id())))
                      : done;

        return Planner.afterChain(fsm, unbound, artifact);
    }

    /// A local step ended. Stale incarnation, no chain, or a token that is not the running step's: ignored.
    static Transition localSettled(NodeFsm fsm, Transition t, NodeFsmInput.LocalOpSettled settled) {
        var artifact = settled.artifact();
        var live = settled.incarnation() == t.incarnation() && settled.token() != 0L;

        return t.instance(artifact)
                .chain()
                .filter(chain -> live && chain.stepToken() == settled.token())
                .fold(() -> Transition.transition(t.model()).ignoring(),
                      chain -> settleStep(fsm,
                                          t,
                                          artifact,
                                          chain,
                                          settled.outcome()));
    }

    private static Transition settleStep(NodeFsm fsm,
                                         Transition t,
                                         Artifact artifact,
                                         Chain chain,
                                         LocalOutcome outcome) {
        return switch (outcome) {
            case LocalOutcome.Done done -> stepDone(fsm, t, artifact, chain, done);
            case LocalOutcome.Failed failed -> stepFailed(fsm, t, artifact, chain, failed.cause());
            case LocalOutcome.TimedOut _ -> stepFailed(fsm, t, artifact, chain, timeoutOf(fsm, chain));
        };
    }

    private static Cause timeoutOf(NodeFsm fsm, Chain chain) {
        var step = chain.running().or(LocalStep.ACTIVATE);
        var bound = fsm.config().timings().boundOf(step);

        return new Intermittent.Timeout(step.name(), NodeFsmError.StepTimedOut.FACTORY.apply(step, bound));
    }

    private static Transition stepDone(NodeFsm fsm,
                                       Transition t,
                                       Artifact artifact,
                                       Chain chain,
                                       LocalOutcome.Done done) {
        var step = chain.running().or(LocalStep.ACTIVATE);
        var recorded = t.update(artifact,
                                rec -> rec.withChain(some(chain.collecting(done.commands(),
                                                                           done.methods())))
                                          .withServing(servingAfter(step,
                                                                    rec.serving())));

        return advance(fsm, loadedAfter(recorded, artifact, step), artifact);
    }

    private static boolean servingAfter(LocalStep step, boolean serving) {
        return switch (step) {
            case REGISTER_BRIDGE -> true;
            case UNPUBLISH, UNLOAD, FORCED_CLEANUP, SUSPEND -> false;
            default -> serving;
        };
    }

    private static Transition loadedAfter(Transition t, Artifact artifact, LocalStep step) {
        return switch (step) {
            case LOAD -> t.loaded(artifact, true);
            case UNLOAD, FORCED_CLEANUP, EVICT -> t.loaded(artifact, false);
            default -> t;
        };
    }

    /// A step failed. Best-effort steps (the teardown preparation, the unpublish) and the unload carry on (B-147); a failed
    /// load is classified PERMANENT (B-121) and evicted (B-178); everything else is a retryable failure (N6, N14).
    private static Transition stepFailed(NodeFsm fsm, Transition t, Artifact artifact, Chain chain, Cause cause) {
        var step = chain.running().or(LocalStep.ACTIVATE);
        var note = t.emit(new NodeFsmEffect.Log(NodeFsmEffect.Level.WARN,
                                                "Step " + step + " of " + artifact + " failed: " + cause.message()));

        return switch (step) {
            case PREPARE_TEARDOWN, UNPUBLISH, UNLOAD -> bestEffort(fsm, note, artifact, chain, step);
            case LOAD -> failChain(fsm, note, artifact, cause, Unrecognised.PERMANENT);
            default -> failChain(fsm, note, artifact, cause, Unrecognised.RETRY);
        };
    }

    private static Transition bestEffort(NodeFsm fsm, Transition t, Artifact artifact, Chain chain, LocalStep step) {
        var carried = t.update(artifact,
                               rec -> rec.withChain(some(chain.collecting(List.of(),
                                                                          List.of())))
                                         .withServing(servingAfter(step,
                                                                   rec.serving())));

        return advance(fsm, loadedAfter(carried, artifact, step), artifact);
    }

    /// N3, N6: the chain is dropped (its running step's token dies with it), what it registered is unregistered, and FAILED
    /// is written with the real reason and the disposition `classify` gives `cause`. A write still in flight keeps the
    /// FAILED row waiting: at most one write per key (R-3.4.9 (2)).
    static Transition failChain(NodeFsm fsm, Transition t, Artifact artifact, Cause cause, Unrecognised unrecognised) {
        var rec = t.instance(artifact);
        var generation = generationOf(rec);
        var note = FailureNote.failureNote(cause.message(),
                                           SliceLoadingFailure.classify(cause, unrecognised).isFatal(),
                                           generation);
        var dropped = rec.chain().fold(() -> t, chain -> dropChain(t, artifact, chain));

        return failWith(fsm, dropped, artifact, note);
    }

    private static Transition dropChain(Transition t, Artifact artifact, Chain chain) {
        var bounded = chain.kind().isBounded()
                      ? t.emit(new NodeFsmEffect.Cancel(boundTimer(t, artifact, chain.id())))
                      : t;
        var cleanup = cleanupOf(chain);
        var cleaned = cleanup.fold(() -> bounded, step -> bounded.emit(cleanupOp(bounded, artifact, step)));
        var updated = cleaned.update(artifact,
                                     rec -> rec.withChain(none())
                                               .withServing(servingAfter(cleanup,
                                                                         rec.serving())));

        return loadedAfterCleanup(updated, artifact, cleanup);
    }

    private static Option<LocalStep> cleanupOf(Chain chain) {
        if (chain.isRunning(LocalStep.LOAD)) {
            return some(LocalStep.EVICT);
        }

        return chain.kind()
                    .registers()
               ? some(LocalStep.UNPUBLISH)
               : none();
    }

    private static NodeFsmEffect cleanupOp(Transition t, Artifact artifact, LocalStep step) {
        return new NodeFsmEffect.LocalOp(t.incarnation(), 0L, some(artifact), step, 0L, none());
    }

    private static boolean servingAfter(Option<LocalStep> cleanup, boolean serving) {
        return cleanup.filter(step -> step == LocalStep.UNPUBLISH)
                      .isPresent()
               ? false
               : serving;
    }

    private static Transition loadedAfterCleanup(Transition t, Artifact artifact, Option<LocalStep> cleanup) {
        return cleanup.filter(step -> step == LocalStep.EVICT)
                      .isPresent()
               ? t.loaded(artifact, false)
               : t;
    }

    /// Writes `note` as a FAILED row now, or once the write in flight resolves.
    static Transition failWith(NodeFsm fsm, Transition t, Artifact artifact, FailureNote note) {
        var rec = t.instance(artifact);

        return rec.write()
                  .isPresent()
               ? t.update(artifact, r -> r.withDeferredFailure(some(note)))
               : Writes.issueFailed(fsm,
                                    t.update(artifact,
                                             r -> r.withChainGeneration(some(note.generation()))),
                                    artifact,
                                    note,
                                    0);
    }

    private static Generation generationOf(InstanceRecord rec) {
        return rec.chain()
                  .map(Chain::generation)
                  .orElse(rec.chainGeneration())
                  .orElse(rec.desired().map(SliceDesiredValue::generation))
                  .or(new Generation(0L, 0L));
    }

    /// The overall bound of an activating chain ran out (B-169, N6).
    static Transition boundExpired(NodeFsm fsm, Transition t, TimerId timer) {
        var artifact = timer.artifact();

        return t.instance(artifact)
                .chain()
                .filter(chain -> chain.id() == timer.token())
                .fold(() -> Transition.transition(t.model()).ignoring(),
                      chain -> failChain(fsm,
                                         t,
                                         artifact,
                                         new Intermittent.Timeout("activation chain",
                                                                  NodeFsmError.ChainTimedOut.FACTORY.apply(artifact,
                                                                                                           fsm.config()
                                                                                                              .timings()
                                                                                                              .chainBoundMs())),
                                         Unrecognised.RETRY));
    }

    record unused() implements Chains {}
}
