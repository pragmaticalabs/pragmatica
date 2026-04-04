package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Persistence abstraction for replica assignments.
/// Implementations write assignment state to durable storage (e.g. KV-Store).
@FunctionalInterface public interface ReplicaAssignmentStore {
    @Contract void persistAssignment(String streamName, int partition, NodeId nodeId, boolean assigned);

    ReplicaAssignmentStore NOOP = (_, _, _, _) -> {};
}
