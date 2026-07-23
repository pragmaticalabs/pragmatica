// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.consensus.ConsensusPublishPath;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.replication.ReplicaPlacement;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Serializer;


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
    private final Option<Function<Integer, Option<NodeId>>> partitionOwnerResolver;
    private final Option<NodeId> selfNodeId;

    private DefaultStreamPublisher(StreamPartitionManager partitionManager,
                                   Serializer serializer,
                                   String streamName,
                                   int partitionCount,
                                   Option<Function<T, Object>> partitionKeyExtractor,
                                   ConsistencyMode consistencyMode,
                                   Option<ConsensusPublishPath> consensusPath,
                                   int minSyncReplicas,
                                   Option<StreamForwardClient> forwardClient,
                                   Option<Fn0<Option<NodeId>>> governorResolver,
                                   Option<Function<Integer, Option<NodeId>>> partitionOwnerResolver,
                                   Option<NodeId> selfNodeId) {
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
        this.partitionOwnerResolver = partitionOwnerResolver;
        this.selfNodeId = selfNodeId;
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
                                            Option.none(),
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
                                            Option.none(),
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
                                            Option.none(),
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
                                                                Option<Fn0<Option<NodeId>>> governorResolver,
                                                                Option<Function<Integer, Option<NodeId>>> partitionOwnerResolver,
                                                                Option<NodeId> selfNodeId) {
        return new DefaultStreamPublisher<>(partitionManager,
                                            serializer,
                                            streamName,
                                            partitionCount,
                                            partitionKeyExtractor,
                                            consistencyMode,
                                            consensusPath,
                                            minSyncReplicas,
                                            forwardClient,
                                            governorResolver,
                                            partitionOwnerResolver,
                                            selfNodeId);
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

    /// #266: an EVENTUAL batch is grouped by each event's COMPUTED partition (not routed wholesale to
    /// the first event's partition) and each group is routed through the SAME per-event path as single
    /// {@link #publish} — local owner publish + replicate + min-sync await, or write-forward to the
    /// remote owner. This preserves key→partition affinity and gives the batch identical replication
    /// semantics to single publish (composes with #262), instead of the prior whole-batch misroute that
    /// also bypassed replication and failed `PARTITION_NOT_LOCAL` for any non-local partition.
    private Promise<Unit> publishBatchEventual(List<T> events) {
        var now = System.currentTimeMillis();
        var byPartition = groupByPartition(events);

        return Promise.allOf(byPartition.values().stream().map(group -> publishGroupInOrder(group, now)).toList()).mapToUnit();
    }

    /// Group events by computed partition, preserving encounter order within each partition group so
    /// per-key ordering is maintained. A `LinkedHashMap` keeps group iteration deterministic.
    private Map<Integer, List<T>> groupByPartition(List<T> events) {
        var groups = new LinkedHashMap<Integer, List<T>>();

        events.forEach(event -> groups.computeIfAbsent(resolvePartition(event),
                                                       _ -> new ArrayList<>())
                                      .add(event));

        return groups;
    }

    /// Publish one partition's events strictly in order: each event awaits the previous so the
    /// partition's append/forward sequence preserves per-key ordering. Different partition groups run
    /// concurrently (the caller's `allOf`).
    private Promise<Unit> publishGroupInOrder(List<T> group, long timestamp) {
        var chain = Promise.<Unit> unitPromise();

        for (var event : group) {
            chain = chain.flatMap(_ -> publishEventual(resolvePartition(event), serializer.encode(event), timestamp));
        }

        return chain;
    }

    private Promise<Unit> publishEventual(int partition, byte[] bytes, long timestamp) {
        if (partitionManager.partitionBuffer(streamName, partition).isPresent()) {
            return publishLocalEventual(partition, bytes, timestamp);
        }

        return publishRemote(partition, bytes, timestamp);
    }

    private Promise<Unit> publishLocalEventual(int partition, byte[] bytes, long timestamp) {
        if (minSyncReplicas <= 1) {
            return partitionManager.publishLocal(streamName, partition, bytes, timestamp)
                                   .mapToUnit()
                                   .async();
        }

        return partitionManager.publishLocal(streamName, partition, bytes, timestamp)
                               .async()
                               .flatMap(offset -> partitionManager.awaitReplication(streamName,
                                                                                    partition,
                                                                                    offset,
                                                                                    minSyncReplicas - 1));
    }

    /// Non-materialized publish: route to the partition's HRW owner instead of the STREAMING leader. The
    /// owner is resolved via the partition-aware HRW resolver (the SAME `ReplicaSetController` placement
    /// that owns the replica set), falling back to the arg-less leader resolver only when no HRW resolver
    /// is wired (legacy / minimal runtimes). A resolved owner that is THIS node — or the absence of an
    /// owner or forward client — falls back to a local append rather than a send-to-self (which QUIC
    /// silently drops, hanging the forward). Mirrors {@link StreamWriteRouter}'s `forwardToOwner` and
    /// {@link PartitionedStreamAccess}'s owner-routed publish.
    private Promise<Unit> publishRemote(int partition, byte[] bytes, long timestamp) {
        return resolveOwner(partition).filter(this::isRemote)
                           .flatMap(owner -> forwardClient.map(client -> forwardToOwner(client,
                                                                                        owner,
                                                                                        partition,
                                                                                        bytes,
                                                                                        timestamp)))
                           .or(() -> publishLocalEventual(partition, bytes, timestamp));
    }

    /// #467: prefer the partition-aware HRW owner-resolver (the placement authority that owns the replica
    /// set); fall back to the arg-less leader resolver only when no HRW resolver is wired.
    private Option<NodeId> resolveOwner(int partition) {
        return partitionOwnerResolver.flatMap(resolver -> resolver.apply(partition))
                                     .orElse(() -> governorResolver.flatMap(Fn0::apply));
    }

    /// A resolved owner is forwardable only when it is known to differ from this node; a self-owner (or an
    /// unknown self) never forwards, so the send-to-self QUIC drop cannot occur.
    private boolean isRemote(NodeId owner) {
        return ! selfNodeId.map(owner::equals)
                           .or(true);
    }

    private Promise<Unit> forwardToOwner(StreamForwardClient client,
                                         NodeId owner,
                                         int partition,
                                         byte[] bytes,
                                         long timestamp) {
        return client.publishRemote(owner, streamName, partition, bytes, timestamp)
                     .mapToUnit();
    }

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
