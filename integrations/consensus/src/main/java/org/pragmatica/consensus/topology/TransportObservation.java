/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.consensus.topology;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.messaging.Message;

import java.util.List;

/// Observations made by THIS NODE about the local transport-layer state of cluster peers.
///
/// Epistemically distinct from {@link MembershipDecision}: a `TransportObservation` is a *local*
/// fact ("I observed peer X's QUIC channel close") whereas a `MembershipDecision` is a *global*
/// fact ("the cluster has agreed peer X is no longer a member"). Conflating the two has caused
/// subtle dual-reaction bugs in the past — code reacting to transport flaps as if they were
/// cluster decisions, and vice versa.
///
/// Properties of this stream:
/// - **Local.** Each node emits its own observations independently; observations from different
///   nodes are NOT aggregated at this layer (cross-node aggregation is the job of the consensus-
///   mediated transducer that produces {@link MembershipDecision}).
/// - **Fast.** Synchronous with transport-level events. Used during cluster bootstrap before
///   consensus exists (chicken-egg: leader election needs a topology view; the membership
///   snapshot only exists after consensus commits).
/// - **May flap.** Transient network blips can produce `PeerDisconnected` followed shortly by
///   `PeerReconnected`. Subscribers must tolerate this.
/// - **Partial-view.** Reflects only what THIS node sees; may diverge from cluster consensus.
///
/// Producers:
/// - `QuicClusterNetwork.processViewChange` — QUIC handshake / eviction / reconnect events
/// - `NettyClusterNetwork.processViewChange` — Netty equivalents
/// - `SwimProtocol.emitFaultyOrUnknown` — SWIM-protocol FAULTY observations
///
/// Consumers: code paths that need fast local reactions and accept partial-view semantics —
/// notably `LeaderManager` and `ClusterFsmRouter` for the leader-election bootstrap fast-path.
/// Code that requires canonical cluster truth must consume {@link MembershipDecision} instead.
public sealed interface TransportObservation extends Message.Local {
    /// The peer that was observed.
    NodeId nodeId();

    /// Local view of the connected peer set after this observation, as known to THIS node.
    /// Note: a local snapshot, not a cluster-canonical membership view.
    List<NodeId> topology();

    /// Where the observation came from. Useful for diagnostics and for subscribers that need
    /// source-aware behaviour (rare; typically subscribers should be source-agnostic).
    ObservationSource source();

    /// Local transport observed a peer joining: handshake completed, channel established.
    /// Fires from QUIC ADD or Netty ADD operations.
    record PeerJoined(NodeId nodeId, List<NodeId> topology, ObservationSource source) implements TransportObservation {}

    /// Local transport observed a peer disconnecting: channel evicted or connection lost.
    /// May be transient — the peer may reconnect, or the cluster may not yet have decided
    /// to remove this peer from canonical membership.
    /// Fires from QUIC REMOVE / Netty REMOVE / SWIM-FAULTY.
    record PeerDisconnected(NodeId nodeId, List<NodeId> topology, ObservationSource source) implements TransportObservation {}

    /// Local transport observed a peer reconnecting after a previous disconnect.
    /// Fires from QUIC RECONNECT (Netty has no equivalent path).
    /// Subscribers may use this to invalidate disconnect-driven cleanup if appropriate.
    record PeerReconnected(NodeId nodeId, List<NodeId> topology, ObservationSource source) implements TransportObservation {}

    /// SWIM protocol marked a peer as FAULTY based on its own protocol state machine
    /// (suspect timeout + indirect-ping failure). Distinct from `PeerDisconnected` because
    /// it indicates protocol-level confidence in the peer being unreachable, not just a
    /// transport-level disconnect event.
    record PeerObservedFaulty(NodeId nodeId, List<NodeId> topology, ObservationSource source) implements TransportObservation {}

    /// THIS node observed itself shutting down. Self-emit only. Topology is empty because the
    /// local view is collapsing.
    /// Fires from QUIC SHUTDOWN with self.id().
    record SelfShutdown(NodeId nodeId, List<NodeId> topology, ObservationSource source) implements TransportObservation {}

    /// The transport layer that produced this observation.
    enum ObservationSource {
        /// QUIC transport (`QuicClusterNetwork`).
        QUIC,
        /// Netty transport (`NettyClusterNetwork`).
        NETTY,
        /// SWIM protocol layer (`SwimProtocol`).
        SWIM
    }

    static PeerJoined peerJoined(NodeId nodeId, List<NodeId> view, ObservationSource source) {
        return new PeerJoined(nodeId, view, source);
    }

    static PeerDisconnected peerDisconnected(NodeId nodeId, List<NodeId> view, ObservationSource source) {
        return new PeerDisconnected(nodeId, view, source);
    }

    static PeerReconnected peerReconnected(NodeId nodeId, List<NodeId> view, ObservationSource source) {
        return new PeerReconnected(nodeId, view, source);
    }

    static PeerObservedFaulty peerObservedFaulty(NodeId nodeId, List<NodeId> view, ObservationSource source) {
        return new PeerObservedFaulty(nodeId, view, source);
    }

    static SelfShutdown selfShutdown(NodeId nodeId, ObservationSource source) {
        return new SelfShutdown(nodeId, List.of(), source);
    }
}
