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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

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

    /// #1232 acceptance 1: a WAL holding offset 1 ("v1") BEFORE offset 0 ("v0") — the file order the
    /// pre-fix owner path could produce. Each record lands at its STORED offset; before the fix recovery
    /// numbered them in scan order and `readLocal(0)` returned "v1".
    @Test
    void rebuild_placesEachRecordAtItsStoredOffset_whenWalFramesAreOutOfOrder() throws IOException {
        writeRawWal(frame(1, "v1"), frame(0, "v0"));

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        createStream(recovered);

        var events = readFrom(recovered, 0);

        assertThat(events).extracting(RawEvent::offset).containsExactly(0L, 1L);
        assertThat(new String(events.get(0).data(), UTF_8)).isEqualTo("v0");
        assertThat(new String(events.get(1).data(), UTF_8)).isEqualTo("v1");

        recovered.close();
    }

    /// #1232 acceptance 2: two frames for one offset. Recovery refuses with the distinct
    /// [StreamError.WalReplayMismatch] instead of shifting every later record by one.
    @Test
    void rebuild_failsLoudly_whenWalHoldsDuplicateOffset() throws IOException {
        writeRawWal(frame(0, "v0"), frame(1, "v1"), frame(1, "v1-again"), frame(2, "v2"));

        assertRecoveryRefused(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)), 2L, 1L);
    }

    /// A missing frame above the sealed bound: recovery refuses instead of numbering offset 5's record as 4.
    @Test
    void rebuild_failsLoudly_whenWalTailHasGapAboveSealedBound() throws IOException {
        writeRawWal(frame(0, "v0"), frame(1, "v1"), frame(2, "v2"), frame(3, "v3"), frame(5, "v5"));

        assertRecoveryRefused(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), sealedUpTo(2L)), 4L, 5L);
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

    private void assertRecoveryRefused(StreamPartitionManager manager, long expectedOffset, long foundOffset) {
        manager.createStream(StreamConfig.streamConfig(STREAM))
               .onSuccess(_ -> fail("recovery must refuse a WAL whose tail does not continue the ring"))
               .onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.WalReplayMismatch.class,
                                                                            mismatch -> assertMismatch(mismatch,
                                                                                                       expectedOffset,
                                                                                                       foundOffset)));
        manager.close();
    }

    private void assertMismatch(StreamError.WalReplayMismatch mismatch, long expectedOffset, long foundOffset) {
        assertThat(mismatch.partition()).isEqualTo(PARTITION);
        assertThat(mismatch.expectedOffset()).isEqualTo(expectedOffset);
        assertThat(mismatch.foundOffset()).isEqualTo(foundOffset);
        assertThat(mismatch.walFile()).isEqualTo(walFile());
        assertThat(mismatch.message()).contains("move that file aside");
    }

    private Path walFile() {
        return walDir.resolve(STREAM).resolve(PARTITION + ".wal");
    }

    /// Writes frames byte-for-byte in the documented `PartitionWal` format, in the order given — the
    /// fixed WAL refuses to WRITE an out-of-order or duplicate offset, so a file holding one (left by
    /// pre-fix code) can only be produced directly.
    private void writeRawWal(byte[]... frames) throws IOException {
        Files.createDirectories(walFile().getParent());
        try (var out = Files.newOutputStream(walFile())) {
            for (var frame : frames) {
                out.write(frame);
            }
        }
    }

    /// `[u32 payloadLen][u64 offset][u64 timestampMillis][u32 crc32(offset||timestamp||payload)][payload]`.
    private static byte[] frame(long offset, String text) {
        var payload = text.getBytes(UTF_8);
        var timestamp = 1000L + offset;
        var crcInput = ByteBuffer.allocate(16 + payload.length).putLong(offset).putLong(timestamp).put(payload);
        var crc = new CRC32();

        crc.update(crcInput.array());
        return ByteBuffer.allocate(24 + payload.length)
                         .putInt(payload.length)
                         .putLong(offset)
                         .putLong(timestamp)
                         .putInt((int) crc.getValue())
                         .put(payload)
                         .array();
    }

    private static byte[] payload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }
}
