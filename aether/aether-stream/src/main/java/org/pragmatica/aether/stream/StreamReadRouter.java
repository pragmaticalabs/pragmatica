// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;


public final class StreamReadRouter {
    private final StreamPartitionManager partitionManager;
    private final Option<ReplicaRegistry> replicaRegistry;
    private final Option<StreamForwardClient> forwardClient;
    private final NodeId selfNodeId;
    private final StreamReadForwardMetrics metrics;

    private StreamReadRouter(StreamPartitionManager partitionManager,
                             Option<ReplicaRegistry> replicaRegistry,
                             Option<StreamForwardClient> forwardClient,
                             NodeId selfNodeId,
                             StreamReadForwardMetrics metrics) {
        this.partitionManager = partitionManager;
        this.replicaRegistry = replicaRegistry;
        this.forwardClient = forwardClient;
        this.selfNodeId = selfNodeId;
        this.metrics = metrics;
    }

    public static StreamReadRouter streamReadRouter(StreamPartitionManager partitionManager,
                                                    Option<ReplicaRegistry> replicaRegistry,
                                                    Option<StreamForwardClient> forwardClient,
                                                    NodeId selfNodeId,
                                                    StreamReadForwardMetrics metrics) {
        return new StreamReadRouter(partitionManager, replicaRegistry, forwardClient, selfNodeId, metrics);
    }

    public static StreamReadRouter localOnly(StreamPartitionManager partitionManager) {
        return new StreamReadRouter(partitionManager,
                                    Option.none(),
                                    Option.none(),
                                    new NodeId("__no_self__"),
                                    StreamReadForwardMetrics.NOOP);
    }

    public Promise<List<OffHeapRingBuffer.RawEvent>> read(String streamName,
                                                          int partition,
                                                          long fromOffset,
                                                          int maxEvents,
                                                          ReadPreference preference) {
        return switch (preference) {
            case GOVERNOR -> readLocal(streamName, partition, fromOffset, maxEvents);
            case ANY_REPLICA, NEAREST -> readFromReplica(streamName, partition, fromOffset, maxEvents);
        };
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> readLocal(String streamName,
                                                                int partition,
                                                                long fromOffset,
                                                                int maxEvents) {
        return partitionManager.readLocal(streamName, partition, fromOffset, maxEvents)
                               .async();
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> readFromReplica(String streamName,
                                                                      int partition,
                                                                      long fromOffset,
                                                                      int maxEvents) {
        return replicaRegistry.map(registry -> selectAndRead(registry, streamName, partition, fromOffset, maxEvents))
                              .or(() -> readLocal(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> selectAndRead(ReplicaRegistry registry,
                                                                    String streamName,
                                                                    int partition,
                                                                    long fromOffset,
                                                                    int maxEvents) {
        var caughtUp = registry.replicasFor(streamName, partition).stream().filter(StreamReadRouter::isCaughtUp).toList();

        if (caughtUp.isEmpty()) {
            return readLocal(streamName, partition, fromOffset, maxEvents);
        }

        var remote = caughtUp.stream().filter(r -> !r.nodeId()
                                                     .equals(selfNodeId)).toList();

        if (remote.isEmpty()) {
            return readLocal(streamName, partition, fromOffset, maxEvents);
        }

        return forwardClient.map(client -> attemptPrimary(client, remote, streamName, partition, fromOffset, maxEvents))
                            .or(() -> readLocal(streamName, partition, fromOffset, maxEvents));
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> attemptPrimary(StreamForwardClient client,
                                                                     List<ReplicaDescriptor> pool,
                                                                     String streamName,
                                                                     int partition,
                                                                     long fromOffset,
                                                                     int maxEvents) {
        var primary = pickReplica(pool);

        return client.readRemote(primary.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents).map(result -> toRawEvents(result.events(),
                                                                      partition))
                                .fold(outcome -> outcome.fold(cause -> retryOrFail(client,
                                                                                   primary,
                                                                                   pool,
                                                                                   streamName,
                                                                                   partition,
                                                                                   fromOffset,
                                                                                   maxEvents,
                                                                                   cause),
                                                              Promise::success));
    }

    private Promise<List<OffHeapRingBuffer.RawEvent>> retryOrFail(StreamForwardClient client,
                                                                  ReplicaDescriptor primary,
                                                                  List<ReplicaDescriptor> pool,
                                                                  String streamName,
                                                                  int partition,
                                                                  long fromOffset,
                                                                  int maxEvents,
                                                                  Cause firstCause) {
        var alternatives = pool.stream().filter(r -> !r.nodeId()
                                                       .equals(primary.nodeId())).toList();

        if (alternatives.isEmpty()) {
            return firstCause.promise();
        }

        metrics.recordRetry();
        var retry = pickReplica(alternatives);

        return client.readRemote(retry.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents)
                     .map(result -> toRawEvents(result.events(),
                                                partition));
    }

    private static List<OffHeapRingBuffer.RawEvent> toRawEvents(List<RawEventDto> events, int partition) {
        return events.stream()
                     .map(StreamReadRouter::toRawEvent)
                     .toList();
    }

    private static OffHeapRingBuffer.RawEvent toRawEvent(RawEventDto dto) {
        return new OffHeapRingBuffer.RawEvent(dto.offset(), dto.data(), dto.timestamp());
    }

    private static ReplicaDescriptor pickReplica(List<ReplicaDescriptor> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private static boolean isCaughtUp(ReplicaDescriptor descriptor) {
        return descriptor.state() == ReplicationState.CAUGHT_UP;
    }
}
