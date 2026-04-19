// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.serialization.Codec;

import java.util.List;
import java.util.Set;


/// Compact per-community view distributed by governors to community workers via
/// Tier 3 `WorkerMetricsPing` (see spec §7.4). Mirrors `ClusterGenerationSnapshot`
/// at the community scope: governor identity, members, partitions held, and a
/// back-reference to the last-observed core epoch for cross-tier fencing.
@Codec public record CommunityGenerationSnapshot(String communityId,
                                                 long communityTerm,
                                                 Epoch communityEpoch,
                                                 NodeId governorNodeId,
                                                 List<NodeId> members,
                                                 Epoch observedCoreEpoch,
                                                 Set<String> partitionsHeld,
                                                 HlcTimestamp committedAt) {
    public CommunityGenerationSnapshot {
        if (communityId == null) {communityId = "";}
        if (communityEpoch == null) {communityEpoch = Epoch.ZERO;}
        if (observedCoreEpoch == null) {observedCoreEpoch = Epoch.ZERO;}
        members = members == null
                 ? List.of()
                 : List.copyOf(members);
        partitionsHeld = partitionsHeld == null
                        ? Set.of()
                        : Set.copyOf(partitionsHeld);
        if (committedAt == null) {committedAt = HlcTimestamp.ZERO;}
    }

    public static CommunityGenerationSnapshot communityGenerationSnapshot(String communityId,
                                                                          long communityTerm,
                                                                          Epoch communityEpoch,
                                                                          NodeId governorNodeId,
                                                                          List<NodeId> members,
                                                                          Epoch observedCoreEpoch,
                                                                          Set<String> partitionsHeld,
                                                                          HlcTimestamp committedAt) {
        return new CommunityGenerationSnapshot(communityId,
                                               communityTerm,
                                               communityEpoch,
                                               governorNodeId,
                                               members,
                                               observedCoreEpoch,
                                               partitionsHeld,
                                               committedAt);
    }

    public static CommunityGenerationSnapshot empty(String communityId, NodeId governorNodeId) {
        return new CommunityGenerationSnapshot(communityId,
                                               0L,
                                               Epoch.ZERO,
                                               governorNodeId,
                                               List.of(),
                                               Epoch.ZERO,
                                               Set.of(),
                                               HlcTimestamp.ZERO);
    }
}
