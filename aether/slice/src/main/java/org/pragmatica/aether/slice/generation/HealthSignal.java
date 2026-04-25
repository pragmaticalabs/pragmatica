// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;

import java.util.List;


/// Inputs to the leader's `HealthReconciler` — exactly the events listed in spec §8.1.
/// Every atom-mutating decision derives from one of these signals.
///
/// Runtime-only value (no `@Codec`). Wire-level encoding for ping/pong integration is
/// introduced in Commit 3; for now the reconciler consumes these via direct in-process calls.
///
/// Every variant carries an `observedAt` epoch so the leader-side reconciler can
/// epoch-fence stale reports that crossed a leader-change boundary. Signals whose
/// `observedAt == Epoch.ZERO` bypass the fence — operator actions and KV-atom
/// notifications are authoritative and not tied to an observation window.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §8.1
/// and `aether/docs/specs/clustersync-refactor-spec.md` commit 5.
public sealed interface HealthSignal {
    Epoch observedAt();

    record PingTimeout(NodeId nodeId, int missedIntervals, Epoch observedAt) implements HealthSignal{}

    record SwimHint(NodeId nodeId, HealthHint state, Epoch observedAt) implements HealthSignal{}

    record QuicDisconnect(NodeId nodeId, Epoch observedAt) implements HealthSignal{}

    record DrainCompleted(NodeId nodeId, Epoch observedAt) implements HealthSignal{}

    record GovernorAnnounced(String communityId, NodeId governor, long communityTerm, Epoch observedAt) implements HealthSignal {
        public GovernorAnnounced(String communityId, NodeId governor, long communityTerm) {
            this(communityId, governor, communityTerm, Epoch.ZERO);
        }
    }

    record CommunityDissolved(String communityId, Epoch observedAt) implements HealthSignal {
        public CommunityDissolved(String communityId) {
            this(communityId, Epoch.ZERO);
        }
    }

    record SpokesmanAssignmentFailed(NodeId coreNodeId,
                                     List<String> affectedCommunities,
                                     String reason,
                                     Epoch observedAt) implements HealthSignal {
        public SpokesmanAssignmentFailed {
            affectedCommunities = List.copyOf(affectedCommunities);
        }

        public SpokesmanAssignmentFailed(NodeId coreNodeId, List<String> affectedCommunities, String reason) {
            this(coreNodeId, affectedCommunities, reason, Epoch.ZERO);
        }
    }

    record OperatorAction(OperatorIntent intent, Epoch observedAt) implements HealthSignal {
        public OperatorAction(OperatorIntent intent) {
            this(intent, Epoch.ZERO);
        }
    }

    /// Remote SWIM health observation from a follower carried via ClusterSyncPong.
    /// `producedAtMs` is the observer's wall-clock millis at observation time —
    /// consumers apply a configurable TTL to drop stale reports independent of
    /// cluster epoch.
    record RemoteSwimHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAtEpoch, long producedAtMs) implements HealthSignal {
        public RemoteSwimHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAtEpoch) {
            this(observer, peer, hint, observedAtEpoch, 0L);
        }

        @Override public Epoch observedAt() {
            return observedAtEpoch;
        }
    }

    /// Remote QUIC connectivity observation from a follower carried via ClusterSyncPong.
    /// `producedAtMs` is the observer's wall-clock millis at observation time —
    /// consumers apply a configurable TTL to drop stale reports independent of
    /// cluster epoch.
    record RemoteConnectivity(NodeId observer, NodeId peer, ConnectivityReport state, Epoch observedAtEpoch, long producedAtMs) implements HealthSignal {
        public RemoteConnectivity(NodeId observer, NodeId peer, ConnectivityReport state, Epoch observedAtEpoch) {
            this(observer, peer, state, observedAtEpoch, 0L);
        }

        @Override public Epoch observedAt() {
            return observedAtEpoch;
        }
    }
}
