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
