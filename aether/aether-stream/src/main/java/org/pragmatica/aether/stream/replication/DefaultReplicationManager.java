package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;

import java.util.List;

import static org.pragmatica.aether.stream.replication.ReplicationMessage.ReplicateEvents.replicateEvents;

/// Default implementation of governor-push replication.
/// Sends events to all registered replicas after each successful publish.
final class DefaultReplicationManager implements ReplicationManager {
    private final NodeId governorId;
    private final ReplicaRegistry registry;
    private final ReplicationTransport transport;

    DefaultReplicationManager(NodeId governorId, ReplicaRegistry registry, ReplicationTransport transport) {
        this.governorId = governorId;
        this.registry = registry;
        this.transport = transport;
    }

    @Contract @Override public void replicateEvent(String streamName,
                                                   int partition,
                                                   long offset,
                                                   byte[] payload,
                                                   long timestamp) {
        var replicas = registry.replicasFor(streamName, partition);
        if ( replicas.isEmpty()) {
        return;}
        sendToAllReplicas(replicas, streamName, partition, offset, payload, timestamp);
    }

    @Contract @Override public void handleAck(ReplicationMessage.ReplicateAck ack) {
        registry.updateWatermark(ack.streamName(), ack.partition(), ack.replicaId(), ack.confirmedOffset());
    }

    @Override public ReplicaRegistry registry() {
        return registry;
    }

    private void sendToAllReplicas(List<ReplicaDescriptor> replicas,
                                   String streamName,
                                   int partition,
                                   long offset,
                                   byte[] payload,
                                   long timestamp) {
        var message = buildReplicateMessage(governorId, streamName, partition, offset, payload, timestamp);
        replicas.forEach(replica -> transport.send(replica.nodeId(), message));
    }

    private static ReplicationMessage.ReplicateEvents buildReplicateMessage(NodeId governorId,
                                                                            String streamName,
                                                                            int partition,
                                                                            long offset,
                                                                            byte[] payload,
                                                                            long timestamp) {
        return replicateEvents(governorId, streamName, partition, offset, List.of(payload), List.of(timestamp));
    }
}
