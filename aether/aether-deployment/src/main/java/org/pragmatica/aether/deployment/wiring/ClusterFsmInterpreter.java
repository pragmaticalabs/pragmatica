// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.wiring;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.pragmatica.aether.deployment.cluster.fsm2.Admission;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterEffect;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterFsm;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterInput;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterState;
import org.pragmatica.aether.deployment.cluster.fsm2.Seams;
import org.pragmatica.aether.deployment.cluster.fsm2.TimerId;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;


/// The interpreter of the cluster (leader) FSM (phase-3 design 2, 3, 5): the same mailbox discipline as the node's, with the cluster FSM's
/// own effects.
///
/// | Effect | Executor | Fed back |
/// |---|---|---|
/// | `Submit` | `Submitter`: the self-check, then **all Submits of one step in one `apply`** | one `WriteSettled` per Submit; `WitnessStale` when the self-check finds another committed leader |
/// | `RequestRebuild` | **synchronously, inside the dispatch of the step that emitted it** (D6): snapshot at a revision, then `step(Rebuild)` | the `Rebuild` step's own effects |
/// | `Enqueue` | the mailbox tail | the input |
/// | `Schedule` / `Cancel` | `FsmTimers`; the incarnation rides with the entry | `Timer(timer, incarnation)` |
/// | `Route` / `Warn` / `Audit` / `Log` / `Metric` / `Gauge` / `DispatchMigration` | the observability sink (the `EffectTranslator`) | none |
///
/// **Why `Rebuild` is synchronous.** `Rebuild` carries no incarnation. A snapshot read in one mailbox turn and stepped in a later one would
/// leave a gap in which another input could be stepped against an unbuilt model, or a stale snapshot could be stepped into a newer
/// incarnation. Reading and stepping in one dispatch leaves no gap.
///
/// **Stop.** `Close` deactivates (the FSM cancels its timers and goes Dormant), cancels the interpreter's timer map and stops the consumer.
public final class ClusterFsmInterpreter extends FsmRuntime<ClusterInput, TimerId, Long> {
    private static final String FSM = "cluster";

    /// What the interpreter is built from.
    public record Parts(ClusterFsm fsm,
                        Submitter submitter,
                        KvSnapshotter snapshotter,
                        Supplier<Seams> seams,
                        Supplier<Map<NodeId, Admission>> admissions,
                        FsmTimers.TimerService clock,
                        Consumer<ClusterEffect> sink,
                        StepObserver observer) {}

    private final Parts parts;
    private volatile ClusterState state = ClusterState.initial();

    private ClusterFsmInterpreter(Parts parts, long stopWaitMs) {
        super("deploy-fsm-cluster", parts.clock(), stopWaitMs);
        this.parts = parts;
    }

    public static ClusterFsmInterpreter clusterFsmInterpreter(Parts parts) {
        return new ClusterFsmInterpreter(parts, STOP_WAIT_MS);
    }

    /// As above, with the wait a `close()` gives the consumer (design 7, the cluster's stop bound) set explicitly.
    public static ClusterFsmInterpreter clusterFsmInterpreter(Parts parts, long stopWaitMs) {
        return new ClusterFsmInterpreter(parts, stopWaitMs);
    }

    /// The state after the last step: read-only, for observers.
    public ClusterState state() {
        return state;
    }

    // -- the consumer ------------------------------------------------------------------------------------------------
    @Override
    Unit step(ClusterInput input) {
        var started = System.nanoTime();
        var stepped = parts.fsm().step(state, input);

        state = stepped.state();
        dispatch(stepped.effects());
        parts.observer().stepped(FSM, inputName(input), System.nanoTime() - started);

        return Unit.unit();
    }

    @Override
    Unit entry(boolean reconcile) {
        return count("EnterRequested", "unsupported");
    }

    @Override
    Unit fired(TimerId id, Long incarnation) {
        return step(new ClusterInput.Timer(id, incarnation));
    }

    @Override
    Unit count(String input, String result) {
        parts.sink()
             .accept(new ClusterEffect.Metric("aether_deployment_fsm_inputs_total",
                                              Map.of("fsm", FSM, "input", input, "result", result),
                                              1L));

        return Unit.unit();
    }

    @Override
    Unit closing() {
        return state instanceof ClusterState.Active || state instanceof ClusterState.Rebuilding
               ? step(new ClusterInput.Deactivate())
               : Unit.unit();
    }

    /// B14, design 4.4: the committed snapshot was restored. A restore implies PASSIVE first, so the leader's FSM is Dormant and nothing happens;
    /// if it is Active the FSM is re-incarnated, `Deactivate` then `Activate` in this one dispatch (a fresh incarnation and a fresh snapshot, no
    /// slice outage, and no Dormant gap in which an input queued behind this one would be dropped). `[unverified: that PASSIVE always reaches
    /// LeaderChange(none) first; W-25 asserts the re-incarnation never fires in E-06]`, which is why it is counted.
    @Override
    Unit restored() {
        return state instanceof ClusterState.Active || state instanceof ClusterState.Rebuilding
               ? reincarnated()
               : count("StateRestored", "skipped_dormant");
    }

    private Unit reincarnated() {
        count("StateRestored", "reincarnated");
        step(new ClusterInput.Deactivate());

        return step(new ClusterInput.Activate());
    }

    // -- effects -----------------------------------------------------------------------------------------------------
    private void dispatch(List<ClusterEffect> effects) {
        var submits = new ArrayList<ClusterEffect.Submit>();

        effects.forEach(effect -> dispatch(effect, submits));
        submit(submits);
    }

    private void dispatch(ClusterEffect effect, List<ClusterEffect.Submit> submits) {
        switch (effect) {
            case ClusterEffect.Submit submit -> submits.add(submit);
            case ClusterEffect.RequestRebuild _ -> rebuild();
            case ClusterEffect.Enqueue enqueue -> offerLast(enqueue.input());
            case ClusterEffect.Schedule schedule -> timers().schedule(schedule.timer(),
                                                                      schedule.incarnation(),
                                                                      schedule.delayMs());
            case ClusterEffect.Cancel cancel -> timers().cancel(cancel.timer());
            case ClusterEffect.Route _, ClusterEffect.Warn _, ClusterEffect.Audit _, ClusterEffect.Log _, ClusterEffect.Metric _, ClusterEffect.Gauge _, ClusterEffect.DispatchMigration _ -> parts.sink().accept(effect);
        }
    }

    /// R-3.1.6: the snapshot is read at a revision and stepped in this very dispatch.
    private void rebuild() {
        var cut = parts.snapshotter().clusterSnapshot(parts.seams(), parts.admissions());

        snapshotTakenAt(cut.revision());
        step(new ClusterInput.Rebuild(cut.snapshot()));
    }

    /// One `apply` for the whole step, after the self-check.
    private void submit(List<ClusterEffect.Submit> submits) {
        switch (parts.submitter().submitTransactions(submits)) {
            case Submitter.Dispatch.Nothing _ -> {}
            case Submitter.Dispatch.Withheld withheld -> withheld(withheld);
            case Submitter.Dispatch.Sent sent -> sent.pending().onSuccess(settled -> settled.forEach(this::settle));
        }
    }

    private void withheld(Submitter.Dispatch.Withheld withheld) {
        withheld.settled().forEach(this::settle);
        if (withheld.witnessStale()) {
            deliver(new ClusterInput.WitnessStale());
        }
    }

    private void settle(Submitter.Settled settled) {
        deliver(new ClusterInput.WriteSettled(settled.correlationId(), settled.incarnation(), settled.settlement()));
    }

    // -- the seams ---------------------------------------------------------------------------------------------------
    /// CI-15, CI-15c: a fresh sample of the read seams replaces a queued older one (design 3.1: only `SeamsRefreshed` is coalesced).
    public boolean refreshSeams(Seams seams) {
        return deliverCoalescing(new ClusterInput.SeamsRefreshed(seams), ClusterInput.SeamsRefreshed.class::isInstance);
    }

    /// CI-15b: the seam reports a token for `node` that it reported as unknown before.
    public boolean processKnown(NodeId node, long token) {
        return deliver(new ClusterInput.ProcessKnown(node, token));
    }

    // -- activation ----------------------------------------------------------------------------------------------------
    /// CI-1: this node became the leader.
    @Contract
    public void activate() {
        deliver(new ClusterInput.Activate());
    }

    /// CI-1: this node stopped being the leader.
    @Contract
    public void deactivate() {
        deliver(new ClusterInput.Deactivate());
    }
}
