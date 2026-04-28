// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;

import java.util.List;


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

    record RemoteSwimHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAtEpoch, long producedAtMs) implements HealthSignal {
        public RemoteSwimHint(NodeId observer, NodeId peer, HealthHint hint, Epoch observedAtEpoch) {
            this(observer, peer, hint, observedAtEpoch, 0L);
        }

        @Override public Epoch observedAt() {
            return observedAtEpoch;
        }
    }

    record RemoteConnectivity(NodeId observer,
                              NodeId peer,
                              ConnectivityReport state,
                              Epoch observedAtEpoch,
                              long producedAtMs) implements HealthSignal {
        public RemoteConnectivity(NodeId observer, NodeId peer, ConnectivityReport state, Epoch observedAtEpoch) {
            this(observer, peer, state, observedAtEpoch, 0L);
        }

        @Override public Epoch observedAt() {
            return observedAtEpoch;
        }
    }
}
