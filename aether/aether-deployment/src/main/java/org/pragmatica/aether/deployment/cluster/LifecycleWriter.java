// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandApplied;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent.CommandReceived;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Direct-write entry point for operator lifecycle transitions that do not flow through the
/// `MembershipFsm` operator-event path:
///   - `requestActivate` (DRAINING / STOPPED → ON_DUTY) — operator re-enables a node.
///   - `requestFailedDrain` (DRAINING → STOPPED+DRAIN_FAILED) — drain hard-deadline expired.
///   - `requestDrain` / `requestDecommission` — kept for CTM-initiated transitions that still
///     route through `LifecycleWriter` (the FSM owns the operator-route path, but CTM and
///     `ConsensusDrainCoordinator` write directly).
///
/// Post-E.8 (spec §9): this interface replaces the legacy lifecycle-reconciler surface for
/// lifecycle writes. All writes are KV `Put` commands proposed through the supplied
/// `commandApplier`; metadata (host/port/observedCoreEpoch/transitionedAt/provisioningSource)
/// is preserved from the prior `NodeLifecycleValue` when available.
///
/// **Convergence-reconciler spec (Phase 1, §6).** `applyCommand` is the new single-ingress
/// entry point: each `LifecycleCommand` (operator/reconciler/CTM/drain intent) maps to the
/// corresponding state-transition write. The legacy `request*` methods remain for now to keep
/// the migration additive — they will be folded into `applyCommand` once all kind-2 producers
/// are converted. Future work (per plan §1.4): route `applyCommand` through the FSM reducer
/// so command-on-illegal-state is no-op + audit rather than unconditional overwrite, and
/// publish `CommandReceived` / `CommandApplied` to the `audit.lifecycle.commands` topic.
public interface LifecycleWriter {
    Promise<Unit> requestDrain(NodeId target);
    Promise<Unit> requestDecommission(NodeId target);
    Promise<Unit> requestActivate(NodeId target);
    Promise<Unit> requestFailedDrain(NodeId target);

    /// Single ingress for `LifecycleCommand` intents (operator API, CTM scale-down, drain
    /// coordinator, reconciler). Dispatches per command type:
    ///   - `ForceDecommission` → STOPPED write carrying `StopReason` sidecar.
    ///   - `ForceOnDuty` → ON_DUTY write (used by cluster-sync `readyCandidate` arrival).
    ///   - `RecordJoining` → JOINING write (used by reconciler `GenerationLifecycleGap` rule).
    ///   - `RequestReJoin` → Remove of the lifecycle entry (used by drain cancellation).
    ///
    /// The default implementation delegates to the legacy `request*` methods so existing
    /// `DirectLifecycleWriter` instances pick up the new API without changes. Implementations
    /// that need reducer-routing or audit-stream publication should override.
    default Promise<Unit> applyCommand(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> requestDecommission(cmd.peer());
            case ForceOnDuty cmd -> requestActivate(cmd.peer());
            case RecordJoining cmd -> requestRecordJoining(cmd.peer());
            case RequestReJoin cmd -> requestReJoin(cmd.peer());
            case ForceDrain cmd -> requestDrain(cmd.peer());
        };
    }

    /// `RecordJoining` write target. The default `DirectLifecycleWriter` overrides; this
    /// stub keeps the interface compilable for hand-rolled writer implementations.
    default Promise<Unit> requestRecordJoining(NodeId target) {
        return Promise.failure(Causes.cause("requestRecordJoining not supported by " + getClass().getSimpleName()));
    }

    /// `RequestReJoin` write target — removes the lifecycle entry so the peer can re-enter
    /// JOINING on the next slot-claim / SwimHealthy / RecordJoining event.
    default Promise<Unit> requestReJoin(NodeId target) {
        return Promise.failure(Causes.cause("requestReJoin not supported by " + getClass().getSimpleName()));
    }

    /// Builds a direct KV-writing `LifecycleWriter`. Writes are unconditional `Put` commands
    /// proposed via `commandApplier`; the prior value (when present) is consulted to preserve
    /// non-state metadata across transitions. The `auditPublisher` is invoked for every
    /// `LifecycleCommand` flowing through `applyCommand(...)` (CommandReceived on entry,
    /// CommandApplied after the underlying write resolves); publish results are fire-and-forget
    /// — `applyCommand` does not wait on them.
    static LifecycleWriter directLifecycleWriter(Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                  Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                                                  StreamPublisher<CommandLifecycleEvent> auditPublisher) {
        return new DirectLifecycleWriter(lifecycleReader, commandApplier, auditPublisher);
    }
}

final class DirectLifecycleWriter implements LifecycleWriter {
    private static final Logger log = LoggerFactory.getLogger(DirectLifecycleWriter.class);

    private final Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;
    private final StreamPublisher<CommandLifecycleEvent> auditPublisher;

    DirectLifecycleWriter(Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier,
                          StreamPublisher<CommandLifecycleEvent> auditPublisher) {
        this.lifecycleReader = lifecycleReader;
        this.commandApplier = commandApplier;
        this.auditPublisher = auditPublisher;
    }

    @Override public Promise<Unit> requestDrain(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.DRAINING, Option.none());
    }

    @Override public Promise<Unit> requestDecommission(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.STOPPED, Option.some(StopReason.FORCED));
    }

    @Override public Promise<Unit> requestActivate(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.ON_DUTY, Option.none());
    }

    @Override public Promise<Unit> requestFailedDrain(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.STOPPED, Option.some(StopReason.DRAIN_FAILED));
    }

    @Override public Promise<Unit> requestRecordJoining(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.JOINING, Option.none());
    }

    @Override public Promise<Unit> requestReJoin(NodeId target) {
        var command = removeLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(target));
        return commandApplier.apply(List.of(command))
                              .onSuccess(_ -> log.info("LifecycleWriter: removed lifecycle entry for {}", target))
                              .mapToUnit();
    }

    /// Override the default `applyCommand` so STOPPED writes triggered by
    /// `ForceDecommission` carry the `StopReason` sidecar on the resulting `NodeLifecycleValue`,
    /// and every command flowing through this writer is recorded on the
    /// `audit.lifecycle.commands` stream (CommandReceived on entry, CommandApplied after the
    /// underlying KV write resolves). Audit publishes are fire-and-forget — failures inside
    /// the audit path do not affect the lifecycle write outcome.
    @Override public Promise<Unit> applyCommand(LifecycleCommand command) {
        publishReceived(command);
        return dispatchCommand(command).onSuccess(_ -> publishApplied(command, true))
                                       .onFailure(_ -> publishApplied(command, false));
    }

    private Promise<Unit> dispatchCommand(LifecycleCommand command) {
        return (command instanceof ForceDecommission cmd)
               ? forceLifecycleWrite(cmd.peer(), NodeLifecycleState.STOPPED, Option.some(cmd.reason()))
               : LifecycleWriter.super.applyCommand(command);
    }

    @Contract private void publishReceived(LifecycleCommand command) {
        auditPublisher.publish(new CommandReceived(commandType(command),
                                                   peerId(command),
                                                   reasonTag(command),
                                                   justificationMessage(command),
                                                   System.currentTimeMillis()));
    }

    @Contract private void publishApplied(LifecycleCommand command, boolean accepted) {
        auditPublisher.publish(new CommandApplied(commandType(command),
                                                  peerId(command),
                                                  reasonTag(command),
                                                  justificationMessage(command),
                                                  System.currentTimeMillis(),
                                                  accepted));
    }

    private static String commandType(LifecycleCommand command) {
        return command.getClass()
                      .getSimpleName();
    }

    private static String peerId(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> cmd.peer()
                                             .toString();
            case ForceDrain cmd -> cmd.peer()
                                      .toString();
            case ForceOnDuty cmd -> cmd.peer()
                                       .toString();
            case RecordJoining cmd -> cmd.peer()
                                         .toString();
            case RequestReJoin cmd -> cmd.peer()
                                         .toString();
        };
    }

    private static String reasonTag(LifecycleCommand command) {
        return switch (command) {
            case ForceDecommission cmd -> cmd.reason()
                                             .name();
            case ForceDrain cmd -> cmd.reason()
                                      .name();
            case ForceOnDuty _, RecordJoining _, RequestReJoin _ -> "";
        };
    }

    private static String justificationMessage(LifecycleCommand command) {
        return command.justification()
                      .message();
    }

    private Promise<Unit> forceLifecycleWrite(NodeId target,
                                              NodeLifecycleState newState,
                                              Option<StopReason> stopReason) {
        var nowMs = System.currentTimeMillis();
        var prior = lifecycleReader.apply(target);
        var value = buildLifecycleValue(prior, newState, nowMs, stopReason);
        var command = putLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(target), value);
        return commandApplier.apply(List.of(command))
                              .onSuccess(_ -> log.info("LifecycleWriter: wrote {} for {} (stopReason={})",
                                                       newState, target, stopReason))
                              .mapToUnit();
    }

    private static NodeLifecycleValue buildLifecycleValue(Option<NodeLifecycleValue> prior,
                                                           NodeLifecycleState newState,
                                                           long nowMs,
                                                           Option<StopReason> stopReason) {
        return prior.fold(() -> NodeLifecycleValue.nodeLifecycleValue(newState, nowMs).withStopReason(stopReason),
                          p -> NodeLifecycleValue.nodeLifecycleValue(newState,
                                                                       nowMs,
                                                                       p.host(),
                                                                       p.port(),
                                                                       p.observedCoreEpoch(),
                                                                       p.transitionedAt(),
                                                                       p.provisioningSource())
                                                  .withStopReason(stopReason));
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putLifecycleAtom(NodeLifecycleKey key,
                                                                                         NodeLifecycleValue value) {
        return (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> removeLifecycleAtom(NodeLifecycleKey key) {
        return (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Remove<AetherKey>(key);
    }
}
