// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;


public record ReplicaDescriptor(NodeId nodeId,
                                String streamName,
                                int partition,
                                long confirmedOffset,
                                ReplicationState state,
                                Epoch epoch) {
    /// A row with no epoch recorded ([Epoch#ZERO]): written by a local update or rebuilt from persisted watermarks.
    public static ReplicaDescriptor replicaDescriptor(NodeId nodeId,
                                                      String streamName,
                                                      int partition,
                                                      long confirmedOffset,
                                                      ReplicationState state) {
        return new ReplicaDescriptor(nodeId, streamName, partition, confirmedOffset, state, Epoch.ZERO);
    }

    /// A row confirmed under the committed owner epoch `epoch` (#1730 phase 2): the owner counts it only while that is its
    /// current epoch.
    public static ReplicaDescriptor replicaDescriptor(NodeId nodeId,
                                                      String streamName,
                                                      int partition,
                                                      long confirmedOffset,
                                                      ReplicationState state,
                                                      Epoch epoch) {
        return new ReplicaDescriptor(nodeId, streamName, partition, confirmedOffset, state, epoch);
    }
}
