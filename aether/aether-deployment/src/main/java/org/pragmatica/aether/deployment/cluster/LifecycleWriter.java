// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Direct-write entry point for operator lifecycle transitions that do not flow through the
/// `MembershipFsm` operator-event path:
///   - `requestActivate` (DRAINING / DECOMMISSIONED → ON_DUTY) — operator re-enables a node.
///   - `requestFailedDrain` (DRAINING → FAILED_DRAIN) — drain hard-deadline expired.
///   - `requestDrain` / `requestDecommission` — kept for CTM-initiated transitions that still
///     route through `LifecycleWriter` (the FSM owns the operator-route path, but CTM and
///     `ConsensusDrainCoordinator` write directly).
///
/// Post-E.8 (spec §9): this interface replaces the legacy `HealthReconciler` surface for
/// lifecycle writes. All writes are KV `Put` commands proposed through the supplied
/// `commandApplier`; metadata (host/port/observedCoreEpoch/transitionedAt/provisioningSource)
/// is preserved from the prior `NodeLifecycleValue` when available.
public interface LifecycleWriter {
    Promise<Unit> requestDrain(NodeId target);
    Promise<Unit> requestDecommission(NodeId target);
    Promise<Unit> requestActivate(NodeId target);
    Promise<Unit> requestFailedDrain(NodeId target);

    /// Builds a direct KV-writing `LifecycleWriter`. Writes are unconditional `Put` commands
    /// proposed via `commandApplier`; the prior value (when present) is consulted to preserve
    /// non-state metadata across transitions.
    static LifecycleWriter directLifecycleWriter(Function<NodeId, Option<NodeLifecycleValue>> lifecycleReader,
                                                  Function<List<KVCommand<AetherKey>>, Promise<List<Object>>> commandApplier) {
        return new DirectLifecycleWriter(lifecycleReader, commandApplier);
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
        return forceLifecycleWrite(target, NodeLifecycleState.DRAINING);
    }

    @Override public Promise<Unit> requestDecommission(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.DECOMMISSIONED);
    }

    @Override public Promise<Unit> requestActivate(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.ON_DUTY);
    }

    @Override public Promise<Unit> requestFailedDrain(NodeId target) {
        return forceLifecycleWrite(target, NodeLifecycleState.FAILED_DRAIN);
    }

    private Promise<Unit> forceLifecycleWrite(NodeId target, NodeLifecycleState newState) {
        var nowMs = System.currentTimeMillis();
        var prior = lifecycleReader.apply(target);
        var value = buildLifecycleValue(prior, newState, nowMs);
        var command = putLifecycleAtom(NodeLifecycleKey.nodeLifecycleKey(target), value);
        return commandApplier.apply(List.of(command))
                              .onSuccess(_ -> log.info("LifecycleWriter: wrote {} for {}", newState, target))
                              .mapToUnit();
    }

    private static NodeLifecycleValue buildLifecycleValue(Option<NodeLifecycleValue> prior,
                                                           NodeLifecycleState newState,
                                                           long nowMs) {
        return prior.fold(() -> NodeLifecycleValue.nodeLifecycleValue(newState, nowMs),
                          p -> NodeLifecycleValue.nodeLifecycleValue(newState,
                                                                       nowMs,
                                                                       p.host(),
                                                                       p.port(),
                                                                       p.observedCoreEpoch(),
                                                                       p.transitionedAt(),
                                                                       p.provisioningSource()));
    }

    @SuppressWarnings("unchecked") private static KVCommand<AetherKey> putLifecycleAtom(NodeLifecycleKey key,
                                                                                         NodeLifecycleValue value) {
        return (KVCommand<AetherKey>) (KVCommand<?>) new KVCommand.Put<AetherKey, AetherValue>(key, value);
    }
}
