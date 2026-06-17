// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.ForwardingReadRouter.OwnerResolver;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;


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

    private StreamReadRouter(StreamPartitionManager partitionManager,
                             Option<ReplicaRegistry> replicaRegistry,
                             Option<StreamForwardClient> forwardClient,
                             NodeId selfNodeId,
                             OwnerResolver ownerResolver,
                             StreamReadForwardMetrics metrics) {
        this.partitionManager = partitionManager;
        this.replicaRegistry = replicaRegistry;
        this.forwardClient = forwardClient;
        this.selfNodeId = selfNodeId;
        this.ownerResolver = ownerResolver;
        this.metrics = metrics;
    }

    public static StreamReadRouter streamReadRouter(StreamPartitionManager partitionManager,
                                                    Option<ReplicaRegistry> replicaRegistry,
                                                    Option<StreamForwardClient> forwardClient,
                                                    NodeId selfNodeId,
                                                    OwnerResolver ownerResolver,
                                                    StreamReadForwardMetrics metrics) {
        return new StreamReadRouter(partitionManager, replicaRegistry, forwardClient, selfNodeId, ownerResolver, metrics);
    }

    public static StreamReadRouter localOnly(StreamPartitionManager partitionManager) {
        return new StreamReadRouter(partitionManager,
                                    Option.none(),
                                    Option.none(),
                                    NO_SELF,
                                    (_, _) -> Option.none(),
                                    StreamReadForwardMetrics.NOOP);
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
                                                                                      metrics).route(streamName,
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

    private static List<OffHeapRingBuffer.RawEvent> toRawEvents(List<RawEventDto> events, int partition) {
        return events.stream()
                     .map(StreamReadRouter::toRawEvent)
                     .toList();
    }

    private static OffHeapRingBuffer.RawEvent toRawEvent(RawEventDto dto) {
        return new OffHeapRingBuffer.RawEvent(dto.offset(), dto.data(), dto.timestamp());
    }
}
