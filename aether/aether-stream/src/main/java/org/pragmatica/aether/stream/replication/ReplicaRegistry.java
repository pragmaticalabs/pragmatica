// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicaDescriptor.replicaDescriptor;


public final class ReplicaRegistry {
    private final ConcurrentHashMap<PartitionKey, ConcurrentHashMap<NodeId, ReplicaDescriptor>> replicas = new ConcurrentHashMap<>();

    private final WatermarkStore watermarkStore;
    private final ReplicaAssignmentStore assignmentStore;

    private ReplicaRegistry(WatermarkStore watermarkStore, ReplicaAssignmentStore assignmentStore) {
        this.watermarkStore = watermarkStore;
        this.assignmentStore = assignmentStore;
    }

    public static ReplicaRegistry replicaRegistry() {
        return new ReplicaRegistry(WatermarkStore.NOOP, ReplicaAssignmentStore.NOOP);
    }

    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore) {
        return new ReplicaRegistry(watermarkStore, ReplicaAssignmentStore.NOOP);
    }

    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore,
                                                  ReplicaAssignmentStore assignmentStore) {
        return new ReplicaRegistry(watermarkStore, assignmentStore);
    }

    @Contract
    public void registerReplica(String streamName, int partition, NodeId nodeId) {
        var key = partitionKey(streamName, partition);
        var descriptor = replicaDescriptor(nodeId, streamName, partition, -1L, ReplicationState.SYNCING);

        replicas.computeIfAbsent(key, _ -> new ConcurrentHashMap<>()).put(nodeId, descriptor);
        assignmentStore.persistAssignment(streamName, partition, nodeId, true);
    }

    @Contract
    public void unregisterReplica(String streamName, int partition, NodeId nodeId) {
        var key = partitionKey(streamName, partition);

        option(replicas.get(key)).onPresent(nodeMap -> nodeMap.remove(nodeId));
        assignmentStore.persistAssignment(streamName, partition, nodeId, false);
    }

    public List<ReplicaDescriptor> replicasFor(String streamName, int partition) {
        var key = partitionKey(streamName, partition);

        return option(replicas.get(key)).map(nodeMap -> List.copyOf(nodeMap.values()))
                     .or(List.of());
    }

    /// Partitions for which `nodeId` is a registered replica that has NOT yet reached
    /// {@link ReplicationState#CAUGHT_UP}. Used by the periodic backfill re-drive to re-attempt
    /// backfill (and the cold-start promotion seam) for exactly the partitions that are still SYNCING —
    /// a CAUGHT_UP replica needs no further backfill and is skipped.
    public List<PartitionKey> incompletePartitionsFor(NodeId nodeId) {
        return incompletePartitionsFor(nodeId, _ -> false);
    }

    /// Like {@link #incompletePartitionsFor(NodeId)}, but additionally re-includes a CAUGHT_UP replica
    /// whose descriptor satisfies `caughtUpNeedsReverify`. This is the #333 write-idle-residual seam: a
    /// node that self-promoted to CAUGHT_UP under an empty/partial member view (cold-start owner-self-
    /// promote) can later turn out NOT to be the HRW owner once the member view populates, leaving it
    /// falsely CAUGHT_UP with none of the real owner's history. The redrive can never re-verify such a
    /// replica through the plain `state != CAUGHT_UP` filter, so on a write-idle partition (no live batch
    /// re-arms the gap loop) it would serve stale/empty data forever. The registry has no HRW knowledge,
    /// so the owner-aware classification is supplied by the caller (`PartitionBackfill`, which holds the
    /// member view); the registry only contributes the per-`nodeId` descriptor lookup.
    public List<PartitionKey> incompletePartitionsFor(NodeId nodeId, Predicate<ReplicaDescriptor> caughtUpNeedsReverify) {
        return replicas.entrySet()
                       .stream()
                       .filter(entry -> needsRedrive(entry.getValue(), nodeId, caughtUpNeedsReverify))
                       .map(Map.Entry::getKey)
                       .toList();
    }

    private static boolean needsRedrive(Map<NodeId, ReplicaDescriptor> nodeMap,
                                        NodeId nodeId,
                                        Predicate<ReplicaDescriptor> caughtUpNeedsReverify) {
        return option(nodeMap.get(nodeId)).map(descriptor -> isRedriveCandidate(descriptor, caughtUpNeedsReverify))
                     .or(false);
    }

    private static boolean isRedriveCandidate(ReplicaDescriptor descriptor,
                                              Predicate<ReplicaDescriptor> caughtUpNeedsReverify) {
        return descriptor.state() != ReplicationState.CAUGHT_UP || caughtUpNeedsReverify.test(descriptor);
    }

    @Contract
    public void updateWatermark(String streamName, int partition, NodeId nodeId, long confirmedOffset) {
        updateWatermark(streamName, partition, nodeId, confirmedOffset, ReplicationState.CAUGHT_UP);
    }

    /// Advance a replica's confirmed watermark, transitioning it to `state`. The watermark is ALWAYS
    /// persisted+advanced; the state is what distinguishes a live-replication ack from a replica that has
    /// genuinely covered the partition's retained history (#261). Callers that have proven full coverage
    /// (backfill completion, watermark rebuild) pass {@link ReplicationState#CAUGHT_UP}; the owner's
    /// live-ack path passes {@link ReplicationState#SYNCING} until the ack reaches back to the partition's
    /// earliest retained offset, so a replica holding only the post-join suffix is NOT a read/backfill
    /// source until backfill confirms coverage.
    @Contract
    public void updateWatermark(String streamName,
                                int partition,
                                NodeId nodeId,
                                long confirmedOffset,
                                ReplicationState state) {
        var key = partitionKey(streamName, partition);

        option(replicas.get(key)).onPresent(nodeMap -> nodeMap.computeIfPresent(nodeId,
                                                                                (_, _) -> replicaDescriptor(nodeId,
                                                                                                            streamName,
                                                                                                            partition,
                                                                                                            confirmedOffset,
                                                                                                            state)));
        watermarkStore.persistWatermark(streamName, partition, nodeId, confirmedOffset);
    }

    @Contract
    public void rebuildFromWatermarks(Map<PartitionKey, Map<NodeId, Long>> watermarks) {
        watermarks.forEach(this::rebuildPartitionWatermarks);
    }

    private void rebuildPartitionWatermarks(PartitionKey key, Map<NodeId, Long> nodeWatermarks) {
        option(replicas.get(key)).onPresent(nodeMap -> nodeWatermarks.forEach((nodeId, offset) -> rebuildSingleWatermark(nodeMap,
                                                                                                                         nodeId,
                                                                                                                         key,
                                                                                                                         offset)));
    }

    private void rebuildSingleWatermark(ConcurrentHashMap<NodeId, ReplicaDescriptor> nodeMap,
                                        NodeId nodeId,
                                        PartitionKey key,
                                        long offset) {
        nodeMap.computeIfPresent(nodeId,
                                 (_, _) -> replicaDescriptor(nodeId,
                                                             key.streamName(),
                                                             key.partition(),
                                                             offset,
                                                             ReplicationState.CAUGHT_UP));
    }

    public Option<Long> minConfirmedOffset(String streamName, int partition) {
        var descriptors = replicasFor(streamName, partition);

        if (descriptors.isEmpty()) {
            return Option.none();
        }

        return Option.some(descriptors.stream().mapToLong(ReplicaDescriptor::confirmedOffset).min().getAsLong());
    }
}
