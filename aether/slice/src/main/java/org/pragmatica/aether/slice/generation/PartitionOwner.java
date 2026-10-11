// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.slice.generation;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.serialization.Codec;


@Codec
public record PartitionOwner(String partitionId,
                             NodeId ownerNodeId,
                             String ownerCommunityId,
                             Epoch ownerEpoch,
                             long ownershipTerm) {
    public static PartitionOwner partitionOwner(String partitionId,
                                                NodeId ownerNodeId,
                                                String ownerCommunityId,
                                                Epoch ownerEpoch,
                                                long ownershipTerm) {
        return new PartitionOwner(partitionId, ownerNodeId, ownerCommunityId, ownerEpoch, ownershipTerm);
    }
}
