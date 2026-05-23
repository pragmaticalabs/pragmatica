// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.cluster.metrics;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Codec;

import java.util.Set;


/// Per-community health report produced by a Spokesman (Tier 2) and piggybacked on
/// that core node's Tier 1 `ClusterSyncPong` to the leader.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §7.3 and
/// `aether/docs/specs/reachability-aggregator-spec.md` Layer 6 for the optional
/// `communityReachability` field that propagates the spokesman's aggregated
/// per-community transport reachability snapshot up to the cluster leader.
@Codec public record CommunityReport(String communityId,
                                     long communityTerm,
                                     long communityEpochTerm,
                                     long communityEpochCounter,
                                     NodeId governorNodeId,
                                     int memberCount,
                                     int healthyMembers,
                                     int suspectedMembers,
                                     int faultyMembers,
                                     Set<String> partitionsHeld,
                                     long lastPongFromGovernorAtMs,
                                     Option<AggregatedReachabilitySnapshot> communityReachability) {
    public CommunityReport {
        if (communityId == null) {communityId = "";}
        partitionsHeld = partitionsHeld == null
                        ? Set.of()
                        : Set.copyOf(partitionsHeld);
        communityReachability = communityReachability == null
                                ? Option.none()
                                : communityReachability;
    }

    public static CommunityReport communityReport(String communityId,
                                                  long communityTerm,
                                                  long communityEpochTerm,
                                                  long communityEpochCounter,
                                                  NodeId governorNodeId,
                                                  int memberCount,
                                                  int healthyMembers,
                                                  int suspectedMembers,
                                                  int faultyMembers,
                                                  Set<String> partitionsHeld,
                                                  long lastPongFromGovernorAtMs) {
        return new CommunityReport(communityId,
                                   communityTerm,
                                   communityEpochTerm,
                                   communityEpochCounter,
                                   governorNodeId,
                                   memberCount,
                                   healthyMembers,
                                   suspectedMembers,
                                   faultyMembers,
                                   partitionsHeld,
                                   lastPongFromGovernorAtMs,
                                   Option.none());
    }
}
