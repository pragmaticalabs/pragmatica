package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.OffHeapRingBuffer.offHeapRingBuffer;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

class EvictionPolicyTest {

    @Nested
    class RejectWhenFull {

        @Test
        void append_returnsBufferFull_whenCapacityExhausted() {
            var buffer = offHeapRingBuffer("test", 0, 3, 1024, EvictionListener.NOOP, EvictionPolicy.REJECT_WHEN_FULL);

            try {
                buffer.append("one".getBytes(), 1000L);
                buffer.append("two".getBytes(), 2000L);
                buffer.append("three".getBytes(), 3000L);

                buffer.append("four".getBytes(), 4000L)
                      .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.BUFFER_FULL));
            } finally {
                buffer.close();
            }
        }

        @Test
        void append_returnsBufferFull_whenDataRegionExhausted() {
            // 10 slots but only 16 bytes of data region; each "msg-X" is 5 bytes, so 3 fit
            var buffer = offHeapRingBuffer("test", 0, 10, 16, EvictionListener.NOOP, EvictionPolicy.REJECT_WHEN_FULL);

            try {
                buffer.append("msg-0".getBytes(), 1000L);
                buffer.append("msg-1".getBytes(), 2000L);
                buffer.append("msg-2".getBytes(), 3000L);

                buffer.append("msg-3".getBytes(), 4000L)
                      .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                      .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.BUFFER_FULL));
            } finally {
                buffer.close();
            }
        }
    }

    @Nested
    class DropOldest {

        @Test
        void append_succeeds_whenCapacityExhausted() {
            var buffer = offHeapRingBuffer("test", 0, 3, 1024, EvictionListener.NOOP, EvictionPolicy.DROP_OLDEST);

            try {
                buffer.append("one".getBytes(), 1000L);
                buffer.append("two".getBytes(), 2000L);
                buffer.append("three".getBytes(), 3000L);

                buffer.append("four".getBytes(), 4000L)
                      .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                      .onSuccess(offset -> assertThat(offset).isEqualTo(3L));

                assertThat(buffer.eventCount()).isEqualTo(3L);
                assertThat(buffer.tailOffset()).isEqualTo(1L);
            } finally {
                buffer.close();
            }
        }
    }

    @Nested
    class StrongConsistencyStreamCreation {

        @Test
        void createStream_returnsAhseRequired_whenNoopListenerAndStrong() {
            var manager = streamPartitionManager(Long.MAX_VALUE);

            try {
                var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
                var config = StreamConfig.streamConfig("strong-stream", 1, retention, "latest", 1_048_576L, ConsistencyMode.STRONG);

                manager.createStream(config)
                       .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("Expected failure"))
                       .onFailure(cause -> assertThat(cause).isEqualTo(StreamError.General.AHSE_REQUIRED_FOR_STRONG));
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_succeeds_whenRealListenerAndStrong() {
            EvictionListener realListener = (_, _, _) -> {};
            var manager = streamPartitionManager(Long.MAX_VALUE, realListener);

            try {
                var retention = RetentionPolicy.retentionPolicy(100, 4096, 60_000);
                var config = StreamConfig.streamConfig("strong-stream", 1, retention, "latest", 1_048_576L, ConsistencyMode.STRONG);

                manager.createStream(config)
                       .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

                assertThat(manager.streamInfo("strong-stream").isPresent()).isTrue();
            } finally {
                manager.close();
            }
        }

        @Test
        void createStream_succeeds_whenNoopListenerAndEventual() {
            var manager = streamPartitionManager(Long.MAX_VALUE);

            try {
                var config = StreamConfig.streamConfig("eventual-stream");

                manager.createStream(config)
                       .onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"));

                assertThat(manager.streamInfo("eventual-stream").isPresent()).isTrue();
            } finally {
                manager.close();
            }
        }
    }
}
