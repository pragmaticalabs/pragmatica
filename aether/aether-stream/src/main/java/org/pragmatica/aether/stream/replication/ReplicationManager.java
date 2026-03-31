package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;

/// Manages replication of stream events from governor to worker replicas.
public interface ReplicationManager {

    /// Called after a successful publish. Replicates the event to all registered replicas.
    /// Fire-and-forget: does not block the publish path.
    void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp);

    /// Handle an acknowledgment from a replica.
    void handleAck(ReplicationMessage.ReplicateAck ack);

    /// Get the replica registry for querying replica state.
    ReplicaRegistry registry();

    /// No-op manager for non-replicated streams.
    ReplicationManager NONE = noOpReplicationManager();

    /// Create a replication manager with the given governor ID, registry, and transport.
    static ReplicationManager replicationManager(NodeId governorId, ReplicaRegistry registry, ReplicationTransport transport) {
        return new DefaultReplicationManager(governorId, registry, transport);
    }

    /// Create a replication manager with the given governor ID, registry, and no-op transport.
    static ReplicationManager replicationManager(NodeId governorId, ReplicaRegistry registry) {
        return replicationManager(governorId, registry, ReplicationTransport.NOOP);
    }

    private static ReplicationManager noOpReplicationManager() {
        var emptyRegistry = ReplicaRegistry.replicaRegistry();

        return new ReplicationManager() {
            @Override
            public void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp) {}

            @Override
            public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override
            public ReplicaRegistry registry() {
                return emptyRegistry;
            }
        };
    }
}
