/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 */

package org.pragmatica.consensus.leader.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.fsm.ClusterFsmEvent;

/// Leader-election domain events layered on top of [`ClusterFsmEvent`]. The FSM's event type is
/// `ClusterFsmEvent` (not a separate sealed interface), so domain events implement that marker
/// directly and flow through the same `Fsm.dispatch` path as cluster-lifecycle events.
public final class LeaderElectionEvents {
    private LeaderElectionEvents() {}

    /// A leader has been committed to the KV-store. Typically triggered by
    /// `KVStoreNotification.ValuePut<LeaderKey>` on every node.
    public record LeaderCommitted(NodeId leader) implements ClusterFsmEvent {}

    /// Consensus sync has completed and the node is ready to propose a leader. Sent by
    /// `AetherNode.startClusterAsync` after `clusterNode.start()` succeeds.
    public record ConsensusReady() implements ClusterFsmEvent {}

    /// Periodic tick driving leader-proposal submission while in an election state.
    public record ElectionTick() implements ClusterFsmEvent {}

    /// Outcome of a submitted proposal. `success=true` means the proposal Promise resolved
    /// (submitted to consensus, not yet necessarily committed); `success=false` means it failed
    /// or timed out.
    public record ProposalSettled(NodeId candidate, boolean success, String detail) implements ClusterFsmEvent {}

    /// Fired by [`LeaderElectionState.AwaitingKvSync`] when its grace timer elapses without
    /// observing a committed leader via the KV-sync push notification path. Triggers the
    /// fall-through transition into `Electing` / `ReElecting` so a genuinely fresh cluster
    /// (no leader committed anywhere) can elect one.
    public record KvSyncGraceTimeout() implements ClusterFsmEvent {}
}
