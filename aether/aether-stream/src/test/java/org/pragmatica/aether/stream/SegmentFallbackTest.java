package org.pragmatica.aether.stream;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.aether.stream.segment.SegmentSealer;
import org.pragmatica.aether.stream.segment.StorageSegmentSink;
import org.pragmatica.aether.stream.segment.TieredStreamReader;
import org.pragmatica.lang.Option;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.PartitionedStreamAccess.streamAccess;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// Tests the read-through fallback from ring buffer to sealed segments.
/// When events are evicted from the ring buffer and sealed to storage,
/// PartitionedStreamAccess should transparently read them from SegmentReader.
class SegmentFallbackTest {

    private static final String STREAM = "fallback-test";
    private static final int PARTITION = 0;
    private static final int PARTITION_COUNT = 1;
    private static final long ONE_GB = 1024 * 1024 * 1024L;

    /// Small ring buffer capacity to force eviction quickly.
    private static final int RING_CAPACITY = 5;
    private static final int RING_DATA_BYTES = 1024;

    private StorageInstance storage;
    private SegmentIndex index;
    private StorageSegmentSink sink;
    private TieredStreamReader tieredReader;
    private SegmentSealer sealer;
    private StreamPartitionManager partitionManager;
    private PartitionedStreamAccess<byte[]> access;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        sink = storageSegmentSink(storage, index);
        tieredReader = tieredStreamReader(index, storage);
        sealer = segmentSealer(sink);

        // Wire SegmentSealer as the eviction listener so evicted events get sealed
        partitionManager = streamPartitionManager(Long.MAX_VALUE, sealer);
        var retention = RetentionPolicy.retentionPolicy(RING_CAPACITY, RING_DATA_BYTES, 600_000);
        partitionManager.createStream(StreamConfig.streamConfig(STREAM, PARTITION_COUNT, retention, "earliest"));

        PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> org.pragmatica.lang.Promise.unitPromise();
        access = streamAccess(partitionManager, identitySerializer(), identityDeserializer(),
                              STREAM, PARTITION_COUNT, Option.<Function<byte[], Object>>none(),
                              noopWriter, tieredReader);
    }

    @AfterEach
    void tearDown() {
        partitionManager.close();
        storage.shutdown();
    }

    @Nested
    class RecentEvents {

        @Test
        void fetch_recentEvents_readsFromRingBuffer() {
            publishEvents(3);

            var result = access.fetch(PARTITION, 0, 10).await();
            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSize(3);
                      assertThat(events.get(0).offset()).isEqualTo(0L);
                      assertThat(events.get(1).offset()).isEqualTo(1L);
                      assertThat(events.get(2).offset()).isEqualTo(2L);
                  });
        }
    }

    @Nested
    class EvictedEvents {

        @Test
        void fetch_evictedEvents_readsFromSealedSegment() {
            // Publish enough events to force eviction (capacity = 5, publish 10)
            publishEvents(10);

            // Offset 0 should have been evicted from the ring buffer and sealed to storage
            var result = access.fetch(PARTITION, 0, 5).await();
            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).isNotEmpty();
                      assertThat(events.getFirst().offset()).isEqualTo(0L);
                  });
        }
    }

    @Nested
    class MixedRange {

        @Test
        void fetch_mixedRange_combinesBothSources() {
            // Publish enough to evict early events, keep recent ones in ring buffer
            publishEvents(10);

            // Request from offset 0 -- should combine sealed + ring buffer
            var result = access.fetch(PARTITION, 0, 20).await();
            result.onFailure(_ -> org.junit.jupiter.api.Assertions.fail("Expected success"))
                  .onSuccess(events -> {
                      assertThat(events).hasSizeGreaterThanOrEqualTo(5);
                      // Events should be in offset order
                      for (int i = 1; i < events.size(); i++) {
                          assertThat(events.get(i).offset())
                              .isGreaterThan(events.get(i - 1).offset());
                      }
                  });
        }
    }

    private void publishEvents(int count) {
        for (int i = 0; i < count; i++) {
            var payload = ("event-" + i).getBytes();
            partitionManager.publishLocal(STREAM, PARTITION, payload, 1000L + i);
        }
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> byte[] encode(T object) {
                return (byte[]) object;
            }

            @Override
            public <T> void write(ByteBuf byteBuf, T object) {
                byteBuf.writeBytes((byte[]) object);
            }
        };
    }

    private static Deserializer identityDeserializer() {
        return new Deserializer() {
            @SuppressWarnings("unchecked")
            @Override
            public <T> T decode(byte[] bytes) {
                return (T) bytes;
            }

            @SuppressWarnings("unchecked")
            @Override
            public <T> T read(ByteBuf byteBuf) {
                var bytes = new byte[byteBuf.readableBytes()];
                byteBuf.readBytes(bytes);
                return (T) bytes;
            }
        };
    }
}
