// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.node.machine;

import java.util.Map;
import java.util.function.LongSupplier;

import org.pragmatica.aether.artifact.Artifact;


/// The node deployment state machine as a pure function (spec R-3.1.1): `step(model, input)` returns the next model and
/// the effects as data (R-3.1.2). Nothing here touches a clock, a scheduler, a store or the network except through the
/// injected `wallClock` (R-3.1.4: committed timestamps and the escalation age) and the effects it returns.
///
/// The node needs no monotonic clock: its only intervals are timers it asks the interpreter to run, and the park age,
/// which is a committed-time comparison on the wall clock as B-158 has it.
public record NodeFsm(NodeFsmConfig config, LongSupplier wallClock) {
    private static final String INPUTS_TOTAL = "aether_deployment_fsm_inputs_total";
    private static final String SUBMISSIONS_TOTAL = "aether_deployment_fsm_submissions_total";

    public static NodeFsm nodeFsm(NodeFsmConfig config, LongSupplier wallClock) {
        return new NodeFsm(config, wallClock);
    }

    public long now() {
        return wallClock.getAsLong();
    }

    /// One transition. Every input yields an `inputs_total{fsm="node", input, result}` metric (R-1.3.1).
    public Transition step(NodeFsmModel model, NodeFsmInput input) {
        var transition = dispatch(model, input);

        return transition.emit(inputCounted(input, transition.dropped()));
    }

    private static NodeFsmEffect inputCounted(NodeFsmInput input, boolean dropped) {
        var labels = Map.of("fsm",
                            "node",
                            "input",
                            input.getClass().getSimpleName(),
                            "result",
                            dropped
                            ? "dropped"
                            : "applied");

        return new NodeFsmEffect.Metric(INPUTS_TOTAL, labels, 1L);
    }

    private Transition dispatch(NodeFsmModel model, NodeFsmInput input) {
        return switch (input) {
            case NodeFsmInput.QuorumEstablished established -> enter(model, established.snapshot());
            case NodeFsmInput.ReconcileActivation reconcile -> enter(model, reconcile.snapshot());
            case NodeFsmInput.QuorumDisappeared _ -> model.active()
                                                     ? Lifecycle.disappeared(this, model)
                                                     : Transition.transition(model).ignoring();
            default -> model.active()
                       ? route(Transition.transition(model), input)
                       : Transition.transition(model).ignoring();
        };
    }

    /// Dormant to Active; in Active a quorum edge, or the level-heal of B-114, changes nothing.
    private Transition enter(NodeFsmModel model, RebuildSnapshot snapshot) {
        return model.active()
               ? Transition.transition(model).ignoring()
               : Lifecycle.established(this, model, snapshot);
    }

    private Transition route(Transition t, NodeFsmInput input) {
        return switch (input) {
            case NodeFsmInput.DesiredPut put -> Observations.desiredPut(this, t, put.artifact(), put.value());
            case NodeFsmInput.DesiredRemove remove -> Observations.desiredRemove(this, t, remove.artifact());
            case NodeFsmInput.ObservedPut put -> Observations.observedPut(this, t, put);
            case NodeFsmInput.ObservedRemove remove -> Observations.observedRemove(this, t, remove);
            case NodeFsmInput.TargetPut put -> Observations.targetPut(this, t, put.base(), put.version());
            case NodeFsmInput.TargetRemove remove -> Observations.targetRemove(t, remove.base());
            case NodeFsmInput.RoutingPut put -> Observations.routingPut(this, t, put);
            case NodeFsmInput.RoutingRemove remove -> Observations.routingRemove(t, remove.base());
            case NodeFsmInput.ConfigChanged changed -> Observations.configChanged(t, changed.key());
            case NodeFsmInput.TimerFired fired -> timerFired(t, fired.timer());
            case NodeFsmInput.WriteSettled settled -> writeSettled(t, settled);
            case NodeFsmInput.LocalOpSettled settled -> Chains.localSettled(this, t, settled);
            case NodeFsmInput.QuorumEstablished _, NodeFsmInput.QuorumDisappeared _, NodeFsmInput.ReconcileActivation _ -> Transition.transition(t.model()).ignoring();
        };
    }

    /// A timer from another incarnation, or one whose watched object is gone, is dropped (R-3.1.3).
    private Transition timerFired(Transition t, TimerId timer) {
        if (timer.incarnation() != t.incarnation()) {
            return Transition.transition(t.model()).ignoring();
        }

        return switch (timer.kind()) {
            case WRITE_CONFIRM -> writeAttemptTimer(t, timer);
            case CHAIN_BOUND -> Chains.boundExpired(this, t, timer);
            case ACTIVATING_CHECK -> Redrive.activatingCheck(this, t, timer);
            case PARKED_ESCALATION -> Planner.escalate(this, t, timer);
            case TOMBSTONE_GRACE -> Planner.tombstoneExpired(this, t, timer);
            case FAILED_RETRY -> failedRetry(t, timer);
        };
    }

    private Transition writeAttemptTimer(Transition t, TimerId timer) {
        return t.instance(timer.artifact())
                .write()
                .filter(write -> write.correlation() == timer.token())
                .fold(() -> Transition.transition(t.model()).ignoring(),
                      _ -> Writes.attemptFailed(this,
                                                t,
                                                timer.artifact()));
    }

    /// N19: the next cycle of a FAILED row nobody confirmed, after R-6.0 has read the committed row.
    private Transition failedRetry(Transition t, TimerId timer) {
        var rec = t.instance(timer.artifact());

        return rec.isIdle() && rec.unconfirmed()
                                  .isPresent()
               ? Redrive.redrive(this, t, timer.artifact(), (int) timer.token())
               : Transition.transition(t.model()).ignoring();
    }

    private Transition writeSettled(Transition t, NodeFsmInput.WriteSettled settled) {
        var artifact = settled.artifact();

        return settled.incarnation() != t.incarnation()
               ? Transition.transition(t.model()).ignoring()
               : t.instance(artifact)
                  .write()
                  .filter(write -> write.correlation() == settled.correlation())
                  .fold(() -> Transition.transition(t.model()).ignoring(),
                        _ -> settledWrite(t,
                                          artifact,
                                          settled.outcome()));
    }

    private Transition settledWrite(Transition t, Artifact artifact, NodeFsmInput.WriteOutcome outcome) {
        return switch (outcome) {
            case NodeFsmInput.WriteOutcome.Accepted _ -> t.emit(submission("accepted"));
            case NodeFsmInput.WriteOutcome.Failed _ -> Writes.attemptFailed(this, t.emit(submission("failed")), artifact);
        };
    }

    private static NodeFsmEffect submission(String outcome) {
        return new NodeFsmEffect.Metric(SUBMISSIONS_TOTAL,
                                        Map.of("fsm", "node", "kind", "observed", "outcome", outcome),
                                        1L);
    }
}
