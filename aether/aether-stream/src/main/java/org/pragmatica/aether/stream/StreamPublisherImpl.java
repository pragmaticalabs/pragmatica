package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Serializer;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/// StreamPublisher implementation backed by StreamPartitionManager.
///
/// Routes events to partitions using either a @PartitionKey extractor
/// (deterministic hash-based routing) or round-robin assignment.
///
/// @param <T> Event type
public final class StreamPublisherImpl<T> implements StreamPublisher<T> {
    private final StreamPartitionManager partitionManager;
    private final Serializer serializer;
    private final String streamName;
    private final int partitionCount;
    private final Option<Function<T, Object>> partitionKeyExtractor;
    private final AtomicLong roundRobinCounter;

    private StreamPublisherImpl(StreamPartitionManager partitionManager,
                                Serializer serializer,
                                String streamName,
                                int partitionCount,
                                Option<Function<T, Object>> partitionKeyExtractor) {
        this.partitionManager = partitionManager;
        this.serializer = serializer;
        this.streamName = streamName;
        this.partitionCount = partitionCount;
        this.partitionKeyExtractor = partitionKeyExtractor;
        this.roundRobinCounter = new AtomicLong(0);
    }

    /// Create a StreamPublisher with a partition key extractor.
    public static <T> StreamPublisherImpl<T> streamPublisher(StreamPartitionManager partitionManager,
                                                             Serializer serializer,
                                                             String streamName,
                                                             int partitionCount,
                                                             Option<Function<T, Object>> partitionKeyExtractor) {
        return new StreamPublisherImpl<>(partitionManager, serializer, streamName, partitionCount, partitionKeyExtractor);
    }

    @Override
    public Promise<Unit> publish(T event) {
        var bytes = serializer.encode(event);
        var partition = resolvePartition(event);
        return partitionManager.publishLocal(streamName,
                                             partition,
                                             bytes,
                                             System.currentTimeMillis())
                               .mapToUnit()
                               .async();
    }

    private int resolvePartition(T event) {
        return partitionKeyExtractor.map(extractor -> Math.floorMod(extractor.apply(event)
                                                                             .hashCode(),
                                                                    partitionCount))
                                    .or(() -> (int)(roundRobinCounter.getAndIncrement() % partitionCount));
    }
}
