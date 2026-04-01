package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

class SegmentReaderTest {

    private static final String STREAM = "test-stream";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;
    private SegmentReader reader;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
        reader = segmentReader(storage, index);
    }

    @Nested
    class ReadEvents {

        @Test
        void readEvents_afterSeal_returnsEvents() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(0L, "hello".getBytes(), 100L),
                RawEvent.rawEvent(1L, "world".getBytes(), 200L)
            ));
            var segment = sealedSegment(STREAM, PARTITION, 0, 1, 2, 100L, 200L, serialized);

            sink.seal(segment).await();

            var events = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            events.onSuccess(list -> {
                assertThat(list).hasSize(2);
                assertThat(list.get(0).offset()).isEqualTo(0L);
                assertThat(list.get(0).data()).isEqualTo("hello".getBytes());
                assertThat(list.get(0).timestamp()).isEqualTo(100L);
                assertThat(list.get(1).offset()).isEqualTo(1L);
                assertThat(list.get(1).data()).isEqualTo("world".getBytes());
            });
        }

        @Test
        void readEvents_middleOfSegment_filtersCorrectly() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(10L, "a".getBytes(), 100L),
                RawEvent.rawEvent(11L, "b".getBytes(), 200L),
                RawEvent.rawEvent(12L, "c".getBytes(), 300L)
            ));
            var segment = sealedSegment(STREAM, PARTITION, 10, 12, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var events = reader.readEvents(STREAM, PARTITION, 11, 10).await();
            events.onSuccess(list -> {
                assertThat(list).hasSize(2);
                assertThat(list.get(0).offset()).isEqualTo(11L);
                assertThat(list.get(1).offset()).isEqualTo(12L);
            });
        }

        @Test
        void readEvents_noSegment_returnsEmpty() {
            var events = reader.readEvents(STREAM, PARTITION, 0, 10).await();
            events.onSuccess(list -> assertThat(list).isEmpty());
        }

        @Test
        void readEvents_maxEventsLimitsOutput() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                RawEvent.rawEvent(2L, "c".getBytes(), 300L)
            ));
            var segment = sealedSegment(STREAM, PARTITION, 0, 2, 3, 100L, 300L, serialized);

            sink.seal(segment).await();

            var events = reader.readEvents(STREAM, PARTITION, 0, 2).await();
            events.onSuccess(list -> assertThat(list).hasSize(2));
        }
    }

    @Nested
    class RoundTrip {

        @Test
        void roundTrip_sealThenRead_preservesData() {
            var originalEvents = List.of(
                RawEvent.rawEvent(100L, "event-one".getBytes(), 5000L),
                RawEvent.rawEvent(101L, "event-two".getBytes(), 5001L),
                RawEvent.rawEvent(102L, "event-three".getBytes(), 5002L)
            );
            var serialized = serializeEvents(originalEvents);
            var segment = sealedSegment(STREAM, PARTITION, 100, 102, 3, 5000L, 5002L, serialized);

            sink.seal(segment).await();

            var events = reader.readEvents(STREAM, PARTITION, 100, 10).await();
            events.onSuccess(list -> {
                assertThat(list).hasSize(3);

                for (int i = 0; i < originalEvents.size(); i++) {
                    var original = originalEvents.get(i);
                    var restored = list.get(i);
                    assertThat(restored.offset()).isEqualTo(original.offset());
                    assertThat(restored.data()).isEqualTo(original.data());
                    assertThat(restored.timestamp()).isEqualTo(original.timestamp());
                }
            });
        }

        @Test
        void roundTrip_multipleSegments_readsAcross() {
            var seg1 = sealedSegment(STREAM, PARTITION, 0, 4, 5, 100L, 500L,
                                     serializeEvents(List.of(
                                         RawEvent.rawEvent(0L, "a".getBytes(), 100L),
                                         RawEvent.rawEvent(1L, "b".getBytes(), 200L),
                                         RawEvent.rawEvent(2L, "c".getBytes(), 300L),
                                         RawEvent.rawEvent(3L, "d".getBytes(), 400L),
                                         RawEvent.rawEvent(4L, "e".getBytes(), 500L)
                                     )));
            var seg2 = sealedSegment(STREAM, PARTITION, 5, 9, 5, 600L, 1000L,
                                     serializeEvents(List.of(
                                         RawEvent.rawEvent(5L, "f".getBytes(), 600L),
                                         RawEvent.rawEvent(6L, "g".getBytes(), 700L),
                                         RawEvent.rawEvent(7L, "h".getBytes(), 800L),
                                         RawEvent.rawEvent(8L, "i".getBytes(), 900L),
                                         RawEvent.rawEvent(9L, "j".getBytes(), 1000L)
                                     )));

            sink.seal(seg1).await();
            sink.seal(seg2).await();

            var events = reader.readEvents(STREAM, PARTITION, 3, 10).await();
            events.onSuccess(list -> {
                assertThat(list).hasSize(7);
                assertThat(list.get(0).offset()).isEqualTo(3L);
                assertThat(list.getLast().offset()).isEqualTo(9L);
            });
        }
    }

    @Nested
    class Deserialization {

        @Test
        void deserializeAndFilter_allEvents_returnsAll() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(0L, "x".getBytes(), 10L),
                RawEvent.rawEvent(1L, "y".getBytes(), 20L)
            ));

            var result = SegmentReader.deserializeAndFilter(serialized, 0, 100);

            assertThat(result).hasSize(2);
        }

        @Test
        void deserializeAndFilter_filtersBeforeFromOffset() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(5L, "skip".getBytes(), 10L),
                RawEvent.rawEvent(6L, "keep".getBytes(), 20L)
            ));

            var result = SegmentReader.deserializeAndFilter(serialized, 6, 100);

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().offset()).isEqualTo(6L);
        }

        @Test
        void deserializeAndFilter_respectsMaxEvents() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                RawEvent.rawEvent(1L, "b".getBytes(), 20L),
                RawEvent.rawEvent(2L, "c".getBytes(), 30L)
            ));

            var result = SegmentReader.deserializeAndFilter(serialized, 0, 2);

            assertThat(result).hasSize(2);
        }
    }

    /// Serialize events using the same format as SegmentSealer: [offset:8][timestamp:8][len:4][data:len]
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
}
