// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ReadPreference;
import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.stream.forward.RawEventDto;
import org.pragmatica.aether.stream.forward.StreamForwardClient;
import org.pragmatica.aether.stream.forward.StreamForwardClient.ReadForwardResult;
import org.pragmatica.aether.stream.forward.StreamReadForwardMetrics;
import org.pragmatica.aether.stream.replication.ReplicaDescriptor;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.ReplicationState;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.allOf;


@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01"}) public final class PartitionedStreamAccess<T> implements StreamAccess<T> {
    @FunctionalInterface public interface CursorCheckpointWriter {
        Promise<Unit> checkpoint(String streamName, String consumerGroup, int partition, long offset);
    }

    private static final CursorCheckpointWriter NOOP_WRITER = (_, _, _, _) -> Promise.unitPromise();

    private static final NodeId EMPTY_SELF = new NodeId("__no_self__");

    private static final System.Logger LOG = System.getLogger(PartitionedStreamAccess.class.getName());

    private final StreamPartitionManager partitionManager;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final String streamName;
    private final int partitionCount;
    private final Option<Function<T, Object>> partitionKeyExtractor;
    private final CursorCheckpointWriter cursorWriter;
    private final Option<TieredStreamReader> tieredReader;
    private final Option<CursorStore> cursorStore;
    private final ReadPreference readPreference;
    private final Option<ReplicaRegistry> replicaRegistry;
    private final Option<StreamForwardClient> forwardClient;
    private final NodeId selfNodeId;
    private final StreamReadForwardMetrics metrics;
    private final AtomicLong roundRobinCounter;
    private final ConcurrentHashMap<ConsumerPartitionKey, Long> committedOffsets;

    private PartitionedStreamAccess(StreamPartitionManager partitionManager,
                                    Serializer serializer,
                                    Deserializer deserializer,
                                    String streamName,
                                    int partitionCount,
                                    Option<Function<T, Object>> partitionKeyExtractor,
                                    CursorCheckpointWriter cursorWriter,
                                    Option<TieredStreamReader> tieredReader,
                                    Option<CursorStore> cursorStore,
                                    ReadPreference readPreference,
                                    Option<ReplicaRegistry> replicaRegistry,
                                    Option<StreamForwardClient> forwardClient,
                                    NodeId selfNodeId,
                                    StreamReadForwardMetrics metrics) {
        this.partitionManager = partitionManager;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.streamName = streamName;
        this.partitionCount = partitionCount;
        this.partitionKeyExtractor = partitionKeyExtractor;
        this.cursorWriter = cursorWriter;
        this.tieredReader = tieredReader;
        this.cursorStore = cursorStore;
        this.readPreference = readPreference;
        this.replicaRegistry = replicaRegistry;
        this.forwardClient = forwardClient;
        this.selfNodeId = selfNodeId;
        this.metrics = metrics;
        this.roundRobinCounter = new AtomicLong(0);
        this.committedOffsets = new ConcurrentHashMap<>();
    }

    public static <T> PartitionedStreamAccess<T> streamAccess(StreamPartitionManager partitionManager,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              String streamName,
                                                              int partitionCount,
                                                              Option<Function<T, Object>> partitionKeyExtractor) {
        return new PartitionedStreamAccess<>(partitionManager,
                                             serializer,
                                             deserializer,
                                             streamName,
                                             partitionCount,
                                             partitionKeyExtractor,
                                             NOOP_WRITER,
                                             Option.none(),
                                             Option.none(),
                                             ReadPreference.GOVERNOR,
                                             Option.none(),
                                             Option.none(),
                                             EMPTY_SELF,
                                             StreamReadForwardMetrics.NOOP);
    }

    public static <T> PartitionedStreamAccess<T> streamAccess(StreamPartitionManager partitionManager,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              String streamName,
                                                              int partitionCount,
                                                              Option<Function<T, Object>> partitionKeyExtractor,
                                                              CursorCheckpointWriter cursorWriter) {
        return new PartitionedStreamAccess<>(partitionManager,
                                             serializer,
                                             deserializer,
                                             streamName,
                                             partitionCount,
                                             partitionKeyExtractor,
                                             cursorWriter,
                                             Option.none(),
                                             Option.none(),
                                             ReadPreference.GOVERNOR,
                                             Option.none(),
                                             Option.none(),
                                             EMPTY_SELF,
                                             StreamReadForwardMetrics.NOOP);
    }

    public static <T> PartitionedStreamAccess<T> streamAccess(StreamPartitionManager partitionManager,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              String streamName,
                                                              int partitionCount,
                                                              Option<Function<T, Object>> partitionKeyExtractor,
                                                              CursorCheckpointWriter cursorWriter,
                                                              TieredStreamReader tieredReader) {
        return new PartitionedStreamAccess<>(partitionManager,
                                             serializer,
                                             deserializer,
                                             streamName,
                                             partitionCount,
                                             partitionKeyExtractor,
                                             cursorWriter,
                                             Option.some(tieredReader),
                                             Option.none(),
                                             ReadPreference.GOVERNOR,
                                             Option.none(),
                                             Option.none(),
                                             EMPTY_SELF,
                                             StreamReadForwardMetrics.NOOP);
    }

    public static <T> PartitionedStreamAccess<T> streamAccess(StreamPartitionManager partitionManager,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              String streamName,
                                                              int partitionCount,
                                                              Option<Function<T, Object>> partitionKeyExtractor,
                                                              CursorCheckpointWriter cursorWriter,
                                                              TieredStreamReader tieredReader,
                                                              CursorStore cursorStore) {
        return new PartitionedStreamAccess<>(partitionManager,
                                             serializer,
                                             deserializer,
                                             streamName,
                                             partitionCount,
                                             partitionKeyExtractor,
                                             cursorWriter,
                                             Option.some(tieredReader),
                                             Option.some(cursorStore),
                                             ReadPreference.GOVERNOR,
                                             Option.none(),
                                             Option.none(),
                                             EMPTY_SELF,
                                             StreamReadForwardMetrics.NOOP);
    }

    public static <T> PartitionedStreamAccess<T> streamAccess(StreamPartitionManager partitionManager,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              String streamName,
                                                              int partitionCount,
                                                              Option<Function<T, Object>> partitionKeyExtractor,
                                                              CursorCheckpointWriter cursorWriter,
                                                              TieredStreamReader tieredReader,
                                                              CursorStore cursorStore,
                                                              ReadPreference readPreference,
                                                              ReplicaRegistry replicaRegistry) {
        return new PartitionedStreamAccess<>(partitionManager,
                                             serializer,
                                             deserializer,
                                             streamName,
                                             partitionCount,
                                             partitionKeyExtractor,
                                             cursorWriter,
                                             Option.some(tieredReader),
                                             Option.some(cursorStore),
                                             readPreference,
                                             Option.some(replicaRegistry),
                                             Option.none(),
                                             EMPTY_SELF,
                                             StreamReadForwardMetrics.NOOP);
    }

    public static <T> PartitionedStreamAccess<T> streamAccess(StreamPartitionManager partitionManager,
                                                              Serializer serializer,
                                                              Deserializer deserializer,
                                                              String streamName,
                                                              int partitionCount,
                                                              Option<Function<T, Object>> partitionKeyExtractor,
                                                              CursorCheckpointWriter cursorWriter,
                                                              TieredStreamReader tieredReader,
                                                              CursorStore cursorStore,
                                                              ReadPreference readPreference,
                                                              ReplicaRegistry replicaRegistry,
                                                              Option<StreamForwardClient> forwardClient,
                                                              NodeId selfNodeId,
                                                              StreamReadForwardMetrics metrics) {
        return new PartitionedStreamAccess<>(partitionManager,
                                             serializer,
                                             deserializer,
                                             streamName,
                                             partitionCount,
                                             partitionKeyExtractor,
                                             cursorWriter,
                                             Option.some(tieredReader),
                                             Option.some(cursorStore),
                                             readPreference,
                                             Option.some(replicaRegistry),
                                             forwardClient,
                                             selfNodeId,
                                             metrics);
    }

    @Override public Promise<Long> publish(T event) {
        var bytes = serializer.encode(event);
        var partition = resolvePartition(event);
        return partitionManager.publishLocal(streamName, partition, bytes, System.currentTimeMillis()).async();
    }

    @Override public Promise<List<StreamEvent<T>>> fetch(long fromOffset, int maxEvents) {
        return fetchFromAllPartitions(fromOffset, maxEvents);
    }

    @Override public Promise<List<StreamEvent<T>>> fetch(int partition, long fromOffset, int maxEvents) {
        return readWithPreference(partition, fromOffset, maxEvents);
    }

    public Result<MemorySegment> readSlice(int partition, long offset) {
        return partitionManager.partitionBuffer(streamName, partition).toResult(StreamError.General.PARTITION_NOT_LOCAL)
                                               .flatMap(buffer -> buffer.readSlice(offset));
    }

    public ReadPreference readPreference() {
        return readPreference;
    }

    @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
        committedOffsets.put(new ConsumerPartitionKey(consumerGroup, partition), offset);
        return cursorWriter.checkpoint(streamName, consumerGroup, partition, offset);
    }

    @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
        var inMemory = option(committedOffsets.get(new ConsumerPartitionKey(consumerGroup, partition)));
        if (inMemory.isPresent()) {return Promise.success(inMemory);}
        return fetchFromCursorStore(consumerGroup, partition);
    }

    private Promise<Option<Long>> fetchFromCursorStore(String consumerGroup, int partition) {
        return cursorStore.map(store -> store.fetch(consumerGroup, streamName, partition))
                              .or(Promise.success(Option.empty()));
    }

    @Override public Promise<StreamMetadata> metadata() {
        return partitionManager.allPartitionInfo(streamName).map(this::toStreamMetadata)
                                                .async();
    }

    private StreamMetadata toStreamMetadata(List<StreamPartitionManager.PartitionInfo> partitions) {
        return new StreamMetadata(streamName,
                                  partitionCount,
                                  partitions.stream().map(PartitionedStreamAccess::toPartitionInfo)
                                                   .toList());
    }

    private static PartitionInfo toPartitionInfo(StreamPartitionManager.PartitionInfo pi) {
        return new PartitionInfo(pi.partition(), pi.headOffset(), pi.tailOffset(), pi.eventCount());
    }

    private Promise<List<StreamEvent<T>>> fetchFromAllPartitions(long fromOffset, int maxEvents) {
        List<Promise<List<StreamEvent<T>>>> perPartitionPromises = IntStream.range(0, partitionCount).mapToObj(p -> readPartition(p,
                                                                                                                                  fromOffset,
                                                                                                                                  maxEvents))
                                                                                  .toList();
        return Promise.allOf(perPartitionPromises)
                            .flatMap(results -> allOf(results).map(lists -> mergeAndLimit(lists, maxEvents)).async());
    }

    private Promise<List<StreamEvent<T>>> readWithPreference(int partition, long fromOffset, int maxEvents) {
        return switch (readPreference){
            case GOVERNOR -> readPartition(partition, fromOffset, maxEvents);
            case ANY_REPLICA, NEAREST -> readFromReplicaOrLocal(partition, fromOffset, maxEvents);
        };
    }

    private Promise<List<StreamEvent<T>>> readFromReplicaOrLocal(int partition, long fromOffset, int maxEvents) {
        return replicaRegistry.map(registry -> selectReplicaAndRead(registry, partition, fromOffset, maxEvents))
                                  .or(() -> readPartition(partition, fromOffset, maxEvents));
    }

    private Promise<List<StreamEvent<T>>> selectReplicaAndRead(ReplicaRegistry registry,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
        var caughtUp = registry.replicasFor(streamName, partition).stream()
                                           .filter(PartitionedStreamAccess::isCaughtUp)
                                           .toList();
        if (caughtUp.isEmpty()) {return readPartition(partition, fromOffset, maxEvents);}
        return readWithPrimaryAndRetry(caughtUp, partition, fromOffset, maxEvents);
    }

    private Promise<List<StreamEvent<T>>> readWithPrimaryAndRetry(List<ReplicaDescriptor> caughtUp,
                                                                  int partition,
                                                                  long fromOffset,
                                                                  int maxEvents) {
        var remote = caughtUp.stream().filter(r -> !r.nodeId().equals(selfNodeId))
                                    .toList();
        if (remote.isEmpty()) {return readPartition(partition, fromOffset, maxEvents);}
        return forwardClient.map(client -> attemptPrimary(client, remote, partition, fromOffset, maxEvents))
                                .or(() -> readPartition(partition, fromOffset, maxEvents));
    }

    private Promise<List<StreamEvent<T>>> attemptPrimary(StreamForwardClient client,
                                                         List<ReplicaDescriptor> pool,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
        var primary = pickReplica(pool);
        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: primary replica {1} for {2}[{3}]",
                readPreference,
                primary.nodeId(),
                streamName,
                partition);
        return client.readRemote(primary.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents).map(result -> decodeAll(result.events(),
                                                                    partition))
                                .fold(result -> result.fold(cause -> retryOrFail(client,
                                                                                 primary,
                                                                                 pool,
                                                                                 partition,
                                                                                 fromOffset,
                                                                                 maxEvents,
                                                                                 cause),
                                                            Promise::success));
    }

    private Promise<List<StreamEvent<T>>> retryOrFail(StreamForwardClient client,
                                                      ReplicaDescriptor primary,
                                                      List<ReplicaDescriptor> pool,
                                                      int partition,
                                                      long fromOffset,
                                                      int maxEvents,
                                                      Cause firstCause) {
        var alternatives = pool.stream().filter(r -> !r.nodeId().equals(primary.nodeId()))
                                      .toList();
        if (alternatives.isEmpty()) {
            LOG.log(System.Logger.Level.DEBUG,
                    "ReadPreference {0}: single-replica failure for {1}[{2}]: {3}",
                    readPreference,
                    streamName,
                    partition,
                    firstCause.message());
            return firstCause.promise();
        }
        metrics.recordRetry();
        var retry = pickReplica(alternatives);
        LOG.log(System.Logger.Level.DEBUG,
                "ReadPreference {0}: retry replica {1} for {2}[{3}] after primary {4} failed: {5}",
                readPreference,
                retry.nodeId(),
                streamName,
                partition,
                primary.nodeId(),
                firstCause.message());
        return client.readRemote(retry.nodeId(),
                                 streamName,
                                 partition,
                                 fromOffset,
                                 maxEvents)
        .map(result -> decodeAll(result.events(),
                                 partition));
    }

    private static ReplicaDescriptor pickReplica(List<ReplicaDescriptor> pool) {
        return pool.get(ThreadLocalRandom.current().nextInt(pool.size()));
    }

    private List<StreamEvent<T>> decodeAll(List<RawEventDto> events, int partition) {
        return events.stream().map(dto -> toStreamEventFromDto(dto, partition))
                            .toList();
    }

    private static boolean isCaughtUp(ReplicaDescriptor descriptor) {
        return descriptor.state() == ReplicationState.CAUGHT_UP;
    }

    private Promise<List<StreamEvent<T>>> readPartition(int partition, long fromOffset, int maxEvents) {
        return partitionManager.readLocal(streamName, partition, fromOffset, maxEvents).map(rawEvents -> toStreamEvents(rawEvents,
                                                                                                                        partition))
                                         .fold(cause -> handleReadFailure(cause, partition, fromOffset, maxEvents),
                                               Promise::success);
    }

    private Promise<List<StreamEvent<T>>> handleReadFailure(org.pragmatica.lang.Cause cause,
                                                            int partition,
                                                            long fromOffset,
                                                            int maxEvents) {
        if (cause instanceof StreamError.CursorExpired expired) {return readWithSegmentFallback(partition,
                                                                                                fromOffset,
                                                                                                maxEvents,
                                                                                                expired.requestedOffset());}
        return cause.promise();
    }

    private Promise<List<StreamEvent<T>>> readWithSegmentFallback(int partition,
                                                                  long fromOffset,
                                                                  int maxEvents,
                                                                  long expiredOffset) {
        return tieredReader.map(reader -> readFromTieredReader(reader, partition, fromOffset, maxEvents))
                               .or(() -> cursorExpiredPromise(partition, expiredOffset));
    }

    private Promise<List<StreamEvent<T>>> cursorExpiredPromise(int partition, long expiredOffset) {
        return new StreamError.CursorExpired(expiredOffset,
                                             partitionManager.partitionInfo(streamName, partition).map(StreamPartitionManager.PartitionInfo::tailOffset)
                                                                           .or(0L)).promise();
    }

    private Promise<List<StreamEvent<T>>> readFromTieredReader(TieredStreamReader reader,
                                                               int partition,
                                                               long fromOffset,
                                                               int maxEvents) {
        return reader.read(streamName, partition, fromOffset, maxEvents)
                          .map(sealedEvents -> combineWithBufferEvents(sealedEvents, partition, fromOffset, maxEvents));
    }

    private List<StreamEvent<T>> combineWithBufferEvents(List<OffHeapRingBuffer.RawEvent> sealedEvents,
                                                         int partition,
                                                         long fromOffset,
                                                         int maxEvents) {
        var remaining = maxEvents - sealedEvents.size();
        var sealed = toStreamEvents(sealedEvents, partition);
        if (remaining <= 0) {return sealed;}
        var bufferStart = sealedEvents.isEmpty()
                         ? fromOffset
                         : sealedEvents.getLast().offset() + 1;
        var bufferEvents = partitionManager.readLocal(streamName, partition, bufferStart, remaining).map(rawEvents -> toStreamEvents(rawEvents,
                                                                                                                                     partition))
                                                     .or(List.of());
        return List.copyOf(Stream.concat(sealed.stream(), bufferEvents.stream()).toList());
    }

    private List<StreamEvent<T>> toStreamEvents(List<OffHeapRingBuffer.RawEvent> rawEvents, int partition) {
        return rawEvents.stream().map(raw -> toStreamEvent(raw, partition))
                               .toList();
    }

    private static <T> List<StreamEvent<T>> mergeAndLimit(List<List<StreamEvent<T>>> lists, int maxEvents) {
        var merged = new ArrayList<StreamEvent<T>>();
        lists.forEach(merged::addAll);
        merged.sort(Comparator.comparingLong(StreamEvent::offset));
        return merged.size() > maxEvents
              ? List.copyOf(merged.subList(0, maxEvents))
              : List.copyOf(merged);
    }

    private StreamEvent<T> toStreamEvent(OffHeapRingBuffer.RawEvent raw, int partition) {
        T payload = deserializer.decode(raw.data());
        return new StreamEvent<>(raw.offset(), raw.timestamp(), partition, payload);
    }

    private StreamEvent<T> toStreamEventFromDto(RawEventDto dto, int partition) {
        T payload = deserializer.decode(dto.data());
        return new StreamEvent<>(dto.offset(), dto.timestamp(), partition, payload);
    }

    private int resolvePartition(T event) {
        return partitionKeyExtractor.map(extractor -> Math.floorMod(extractor.apply(event).hashCode(),
                                                                    partitionCount))
        .or(() -> (int)(roundRobinCounter.getAndIncrement() % partitionCount));
    }

    private record ConsumerPartitionKey(String consumerGroup, int partition){}
}
