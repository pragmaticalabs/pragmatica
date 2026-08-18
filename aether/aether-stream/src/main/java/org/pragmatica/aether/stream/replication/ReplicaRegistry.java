// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.aether.stream.replication.PartitionKey.partitionKey;
import static org.pragmatica.aether.stream.replication.ReplicaDescriptor.replicaDescriptor;


public final class ReplicaRegistry {
    private final ConcurrentHashMap<PartitionKey, ConcurrentHashMap<NodeId, ReplicaDescriptor>> replicas = new ConcurrentHashMap<>();

    private final WatermarkStore watermarkStore;
    private final ReplicaAssignmentStore assignmentStore;
    private final long caughtUpMaxLagOffsets;

    /// How far a `CAUGHT_UP` peer may trail the freshest peer watermark and still be trusted — see
    /// [#freshPeersFor]. Mirrors `StreamingConfig.DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS`; duplicated as a
    /// literal rather than imported so this module keeps no dependency on `aether-config`. The VALUE IS
    /// A GUESS, not a measured steady-state lag — the config knob exists so it can be relieved without
    /// a rebuild.
    public static final long DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS = 1024L;
    /// Trust a `CAUGHT_UP` peer regardless of how far it trails. This is the PRE-guard behaviour, kept
    /// for degenerate and test wiring that has no freshness requirement. The no-argument factories
    /// deliberately do NOT default to it: an unwired path must come up GUARDED, never silently inert.
    /// A fence whose signal provenance is never exercised is a fence that does nothing.
    public static final long CAUGHT_UP_LAG_UNBOUNDED = Long.MAX_VALUE;

    private ReplicaRegistry(WatermarkStore watermarkStore,
                            ReplicaAssignmentStore assignmentStore,
                            long caughtUpMaxLagOffsets) {
        this.watermarkStore = watermarkStore;
        this.assignmentStore = assignmentStore;
        this.caughtUpMaxLagOffsets = caughtUpMaxLagOffsets;
    }

    public static ReplicaRegistry replicaRegistry() {
        return new ReplicaRegistry(WatermarkStore.NOOP, ReplicaAssignmentStore.NOOP, DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
    }

    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore) {
        return new ReplicaRegistry(watermarkStore, ReplicaAssignmentStore.NOOP, DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
    }

    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore,
                                                  ReplicaAssignmentStore assignmentStore) {
        return new ReplicaRegistry(watermarkStore, assignmentStore, DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS);
    }

    /// Production wiring for a registry with no persistent stores: the freshness bound comes from
    /// `StreamingConfig.caughtUpMaxLagOffsets`. Mirrors [#replicaRegistry()], which uses the same NOOP
    /// stores but falls back to [#DEFAULT_CAUGHT_UP_MAX_LAG_OFFSETS].
    public static ReplicaRegistry replicaRegistry(long caughtUpMaxLagOffsets) {
        return new ReplicaRegistry(WatermarkStore.NOOP, ReplicaAssignmentStore.NOOP, caughtUpMaxLagOffsets);
    }

    /// Production wiring: the freshness bound comes from `StreamingConfig.caughtUpMaxLagOffsets`.
    public static ReplicaRegistry replicaRegistry(WatermarkStore watermarkStore,
                                                  ReplicaAssignmentStore assignmentStore,
                                                  long caughtUpMaxLagOffsets) {
        return new ReplicaRegistry(watermarkStore, assignmentStore, caughtUpMaxLagOffsets);
    }

    public long caughtUpMaxLagOffsets() {
        return caughtUpMaxLagOffsets;
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

    /// PEER replicas of `(streamName, partition)` — excluding `self` — that are both `CAUGHT_UP` AND
    /// FRESH, i.e. whose `confirmedOffset` trails the freshest peer watermark by no more than
    /// `caughtUpMaxLagOffsets`.
    ///
    /// **Why the state alone is not enough.** `CAUGHT_UP` never downgrades — nothing moves a replica out
    /// of it when it stops acking. Under partition the value does not go stale, it FREEZES at its last
    /// good reading and goes on reading as healthy forever. Two consumers acted on that raw state:
    /// `ForwardingReadRouter` picked read targets with it (so a replica that stopped acking kept serving
    /// stale data with no error) and `AetherNode.streamCatchupView` counted it (so an owner could release
    /// its ring believing enough replicas were caught up).
    ///
    /// **One method, both consumers, on purpose.** Fixing one reader and not the other is precisely the
    /// half-applied fix that left #590 live at the placement grain: `CommunityLivenessView` was built as
    /// its fix and had exactly one consumer, which was not the placement planner. Routing both call sites
    /// through this method makes that failure mode structural rather than a review question.
    ///
    /// **A third peer-side reader exists and deliberately does NOT call this.** `PartitionBackfill.
    /// selectSource` picks a backfill donor as the `max(confirmedOffset)` over non-self `CAUGHT_UP`
    /// peers. That argmax is the very value this method uses as its freshness reference, so the donor has
    /// lag 0 by construction and is always fresh: routing it through here would select the identical node
    /// every time. Choosing the freshest peer is STRICTLY STRONGER than requiring a peer to be within a
    /// bound of the freshest, so the guard has nothing to add. Do not "complete" the fix there — it is
    /// already maximally fresh, and the audit that flags it as unguarded is reading the call graph rather
    /// than the arithmetic.
    ///
    /// **The reference is the freshest PEER watermark, not the owner's ring head.** The registry has no
    /// head or HRW knowledge by design (see [#incompletePartitionsFor(NodeId, Predicate)]), and
    /// `ForwardingReadRouter` runs on nodes that are forwarding precisely BECAUSE they do not hold the
    /// partition — so a head-based reference would be unavailable at the consumer that needs it most. A
    /// relative reference is available everywhere the descriptors are.
    ///
    /// Consequences worth knowing:
    ///  - a write-idle partition has every peer at the same watermark, so lag is 0 and nothing is falsely
    ///    aged out. This is the exact failure a time-based TTL would have caused, since NOTHING refreshes
    ///    a watermark on a quiet partition;
    ///  - the asymmetric-partition case is caught: writes continue, the owner keeps acking the reachable
    ///    peers so the reference advances, and the peer that stopped acking falls behind it;
    ///  - a partition with ONE registered peer compares that peer against itself, so lag is always 0. No
    ///    relative judgement is possible from a single sample, and inventing one would be a guess;
    ///  - if EVERY peer row freezes together, all lags stay 0 and none is flagged. A registry that sees no
    ///    fresh acks at all cannot distinguish that from a quiet partition.
    ///
    /// SELF is excluded, and deliberately never lag-checked anywhere: a node never acks itself, so its own
    /// descriptor keeps the `SYNCING` / `-1` seed for the partition's lifetime (#593) and its `CAUGHT_UP`
    /// comes from backfill completion rather than the ack path. Comparing a self row against a peer
    /// watermark would manufacture lag on a healthy owner.
    public List<ReplicaDescriptor> freshPeersFor(String streamName, int partition, NodeId self) {
        var peers = replicasFor(streamName, partition).stream()
                               .filter(descriptor -> !descriptor.nodeId()
                                                                .equals(self))
                               .filter(descriptor -> descriptor.state() == ReplicationState.CAUGHT_UP)
                               .toList();
        var reference = peers.stream().mapToLong(ReplicaDescriptor::confirmedOffset).max().orElse(Long.MIN_VALUE);

        return peers.stream()
                    .filter(descriptor -> withinLagBound(descriptor, reference))
                    .toList();
    }

    /// No special-casing of the unbounded sentinel: a bound of [#CAUGHT_UP_LAG_UNBOUNDED] admits every
    /// lag by arithmetic alone. `reference` is only `Long.MIN_VALUE` when the peer list is empty, and
    /// then there is nothing to filter, so the subtraction cannot underflow.
    private boolean withinLagBound(ReplicaDescriptor descriptor, long reference) {
        return reference - descriptor.confirmedOffset() <= caughtUpMaxLagOffsets;
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
    public List<PartitionKey> incompletePartitionsFor(NodeId nodeId,
                                                      Predicate<ReplicaDescriptor> caughtUpNeedsReverify) {
        return replicas.entrySet()
                       .stream()
                       .filter(entry -> needsRedrive(entry.getValue(),
                                                     nodeId,
                                                     caughtUpNeedsReverify))
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
