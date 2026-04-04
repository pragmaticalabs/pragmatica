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
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;


class TieredStreamReaderTest {

    private static final String STREAM = "tiered-test";
    private static final int PARTITION = 0;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;
    private TieredStreamReader reader;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
        reader = tieredStreamReader(index, storage);
    }

    @Nested
    class ReadFromSegments {

        @Test
        void read_singleSegment_returnsEvents() {
            sealEvents(0, 4, 100L, 500L);

            var result = reader.read(STREAM, PARTITION, 0, 10).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(5);
                      assertThat(events.getFirst().offset()).isEqualTo(0L);
                      assertThat(events.getLast().offset()).isEqualTo(4L);
                  });
        }

        @Test
        void read_fromMiddleOfSegment_filtersCorrectly() {
            sealEvents(10, 14, 100L, 500L);

            var result = reader.read(STREAM, PARTITION, 12, 10).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(3);
                      assertThat(events.getFirst().offset()).isEqualTo(12L);
                      assertThat(events.getLast().offset()).isEqualTo(14L);
                  });
        }

        @Test
        void read_noSegments_returnsEmpty() {
            var result = reader.read(STREAM, PARTITION, 0, 10).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> assertThat(events).isEmpty());
        }

        @Test
        void read_maxEventsLimitsOutput() {
            sealEvents(0, 9, 100L, 1000L);

            var result = reader.read(STREAM, PARTITION, 0, 3).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> assertThat(events).hasSize(3));
        }
    }

    @Nested
    class SpanningMultipleSegments {

        @Test
        void read_acrossSegments_returnsAll() {
            sealEvents(0, 4, 100L, 500L);
            sealEvents(5, 9, 600L, 1000L);

            var result = reader.read(STREAM, PARTITION, 3, 10).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(7);
                      assertThat(events.getFirst().offset()).isEqualTo(3L);
                      assertThat(events.getLast().offset()).isEqualTo(9L);
                  });
        }

        @Test
        void read_acrossThreeSegments_returnsAll() {
            sealEvents(0, 4, 100L, 500L);
            sealEvents(5, 9, 600L, 1000L);
            sealEvents(10, 14, 1100L, 1500L);

            var result = reader.read(STREAM, PARTITION, 2, 20).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(13);
                      assertThat(events.getFirst().offset()).isEqualTo(2L);
                      assertThat(events.getLast().offset()).isEqualTo(14L);
                  });
        }
    }

    @Nested
    class PrefetchBehavior {

        @Test
        void prefetch_existingSegment_succeeds() {
            sealEvents(0, 4, 100L, 500L);

            var result = reader.prefetch(STREAM, PARTITION, 2).await();

            result.onFailure(_ -> fail("Prefetch should succeed for existing segment"));
        }

        @Test
        void prefetch_nonexistentSegment_succeeds() {
            var result = reader.prefetch(STREAM, PARTITION, 999).await();

            result.onFailure(_ -> fail("Prefetch of missing segment should not fail"));
        }

        @Test
        void read_nearSegmentEnd_triggersImplicitPrefetch() {
            sealEvents(0, 9, 100L, 1000L);
            sealEvents(10, 19, 1100L, 2000L);

            // Read near the end of first segment (offset 8 is at 80% of 0-9 range)
            var result = reader.read(STREAM, PARTITION, 8, 2).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(2);
                      assertThat(events.getFirst().offset()).isEqualTo(8L);
                      assertThat(events.getLast().offset()).isEqualTo(9L);
                  });
            // Implicit prefetch of segment [10-19] is fire-and-forget; we verify the read still works
            // and that subsequent reads of segment [10-19] succeed (warmed in cache)
            var nextResult = reader.read(STREAM, PARTITION, 10, 5).await();

            nextResult.onFailure(_ -> fail("Expected success on prefetched segment"))
                      .onSuccess(events -> {
                          assertThat(events).hasSize(5);
                          assertThat(events.getFirst().offset()).isEqualTo(10L);
                      });
        }
    }

    @Nested
    class SegmentIndexIntegration {

        @Test
        void read_respectsPartitionIsolation() {
            sealEventsForPartition(0, 0, 4, 100L, 500L);
            sealEventsForPartition(1, 0, 4, 100L, 500L);

            var result = reader.read(STREAM, 0, 0, 10).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> assertThat(events).hasSize(5));
        }

        @Test
        void read_respectsStreamIsolation() {
            sealEvents(0, 4, 100L, 500L);

            var result = reader.read("other-stream", PARTITION, 0, 10).await();

            result.onFailure(_ -> fail("Expected success"))
                  .onSuccess(events -> assertThat(events).isEmpty());
        }
    }

    // --- Helpers ---

    private void sealEvents(long startOffset, long endOffset, long minTs, long maxTs) {
        sealEventsForPartition(PARTITION, startOffset, endOffset, minTs, maxTs);
    }

    private void sealEventsForPartition(int partition, long startOffset, long endOffset, long minTs, long maxTs) {
        var events = buildRawEvents(startOffset, endOffset, minTs);
        var serialized = serializeEvents(events);
        var count = (int) (endOffset - startOffset + 1);
        var segment = sealedSegment(STREAM, partition, startOffset, endOffset, count, minTs, maxTs, serialized);
        sink.seal(segment).await();
    }

    private static List<RawEvent> buildRawEvents(long startOffset, long endOffset, long baseTimestamp) {
        return java.util.stream.LongStream.rangeClosed(startOffset, endOffset)
                                          .mapToObj(offset -> RawEvent.rawEvent(offset,
                                                                                ("evt-" + offset).getBytes(),
                                                                                baseTimestamp + (offset - startOffset) * 100))
                                          .toList();
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
}
