// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.ToLongFunction;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.node.machine.NodeFsm;
import org.pragmatica.aether.deployment.node.machine.NodeFsmEffect;
import org.pragmatica.aether.deployment.node.machine.NodeFsmInput;
import org.pragmatica.aether.deployment.node.machine.NodeFsmModel;
import org.pragmatica.aether.deployment.node.machine.TimerId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Unit;


/// The interpreter of the node FSM (phase-3 design 2, 3, 4): it steps inputs from one mailbox on one consumer thread and dispatches the
/// effects in the order the FSM emitted them. It contains no spec rule; every decision is `NodeFsm.step`'s.
///
/// | Effect | Executor | Fed back |
/// |---|---|---|
/// | `SelfReady` | the `selfReady` callback | none |
/// | `Submit` | `Submitter`, **all Submits of one step in one `apply`** | `WriteSettled(incarnation, artifact, correlation, outcome)` |
/// | `LocalOp` | `LocalOps` | `LocalOpSettled` |
/// | `Schedule` / `Cancel` | `FsmTimers` | `TimerFired` |
/// | `Route` / `Warn` / `WarnResolved` / `Log` / `Metric` | the observability sink (the `EffectTranslator`) | none |
///
/// **Entry (NI-1, NI-6).** [#requestEntry] enqueues a marker; when the consumer reaches it and the model is still Dormant it reads the
/// snapshot at a revision (`KvSnapshotter`) and steps `QuorumEstablished` (or `ReconcileActivation` for the level-heal driver). The
/// revision becomes the watermark below which KV-sourced inputs are dropped (the snapshot holds them).
///
/// **Stop.** `Close` only: timers cancelled, consumer stopped. The `SUSPEND` drain of design 7 is not part of this interpreter.
public final class NodeFsmInterpreter extends FsmRuntime<NodeFsmInput, TimerId, Unit> {
    private static final String FSM = "node";

    /// What the interpreter is built from.
    public record Parts(NodeFsm fsm,
                        Submitter submitter,
                        KvSnapshotter snapshotter,
                        Supplier<Set<Artifact>> loaded,
                        ToLongFunction<NodeId> tokenOf,
                        LocalOps localOps,
                        FsmTimers.TimerService clock,
                        Consumer<NodeFsmEffect> sink,
                        Runnable selfReady,
                        StepObserver observer) {}

    private final Parts parts;
    private volatile NodeFsmModel model = NodeFsmModel.nodeFsmModel();

    private NodeFsmInterpreter(Parts parts) {
        super("deploy-fsm-node", parts.clock());
        this.parts = parts;
    }

    public static NodeFsmInterpreter nodeFsmInterpreter(Parts parts) {
        return new NodeFsmInterpreter(parts);
    }

    /// The model after the last step: read-only, for observers (the tracer's sampler reads an instance's record from it).
    public NodeFsmModel model() {
        return model;
    }

    /// The incarnation of the model after the last step; a local op of another incarnation is stale.
    public long incarnation() {
        return model.incarnation();
    }

    // -- the consumer ------------------------------------------------------------------------------------------------
    @Override
    Unit step(NodeFsmInput input) {
        var started = System.nanoTime();
        var transition = parts.fsm().step(model, input);

        model = transition.model();
        dispatch(transition.effects());
        parts.observer().stepped(FSM, inputName(input), System.nanoTime() - started);

        return Unit.unit();
    }

    @Override
    Unit entry(boolean reconcile) {
        return model.active()
               ? count("EnterRequested", "skipped_active")
               : enter(reconcile);
    }

    private Unit enter(boolean reconcile) {
        var cut = parts.snapshotter()
                       .nodeSnapshot(parts.fsm().config().self(),
                                     parts.tokenOf()::applyAsLong,
                                     parts.loaded().get());

        snapshotTakenAt(cut.revision());

        return step(reconcile
                    ? new NodeFsmInput.ReconcileActivation(cut.snapshot())
                    : new NodeFsmInput.QuorumEstablished(cut.snapshot()));
    }

    @Override
    Unit fired(TimerId id, Unit attachment) {
        return step(new NodeFsmInput.TimerFired(id));
    }

    @Override
    Unit count(String input, String result) {
        parts.sink()
             .accept(new NodeFsmEffect.Metric("aether_deployment_fsm_inputs_total",
                                              Map.of("fsm", FSM, "input", input, "result", result),
                                              1L));

        return Unit.unit();
    }

    @Override
    Unit closing() {
        parts.localOps().close();

        return Unit.unit();
    }

    // -- effects -----------------------------------------------------------------------------------------------------
    private void dispatch(List<NodeFsmEffect> effects) {
        var submits = new ArrayList<NodeFsmEffect.Submit>();

        effects.forEach(effect -> dispatch(effect, submits));
        submit(submits);
    }

    private void dispatch(NodeFsmEffect effect, List<NodeFsmEffect.Submit> submits) {
        switch (effect) {
            case NodeFsmEffect.SelfReady _ -> parts.selfReady().run();
            case NodeFsmEffect.Submit submit -> submits.add(submit);
            case NodeFsmEffect.LocalOp op -> parts.localOps().run(op, this::incarnation, this::deliver);
            case NodeFsmEffect.Schedule schedule -> timers().schedule(schedule.timer(), Unit.unit(), schedule.delayMs());
            case NodeFsmEffect.Cancel cancel -> timers().cancel(cancel.timer());
            case NodeFsmEffect.Route _, NodeFsmEffect.Warn _, NodeFsmEffect.WarnResolved _, NodeFsmEffect.Log _, NodeFsmEffect.Metric _ -> parts.sink().accept(effect);
        }
    }

    /// One `apply` for the whole step: every Submit's commands leave in one batch, and every Submit is settled with that batch's outcome. A Submit
    /// always carries its observed Put (R-3.4.9 (4)); an empty step never reaches the engine.
    private void submit(List<NodeFsmEffect.Submit> submits) {
        if (!submits.isEmpty()) {
            parts.submitter()
                 .submitBatch(submits.stream().flatMap(submit -> submit.commands()
                                                                       .stream()).toList())
                 .onSuccess(outcome -> submits.forEach(submit -> settle(submit, outcome)));
        }
    }

    private void settle(NodeFsmEffect.Submit submit, NodeFsmInput.WriteOutcome outcome) {
        deliver(new NodeFsmInput.WriteSettled(submit.incarnation(), submit.artifact(), submit.correlation(), outcome));
    }
}
