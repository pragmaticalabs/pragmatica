// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.List;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.pragmatica.aether.stream.segment.SegmentReader.segmentReader;
import static org.pragmatica.aether.stream.segment.SealedSegment.sealedSegment;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;

class SegmentReaderTest {

    private static final String STREAM = "test-stream";
    private static final String SEGMENT = "streams/test-stream/0/0-1";
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

            var result = decoded(serialized, 0, 100);

            assertThat(result).hasSize(2);
        }

        @Test
        void deserializeAndFilter_filtersBeforeFromOffset() {
            var serialized = serializeEvents(List.of(
                RawEvent.rawEvent(5L, "skip".getBytes(), 10L),
                RawEvent.rawEvent(6L, "keep".getBytes(), 20L)
            ));

            var result = decoded(serialized, 6, 100);

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

            var result = decoded(serialized, 0, 2);

            assertThat(result).hasSize(2);
        }
    }

    /// #1265: a cold read skips records below `fromOffset` by header alone — no payload allocation, no
    /// copy — and accumulates across segments into one list. Allocation is measured, not time: the
    /// per-thread allocated-bytes counter brackets exactly one decode.
    @Nested
    class AllocationAndAccumulation {
        private static final int EVENT_COUNT = 1_000;
        private static final int PAYLOAD_BYTES = 1_024;
        private static final long ALLOCATION_BUDGET_BYTES = 16 * 1_024;
        private static final int SEGMENT_COUNT = 50;

        @Test
        void deserializeAndFilter_allocatesUnderBudget_whenSkippingRecordsBelowFromOffset() {
            var serialized = serializeEvents(oneKibEvents());
            var lastOffset = EVENT_COUNT - 1;

            // Warm-up: class loading and first-call allocations belong to no decode.
            decoded(serialized, lastOffset, 1);

            var before = allocatedBytes();
            var result = decoded(serialized, lastOffset, 1);
            var allocated = allocatedBytes() - before;

            assertThat(result).hasSize(1);
            assertThat(result.getFirst().offset()).isEqualTo(lastOffset);
            assertThat(allocated).as("bytes allocated decoding one event past %d skipped records", lastOffset)
                                 .isLessThan(ALLOCATION_BUDGET_BYTES);
        }

        @Test
        void deserializeAndFilter_failsWithoutAllocating_whenLengthExceedsRemainingBytes() {
            var serialized = serializeEvents(List.of(RawEvent.rawEvent(0L, "ok".getBytes(), 10L),
                                                     RawEvent.rawEvent(1L, "cut".getBytes(), 20L)));
            var truncated = Arrays.copyOf(serialized, serialized.length - 1);
            var secondRecordAt = Long.BYTES + Long.BYTES + Integer.BYTES + "ok".length();

            assertCorruptRecord(SegmentReader.deserializeAndFilter(SEGMENT, truncated, 0, 100),
                                secondRecordAt,
                                "cut".length());
        }

        @Test
        void deserializeAndFilter_failsWithoutAllocating_whenLengthIsNegative() {
            var serialized = serializeEvents(List.of(RawEvent.rawEvent(0L, "ok".getBytes(), 10L),
                                                     RawEvent.rawEvent(1L, "bad".getBytes(), 20L)));
            var secondRecordAt = Long.BYTES + Long.BYTES + Integer.BYTES + "ok".length();
            var secondLengthAt = secondRecordAt + Long.BYTES + Long.BYTES;

            ByteBuffer.wrap(serialized).order(ByteOrder.BIG_ENDIAN).putInt(secondLengthAt, -1);

            assertCorruptRecord(SegmentReader.deserializeAndFilter(SEGMENT, serialized, 0, 100), secondRecordAt, -1);
        }

        @Test
        void readEvents_returnsAllEventsInOrder_acrossFiftyOneEventSegments() {
            for (long offset = 0; offset < SEGMENT_COUNT; offset++) {
                var serialized = serializeEvents(List.of(RawEvent.rawEvent(offset, ("e" + offset).getBytes(), offset)));

                sink.seal(sealedSegment(STREAM, PARTITION, offset, offset, 1, offset, offset, serialized)).await();
            }

            var events = reader.readEvents(STREAM, PARTITION, 0, SEGMENT_COUNT).await();

            assertThat(events.isSuccess()).isTrue();
            events.onSuccess(list -> assertThat(list).extracting(RawEvent::offset)
                                                     .containsExactlyElementsOf(LongStream.range(0, SEGMENT_COUNT)
                                                                                          .boxed()
                                                                                          .toList()));
        }

        private static List<RawEvent> oneKibEvents() {
            return LongStream.range(0, EVENT_COUNT)
                             .mapToObj(offset -> RawEvent.rawEvent(offset, new byte[PAYLOAD_BYTES], offset))
                             .toList();
        }

        private static long allocatedBytes() {
            var threads = (ThreadMXBean) ManagementFactory.getThreadMXBean();

            return threads.getThreadAllocatedBytes(Thread.currentThread().threadId());
        }
    }

    /// A corrupt record length is a typed failure of the read — never a truncated success. Found by the
    /// adversarial review of PR #1291: stopping the decode at the corrupt record let the read continue into
    /// the next segment and return `[0, 3, 4]`, so offsets 1 and 2 vanished with nothing failing.
    @Nested
    class CorruptRecordLength {
        @Test
        void readEvents_failsWithCorruptRecord_whenALengthMidSegmentIsCorruptAndAHealthySegmentFollows() {
            var first = serializeEvents(List.of(RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                                                RawEvent.rawEvent(1L, "b".getBytes(), 20L),
                                                RawEvent.rawEvent(2L, "c".getBytes(), 30L)));
            var secondLengthAt = (Long.BYTES + Long.BYTES + Integer.BYTES + "a".length()) + Long.BYTES + Long.BYTES;

            ByteBuffer.wrap(first).order(ByteOrder.BIG_ENDIAN).putInt(secondLengthAt, -1);

            var second = serializeEvents(List.of(RawEvent.rawEvent(3L, "d".getBytes(), 40L),
                                                 RawEvent.rawEvent(4L, "e".getBytes(), 50L)));

            sink.seal(sealedSegment(STREAM, PARTITION, 0, 2, 3, 10L, 30L, first)).await();
            sink.seal(sealedSegment(STREAM, PARTITION, 3, 4, 2, 40L, 50L, second)).await();

            var events = reader.readEvents(STREAM, PARTITION, 0, 100).await();

            events.onSuccess(list -> fail("a corrupt record length must fail the read, not return "
                                          + list.stream().map(RawEvent::offset).toList()))
                  .onFailure(cause -> assertThat(cause).isEqualTo(SegmentError.CorruptRecord.FACTORY.apply("streams/test-stream/0/0-2",
                                                                                                           secondLengthAt
                                                                                                           - Long.BYTES
                                                                                                           - Long.BYTES,
                                                                                                           -1)));
        }
    }

    /// A segment whose last record header is cut short is corrupt too — the same loss mode as a corrupt
    /// length. Found by the round-2 review of PR #1291: the decode loop ended quietly on fewer than a
    /// header's worth of bytes, the read continued into the next segment, and returned `[0, 1, 3, 4]`.
    @Nested
    class TruncatedTailHeader {
        private static final int RECORD_BYTES = Long.BYTES + Long.BYTES + Integer.BYTES + 1;
        private static final int KEPT_OF_LAST_RECORD = 10;

        @Test
        void readEvents_failsWithCorruptRecord_whenASegmentEndsInATruncatedHeaderAndAHealthySegmentFollows() {
            var whole = serializeEvents(List.of(RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                                                RawEvent.rawEvent(1L, "b".getBytes(), 20L),
                                                RawEvent.rawEvent(2L, "c".getBytes(), 30L)));
            var first = Arrays.copyOf(whole, 2 * RECORD_BYTES + KEPT_OF_LAST_RECORD);
            var second = serializeEvents(List.of(RawEvent.rawEvent(3L, "d".getBytes(), 40L),
                                                 RawEvent.rawEvent(4L, "e".getBytes(), 50L)));

            sink.seal(sealedSegment(STREAM, PARTITION, 0, 2, 3, 10L, 30L, first)).await();
            sink.seal(sealedSegment(STREAM, PARTITION, 3, 4, 2, 40L, 50L, second)).await();

            var events = reader.readEvents(STREAM, PARTITION, 0, 100).await();

            events.onSuccess(list -> fail("a truncated tail header must fail the read, not return "
                                          + list.stream().map(RawEvent::offset).toList()))
                  .onFailure(cause -> assertThat(cause).isEqualTo(SegmentError.CorruptRecord.TRUNCATED_HEADER.apply("streams/test-stream/0/0-2",
                                                                                                                    2 * RECORD_BYTES,
                                                                                                                    KEPT_OF_LAST_RECORD)));
        }

        @Test
        void deserializeAndFilter_failsWithCorruptRecord_whenTheSegmentEndsInsideARecordHeader() {
            var whole = serializeEvents(List.of(RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                                                RawEvent.rawEvent(1L, "b".getBytes(), 20L)));
            var truncated = Arrays.copyOf(whole, RECORD_BYTES + KEPT_OF_LAST_RECORD);

            SegmentReader.deserializeAndFilter(SEGMENT, truncated, 0, 100)
                         .onSuccess(list -> fail("a truncated tail header must fail the decode, not return " + list))
                         .onFailure(cause -> assertThat(cause).isEqualTo(SegmentError.CorruptRecord.TRUNCATED_HEADER.apply(SEGMENT,
                                                                                                                           RECORD_BYTES,
                                                                                                                           KEPT_OF_LAST_RECORD)));
        }

        /// Bytes left over because `maxEvents` was reached are the rest of a well-formed segment, not a
        /// truncation — the decode must still succeed.
        @Test
        void deserializeAndFilter_succeeds_whenMaxEventsIsReachedWithWholeRecordsLeftOver() {
            var serialized = serializeEvents(List.of(RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                                                     RawEvent.rawEvent(1L, "b".getBytes(), 20L)));

            assertThat(decoded(serialized, 0, 1)).extracting(RawEvent::offset).containsExactly(0L);
        }
    }

    private static List<RawEvent> decoded(byte[] serialized, long fromOffset, int maxEvents) {
        return SegmentReader.deserializeAndFilter(SEGMENT, serialized, fromOffset, maxEvents)
                            .onFailure(cause -> fail(cause.message()))
                            .unwrap();
    }

    private static void assertCorruptRecord(Result<List<RawEvent>> result, int position, int length) {
        result.onSuccess(list -> fail("a corrupt record length must fail the decode, not return " + list))
              .onFailure(cause -> assertThat(cause).isEqualTo(SegmentError.CorruptRecord.FACTORY.apply(SEGMENT,
                                                                                                        position,
                                                                                                        length)));
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
