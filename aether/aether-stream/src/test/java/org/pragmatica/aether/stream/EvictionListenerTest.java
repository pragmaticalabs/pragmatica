package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;

class EvictionListenerTest {

    private OffHeapRingBuffer buffer;

    @AfterEach
    void tearDown() {
        if (buffer != null) {
            buffer.close();
        }
    }

    @Nested
    class CapacityEviction {

        @Test
        void listener_receivesEventsBeforeEviction() {
            var captured = new CopyOnWriteArrayList<List<OffHeapRingBuffer.RawEvent>>();
            EvictionListener listener = (_, _, events) -> captured.add(new ArrayList<>(events));
            buffer = offHeapRingBuffer("test-stream", 0, 3, 1024, listener);

            // Fill buffer to capacity
            buffer.append("msg-0".getBytes(), 1000L);
            buffer.append("msg-1".getBytes(), 2000L);
            buffer.append("msg-2".getBytes(), 3000L);

            assertThat(captured).isEmpty();

            // This triggers eviction of msg-0
            buffer.append("msg-3".getBytes(), 4000L);

            assertThat(captured).hasSize(1);
            assertThat(captured.getFirst()).hasSize(1);
            assertThat(captured.getFirst().getFirst().data()).isEqualTo("msg-0".getBytes());
            assertThat(captured.getFirst().getFirst().offset()).isEqualTo(0L);
        }

        @Test
        void listener_receivesCorrectOffsetRange() {
            var capturedOffsets = new CopyOnWriteArrayList<Long>();
            EvictionListener listener = (_, _, events) -> events.forEach(e -> capturedOffsets.add(e.offset()));
            buffer = offHeapRingBuffer("test-stream", 2, 3, 1024, listener);

            for (int i = 0; i < 6; i++) {
                buffer.append(("msg-" + i).getBytes(), 1000L + i);
            }

            // Offsets 0, 1, 2 should have been evicted (one per append after filling)
            assertThat(capturedOffsets).containsExactly(0L, 1L, 2L);
        }

        @Test
        void listener_calledOnCapacityEviction() {
            var callCount = new int[]{0};
            EvictionListener listener = (streamName, partition, _) -> {
                callCount[0]++;
                assertThat(streamName).isEqualTo("orders");
                assertThat(partition).isEqualTo(1);
            };
            buffer = offHeapRingBuffer("orders", 1, 5, 1024, listener);

            // Fill + overflow by 3
            for (int i = 0; i < 8; i++) {
                buffer.append(("event-" + i).getBytes(), 1000L + i);
            }

            assertThat(callCount[0]).isEqualTo(3);
        }
    }

    @Nested
    class AgeEviction {

        @Test
        void listener_calledOnAgeEviction() {
            var captured = new CopyOnWriteArrayList<List<OffHeapRingBuffer.RawEvent>>();
            EvictionListener listener = (_, _, events) -> captured.add(new ArrayList<>(events));
            buffer = offHeapRingBuffer("test-stream", 0, 100, 4096, listener);

            var now = System.currentTimeMillis();

            // Old events
            buffer.append("old-0".getBytes(), now - 10_000);
            buffer.append("old-1".getBytes(), now - 10_000);
            // Recent events
            buffer.append("new-0".getBytes(), now);
            buffer.append("new-1".getBytes(), now);

            buffer.evictByAge(5_000);

            assertThat(captured).hasSize(1);
            assertThat(captured.getFirst()).hasSize(2);
            assertThat(captured.getFirst().get(0).data()).isEqualTo("old-0".getBytes());
            assertThat(captured.getFirst().get(1).data()).isEqualTo("old-1".getBytes());
        }
    }

    @Nested
    class NoopBehavior {

        @Test
        void listener_noopDoesNotBreakExisting() {
            buffer = offHeapRingBuffer(3, 1024);

            for (int i = 0; i < 6; i++) {
                buffer.append(("msg-" + i).getBytes(), 1000L + i);
            }

            assertThat(buffer.eventCount()).isEqualTo(3L);
            assertThat(buffer.tailOffset()).isEqualTo(3L);
            assertThat(buffer.headOffset()).isEqualTo(5L);

            buffer.read(3, 10)
                  .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(3);
                      assertThat(events.getFirst().data()).isEqualTo("msg-3".getBytes());
                      assertThat(events.getLast().data()).isEqualTo("msg-5".getBytes());
                  });
        }
    }

    @Nested
    class MultipleBatches {

        @Test
        void listener_multipleEvictionBatches_calledMultipleTimes() {
            var batchSizes = new CopyOnWriteArrayList<Integer>();
            EvictionListener listener = (_, _, events) -> batchSizes.add(events.size());
            buffer = offHeapRingBuffer("test-stream", 0, 5, 1024, listener);

            // Fill buffer
            for (int i = 0; i < 5; i++) {
                buffer.append(("msg-" + i).getBytes(), 1000L + i);
            }

            assertThat(batchSizes).isEmpty();

            // Each append triggers one eviction
            buffer.append("extra-0".getBytes(), 6000L);
            buffer.append("extra-1".getBytes(), 7000L);
            buffer.append("extra-2".getBytes(), 8000L);

            assertThat(batchSizes).hasSize(3);
            assertThat(batchSizes).containsExactly(1, 1, 1);
        }

        @Test
        void listener_dataRegionEviction_collectsMultipleEvents() {
            var captured = new CopyOnWriteArrayList<List<OffHeapRingBuffer.RawEvent>>();
            EvictionListener listener = (_, _, events) -> captured.add(new ArrayList<>(events));
            // Small data region: 20 bytes. Each "msg-X" is 5 bytes, so 4 fit.
            buffer = offHeapRingBuffer("test-stream", 0, 100, 20, listener);

            // Fill data region (4 events * 5 bytes = 20 bytes)
            for (int i = 0; i < 4; i++) {
                buffer.append(("msg-" + i).getBytes(), 1000L + i);
            }

            assertThat(captured).isEmpty();

            // Next append needs space, will evict at least one event
            buffer.append("msg-4".getBytes(), 5000L);

            assertThat(captured).isNotEmpty();
            // Verify evicted events have correct data
            var allEvicted = captured.stream().flatMap(List::stream).toList();
            assertThat(allEvicted.getFirst().data()).isEqualTo("msg-0".getBytes());
        }
    }
}
