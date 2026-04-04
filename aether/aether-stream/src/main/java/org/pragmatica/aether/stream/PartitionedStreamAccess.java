package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamAccess;
import org.pragmatica.aether.stream.segment.CursorStore;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Result.allOf;


/// StreamAccess implementation backed by StreamPartitionManager.
///
/// Provides full publish/fetch/commit/metadata operations on a stream.
///
/// @param <T> Event type
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-LAM-01"}) public final class PartitionedStreamAccess<T> implements StreamAccess<T> {
    @FunctionalInterface public interface CursorCheckpointWriter {
        Promise<Unit> checkpoint(String streamName, String consumerGroup, int partition, long offset);
    }

    private static final CursorCheckpointWriter NOOP_WRITER = (_, _, _, _) -> Promise.unitPromise();

    private final StreamPartitionManager partitionManager;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final String streamName;
    private final int partitionCount;
    private final Option<Function<T, Object>> partitionKeyExtractor;
    private final CursorCheckpointWriter cursorWriter;
    private final Option<TieredStreamReader> tieredReader;
    private final Option<CursorStore> cursorStore;
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
                                    Option<CursorStore> cursorStore) {
        this.partitionManager = partitionManager;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.streamName = streamName;
        this.partitionCount = partitionCount;
        this.partitionKeyExtractor = partitionKeyExtractor;
        this.cursorWriter = cursorWriter;
        this.tieredReader = tieredReader;
        this.cursorStore = cursorStore;
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
                                             Option.none());
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
                                             Option.none());
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
                                             Option.none());
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
                                             Option.some(cursorStore));
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
        return readPartition(partition, fromOffset, maxEvents).async();
    }

    @Override public Promise<Unit> commit(String consumerGroup, int partition, long offset) {
        committedOffsets.put(new ConsumerPartitionKey(consumerGroup, partition), offset);
        return cursorWriter.checkpoint(streamName, consumerGroup, partition, offset);
    }

    @Override public Promise<Option<Long>> committedOffset(String consumerGroup, int partition) {
        var inMemory = option(committedOffsets.get(new ConsumerPartitionKey(consumerGroup, partition)));
        return Promise.success(inMemory.isPresent()
                               ? inMemory
                               : fetchFromCursorStore(consumerGroup, partition));
    }

    private Option<Long> fetchFromCursorStore(String consumerGroup, int partition) {
        return cursorStore.flatMap(store -> store.fetch(consumerGroup, streamName, partition));
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
        List<Result<List<StreamEvent<T>>>> perPartitionResults = IntStream.range(0, partitionCount).mapToObj(p -> readPartition(p,
                                                                                                                                fromOffset,
                                                                                                                                maxEvents))
                                                                                .toList();
        return allOf(perPartitionResults).map(lists -> mergeAndLimit(lists, maxEvents)).async();
    }

    private Result<List<StreamEvent<T>>> readPartition(int partition, long fromOffset, int maxEvents) {
        return partitionManager.readLocal(streamName, partition, fromOffset, maxEvents).map(rawEvents -> toStreamEvents(rawEvents,
                                                                                                                        partition))
                                         .fold(cause -> handleReadFailure(cause, partition, fromOffset, maxEvents),
                                               Result::success);
    }

    private Result<List<StreamEvent<T>>> handleReadFailure(org.pragmatica.lang.Cause cause,
                                                           int partition,
                                                           long fromOffset,
                                                           int maxEvents) {
        if (cause instanceof StreamError.CursorExpired expired) {return readWithSegmentFallback(partition,
                                                                                                fromOffset,
                                                                                                maxEvents,
                                                                                                expired.requestedOffset());}
        return cause.result();
    }

    private Result<List<StreamEvent<T>>> readWithSegmentFallback(int partition,
                                                                 long fromOffset,
                                                                 int maxEvents,
                                                                 long expiredOffset) {
        return tieredReader.map(reader -> readFromTieredReader(reader, partition, fromOffset, maxEvents))
                               .or(() -> new StreamError.CursorExpired(expiredOffset,
                                                                       partitionManager.partitionInfo(streamName,
                                                                                                      partition).map(StreamPartitionManager.PartitionInfo::tailOffset)
                                                                                                     .or(0L)).result());
    }

    private Result<List<StreamEvent<T>>> readFromTieredReader(TieredStreamReader reader,
                                                              int partition,
                                                              long fromOffset,
                                                              int maxEvents) {
        var segmentEvents = reader.read(streamName, partition, fromOffset, maxEvents).await();
        return segmentEvents.map(sealedEvents -> combineWithBufferEvents(sealedEvents, partition, fromOffset, maxEvents));
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

    private int resolvePartition(T event) {
        return partitionKeyExtractor.map(extractor -> Math.floorMod(extractor.apply(event).hashCode(),
                                                                    partitionCount))
        .or(() -> (int)(roundRobinCounter.getAndIncrement() % partitionCount));
    }

    private record ConsumerPartitionKey(String consumerGroup, int partition){}
}
