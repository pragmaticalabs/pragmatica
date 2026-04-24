// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation.fsm;

import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthSignal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;

import java.util.Set;
import java.util.function.Supplier;


/// Domain events specific to the [`org.pragmatica.aether.deployment.generation.HealthReconciler`]
/// FSM. Layered on [`ClusterFsmEvent`] so shared cluster-lifecycle events
/// (`QuorumEstablished`, `QuorumDisappeared`, `LeaderChange`, `Shutdown`) and the
/// reconciler-specific events below all flow through the same `Fsm.dispatch` path.
///
/// Mapping from legacy call sites:
/// - `HealthReconciler.start(Epoch)` → [`BecameLeader(Epoch)`]. The thin adapter first dispatches
///   [`ClusterFsmEvent.QuorumEstablished`] (idempotent if already past Dormant), then
///   `BecameLeader(epoch)` to drive the QuorumWaiting/Following → LeadingSteady transition.
/// - `HealthReconciler.stop(StopReason.LEADER_LOST)` → [`ClusterFsmEvent.QuorumDisappeared`].
///   The reconciler was driven by a leader-change notification; the cluster-wide quorum did not
///   necessarily disappear, but from this node's viewpoint the leader-only responsibility is gone
///   and all leader data must be cleared — the same semantics as `QuorumDisappeared`.
/// - `HealthReconciler.stop(StopReason.SHUTDOWN)` → [`ClusterFsmEvent.Shutdown`].
/// - `HealthReconciler.seedSnapshot(snapshot)` → [`SnapshotSeeded(snapshot)`].
/// - `HealthReconciler.reseedMembership(snapshot)` → [`MembershipReseeded(snapshot)`].
/// - `HealthReconciler.onSignal(signal)` → [`SignalReceived(signal)`].
/// - `HealthReconciler.requestReprojection(supplier, reason)` → [`ReprojectionRequested(supplier, reason)`].
/// - Reprojection task completion (inside the executor) → [`ReprojectionCompleted(startEpoch, newSnapshot)`]
///   or [`ReprojectionFailed(startEpoch)`].
/// - `cluster.apply(commands).onSuccess` → [`CommandsApplied(reason, previous, next)`].
/// - `cluster.apply(commands).onFailure` → [`CommandsApplyFailed(attempted)`].
public interface HealthReconcilerEvents extends ClusterFsmEvent {
    record BecameLeader(Epoch startEpoch) implements HealthReconcilerEvents{}

    record SnapshotSeeded(ClusterGenerationSnapshot snapshot) implements HealthReconcilerEvents{}

    record MembershipReseeded(ClusterGenerationSnapshot freshProjection) implements HealthReconcilerEvents{}

    record SignalReceived(HealthSignal signal) implements HealthReconcilerEvents{}

    record ReprojectionRequested(Supplier<ClusterGenerationSnapshot> supplier, String reason) implements HealthReconcilerEvents{}

    record ReprojectionCompleted(Epoch startEpoch, ClusterGenerationSnapshot newSnapshot) implements HealthReconcilerEvents{}

    record ReprojectionFailed(Epoch startEpoch) implements HealthReconcilerEvents{}

    record CommandsApplied(GenerationReason reason,
                           Epoch startEpoch,
                           ClusterGenerationSnapshot previousSnapshot,
                           ClusterGenerationSnapshot nextSnapshot) implements HealthReconcilerEvents{}

    record CommandsApplyFailed(Epoch startEpoch, Set<NodeId> attemptedNodeIds) implements HealthReconcilerEvents{}
}
