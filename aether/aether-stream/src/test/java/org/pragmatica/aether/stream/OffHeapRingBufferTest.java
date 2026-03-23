package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

class OffHeapRingBufferTest {

    private static final long CAPACITY = 100;
    private static final long DATA_REGION = 4096;

    private OffHeapRingBuffer buffer;

    @BeforeEach
    void setUp() {
        buffer = offHeapRingBuffer(CAPACITY, DATA_REGION);
    }

    @AfterEach
    void tearDown() {
        buffer.close();
    }

    @Nested
    class BasicOperations {

        @Test
        void append_returnsOffset_singleEntry() {
            var result = buffer.append("hello".getBytes(), 1000L);

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(offset -> assertThat(offset).isEqualTo(0L));
        }

        @Test
        void append_incrementsOffset_multipleEntries() {
            buffer.append("one".getBytes(), 1000L);
            buffer.append("two".getBytes(), 2000L);
            var result = buffer.append("three".getBytes(), 3000L);

            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(offset -> assertThat(offset).isEqualTo(2L));
        }

        @Test
        void read_returnsSingleEntry_afterAppend() {
            buffer.append("hello".getBytes(), 1000L);

            buffer.read(0, 10)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(1);
                      assertThat(events.getFirst().offset()).isEqualTo(0L);
                      assertThat(events.getFirst().data()).isEqualTo("hello".getBytes());
                      assertThat(events.getFirst().timestamp()).isEqualTo(1000L);
                  });
        }

        @Test
        void read_returnsMultipleEntries_afterMultipleAppends() {
            buffer.append("one".getBytes(), 1000L);
            buffer.append("two".getBytes(), 2000L);
            buffer.append("three".getBytes(), 3000L);

            buffer.read(0, 10)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(3);
                      assertThat(events.get(0).data()).isEqualTo("one".getBytes());
                      assertThat(events.get(1).data()).isEqualTo("two".getBytes());
                      assertThat(events.get(2).data()).isEqualTo("three".getBytes());
                  });
        }

        @Test
        void read_respectsMaxCount_limit() {
            buffer.append("one".getBytes(), 1000L);
            buffer.append("two".getBytes(), 2000L);
            buffer.append("three".getBytes(), 3000L);

            buffer.read(0, 2)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> assertThat(events).hasSize(2));
        }

        @Test
        void read_fromSpecificOffset_returnsSubsequentEntries() {
            buffer.append("one".getBytes(), 1000L);
            buffer.append("two".getBytes(), 2000L);
            buffer.append("three".getBytes(), 3000L);

            buffer.read(1, 10)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(2);
                      assertThat(events.get(0).data()).isEqualTo("two".getBytes());
                      assertThat(events.get(1).data()).isEqualTo("three".getBytes());
                  });
        }

        @Test
        void headOffset_reflectsLastAppend() {
            assertThat(buffer.headOffset()).isEqualTo(-1L);

            buffer.append("one".getBytes(), 1000L);
            assertThat(buffer.headOffset()).isEqualTo(0L);

            buffer.append("two".getBytes(), 2000L);
            assertThat(buffer.headOffset()).isEqualTo(1L);
        }

        @Test
        void eventCount_tracksCorrectly() {
            assertThat(buffer.eventCount()).isEqualTo(0L);

            buffer.append("one".getBytes(), 1000L);
            assertThat(buffer.eventCount()).isEqualTo(1L);

            buffer.append("two".getBytes(), 2000L);
            assertThat(buffer.eventCount()).isEqualTo(2L);
        }

        @Test
        void allocatedBytes_returnsCorrectSize() {
            var expectedSize = 64 + 24 * CAPACITY + DATA_REGION;
            assertThat(buffer.allocatedBytes()).isGreaterThanOrEqualTo(expectedSize);
        }
    }

    @Nested
    class EmptyBufferBehavior {

        @Test
        void read_returnsEmptyList_emptyBuffer() {
            buffer.read(0, 10)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> assertThat(events).isEmpty());
        }

        @Test
        void read_returnsEmptyList_offsetBeyondHead() {
            buffer.append("hello".getBytes(), 1000L);

            buffer.read(5, 10)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> assertThat(events).isEmpty());
        }
    }

    @Nested
    class WrapAround {

        @Test
        void append_wrapsAround_whenCapacityExceeded() {
            var smallBuffer = offHeapRingBuffer(5, 1024);

            try {
                for (int i = 0; i < 8; i++) {
                    smallBuffer.append(("msg-" + i).getBytes(), 1000L + i);
                }

                assertThat(smallBuffer.eventCount()).isEqualTo(5L);
                assertThat(smallBuffer.tailOffset()).isEqualTo(3L);
                assertThat(smallBuffer.headOffset()).isEqualTo(7L);

                smallBuffer.read(3, 10)
                           .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                           .onSuccess(events -> {
                               assertThat(events).hasSize(5);
                               assertThat(events.getFirst().data()).isEqualTo("msg-3".getBytes());
                               assertThat(events.getLast().data()).isEqualTo("msg-7".getBytes());
                           });
            } finally {
                smallBuffer.close();
            }
        }

        @Test
        void append_evictsOldEntries_whenDataRegionFull() {
            // Small data region: 32 bytes. Each "msg-X" is 5 bytes.
            // Can fit ~6 entries before wrapping data.
            var smallBuffer = offHeapRingBuffer(100, 32);

            try {
                for (int i = 0; i < 10; i++) {
                    smallBuffer.append(("msg-" + i).getBytes(), 1000L + i);
                }

                assertThat(smallBuffer.eventCount()).isGreaterThan(0L);
                assertThat(smallBuffer.tailOffset()).isGreaterThan(0L);

                // Latest entries are readable
                var head = smallBuffer.headOffset();
                smallBuffer.read(head, 1)
                           .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                           .onSuccess(events -> {
                               assertThat(events).hasSize(1);
                               assertThat(events.getFirst().data()).isEqualTo("msg-9".getBytes());
                           });
            } finally {
                smallBuffer.close();
            }
        }

        @Test
        void read_returnsCursorExpired_whenOffsetEvicted() {
            var smallBuffer = offHeapRingBuffer(3, 1024);

            try {
                for (int i = 0; i < 6; i++) {
                    smallBuffer.append(("msg-" + i).getBytes(), 1000L + i);
                }

                // Offset 0 has been evicted
                smallBuffer.read(0, 10)
                           .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                           .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.CursorExpired.class));
            } finally {
                smallBuffer.close();
            }
        }
    }

    @Nested
    class Retention {

        @Test
        void applyRetention_evictsByCount() {
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            assertThat(buffer.eventCount()).isEqualTo(50L);

            var policy = RetentionPolicy.retentionPolicy(20, Long.MAX_VALUE, Long.MAX_VALUE);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isEqualTo(20L);
            assertThat(buffer.tailOffset()).isEqualTo(30L);
        }

        @Test
        void applyRetention_evictsByAge() {
            var now = System.currentTimeMillis();

            // Old events
            for (int i = 0; i < 5; i++) {
                buffer.append(("old-" + i).getBytes(), now - 10_000);
            }
            // Recent events
            for (int i = 0; i < 5; i++) {
                buffer.append(("new-" + i).getBytes(), now);
            }

            var policy = RetentionPolicy.retentionPolicy(Long.MAX_VALUE, Long.MAX_VALUE, 5_000);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isEqualTo(5L);
            assertThat(buffer.tailOffset()).isEqualTo(5L);
        }

        @Test
        void applyRetention_evictsBySize() {
            // Each event payload is ~8 bytes ("event-XX")
            for (int i = 0; i < 50; i++) {
                buffer.append(("event-%02d".formatted(i)).getBytes(), 1000L + i);
            }

            // Allow only ~80 bytes of data
            var policy = RetentionPolicy.retentionPolicy(Long.MAX_VALUE, 80, Long.MAX_VALUE);
            buffer.applyRetention(policy);

            assertThat(buffer.eventCount()).isLessThanOrEqualTo(10L);
        }

        @Test
        void tailOffset_advancesAfterRetention() {
            for (int i = 0; i < 20; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            assertThat(buffer.tailOffset()).isEqualTo(0L);

            var policy = RetentionPolicy.retentionPolicy(10, Long.MAX_VALUE, Long.MAX_VALUE);
            buffer.applyRetention(policy);

            assertThat(buffer.tailOffset()).isEqualTo(10L);
        }

        @Test
        void evictByAge_noEviction_whenAllRecent() {
            var now = System.currentTimeMillis();

            for (int i = 0; i < 10; i++) {
                buffer.append(("event-" + i).getBytes(), now);
            }

            buffer.evictByAge(60_000);

            assertThat(buffer.eventCount()).isEqualTo(10L);
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void append_singleBytePayload() {
            buffer.append(new byte[]{42}, 1000L);

            buffer.read(0, 1)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(1);
                      assertThat(events.getFirst().data()).isEqualTo(new byte[]{42});
                  });
        }

        @Test
        void append_largePayload_nearCapacity() {
            var largeBuffer = offHeapRingBuffer(10, 2048);

            try {
                var payload = new byte[1024];
                IntStream.range(0, 1024).forEach(i -> payload[i] = (byte) (i % 256));

                largeBuffer.append(payload, 1000L);

                largeBuffer.read(0, 1)
                           .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                           .onSuccess(events -> {
                               assertThat(events).hasSize(1);
                               assertThat(events.getFirst().data()).isEqualTo(payload);
                           });
            } finally {
                largeBuffer.close();
            }
        }

        @Test
        void append_failsWhenEventExceedsDataRegion() {
            var tinyBuffer = offHeapRingBuffer(10, 16);

            try {
                var oversized = new byte[32];
                tinyBuffer.append(oversized, 1000L)
                          .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                          .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.EventTooLarge.class));
            } finally {
                tinyBuffer.close();
            }
        }

        @Test
        void close_releasesMemory() {
            var localBuffer = offHeapRingBuffer(10, 256);
            localBuffer.close();

            localBuffer.append("test".getBytes(), 1000L)
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.BUFFER_CLOSED));
        }

        @Test
        void close_isIdempotent() {
            var localBuffer = offHeapRingBuffer(10, 256);
            localBuffer.close();
            localBuffer.close(); // should not throw
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentReads_whileWriting_noCorruption() throws InterruptedException {
            var writerDone = new CountDownLatch(1);
            var errors = new CopyOnWriteArrayList<String>();

            // Writer thread
            var writerThread = Thread.ofVirtual().start(() -> {
                for (int i = 0; i < 1000; i++) {
                    var result = buffer.append(("event-" + i).getBytes(), System.currentTimeMillis());

                    result.onFailure(cause -> errors.add("Write failed: " + cause.message()));
                }
                writerDone.countDown();
            });

            // Reader threads
            var readerCount = 4;
            var executor = Executors.newVirtualThreadPerTaskExecutor();

            for (int r = 0; r < readerCount; r++) {
                executor.submit(() -> {
                    var offset = 0L;

                    while (!writerDone.await(1, TimeUnit.MILLISECONDS)) {
                        var readResult = buffer.read(offset, 50);

                        readResult.onSuccess(events -> {
                            for (var event : events) {
                                if (event.data().length == 0) {
                                    errors.add("Empty event at offset " + event.offset());
                                }
                            }
                        });
                    }
                    return null;
                });
            }

            writerThread.join(5000);
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.SECONDS);

            assertThat(errors).isEmpty();
            assertThat(buffer.headOffset()).isEqualTo(999L);
        }

        @Test
        void multipleWriters_sequentialAccess_preservesData() {
            var writerCount = 4;
            var eventsPerWriter = 25;
            var allOffsets = new CopyOnWriteArrayList<Long>();

            // Writers run sequentially (spec says single-writer)
            for (int w = 0; w < writerCount; w++) {
                int writerId = w;

                for (int i = 0; i < eventsPerWriter; i++) {
                    var msg = "w%d-e%d".formatted(writerId, i);
                    buffer.append(msg.getBytes(), System.currentTimeMillis())
                          .onSuccess(allOffsets::add);
                }
            }

            assertThat(allOffsets).hasSize(writerCount * eventsPerWriter);
            assertThat(buffer.eventCount()).isEqualTo(writerCount * eventsPerWriter);
        }
    }

    @Nested
    class DataIntegrity {

        @Test
        void appendAndRead_preservesPayloadExactly() {
            var payloads = List.of(
                "simple text".getBytes(),
                new byte[]{0, 1, 2, 127, -128, -1},
                "".getBytes(),
                "unicode: cafe\u0301".getBytes()
            );

            for (var payload : payloads) {
                buffer.append(payload, 1000L);
            }

            buffer.read(0, payloads.size())
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      for (int i = 0; i < payloads.size(); i++) {
                          assertThat(events.get(i).data()).isEqualTo(payloads.get(i));
                      }
                  });
        }

        @Test
        void appendAndRead_preservesTimestamps() {
            buffer.append("a".getBytes(), 100L);
            buffer.append("b".getBytes(), 200L);
            buffer.append("c".getBytes(), 300L);

            buffer.read(0, 3)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events.get(0).timestamp()).isEqualTo(100L);
                      assertThat(events.get(1).timestamp()).isEqualTo(200L);
                      assertThat(events.get(2).timestamp()).isEqualTo(300L);
                  });
        }

        @Test
        void wrapAround_preservesDataIntegrity() {
            // 5 slots, 64 bytes data. Each message is ~10 bytes.
            var smallBuffer = offHeapRingBuffer(5, 64);

            try {
                for (int i = 0; i < 20; i++) {
                    smallBuffer.append(("message-%02d".formatted(i)).getBytes(), 1000L + i);
                }

                var tail = smallBuffer.tailOffset();
                var head = smallBuffer.headOffset();

                smallBuffer.read(tail, (int) (head - tail + 1))
                           .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                           .onSuccess(events -> {
                               for (var event : events) {
                                   var expected = "message-%02d".formatted((int) event.offset());
                                   assertThat(event.data()).isEqualTo(expected.getBytes());
                               }
                           });
            } finally {
                smallBuffer.close();
            }
        }
    }
}
