package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.replication.GovernorFailoverHandler;
import org.pragmatica.aether.stream.replication.ReplicaRegistry;
import org.pragmatica.aether.stream.replication.StreamPartitionRecovery;
import org.pragmatica.aether.stream.replication.WatermarkTracker;
import org.pragmatica.aether.stream.segment.RetentionEnforcer;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentReader;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamingCoordinator.streamingCoordinator;


class StreamingCoordinatorTest {

    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long ONE_DAY_MS = 24 * 60 * 60 * 1000L;
    private static final RetentionPolicy SMALL_RETENTION = RetentionPolicy.retentionPolicy(100, 4096, ONE_DAY_MS);

    private StreamPartitionManager partitionManager;
    private WatermarkTracker watermarkTracker;
    private SegmentIndex segmentIndex;
    private SegmentReader segmentReader;
    private StorageInstance storage;
    private RetentionEnforcer retentionEnforcer;
    private StorageSegmentSink segmentSink;
    private CopyOnWriteArrayList<RecoveredEvent> recoveredEvents;
    private AtomicLong eventCounter;
    private GovernorFailoverHandler failoverHandler;

    @BeforeEach
    void setUp() {
        partitionManager = StreamPartitionManager.streamPartitionManager();
        watermarkTracker = WatermarkTracker.watermarkTracker();
        segmentIndex = new SegmentIndex();
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        segmentReader = SegmentReader.segmentReader(storage, segmentIndex);
        retentionEnforcer = RetentionEnforcer.retentionEnforcer(storage, segmentIndex, ONE_DAY_MS);
        segmentSink = StorageSegmentSink.storageSegmentSink(storage, segmentIndex);
        recoveredEvents = new CopyOnWriteArrayList<>();
        eventCounter = new AtomicLong(0);
        failoverHandler = GovernorFailoverHandler.governorFailoverHandler(ReplicaRegistry.replicaRegistry(), recordingRecovery());
    }

    @Nested
    class FailoverRecovery {

        @Test
        void activate_withStreamsAndSegments_recoversEvents() {
            createStream("orders", 2);
            sealSegment("orders", 0, 0L, 1L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L)));
            sealSegment("orders", 1, 0L, 0L, List.of(
                RawEvent.rawEvent(0L, "c".getBytes(), 300L)));

            var coordinator = buildCoordinator();
            awaitSuccess(coordinator.activate());

            assertThat(coordinator.isActive()).isTrue();
            assertThat(recoveredEvents).hasSize(3);
            assertThat(recoveredEvents.stream().filter(e -> e.streamName.equals("orders") && e.partition == 0).count()).isEqualTo(2);
            assertThat(recoveredEvents.stream().filter(e -> e.streamName.equals("orders") && e.partition == 1).count()).isEqualTo(1);
        }

        @Test
        void activate_noStreams_completesImmediately() {
            var coordinator = buildCoordinator();
            awaitSuccess(coordinator.activate());

            assertThat(coordinator.isActive()).isTrue();
            assertThat(recoveredEvents).isEmpty();
        }

        @Test
        void activate_streamsWithNoSegments_completesWithoutRecovery() {
            createStream("orders", 3);

            var coordinator = buildCoordinator();
            awaitSuccess(coordinator.activate());

            assertThat(coordinator.isActive()).isTrue();
            assertThat(recoveredEvents).isEmpty();
        }

        @Test
        void activate_idempotent_secondCallDoesNotRetrigger() {
            createStream("orders", 1);
            sealSegment("orders", 0, 0L, 0L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L)));

            var coordinator = buildCoordinator();
            awaitSuccess(coordinator.activate());
            awaitSuccess(coordinator.activate());

            assertThat(recoveredEvents).hasSize(1);
        }

        @Test
        void activate_afterDeactivateAndReactivate_retriggersRecovery() {
            createStream("orders", 1);
            sealSegment("orders", 0, 0L, 0L, List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L)));

            var coordinator = buildCoordinator();
            awaitSuccess(coordinator.activate());
            assertThat(recoveredEvents).hasSize(1);

            awaitSuccess(coordinator.deactivate());
            assertThat(coordinator.isActive()).isFalse();

            awaitSuccess(coordinator.activate());
            assertThat(recoveredEvents).hasSize(2);
        }
    }

    @Nested
    class FailoverErrorHandling {

        @Test
        void activate_failoverFailure_doesNotPreventActivation() {
            createStream("orders", 2);

            var unusedHandler = new GovernorFailoverHandler.unused();
            var coordinator = streamingCoordinator(unusedHandler,
                                                    retentionEnforcer,
                                                    partitionManager,
                                                    watermarkTracker,
                                                    segmentIndex,
                                                    segmentReader);

            awaitSuccess(coordinator.activate());

            assertThat(coordinator.isActive()).isTrue();
        }
    }

    @Nested
    class Lifecycle {

        @Test
        void deactivate_whenNotActive_isNoOp() {
            var coordinator = buildCoordinator();
            awaitSuccess(coordinator.deactivate());

            assertThat(coordinator.isActive()).isFalse();
        }

        @Test
        void taskGroup_returnsStreaming() {
            var coordinator = buildCoordinator();
            assertThat(coordinator.taskGroup()).isEqualTo(TaskGroup.STREAMING);
        }

        @Test
        void failoverHandler_returnsConfiguredHandler() {
            var coordinator = buildCoordinator();
            assertThat(coordinator.failoverHandler()).isSameAs(failoverHandler);
        }
    }

    private StreamingCoordinator buildCoordinator() {
        return streamingCoordinator(failoverHandler,
                                     retentionEnforcer,
                                     partitionManager,
                                     watermarkTracker,
                                     segmentIndex,
                                     segmentReader);
    }

    private void createStream(String name, int partitions) {
        partitionManager.createStream(StreamConfig.streamConfig(name, partitions, SMALL_RETENTION, "latest"))
                        .onFailure(cause -> Assertions.fail("Stream creation failed: " + cause.message()));
    }

    private void sealSegment(String streamName, int partition, long startOffset, long endOffset, List<RawEvent> events) {
        var serialized = serializeEvents(events);
        var minTs = events.stream().mapToLong(RawEvent::timestamp).min().orElse(0L);
        var maxTs = events.stream().mapToLong(RawEvent::timestamp).max().orElse(0L);
        var segment = SealedSegment.sealedSegment(streamName, partition, startOffset, endOffset,
                                                   events.size(), minTs, maxTs, serialized);
        segmentSink.seal(segment).await();
    }

    private StreamPartitionRecovery recordingRecovery() {
        return (streamName, partition, payload, timestamp) -> {
            recoveredEvents.add(new RecoveredEvent(streamName, partition, payload.clone(), timestamp));
            return Result.success(eventCounter.incrementAndGet());
        };
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

    private static void awaitSuccess(Promise<Unit> promise) {
        promise.await().onFailure(cause -> Assertions.fail("Expected success but got: " + cause.message()));
    }

    record RecoveredEvent(String streamName, int partition, byte[] payload, long timestamp) {}
}
