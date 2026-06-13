// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.consensus.ConsensusPublishPath;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardError;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Serializer;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;


public final class DefaultStreamPublisher<T> implements StreamPublisher<T> {
    private final StreamPartitionManager partitionManager;
    private final Serializer serializer;
    private final String streamName;
    private final int partitionCount;
    private final Option<Function<T, Object>> partitionKeyExtractor;
    private final AtomicLong roundRobinCounter;
    private final ConsistencyMode consistencyMode;
    private final Option<ConsensusPublishPath> consensusPath;
    private final int minSyncReplicas;
    private final Option<StreamForwardClient> forwardClient;
    private final Option<Fn0<Option<NodeId>>> governorResolver;

    private DefaultStreamPublisher(StreamPartitionManager partitionManager,
                                   Serializer serializer,
                                   String streamName,
                                   int partitionCount,
                                   Option<Function<T, Object>> partitionKeyExtractor,
                                   ConsistencyMode consistencyMode,
                                   Option<ConsensusPublishPath> consensusPath,
                                   int minSyncReplicas,
                                   Option<StreamForwardClient> forwardClient,
                                   Option<Fn0<Option<NodeId>>> governorResolver) {
        this.partitionManager = partitionManager;
        this.serializer = serializer;
        this.streamName = streamName;
        this.partitionCount = partitionCount;
        this.partitionKeyExtractor = partitionKeyExtractor;
        this.roundRobinCounter = new AtomicLong(0);
        this.consistencyMode = consistencyMode;
        this.consensusPath = consensusPath;
        this.minSyncReplicas = minSyncReplicas;
        this.forwardClient = forwardClient;
        this.governorResolver = governorResolver;
    }

    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor) {
        return new DefaultStreamPublisher<>(partitionManager,
                                            serializer,
                                            streamName,
                                            partitionCount,
                                            partitionKeyExtractor,
                                            ConsistencyMode.EVENTUAL,
                                            Option.none(),
                                            0,
                                            Option.none(),
                                            Option.none());
    }

    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor,
                                                                ConsistencyMode consistencyMode,
                                                                Option<ConsensusPublishPath> consensusPath) {
        return new DefaultStreamPublisher<>(partitionManager,
                                            serializer,
                                            streamName,
                                            partitionCount,
                                            partitionKeyExtractor,
                                            consistencyMode,
                                            consensusPath,
                                            0,
                                            Option.none(),
                                            Option.none());
    }

    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor,
                                                                ConsistencyMode consistencyMode,
                                                                Option<ConsensusPublishPath> consensusPath,
                                                                int minSyncReplicas) {
        return new DefaultStreamPublisher<>(partitionManager,
                                            serializer,
                                            streamName,
                                            partitionCount,
                                            partitionKeyExtractor,
                                            consistencyMode,
                                            consensusPath,
                                            minSyncReplicas,
                                            Option.none(),
                                            Option.none());
    }

    public static <T> DefaultStreamPublisher<T> streamPublisher(StreamPartitionManager partitionManager,
                                                                Serializer serializer,
                                                                String streamName,
                                                                int partitionCount,
                                                                Option<Function<T, Object>> partitionKeyExtractor,
                                                                ConsistencyMode consistencyMode,
                                                                Option<ConsensusPublishPath> consensusPath,
                                                                int minSyncReplicas,
                                                                Option<StreamForwardClient> forwardClient,
                                                                Option<Fn0<Option<NodeId>>> governorResolver) {
        return new DefaultStreamPublisher<>(partitionManager,
                                            serializer,
                                            streamName,
                                            partitionCount,
                                            partitionKeyExtractor,
                                            consistencyMode,
                                            consensusPath,
                                            minSyncReplicas,
                                            forwardClient,
                                            governorResolver);
    }

    @Override
    public Promise<Unit> publish(T event) {
        var bytes = serializer.encode(event);
        var partition = resolvePartition(event);
        var timestamp = System.currentTimeMillis();

        return switch (consistencyMode) {
            case EVENTUAL -> publishEventual(partition, bytes, timestamp);
            case STRONG -> publishStrong(partition, bytes, timestamp);
        };
    }

    @Override
    public Promise<Unit> publishBatch(List<T> events) {
        if (events.isEmpty()) {
            return Promise.unitPromise();
        }

        if (consistencyMode == ConsistencyMode.STRONG) {
            return publishBatchStrong(events);
        }

        return publishBatchEventual(events);
    }

    private Promise<Unit> publishBatchStrong(List<T> events) {
        return Promise.allOf(events.stream().map(this::publish).toList()).mapToUnit();
    }

    private Promise<Unit> publishBatchEventual(List<T> events) {
        var payloads = events.stream().map(serializer::encode).toList();
        var timestamps = new long[events.size()];
        var now = System.currentTimeMillis();

        Arrays.fill(timestamps, now);
        var partition = resolvePartition(events.getFirst());

        return partitionManager.partitionBuffer(streamName, partition)
                               .toResult(StreamError.General.PARTITION_NOT_LOCAL)
                               .flatMap(buffer -> buffer.appendBatch(payloads, timestamps))
                               .mapToUnit()
                               .async();
    }

    private Promise<Unit> publishEventual(int partition, byte[] bytes, long timestamp) {
        if (partitionManager.partitionBuffer(streamName, partition).isPresent()) {
            return publishLocalEventual(partition, bytes, timestamp);
        }

        return publishRemote(partition, bytes, timestamp);
    }

    private Promise<Unit> publishLocalEventual(int partition, byte[] bytes, long timestamp) {
        if (minSyncReplicas <= 0) {
            return partitionManager.publishLocal(streamName, partition, bytes, timestamp)
                                   .mapToUnit()
                                   .async();
        }

        return partitionManager.publishLocal(streamName, partition, bytes, timestamp)
                               .async()
                               .flatMap(offset -> partitionManager.awaitReplication(streamName,
                                                                                    partition,
                                                                                    offset,
                                                                                    minSyncReplicas));
    }

    private Promise<Unit> publishRemote(int partition, byte[] bytes, long timestamp) {
        return resolveForwardClientAndGovernor().flatMap(pair -> pair.client()
                                                                     .publishRemote(pair.governorId(),
                                                                                    streamName,
                                                                                    partition,
                                                                                    bytes,
                                                                                    timestamp))
                                              .mapToUnit();
    }

    private Promise<ForwardTarget> resolveForwardClientAndGovernor() {
        return forwardClient.async(StreamForwardError.General.GOVERNOR_UNAVAILABLE)
                            .flatMap(this::resolveGovernorForClient);
    }

    private Promise<ForwardTarget> resolveGovernorForClient(StreamForwardClient client) {
        return governorResolver.flatMap(Fn0::apply)
                               .async(StreamForwardError.General.GOVERNOR_UNAVAILABLE)
                               .map(governorId -> new ForwardTarget(client, governorId));
    }

    private record ForwardTarget(StreamForwardClient client, NodeId governorId) {}

    private Promise<Unit> publishStrong(int partition, byte[] bytes, long timestamp) {
        return consensusPath.async(StreamError.General.CONSENSUS_PATH_UNAVAILABLE)
                            .flatMap(path -> path.publish(streamName, partition, bytes, timestamp))
                            .mapToUnit();
    }

    /// Resolve the target partition for `event`. A configured key extractor routes through the STABLE
    /// 64-bit hash used for replica placement ({@link ReplicaPlacement#stableHash64}) rather than
    /// identity-unstable `Object#hashCode()`, so the same logical key maps to the same partition on
    /// every node/JVM (m2). Keyless publishes fall back to round-robin.
    private int resolvePartition(T event) {
        return partitionKeyExtractor.map(extractor -> stablePartition(extractor.apply(event)))
                                    .or(() -> (int)(roundRobinCounter.getAndIncrement() % partitionCount));
    }

    private int stablePartition(Object key) {
        return (int) Math.floorMod(ReplicaPlacement.stableHash64(String.valueOf(key)),
                                   (long) partitionCount);
    }
}
