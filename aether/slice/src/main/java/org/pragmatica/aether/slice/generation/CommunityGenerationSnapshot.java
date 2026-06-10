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


@Codec
public record CommunityGenerationSnapshot(String communityId,
                                          long communityTerm,
                                          Epoch communityEpoch,
                                          NodeId governorNodeId,
                                          List<NodeId> members,
                                          Epoch observedCoreEpoch,
                                          Set<String> partitionsHeld,
                                          HlcTimestamp committedAt) {
    public CommunityGenerationSnapshot {
        members = List.copyOf(members);
        partitionsHeld = Set.copyOf(partitionsHeld);
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
