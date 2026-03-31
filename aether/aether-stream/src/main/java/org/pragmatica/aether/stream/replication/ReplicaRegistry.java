package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicaDescriptor.replicaDescriptor;

/// Registry of stream partition replicas.
/// Thread-safe for concurrent registration and lookup.
public final class ReplicaRegistry {
    private final ConcurrentHashMap<PartitionKey, ConcurrentHashMap<NodeId, ReplicaDescriptor>> replicas = new ConcurrentHashMap<>();

    private ReplicaRegistry() {}

    /// Create a new empty replica registry.
    public static ReplicaRegistry replicaRegistry() {
        return new ReplicaRegistry();
    }

    /// Register a node as a replica for the given stream partition.
    /// Initial state is SYNCING with confirmed offset -1.
    public void registerReplica(String streamName, int partition, NodeId nodeId) {
        var key = partitionKey(streamName, partition);
        var descriptor = replicaDescriptor(nodeId, streamName, partition, -1L, ReplicationState.SYNCING);

        replicas.computeIfAbsent(key, _ -> new ConcurrentHashMap<>())
                .put(nodeId, descriptor);
    }

    /// Unregister a node as a replica for the given stream partition.
    public void unregisterReplica(String streamName, int partition, NodeId nodeId) {
        var key = partitionKey(streamName, partition);

        option(replicas.get(key))
            .onPresent(nodeMap -> nodeMap.remove(nodeId));
    }

    /// Get all replica descriptors for a given stream partition.
    /// Returns an empty list if no replicas are registered.
    public List<ReplicaDescriptor> replicasFor(String streamName, int partition) {
        var key = partitionKey(streamName, partition);

        return option(replicas.get(key))
            .map(nodeMap -> List.copyOf(nodeMap.values()))
            .or(List.of());
    }

    /// Update the confirmed watermark for a specific replica.
    /// Also transitions state to CAUGHT_UP if the replica reaches the expected offset.
    public void updateWatermark(String streamName, int partition, NodeId nodeId, long confirmedOffset) {
        var key = partitionKey(streamName, partition);

        option(replicas.get(key))
            .onPresent(nodeMap -> nodeMap.computeIfPresent(nodeId, (_, _) ->
                replicaDescriptor(nodeId, streamName, partition, confirmedOffset, ReplicationState.CAUGHT_UP)));
    }

    /// Compute the minimum confirmed offset across all replicas for a partition.
    /// Returns `Option.none()` if no replicas are registered.
    public Option<Long> minConfirmedOffset(String streamName, int partition) {
        var descriptors = replicasFor(streamName, partition);

        if (descriptors.isEmpty()) {
            return Option.none();
        }

        return Option.some(descriptors.stream()
                                      .mapToLong(ReplicaDescriptor::confirmedOffset)
                                      .min()
                                      .getAsLong());
    }
}
