// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.lang.Option;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Proves streaming-persistence W4 (replay-on-recovery): when a partition ring is rebuilt and a WAL
/// exists, the un-sealed tail is recovered into the fresh ring at its ORIGINAL offsets — bounded by the
/// durable last-sealed offset so already-sealed records (served by the tiered reader) are NOT re-added.
/// A "restart" is simulated by building a SECOND manager on the same `walBaseDir`.
class StreamPartitionManagerRecoveryTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int EVENTS = 6;

    @TempDir
    Path walDir;

    @Test
    void rebuild_recoversFullTail_whenNothingSealed() {
        publishAll(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        createStream(recovered);

        var events = readFrom(recovered, 0);

        assertThat(events).hasSize(EVENTS);
        IntStream.range(0, EVENTS).forEach(i -> assertEvent(events.get(i), i));

        recovered.close();
    }

    @Test
    void rebuild_recoversOnlyUnsealedTail_whenSealedBoundPresent() {
        var sealedBound = 3L;

        publishAll(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), sealedUpTo(sealedBound));
        createStream(recovered);

        // Only offsets ABOVE the sealed bound (4, 5) are replayed into the ring.
        var tail = readFrom(recovered, sealedBound + 1);

        assertThat(tail).hasSize(EVENTS - (int) (sealedBound + 1));
        IntStream.range(0, tail.size()).forEach(i -> assertEvent(tail.get(i), (int) sealedBound + 1 + i));

        // Offsets at or below the sealed bound cleanly miss (served by the tiered reader, not the ring).
        assertCursorExpired(recovered, sealedBound);
        assertCursorExpired(recovered, 0);

        recovered.close();
    }

    @Test
    void rebuild_recoversNothing_whenNoWalBaseDir() {
        publishAll(streamPartitionManager(Long.MAX_VALUE, Option.none()));

        assertThat(Files.exists(walDir.resolve(STREAM))).isFalse();

        var rebuilt = streamPartitionManager(Long.MAX_VALUE, Option.none());
        createStream(rebuilt);

        // No WAL ⇒ no recovery: the rebuilt partition is empty, behavior unchanged from pre-WAL.
        assertThat(readFrom(rebuilt, 0)).isEmpty();

        rebuilt.close();
    }

    // === helpers ===

    private static void publishAll(StreamPartitionManager manager) {
        createStream(manager);
        IntStream.range(0, EVENTS).forEach(i -> publishOne(manager, i));
        manager.close();
    }

    private static void createStream(StreamPartitionManager manager) {
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    private static void publishOne(StreamPartitionManager manager, int i) {
        manager.publishLocal(STREAM, PARTITION, payload(i), 1000L + i)
               .onFailure(cause -> fail(cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private static List<RawEvent> readFrom(StreamPartitionManager manager, long fromOffset) {
        return manager.readLocal(STREAM, PARTITION, fromOffset, 100).onFailure(cause -> fail(cause.message())).unwrap();
    }

    private static void assertCursorExpired(StreamPartitionManager manager, long fromOffset) {
        manager.readLocal(STREAM, PARTITION, fromOffset, 100)
               .onSuccess(events -> fail("expected CursorExpired at " + fromOffset + " but read " + events.size() + " events"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.CursorExpired.class));
    }

    private static LastSealedOffsetSource sealedUpTo(long bound) {
        return (_, _) -> bound;
    }

    private static void assertEvent(RawEvent event, int i) {
        assertThat(event.offset()).isEqualTo((long) i);
        assertThat(event.timestamp()).isEqualTo(1000L + i);
        assertThat(new String(event.data(), UTF_8)).isEqualTo("evt-" + i);
    }

    private static byte[] payload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }
}
