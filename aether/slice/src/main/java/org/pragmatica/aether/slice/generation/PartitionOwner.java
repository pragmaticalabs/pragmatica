// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


/// Current owner of one DHT partition.
///
/// See `aether/docs/specs/cluster-generation-spec.md` §6 / §9.
@Codec public record PartitionOwner(String partitionId,
                                    NodeId ownerNodeId,
                                    String ownerCommunityId,
                                    Epoch ownerEpoch,
                                    long ownershipTerm) {
    public PartitionOwner {
        if (partitionId == null) {partitionId = "";}
        if (ownerCommunityId == null) {ownerCommunityId = "";}
        if (ownerEpoch == null) {ownerEpoch = Epoch.ZERO;}
    }

    public static PartitionOwner partitionOwner(String partitionId,
                                                NodeId ownerNodeId,
                                                String ownerCommunityId,
                                                Epoch ownerEpoch,
                                                long ownershipTerm) {
        return new PartitionOwner(partitionId, ownerNodeId, ownerCommunityId, ownerEpoch, ownershipTerm);
    }
}
