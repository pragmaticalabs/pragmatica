package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicaDescriptor.replicaDescriptor;

/// Registry of stream partition replicas.
/// Thread-safe for concurrent registration and lookup.
/// Assignment and watermark changes are persisted via the configured stores.
public final class ReplicaRegistry {
    private final ConcurrentHashMap<PartitionKey, ConcurrentHashMap<NodeId, ReplicaDescriptor>> replicas = new ConcurrentHashMap<>();
    private final WatermarkStore watermarkStore;
    private final ReplicaAssignmentStore assignmentStore;

    private ReplicaRegistry(WatermarkStore watermarkStore, ReplicaAssignmentStore assignmentStore) {
        this.watermarkStore = watermarkStore;
        this.assignmentStore = assignmentStore;
    }

    /// Create a new empty replica registry with no-op persistence.
    public static ReplicaRegistry replicaRegistry() {
        return new ReplicaRegistry(WatermarkStore.NOOP, ReplicaAssignmentStore.NOOP);
    }

    /// Create a new empty replica registry with the given watermark store.
    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore) {
        return new ReplicaRegistry(watermarkStore, ReplicaAssignmentStore.NOOP);
    }

    /// Create a new empty replica registry with both persistence stores.
    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore,
                                                   ReplicaAssignmentStore assignmentStore) {
        return new ReplicaRegistry(watermarkStore, assignmentStore);
    }

    /// Register a node as a replica for the given stream partition.
    /// Initial state is SYNCING with confirmed offset -1.
    /// Persists the assignment to the configured store.
    public void registerReplica(String streamName, int partition, NodeId nodeId) {
        var key = partitionKey(streamName, partition);
        var descriptor = replicaDescriptor(nodeId, streamName, partition, -1L, ReplicationState.SYNCING);

        replicas.computeIfAbsent(key, _ -> new ConcurrentHashMap<>())
                .put(nodeId, descriptor);

        assignmentStore.persistAssignment(streamName, partition, nodeId, true);
    }

    /// Unregister a node as a replica for the given stream partition.
    /// Persists the removal to the configured store.
    public void unregisterReplica(String streamName, int partition, NodeId nodeId) {
        var key = partitionKey(streamName, partition);

        option(replicas.get(key))
            .onPresent(nodeMap -> nodeMap.remove(nodeId));

        assignmentStore.persistAssignment(streamName, partition, nodeId, false);
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
    /// Persists the watermark to the configured WatermarkStore.
    public void updateWatermark(String streamName, int partition, NodeId nodeId, long confirmedOffset) {
        var key = partitionKey(streamName, partition);

        option(replicas.get(key))
            .onPresent(nodeMap -> nodeMap.computeIfPresent(nodeId, (_, _) ->
                replicaDescriptor(nodeId, streamName, partition, confirmedOffset, ReplicationState.CAUGHT_UP)));

        watermarkStore.persistWatermark(streamName, partition, nodeId, confirmedOffset);
    }

    /// Rebuild in-memory watermarks from a persisted snapshot.
    /// Entries must correspond to already-registered replicas.
    public void rebuildFromWatermarks(Map<PartitionKey, Map<NodeId, Long>> watermarks) {
        watermarks.forEach(this::rebuildPartitionWatermarks);
    }

    private void rebuildPartitionWatermarks(PartitionKey key, Map<NodeId, Long> nodeWatermarks) {
        option(replicas.get(key))
            .onPresent(nodeMap -> nodeWatermarks.forEach((nodeId, offset) -> rebuildSingleWatermark(nodeMap, nodeId, key, offset)));
    }

    private void rebuildSingleWatermark(ConcurrentHashMap<NodeId, ReplicaDescriptor> nodeMap,
                                         NodeId nodeId, PartitionKey key, long offset) {
        nodeMap.computeIfPresent(nodeId, (_, _) ->
            replicaDescriptor(nodeId, key.streamName(), key.partition(), offset, ReplicationState.CAUGHT_UP));
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
