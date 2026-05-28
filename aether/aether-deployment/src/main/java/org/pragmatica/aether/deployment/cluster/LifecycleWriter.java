// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceOnDuty;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RequestReJoin;
import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDrain;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;

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
/// **E2 Phase 2c-α.1a (2026-05-28).** The `audit.lifecycle.commands` publisher and the
/// per-emitter `source` tag (`SOURCE_OPERATOR` / `SOURCE_RECONCILER` / `SOURCE_CTM` / ...) have
/// been deleted. Audit-event publishing was the only consumer of the `source` argument, so
/// `applyCommand` collapses to a single arity. Lifecycle execution paths are unchanged —
/// reducer-routed FSM dispatch (the production path) and the direct test fixture both still
/// propose KV writes via `commandApplier`; only the observability tee is gone.
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
    /// that need reducer-routing should override.
    default Promise<Unit> applyCommand(LifecycleCommand command) {
        return dispatchCommandToLegacyWriters(command);
    }

    /// Shared dispatch — used by the `applyCommand` default to avoid mutual recursion.
    /// `DirectLifecycleWriter` and `FsmRoutedLifecycleWriter` override `applyCommand` and route
    /// through their own dispatcher directly, so this helper is only invoked by non-overriding
    /// implementations (legacy fixtures, hand-rolled writers).
    private Promise<Unit> dispatchCommandToLegacyWriters(LifecycleCommand command) {
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
        return Causes.cause("requestRecordJoining not supported by " + getClass().getSimpleName()).promise();
    }

    /// `RequestReJoin` write target — removes the lifecycle entry so the peer can re-enter
    /// JOINING on the next slot-claim / SwimHealthy / RecordJoining event.
    default Promise<Unit> requestReJoin(NodeId target) {
        return Causes.cause("requestReJoin not supported by " + getClass().getSimpleName()).promise();
    }

    /// Builds a direct KV-writing `LifecycleWriter`. Writes are unconditional `Put` commands
    /// proposed via `commandApplier`; the prior value (when present) is consulted to preserve
    /// non-state metadata across transitions.
    static LifecycleWriter directLifecycleWriter(Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                  Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier) {
        return new DirectLifecycleWriter(lifecycleReader, commandApplier);
    }

    /// Builds an FSM-routed `LifecycleWriter`. Every command is dispatched through
    /// `commandIngress` (`MembershipFsm::applyLifecycleCommand`), making the membership FSM the
    /// sovereign single writer of lifecycle KV: illegal command-on-state is a no-op rather than
    /// an unconditional overwrite. `clock` stamps a fresh HLC timestamp onto the timestamp-less
    /// legacy `request*` commands.
    static LifecycleWriter fsmRoutedLifecycleWriter(Function<LifecycleCommand, Promise<Boolean>> commandIngress,
                                                     Supplier<HlcTimestamp> clock) {
        return new FsmRoutedLifecycleWriter(commandIngress, clock);
    }
}

final class DirectLifecycleWriter implements LifecycleWriter {
    private static final Logger log = LoggerFactory.getLogger(DirectLifecycleWriter.class);

    private final Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader;
    private final Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier;

    DirectLifecycleWriter(Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                          Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier) {
        this.lifecycleReader = lifecycleReader;
        this.commandApplier = commandApplier;
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

    /// Override the default `applyCommand` so STOPPED writes triggered by `ForceDecommission`
    /// carry the `StopReason` sidecar on the resulting `NodeLifecycleValue`. Audit-event
    /// publication was removed in E2 Phase 2c-α.1a.
    @Override public Promise<Unit> applyCommand(LifecycleCommand command) {
        return dispatchCommand(command);
    }

    private Promise<Unit> dispatchCommand(LifecycleCommand command) {
        return (command instanceof ForceDecommission cmd)
               ? forceLifecycleWrite(cmd.peer(), NodeLifecycleState.STOPPED, Option.some(cmd.reason()))
               : LifecycleWriter.super.applyCommand(command);
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
