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
    /// `KVStoreNotification.ValuePut<LeaderKey>` on every node. `viewSequence` is the committed
    /// leader-election sequence (H4 fence) — STRICTLY POSITIVE for a real consensus commit, and a
    /// non-positive sentinel ([`#NO_SEQUENCE`]) for the sequence-less synthesis paths (KV-pull
    /// adoption, local-election mode) that carry no sequence. The `Led` swap fence treats a
    /// sentinel sequence as "always adoptable" so those paths keep their prior unfenced behaviour.
    public record LeaderCommitted(NodeId leader, long viewSequence) implements ClusterFsmEvent {
        /// Sentinel sequence for commits synthesized without a real consensus viewSequence
        /// (KV-pull adoption in `adoptLeaderFromKvIfPresent`, local-election mode). Bypasses the
        /// `Led` swap fence.
        public static final long NO_SEQUENCE = 0L;

        /// Sequence-less convenience constructor — synthesizes a commit carrying [`#NO_SEQUENCE`].
        /// Used by KV-pull adoption and local-election mode, neither of which carries a real
        /// committed sequence.
        public LeaderCommitted(NodeId leader) {
            this(leader, NO_SEQUENCE);
        }
    }

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

    /// Fired by [`LeaderElectionState.Led`]'s lease timer when the believed leader's
    /// leader-stamped `ClusterSync` ping has been silent for
    /// [`LeaderElectionContext#LEADER_SILENCE_THRESHOLD_INTERVALS`] consecutive lease-check
    /// intervals. Drives `Led → ReElecting`, providing the recovery edge that was previously
    /// absent when a believed leader stays SWIM-alive but never re-commits its leadership (the
    /// crossed-pointer zero-leader wedge: every node in `Led(other)`, nobody believes self, so
    /// re-election never fires off `NodeGone`). `silentLeader` is the leader the lease tracked —
    /// carried so a stale `LeaderSilent` from a prior tenure (leader already swapped) is
    /// discarded rather than triggering a spurious re-election.
    public record LeaderSilent(NodeId silentLeader) implements ClusterFsmEvent {}
}
