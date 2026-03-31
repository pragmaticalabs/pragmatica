package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;

/// Persistence abstraction for replica assignments.
/// Implementations write assignment state to durable storage (e.g. KV-Store).
@FunctionalInterface
public interface ReplicaAssignmentStore {

    /// Persist a replica assignment change for a stream partition.
    /// @param assigned true when registering, false when unregistering
    void persistAssignment(String streamName, int partition, NodeId nodeId, boolean assigned);

    /// No-op store for testing or single-node deployments.
    ReplicaAssignmentStore NOOP = (_, _, _, _) -> {};
}
