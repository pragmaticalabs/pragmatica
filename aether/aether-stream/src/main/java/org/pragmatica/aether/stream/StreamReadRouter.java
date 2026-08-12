// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.fence.OwnershipEpochHighWater;
import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// Raw-event read router for the generic stream-read API (StreamRoutes). The forward-read ALGORITHM
/// lives once in {@link ForwardingReadRouter}; this class supplies only the raw-event local-read
/// ({@link #readLocal}) and decode ({@link #toRawEvents}) closures plus the owner resolver, so it cannot
/// drift from {@link PartitionedStreamAccess}'s typed read path.
public final class StreamReadRouter {
    private static final NodeId NO_SELF = new NodeId("__no_self__");

    private final StreamPartitionManager partitionManager;
    private final Option<ReplicaRegistry> replicaRegistry;
    private final Option<StreamForwardClient> forwardClient;
    private final NodeId selfNodeId;
    private final OwnerResolver ownerResolver;
    private final StreamReadForwardMetrics metrics;
    private final Option<CommittedStreamOwnerSource> committedOwnerSource;
    private final Option<OwnershipEpochHighWater> epochHighWater;
    private final Option<LinearizableBarrier> barrier;

    private StreamReadRouter(StreamPartitionManager partitionManager,
                             Option<ReplicaRegistry> replicaRegistry,
                             Option<StreamForwardClient> forwardClient,
                             NodeId selfNodeId,
                             OwnerResolver ownerResolver,
                             StreamReadForwardMetrics metrics,
                             Option<CommittedStreamOwnerSource> committedOwnerSource,
                             Option<OwnershipEpochHighWater> epochHighWater,
                             Option<LinearizableBarrier> barrier) {
        this.partitionManager = partitionManager;
        this.replicaRegistry = replicaRegistry;
        this.forwardClient = forwardClient;
        this.selfNodeId = selfNodeId;
        this.ownerResolver = ownerResolver;
        this.metrics = metrics;
        this.committedOwnerSource = committedOwnerSource;
        this.epochHighWater = epochHighWater;
        this.barrier = barrier;
    }

    public static StreamReadRouter streamReadRouter(StreamPartitionManager partitionManager,
                                                    Option<ReplicaRegistry> replicaRegistry,
                                                    Option<StreamForwardClient> forwardClient,
                                                    NodeId selfNodeId,
                                                    OwnerResolver ownerResolver,
                                                    StreamReadForwardMetrics metrics) {
        return new StreamReadRouter(partitionManager,
                                    replicaRegistry,
                                    forwardClient,
                                    selfNodeId,
                                    ownerResolver,
                                    metrics,
                                    Option.none(),
                                    Option.none(),
                                    Option.none());
    }

    /// #345 item 1e overload: also wires the COMMITTED-owner source + the ownership epoch high-water +
    /// the linearizable no-op-round barrier (1e-a) so a `LINEARIZABLE` read routes to the fenced owner,
    /// runs the owner-side guards, and orders the no-op round before serving. Non-linearizable reads are
    /// unaffected.
    public static StreamReadRouter streamReadRouter(StreamPartitionManager partitionManager,
                                                    Option<ReplicaRegistry> replicaRegistry,
                                                    Option<StreamForwardClient> forwardClient,
                                                    NodeId selfNodeId,
                                                    OwnerResolver ownerResolver,
                                                    StreamReadForwardMetrics metrics,
                                                    Option<CommittedStreamOwnerSource> committedOwnerSource,
                                                    Option<OwnershipEpochHighWater> epochHighWater,
                                                    Option<LinearizableBarrier> barrier) {
        return new StreamReadRouter(partitionManager,
                                    replicaRegistry,
                                    forwardClient,
                                    selfNodeId,
                                    ownerResolver,
                                    metrics,
                                    committedOwnerSource,
                                    epochHighWater,
                                    barrier);
    }

    public static StreamReadRouter localOnly(StreamPartitionManager partitionManager) {
        return new StreamReadRouter(partitionManager,
                                    Option.none(),
                                    Option.none(),
                                    NO_SELF,
                                    (_, _) -> Option.none(),
                                    StreamReadForwardMetrics.NOOP,
                                    Option.none(),
                                    Option.none(),
                                    Option.none());
    }

    public Promise<List<OffHeapRingBuffer.RawEvent>> read(String streamName,
                                                          int partition,
                                                          long fromOffset,
                                                          int maxEvents,
                                                          ReadPreference preference) {
        return ForwardingReadRouter.<OffHeapRingBuffer.RawEvent> forwardingReadRouter(replicaRegistry,
                                                                                      selfNodeId,
                                                                                      forwardClient,
                                                                                      preference,
                                                                                      ownerResolver,
                                                                                      this::readLocal,
                                                                                      StreamReadRouter::toRawEvents,
                                                                                      metrics,
                                                                                      committedOwnerSource,
                                                                                      epochHighWater,
                                                                                      barrier).route(streamName,
                                                                                                     partition,
                                                                                                     fromOffset,
                                                                                                     maxEvents);
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                                int partition,
                                                                long fromOffset,
                                                                int maxEvents) {
        return partitionManager.readLocal(streamName, partition, fromOffset, maxEvents)
                               .async();
    }

    /// Read-only replica-state snapshot for `(streamName, partition)` — the #260/#261/#333
    /// regression sensor surface. Assembled from THIS node's `replicaRegistry` (the per-peer
    /// confirmed-watermark + replication-state view advanced by `DefaultReplicationManager.handleAck`)
    /// plus the local partition `headOffset`/`earliestRetainedOffset`, with the deterministic HRW owner
    /// resolved via the same `ownerResolver` the read path forwards to.
    ///
    /// The registry is AUTHORITATIVE only on the partition's HRW owner: only the owner receives every
    /// replica's ack and so holds the full peer-watermark view (a non-owner mostly knows itself). The
    /// snapshot therefore reports `servedByOwner` (true when self IS the resolved owner) so an operator
    /// can tell a complete view from a partial one; when it is `false`, `hrwOwner` names the node to
    /// re-query. A Leaf: pure assembly off already-computed router/partition state, no I/O, no
    /// hot-path cost.
    ///
    /// #593: THIS node's own row is answered from local truth rather than from the registry — see
    /// [#selfRowOverride]. The registry can never advance a node's own entry (acks come from peers,
    /// and a node does not ack to itself), so without the substitution an owner reports itself
    /// permanently `SYNCING` at `-1` while serving a complete partition.
    public ReplicaSetView replicaSnapshot(String streamName, int partition) {
        var owner = ownerResolver.resolve(streamName, partition);
        var descriptors = replicaRegistry.map(registry -> registry.replicasFor(streamName, partition)).or(List.of());
        var replicas = descriptors.stream()
                                  .map(descriptor -> toReplicaView(descriptor, owner, selfRowOverride(streamName, partition, descriptor)))
                                  .sorted(Comparator.comparing(ReplicaView::nodeId))
                                  .toList();

        return new ReplicaSetView(streamName,
                                  partition,
                                  owner.map(NodeId::id),
                                  owner.map(selfNodeId::equals).or(false),
                                  partitionManager.nextExpectedOffset(streamName, partition),
                                  partitionManager.earliestRetainedOffset(streamName, partition),
                                  replicas);
    }

    private static ReplicaView toReplicaView(ReplicaDescriptor descriptor,
                                             Option<NodeId> owner,
                                             Option<LocalReplicaState> selfOverride) {
        return new ReplicaView(descriptor.nodeId().id(),
                               selfOverride.map(LocalReplicaState::state).or(descriptor.state().name()),
                               selfOverride.map(LocalReplicaState::confirmedOffset).or(descriptor.confirmedOffset()),
                               owner.map(descriptor.nodeId()::equals).or(false));
    }

    /// This node's OWN row, answered from local truth instead of the peer-ack registry (#593).
    ///
    /// `ReplicaRegistry.registerReplica` seeds every descriptor at `SYNCING` / `confirmedOffset = -1`
    /// and only `updateWatermark` advances it — and that is driven by ACKS ARRIVING FROM PEERS
    /// (`DefaultReplicationManager.handleAck`). A node never acks to itself, so its own descriptor
    /// stays at the seed value for the lifetime of the partition. On the OWNER that produced a view
    /// which read as permanently broken replication while the partition was in fact complete:
    /// measured on a live 5-node cluster, an owner reporting `SYNCING` / `-1` for itself while
    /// `ownerHeadOffset` was 24, a peer replica was `CAUGHT_UP` at 23, and all 24 events were
    /// readable in order. The data was never the problem; the row describing it was.
    ///
    /// The answering node has authoritative local knowledge of exactly ONE row — its own — so that is
    /// the only row substituted. Every peer row still comes from the registry, where an ack is the
    /// honest source. When this node does not hold the partition locally, nothing is substituted and
    /// the registry's value stands: absence of a local ring is not evidence about what a peer holds.
    private Option<LocalReplicaState> selfRowOverride(String streamName, int partition, ReplicaDescriptor descriptor) {
        if (!selfNodeId.equals(descriptor.nodeId())) {
            return Option.empty();
        }

        return partitionManager.partitionBuffer(streamName, partition)
                               .map(buffer -> new LocalReplicaState(ReplicationState.CAUGHT_UP.name(), buffer.headOffset()));
    }

    /// Carrier for the locally-derived substitution above: the state this node is genuinely in for a
    /// partition it holds, and the offset it has genuinely durably got (the ring head, not head+1 —
    /// `confirmedOffset` is a watermark of what IS present, matching the peer-ack semantics it sits
    /// beside in the same list).
    private record LocalReplicaState(String state, long confirmedOffset) {}

    /// Module-local carrier for the assembled replica-state snapshot. `ownerNodeId` is empty during the
    /// bootstrap window (placement not yet known); `servedByOwner` is true only when the answering node
    /// is itself the resolved HRW owner, in which case `replicas` is the authoritative full set.
    /// `ownerHeadOffset` is the local partition next-expected offset (head + 1) — on the owner this is
    /// the true tail used to spot a CAUGHT_UP replica whose `confirmedOffset` lags it (#333).
    public record ReplicaSetView(String streamName,
                                 int partition,
                                 Option<String> ownerNodeId,
                                 boolean servedByOwner,
                                 long ownerHeadOffset,
                                 long earliestRetainedOffset,
                                 List<ReplicaView> replicas) {}

    /// Per-replica state row: `state` is the `ReplicationState` name (SYNCING / CAUGHT_UP / LAGGING),
    /// `confirmedOffset` the replica's acked watermark, `hrwOwner` whether this replica is the resolved
    /// HRW owner of the partition.
    public record ReplicaView(String nodeId, String state, long confirmedOffset, boolean hrwOwner) {}

    private static List<OffHeapRingBuffer.RawEvent> toRawEvents(List<RawEventDto> events, int partition) {
        return events.stream()
                     .map(StreamReadRouter::toRawEvent)
                     .toList();
    }

    private static OffHeapRingBuffer.RawEvent toRawEvent(RawEventDto dto) {
        return new OffHeapRingBuffer.RawEvent(dto.offset(), dto.data(), dto.timestamp());
    }
}
