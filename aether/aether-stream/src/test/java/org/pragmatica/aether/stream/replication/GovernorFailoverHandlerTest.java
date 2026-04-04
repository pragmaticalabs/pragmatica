package org.pragmatica.aether.stream.replication;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.replication.GovernorFailoverHandler.governorFailoverHandler;
import static org.pragmatica.aether.stream.replication.ReplicaRegistry.replicaRegistry;
import static org.pragmatica.aether.stream.replication.WatermarkTracker.watermarkTracker;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;


class GovernorFailoverHandlerTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final NodeId REPLICA_A = NodeId.randomNodeId();
    private static final NodeId REPLICA_B = NodeId.randomNodeId();

    private ReplicaRegistry registry;
    private WatermarkTracker localWatermarks;
    private SegmentIndex index;
    private StorageInstance storage;
    private StorageSegmentSink sink;
    private SegmentReader reader;
    private List<RecoveredEvent> recoveredEvents;
    private AtomicLong eventCounter;
    private GovernorFailoverHandler handler;

    @BeforeEach
    void setUp() {
        registry = replicaRegistry();
        localWatermarks = watermarkTracker();
        index = new SegmentIndex();
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        sink = storageSegmentSink(storage, index);
        reader = segmentReader(storage, index);
        recoveredEvents = new ArrayList<>();
        eventCounter = new AtomicLong(0);
        handler = governorFailoverHandler(registry, this::handleRecoveredEvent);
    }

    private Result<Long> handleRecoveredEvent(String streamName, int partition, byte[] payload, long timestamp) {
        recoveredEvents.add(new RecoveredEvent(streamName, partition, payload.clone(), timestamp));
        return Result.success(eventCounter.incrementAndGet());
    }

    private void awaitSuccess(Promise<Unit> promise) {
        promise.await().onFailure(_ -> Assertions.fail("Expected success"));
    }

    @Nested
    class CatchUpFromSegments {

        @Test
        void handleFailover_withSegments_replaysEvents() {
            sealSegment(0L, 2L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, -1L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(3);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("a".getBytes());
            assertThat(recoveredEvents.get(1).payload()).isEqualTo("b".getBytes());
            assertThat(recoveredEvents.get(2).payload()).isEqualTo("c".getBytes());
        }

        @Test
        void handleFailover_withWatermark_replaysOnlyMissingEvents() {
            sealSegment(0L, 4L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 2L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(2);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("d".getBytes());
            assertThat(recoveredEvents.get(1).payload()).isEqualTo("e".getBytes());
        }

        @Test
        void handleFailover_selectsHighestReplicaWatermark() {
            sealSegment(0L, 9L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L),
                RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.registerReplica(STREAM, PARTITION, REPLICA_B);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 3L);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_B, 7L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(2);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("i".getBytes());
            assertThat(recoveredEvents.get(1).payload()).isEqualTo("j".getBytes());
        }
    }

    @Nested
    class EmptySegments {

        @Test
        void handleFailover_noSegments_noReplicas_succeeds() {
            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).isEmpty();
        }

        @Test
        void handleFailover_noSegments_withWatermark_succeeds() {
            localWatermarks.advance(STREAM, PARTITION, 10L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).isEmpty();
        }
    }

    @Nested
    class LocalWatermarkInteraction {

        @Test
        void handleFailover_usesLocalWatermarkWhenHigherThanReplica() {
            sealSegment(0L, 9L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L),
                RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
            ));

            localWatermarks.advance(STREAM, PARTITION, 8L);
            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 5L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(1);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("j".getBytes());
        }
    }

    @Nested
    class PartialSegments {

        @Test
        void handleFailover_multipleSegments_replaysAcross() {
            sealSegment(0L, 4L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                RawEvent.rawEvent(4L, "e".getBytes(), 500L)
            ));
            sealSegment(5L, 9L, List.of(
                RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
            ));

            registry.registerReplica(STREAM, PARTITION, REPLICA_A);
            registry.updateWatermark(STREAM, PARTITION, REPLICA_A, 2L);

            awaitSuccess(handler.handleFailover(STREAM, PARTITION, localWatermarks, index, reader));

            assertThat(recoveredEvents).hasSize(7);
            assertThat(recoveredEvents.get(0).payload()).isEqualTo("d".getBytes());
            assertThat(recoveredEvents.getLast().payload()).isEqualTo("j".getBytes());
        }
    }

    private void sealSegment(long startOffset, long endOffset, List<RawEvent> events) {
        var serialized = serializeEvents(events);
        var minTs = events.stream().mapToLong(RawEvent::timestamp).min().orElse(0L);
        var maxTs = events.stream().mapToLong(RawEvent::timestamp).max().orElse(0L);
        var segment = SealedSegment.sealedSegment(STREAM, PARTITION, startOffset, endOffset,
                                                   events.size(), minTs, maxTs, serialized);
        sink.seal(segment).await();
    }

    private static byte[] serializeEvents(List<RawEvent> events) {
        var totalSize = events.stream()
                              .mapToInt(e -> Long.BYTES + Long.BYTES + Integer.BYTES + e.data().length)
                              .sum();
        var buffer = ByteBuffer.allocate(totalSize).order(ByteOrder.BIG_ENDIAN);
        for (var event : events) {
            buffer.putLong(event.offset());
            buffer.putLong(event.timestamp());
            buffer.putInt(event.data().length);
            buffer.put(event.data());
        }
        return buffer.array();
    }

    record RecoveredEvent(String streamName, int partition, byte[] payload, long timestamp) {}
}
