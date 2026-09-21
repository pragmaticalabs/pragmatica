// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.serialization.Codec;


/// Leader-independent observation exchange. Authority is checked separately by the receiver.
/// Fresh-cluster schema: no compatibility with earlier ping/pong layouts is provided.
@Codec
public sealed interface ClusterSyncMessage extends ProtocolMessage {
    @Override
    default StreamType streamType() {
        return StreamType.METRICS;
    }

    /// A partial producer batch never claims that omitted producers have disappeared.
    /// Only the authority-bearing batch may change drain, readiness, or provisioning state.
    record ClusterSyncPing(NodeId sender,
                           Map<NodeId, MetricObservation> observations,
                           long rabiaTerm,
                           long epochTerm,
                           long epochCounter,
                           Set<NodeId> evictionHints,
                           Set<NodeId> drainNodes,
                           Map<NodeId, String> readinessView,
                           Set<NodeId> dispatchedNodes,
                           boolean completeMetricsRoster,
                           boolean carriesAuthority) implements ClusterSyncMessage {
        public ClusterSyncPing {
            observations = Map.copyOf(observations);
            evictionHints = Set.copyOf(evictionHints);
            drainNodes = Set.copyOf(drainNodes);
            readinessView = Map.copyOf(readinessView);
            dispatchedNodes = Set.copyOf(dispatchedNodes);
        }

        public Map<NodeId, Map<String, Double>> allMetrics() {
            return observations.entrySet()
                               .stream()
                               .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey,
                                                                     entry -> entry.getValue()
                                                                                   .values()));
        }
    }

    record ClusterSyncPong(NodeId sender,
                           MetricObservation observation,
                           long incarnation,
                           long observedRabiaTerm,
                           long observedEpochTerm,
                           long observedEpochCounter,
                           String lifecycleState,
                           List<CommunityReport> communityReports,
                           List<PeerHealthObservation> peerHealth,
                           List<PeerConnectivityObservation> peerConnectivity,
                           Option<NodeId> readyCandidate) implements ClusterSyncMessage {
        public ClusterSyncPong {
            communityReports = List.copyOf(communityReports);
            peerHealth = List.copyOf(peerHealth);
            peerConnectivity = List.copyOf(peerConnectivity);
        }

        public Map<String, Double> metrics() {
            return observation.values();
        }
    }
}
