// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.segment;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import com.sun.management.ThreadMXBean;

import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.stream.LongStream;
import java.util.stream.Stream;

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

    /// #1392: a read across k segments must not nest a frame group per segment. With a memory tier every
    /// `storage.get` settles synchronously, and the `flatMap`-per-segment chain then ran each continuation
    /// inline: 7 frames per segment, ~3,600 for a 512-segment replay batch — and the sealer seals one evicted
    /// record per segment, so that is the production shape. On CI's 1 MB x64 thread stack the entity replay
    /// overflowed at about 480 segments. Depth is measured with `StackWalker`, which `MaxJavaStackTraceDepth`
    /// does not cap.
    @Nested
    class StackDepthAcrossSegments {
        private static final int MANY_SEGMENTS = 2_000;
        private static final int SOME_SEGMENTS = 200;
        private static final int FAILING_SEGMENT = 5;
        /// Well under one segment's worth of nesting (7 frames) times any small constant, and four orders of
        /// magnitude under the 13,993 frames the recursion grew by before the fix.
        private static final long MAX_DEPTH_GROWTH = 50;

        /// RED before the fix: the last segment's `get` ran 7 × 1,999 frames deeper than the first's.
        @Test
        void readEvents_keepsStackDepthFlat_acrossTwoThousandSynchronouslySettlingSegments() {
            var depths = new ArrayList<Long>();
            var probedReader = segmentReader(recordingDepthOnGet(storage, depths), index);

            sealOneEventSegments(MANY_SEGMENTS);
            var events = probedReader.readEvents(STREAM, PARTITION, 0, MANY_SEGMENTS).await();

            assertThat(events.isSuccess()).as(() -> "read failed: " + events).isTrue();
            events.onSuccess(list -> assertThat(list).extracting(RawEvent::offset)
                                                     .containsExactlyElementsOf(offsets(MANY_SEGMENTS)));
            assertThat(depths).as("control: every segment was fetched through the probe").hasSize(MANY_SEGMENTS);
            assertThat(depths.getLast() - depths.getFirst()).as("stack depth at the last segment's get relative to the first's")
                                                              .isLessThan(MAX_DEPTH_GROWTH);
        }

        /// The suspended branch: a `get` that settles on another thread parks the loop, which resumes there.
        @Test
        void readEvents_readsEverySegmentInOrder_whenEachGetSettlesOffThread() {
            var offThreadReader = segmentReader(settlingOffThread(storage, Option.none()), index);

            sealOneEventSegments(SOME_SEGMENTS);
            var events = offThreadReader.readEvents(STREAM, PARTITION, 0, SOME_SEGMENTS).await();

            assertThat(events.isSuccess()).as(() -> "read failed: " + events).isTrue();
            events.onSuccess(list -> assertThat(list).extracting(RawEvent::offset)
                                                     .containsExactlyElementsOf(offsets(SOME_SEGMENTS)));
        }

        @Test
        void readEvents_failsTheWholeRead_whenAGetSettlingOffThreadFails() {
            var failure = SegmentError.General.SEGMENT_DATA_NOT_FOUND;
            var failingReader = segmentReader(settlingOffThread(storage, Option.some(failure)), index);

            sealOneEventSegments(SOME_SEGMENTS);
            var events = failingReader.readEvents(STREAM, PARTITION, 0, SOME_SEGMENTS).await();

            events.onSuccess(list -> fail("a failed segment read must fail the whole read, not return " + list.size() + " events"))
                  .onFailure(cause -> assertThat(cause).isEqualTo(failure));
        }

        private void sealOneEventSegments(int count) {
            for (long offset = 0; offset < count; offset++) {
                var serialized = serializeEvents(List.of(RawEvent.rawEvent(offset, ("e" + offset).getBytes(), offset)));

                sink.seal(sealedSegment(STREAM, PARTITION, offset, offset, 1, offset, offset, serialized)).await();
            }
        }

        private static List<Long> offsets(int count) {
            return LongStream.range(0, count).boxed().toList();
        }

        /// The real storage, with the calling thread's stack depth recorded at every `get`.
        private static StorageInstance recordingDepthOnGet(StorageInstance delegate, List<Long> depths) {
            return proxy(delegate, (method, args) -> {
                if (method.getName().equals("get")) {
                    depths.add(StackWalker.getInstance().walk(Stream::count));
                }

                return Option.none();
            });
        }

        /// The real storage, every `get` settling on a fresh virtual thread — or failing there with `failure`
        /// once `FAILING_SEGMENT` gets have gone through.
        private static StorageInstance settlingOffThread(StorageInstance delegate, Option<Cause> failure) {
            var gets = new AtomicInteger();

            return proxy(delegate, (method, args) -> {
                if (!method.getName().equals("get")) {
                    return Option.none();
                }

                var id = (BlockId) args[0];
                var failing = failure.filter(_ -> gets.incrementAndGet() > FAILING_SEGMENT);

                return Option.some(Promise.<Option<byte[]>> promise()
                                          .async(promise -> failing.onPresent(cause -> promise.fail(cause))
                                                                   .onEmpty(() -> delegate.get(id).onResult(promise::resolve))));
            });
        }

        /// `intercept` answers a call itself or returns none to pass it to `delegate`.
        private static StorageInstance proxy(StorageInstance delegate, BiFunction<Method, Object[], Option<Object>> intercept) {
            return (StorageInstance) Proxy.newProxyInstance(StorageInstance.class.getClassLoader(),
                                                            new Class<?>[]{StorageInstance.class},
                                                            (_, method, args) -> intercept.apply(method, args)
                                                                                          .or(() -> invoke(delegate, method, args)));
        }

        private static Object invoke(StorageInstance delegate, Method method, Object[] args) {
            try {
                return method.invoke(delegate, args);
            } catch (InvocationTargetException e) {
                throw new IllegalStateException(e.getCause());
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
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

    /// Boundary sweeps over `(fromOffset, maxEvents)`, adopted from the round-3 review of PR #1291. The
    /// `maxEvents` guard on the truncated-tail check must neither mis-report a VALID segment nor let a read
    /// that stops on a full batch SKIP a truncated record and serve the next segment's offsets instead.
    @Nested
    class MaxEventsBoundary {
        private static final int JUNK_TAIL_BYTES = 11;

        /// No `(from, max)` over valid segments may report truncation: a well-formed segment ends exactly
        /// on a record boundary, so nothing is left over once the loop has consumed it. Zero-length
        /// payloads are included, since they are the shortest record a boundary can land on.
        @Test
        void readEvents_neverReportsTruncation_forAnyFromAndMaxOverValidSegments() {
            sealValidSegments();

            for (var from = 0L; from <= 4L; from++) {
                for (var max = 1; max <= 7; max++) {
                    assertSlice(from, max);
                }
            }
        }

        /// No `(from, max)` may read PAST a truncated tail into the next segment. Each read either fails
        /// with [SegmentError.CorruptRecord] or returns a contiguous prefix that stops at the tail.
        @Test
        void readEvents_neverSkipsIntoTheNextSegment_forAnyFromAndMaxOverATruncatedTail() {
            sealTruncatedTailThenHealthySegment();
            var refusals = new AtomicInteger();

            for (var from = 0L; from <= 3L; from++) {
                for (var max = 1; max <= 7; max++) {
                    assertStopsAtTheTail(from, max, refusals);
                }
            }

            assertThat(refusals.get()).describedAs("non-vacuity: reads that reach the tail without filling"
                                                   + " their batch must refuse, or this sweep asserts nothing")
                                      .isPositive();
        }

        private void assertSlice(long from, int max) {
            var expected = LongStream.rangeClosed(from, Math.min(4L, from + max - 1))
                                     .boxed()
                                     .toList();

            reader.readEvents(STREAM, PARTITION, from, max)
                  .await()
                  .onFailure(cause -> fail("a valid segment must never report truncation — from=" + from
                                           + " max=" + max + ": " + cause.message()))
                  .onSuccess(list -> assertThat(list).extracting(RawEvent::offset)
                                                     .describedAs("from=%d max=%d", from, max)
                                                     .containsExactlyElementsOf(expected));
        }

        private void assertStopsAtTheTail(long from, int max, AtomicInteger refusals) {
            reader.readEvents(STREAM, PARTITION, from, max)
                  .await()
                  .onFailure(cause -> refusals.incrementAndGet())
                  .onFailure(cause -> assertThat(cause).describedAs("from=%d max=%d", from, max)
                                                       .isInstanceOf(SegmentError.CorruptRecord.class))
                  .onSuccess(list -> assertThat(list).extracting(RawEvent::offset)
                                                     .describedAs("from=%d max=%d must stop at the truncated"
                                                                  + " tail, not serve the next segment", from, max)
                                                     .containsExactlyElementsOf(LongStream.rangeClosed(from,
                                                                                                       Math.min(2L, from + max - 1))
                                                                                          .boxed()
                                                                                          .toList()));
        }

        /// Offsets 0-2 and 3-4, each segment ending in a zero-length payload.
        private void sealValidSegments() {
            var first = serializeEvents(List.of(RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                                                RawEvent.rawEvent(1L, "b".getBytes(), 20L),
                                                RawEvent.rawEvent(2L, new byte[0], 30L)));
            var second = serializeEvents(List.of(RawEvent.rawEvent(3L, "d".getBytes(), 40L),
                                                 RawEvent.rawEvent(4L, new byte[0], 50L)));

            sink.seal(sealedSegment(STREAM, PARTITION, 0, 2, 3, 10L, 30L, first)).await();
            sink.seal(sealedSegment(STREAM, PARTITION, 3, 4, 2, 40L, 50L, second)).await();
        }

        /// Offsets 0-2 followed by junk too short to be a header, under metadata claiming through offset 3
        /// — a segment whose last record was cut — and then a healthy segment holding 4-5.
        private void sealTruncatedTailThenHealthySegment() {
            var whole = serializeEvents(List.of(RawEvent.rawEvent(0L, "a".getBytes(), 10L),
                                                RawEvent.rawEvent(1L, "b".getBytes(), 20L),
                                                RawEvent.rawEvent(2L, "c".getBytes(), 30L)));
            var truncated = Arrays.copyOf(whole, whole.length + JUNK_TAIL_BYTES);
            var healthy = serializeEvents(List.of(RawEvent.rawEvent(4L, "e".getBytes(), 50L),
                                                  RawEvent.rawEvent(5L, "f".getBytes(), 60L)));

            sink.seal(sealedSegment(STREAM, PARTITION, 0, 3, 4, 10L, 40L, truncated)).await();
            sink.seal(sealedSegment(STREAM, PARTITION, 4, 5, 2, 50L, 60L, healthy)).await();
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
