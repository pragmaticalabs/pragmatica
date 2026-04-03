package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;


/// Manages replication of stream events from governor to worker replicas.
public interface ReplicationManager {
    @Contract void replicateEvent(String streamName, int partition, long offset, byte[] payload, long timestamp);
    @Contract void handleAck(ReplicationMessage.ReplicateAck ack);
    ReplicaRegistry registry();

    ReplicationManager NONE = noOpReplicationManager();

    static ReplicationManager replicationManager(NodeId governorId,
                                                 ReplicaRegistry registry,
                                                 ReplicationTransport transport) {
        return new DefaultReplicationManager(governorId, registry, transport);
    }

    static ReplicationManager replicationManager(NodeId governorId, ReplicaRegistry registry) {
        return replicationManager(governorId, registry, ReplicationTransport.NOOP);
    }

    private static ReplicationManager noOpReplicationManager() {
        var emptyRegistry = ReplicaRegistry.replicaRegistry();
        return new ReplicationManager() {
            @Contract@Override public void replicateEvent(String streamName,
                                                          int partition,
                                                          long offset,
                                                          byte[] payload,
                                                          long timestamp) {}

            @Contract@Override public void handleAck(ReplicationMessage.ReplicateAck ack) {}

            @Override public ReplicaRegistry registry() {
                return emptyRegistry;
            }
        };
    }
}
