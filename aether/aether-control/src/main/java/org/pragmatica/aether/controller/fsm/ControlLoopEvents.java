// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.controller.fsm;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;

/// Domain events specific to the [`ControlLoop`] FSM.
///
/// Layered on top of [`ClusterFsmEvent`] so that cluster-lifecycle events ([`QuorumEstablished`],
/// [`QuorumDisappeared`], [`LeaderChange`], [`Shutdown`]) and control-loop-specific events flow
/// through the same `Fsm.dispatch` path.
///
/// `Activate` / `Deactivate` carry the semantic meaning of "this node was assigned / unassigned
/// the SCALING task group" — the TaskGroupActivator delivers them via the `DelegatedComponent`
/// activate/deactivate contract. They are equivalent in effect to a `LeaderChange(localIsLeader)`
/// flip for this component.
public final class ControlLoopEvents {
    private ControlLoopEvents() {}

    /// Delivered by `ControlLoop.activate()` (from TaskGroupActivator) when the local node is
    /// assigned the SCALING task group. Drives `Dormant → Warmup`.
    public record Activate() implements ClusterFsmEvent {}

    /// Delivered by `ControlLoop.deactivate()` when the local node is unassigned the SCALING task
    /// group. Drives any non-terminal state back to `Dormant`.
    public record Deactivate() implements ClusterFsmEvent {}

    /// Warm-up timer fired — protection window closed. Drives `Warmup → Evaluating`.
    public record ActivationTimeReached() implements ClusterFsmEvent {}

    /// A slice reached `ACTIVE`, starting a per-slice cooldown. Drives `Evaluating → Cooldown`
    /// (or stays within Cooldown, extending the active-cooldown set).
    public record CooldownRequested(Artifact artifact, long cooldownStartMs) implements ClusterFsmEvent {}

    /// Cooldown expiry tick. Cooldown's onEntry schedules this periodically; when the last slice
    /// cooldown has expired the state handler drives `Cooldown → Evaluating`.
    public record CooldownExpired() implements ClusterFsmEvent {}
}
