// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.LongStream;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamAccess.StreamEvent;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.storage.MemoryTier;
import org.pragmatica.storage.StorageInstance;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.PartitionedStreamAccess.streamAccess;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;
import static org.pragmatica.aether.stream.segment.StorageSegmentSink.storageSegmentSink;
import static org.pragmatica.aether.stream.segment.TieredStreamReader.tieredStreamReader;

/// PR-0c with the REAL sealer and a real tiered reader: the claim "a consumer whose position is older than `max_age`
/// reads it from the tier" (spec 3.3 item 3), and the tick running against concurrent appends.
class RingAgeTickTierTest {
    private static final String STREAM = "aged";
    private static final int PARTITION = 0;
    private static final long HOUR_MS = 3_600_000L;
    private static final long ONE_GB = 1024 * 1024 * 1024L;
    private static final long DEFAULT_MAX_BYTES = 256L * 1024 * 1024;

    private StorageInstance storage;
    private SegmentIndex index;
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        storage = StorageInstance.storageInstance("test", List.of(MemoryTier.memoryTier(ONE_GB)));
        index = new SegmentIndex();
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         segmentSealer(SealReleaseLag.laggingRelease(storageSegmentSink(storage, index))));
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(100_000, DEFAULT_MAX_BYTES, HOUR_MS).withDeclared(RetentionDimensions.AGE),
                                                       "earliest"))
               .onFailure(cause -> fail(cause.message()));
    }

    @AfterEach
    void tearDown() {
        manager.close();
        storage.shutdown();
    }

    @Test
    void agedEvents_leftTheRingByTheTick_areReadFromTheSealedTier_inOrder() {
        var now = System.currentTimeMillis();

        publish("old-0", now - 2 * HOUR_MS);
        publish("old-1", now - 2 * HOUR_MS);
        publish("fresh-2", now);
        publish("fresh-3", now);

        manager.evictRingsByAge();
        SealReleaseLag.awaitReadable(index, manager, STREAM, PARTITION, 1);

        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::tailOffset).or(-1L))
            .as("the aged events left the ring").isEqualTo(2L);
        assertThat(manager.readLocal(STREAM, PARTITION, 0, 10).isFailure()).as("the ring no longer serves offset 0").isTrue();
        assertThat(fetch(0)).as("a consumer at 0 gets the aged events from the tier, then the ring's").containsExactly(0L, 1L, 2L, 3L);
    }

    @Test
    void evictRingsByAge_concurrentWithAppends_handsEachStaleEventOverExactlyOnce_inOffsetOrder() throws Exception {
        var handed = new CopyOnWriteArrayList<Long>();

        manager.close();
        manager = streamPartitionManager(Long.MAX_VALUE, (_, _, events) -> {
            events.forEach(event -> handed.add(event.offset()));
            return Result.unitResult();
        });
        manager.createStream(StreamConfig.streamConfig(STREAM,
                                                       1,
                                                       RetentionPolicy.retentionPolicy(100_000, DEFAULT_MAX_BYTES, HOUR_MS).withDeclared(RetentionDimensions.AGE),
                                                       "earliest"))
               .onFailure(cause -> fail(cause.message()));

        var stale = 1_000;
        var fresh = 1_000;
        var done = new AtomicBoolean();
        var ticker = Thread.ofPlatform().start(() -> tickUntil(done));
        var now = System.currentTimeMillis();

        for (int i = 0; i < stale; i++) {
            publish("s" + i, now - 2 * HOUR_MS);
        }

        for (int i = 0; i < fresh; i++) {
            publish("f" + i, now);
        }

        done.set(true);
        ticker.join();
        manager.evictRingsByAge();

        assertThat(handed).as("every stale event went to the sealer once, in order, none of the fresh ones")
                          .containsExactlyElementsOf(LongStream.range(0, stale).boxed().toList());
        assertThat(manager.partitionBuffer(STREAM, PARTITION).map(OffHeapRingBuffer::eventCount).or(-1L)).isEqualTo((long) fresh);
    }

    private void tickUntil(AtomicBoolean done) {
        while (!done.get()) {
            manager.evictRingsByAge();
        }
    }

    private void publish(String payload, long timestamp) {
        manager.publishLocal(STREAM, PARTITION, payload.getBytes(UTF_8), timestamp)
               .onFailure(cause -> fail("publish " + payload + ": " + cause.message()));
    }

    private List<Long> fetch(long fromOffset) {
        PartitionedStreamAccess.CursorCheckpointWriter noopWriter = (_, _, _, _) -> Promise.unitPromise();
        var access = streamAccess(manager,
                                  identitySerializer(),
                                  identityDeserializer(),
                                  STREAM,
                                  1,
                                  Option.<Function<byte[], Object>>none(),
                                  noopWriter,
                                  tieredStreamReader(index, storage));

        return access.fetch(PARTITION, fromOffset, 10)
                     .await()
                     .onFailure(cause -> fail("fetch(" + fromOffset + ") failed: " + cause.message()))
                     .or(List.of())
                     .stream()
                     .map(StreamEvent::offset)
                     .toList();
    }

    private static Serializer identitySerializer() {
        return new Serializer() {
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
