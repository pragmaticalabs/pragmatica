// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;


/// Describes a replica's current state for a specific partition.
public record ReplicaDescriptor(NodeId nodeId,
                                String streamName,
                                int partition,
                                long confirmedOffset,
                                ReplicationState state) {
    public static ReplicaDescriptor replicaDescriptor(NodeId nodeId,
                                                      String streamName,
                                                      int partition,
                                                      long confirmedOffset,
                                                      ReplicationState state) {
        return new ReplicaDescriptor(nodeId, streamName, partition, confirmedOffset, state);
    }
}
