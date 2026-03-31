package org.pragmatica.aether.stream;

import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.stream.consensus.ConsensusPublishPath;
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
/// For EVENTUAL consistency, events are written directly to the local ring buffer.
/// For STRONG consistency, events are proposed through Rabia consensus before local append.
///
/// @param <T> Event type
public final class StreamPublisherImpl<T> implements StreamPublisher<T> {
    private final StreamPartitionManager partitionManager;
    private final Serializer serializer;
    private final String streamName;
    private final int partitionCount;
    private final Option<Function<T, Object>> partitionKeyExtractor;
    private final AtomicLong roundRobinCounter;
    private final ConsistencyMode consistencyMode;
    private final Option<ConsensusPublishPath> consensusPath;

    private StreamPublisherImpl(StreamPartitionManager partitionManager,
                                Serializer serializer,
                                String streamName,
                                int partitionCount,
                                Option<Function<T, Object>> partitionKeyExtractor,
                                ConsistencyMode consistencyMode,
                                Option<ConsensusPublishPath> consensusPath) {
        this.partitionManager = partitionManager;
        this.serializer = serializer;
        this.streamName = streamName;
        this.partitionCount = partitionCount;
        this.partitionKeyExtractor = partitionKeyExtractor;
        this.roundRobinCounter = new AtomicLong(0);
        this.consistencyMode = consistencyMode;
        this.consensusPath = consensusPath;
    }

    /// Create a StreamPublisher for EVENTUAL consistency (Phase 1 default).
    public static <T> StreamPublisherImpl<T> streamPublisher(StreamPartitionManager partitionManager,
                                                             Serializer serializer,
                                                             String streamName,
                                                             int partitionCount,
                                                             Option<Function<T, Object>> partitionKeyExtractor) {
        return new StreamPublisherImpl<>(partitionManager, serializer, streamName, partitionCount,
                                        partitionKeyExtractor, ConsistencyMode.EVENTUAL, Option.none());
    }

    /// Create a StreamPublisher with explicit consistency mode and optional consensus path.
    public static <T> StreamPublisherImpl<T> streamPublisher(StreamPartitionManager partitionManager,
                                                             Serializer serializer,
                                                             String streamName,
                                                             int partitionCount,
                                                             Option<Function<T, Object>> partitionKeyExtractor,
                                                             ConsistencyMode consistencyMode,
                                                             Option<ConsensusPublishPath> consensusPath) {
        return new StreamPublisherImpl<>(partitionManager, serializer, streamName, partitionCount,
                                        partitionKeyExtractor, consistencyMode, consensusPath);
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

    private Promise<Unit> publishEventual(int partition, byte[] bytes, long timestamp) {
        return partitionManager.publishLocal(streamName, partition, bytes, timestamp)
                               .mapToUnit()
                               .async();
    }

    private Promise<Unit> publishStrong(int partition, byte[] bytes, long timestamp) {
        return consensusPath.async(StreamError.General.CONSENSUS_PATH_UNAVAILABLE)
                            .flatMap(path -> path.publish(streamName, partition, bytes, timestamp))
                            .mapToUnit();
    }

    private int resolvePartition(T event) {
        return partitionKeyExtractor.map(extractor -> Math.floorMod(extractor.apply(event)
                                                                             .hashCode(),
                                                                    partitionCount))
                                    .or(() -> (int) (roundRobinCounter.getAndIncrement() % partitionCount));
    }
}
