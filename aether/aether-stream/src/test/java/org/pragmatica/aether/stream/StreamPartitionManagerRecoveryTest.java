// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.OffHeapRingBuffer.RawEvent;
import org.pragmatica.aether.stream.segment.SealedSegment;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;

/// Proves streaming-persistence W4 (replay-on-recovery): when a partition ring is rebuilt and a WAL
/// exists, the un-sealed tail is recovered into the fresh ring at its ORIGINAL offsets — bounded by the
/// durable last-sealed offset so already-sealed records (served by the tiered reader) are NOT re-added.
/// A "restart" is simulated by building a SECOND manager on the same `walBaseDir`.
class StreamPartitionManagerRecoveryTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int EVENTS = 6;
    private static final int SMALL_RING_EVENTS = 4;
    private static final int RECOVERY_EVENTS = 30;
    /// Two pending one-event segments of an `evt-N` payload (20-byte header + up to 6 bytes each).
    private static final long TWO_SEGMENTS_BYTES = 2 * (20 + 6);

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

    /// #1234: segment 1 ([0-1]) failed to seal while a later segment ([2-3]) is sealed, then the node
    /// restarts. Recovery seeds the ring at the sealed watermark and replays only the WAL above it, so the
    /// watermark must stop below the failed segment: the failed range comes back from the WAL. Before the fix
    /// recovery seeded above the later segment and the failed range was in neither the ring, the segments nor
    /// the replay. The index is written directly — an index rebuilt from refs written before the fix.
    @Test
    void rebuild_replaysFailedSegmentRangeFromWal_whenLaterSegmentSealed() {
        var index = new SegmentIndex();

        publishAll(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir)));
        index.addSegment(STREAM, PARTITION, 2, 3);

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), index::lastSealedOffset);
        createStream(recovered);

        var events = readFrom(recovered, 0);

        assertThat(events).hasSize(EVENTS);
        IntStream.range(0, EVENTS).forEach(i -> assertEvent(events.get(i), i));

        recovered.close();
    }

    /// #1234, ruling B: the node restarts while sealing is still failing. The ring had already reclaimed the
    /// evicted events and the sealer's retained copies die with the process, so the WAL — never truncated past
    /// the contiguous sealed watermark — is the copy that survives: recovery replays the whole range.
    @Test
    void rebuild_replaysEvictedRangeFromWal_whenRestartedWhileSealingFails() {
        var index = new SegmentIndex();
        var storageDown = new AtomicBoolean(true);
        var failing = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(segment -> sealUnlessDown(storageDown, index, segment)),
                                             Option.some(walDir),
                                             index::lastSealedOffset);

        createStream(failing, SMALL_RING_EVENTS);
        IntStream.range(0, EVENTS).forEach(i -> publishOne(failing, i));

        assertCursorExpired(failing, 0);
        failing.truncateWalsToSealed();
        failing.close();

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), index::lastSealedOffset);
        createStream(recovered, EVENTS * 10);

        var events = readFrom(recovered, 0);

        assertThat(index.lastSealedOffset(STREAM, PARTITION)).isEqualTo(-1L);
        assertThat(events).hasSize(EVENTS);
        IntStream.range(0, EVENTS).forEach(i -> assertEvent(events.get(i), i));

        recovered.close();
        // Let the orphaned sealer's next retry succeed, so it stops retrying for the rest of the test JVM.
        storageDown.set(false);
    }

    /// #1234 (review of 510829642, the recovery path): a restart replays the WAL into the ring BEFORE the
    /// stream is registered with the manager. With storage down and the replay evicting past the pending-seal
    /// cap, every recovery-time hand-over must already see the partition as WAL-backed — spill, never refuse.
    /// Keyed on manager registration instead, recovery refused each hand-over as "no WAL" and createStream
    /// failed.
    @Test
    void rebuild_replayPastPendingCapWhileStorageDown_neverRefused_spillsToWal() {
        var index = new SegmentIndex();
        var writer = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        createStream(writer, RECOVERY_EVENTS * 2);
        IntStream.range(0, RECOVERY_EVENTS).forEach(i -> publishOne(writer, i));
        writer.close();

        var storageDown = new AtomicBoolean(true);
        var sealer = segmentSealer(segment -> sealUnlessDown(storageDown, index, segment), TWO_SEGMENTS_BYTES);
        var recovered = streamPartitionManager(Long.MAX_VALUE, sealer, Option.some(walDir), index::lastSealedOffset);

        createStream(recovered, SMALL_RING_EVENTS);

        assertThat(sealer.refusalCount()).isZero();
        assertThat(sealer.spillCount()).isPositive();
        assertThat(sealer.pendingBytes()).isLessThanOrEqualTo(TWO_SEGMENTS_BYTES);

        var tail = readFrom(recovered, RECOVERY_EVENTS - SMALL_RING_EVENTS);

        assertThat(tail).hasSize(SMALL_RING_EVENTS);
        IntStream.range(0, tail.size()).forEach(i -> assertEvent(tail.get(i), RECOVERY_EVENTS - SMALL_RING_EVENTS + i));

        recovered.close();
        storageDown.set(false);
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

    /// #1258 review B2 (CTO ruling): a gap BEFORE the first WAL record is indistinguishable today from
    /// retention having reclaimed every sealed segment (the sealed floor then drops to -1 while compaction
    /// already removed the records below the old floor). It is accepted: the ring is seeded just below
    /// the first record, the records land at their stored offsets, and the head gap is counted and WARNed.
    @Test
    void rebuild_acceptsLeadingGap_asReclaimedHistory_whenSealedFloorRegressed() throws IOException {
        writeRawWal(frame(5, "v5"), frame(6, "v6"), frame(7, "v7"));
        var headGapsBefore = StreamPartitionManager.walRecoveryHeadGapsAccepted();

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));
        createStream(recovered);

        var events = readFrom(recovered, 5);

        assertThat(events).extracting(RawEvent::offset).containsExactly(5L, 6L, 7L);
        assertThat(new String(events.get(0).data(), UTF_8)).isEqualTo("v5");
        assertCursorExpired(recovered, 0);
        assertThat(StreamPartitionManager.walRecoveryHeadGapsAccepted() - headGapsBefore).isEqualTo(1);

        recovered.close();
    }

    /// #1258 review round 2 (R2-3, reviewer probe D): the file still physically holds records at and below
    /// the floor (lazy truncation), so a missing offset right above the floor is a HOLE, not reclaimed
    /// history. A leading gap is accepted only when the file's LOWEST stored offset is above floor + 1.
    @Test
    void rebuild_failsLoudly_whenHoleSitsDirectlyAboveTheFloor_andEarlierRecordsExist() throws IOException {
        writeRawWal(frame(0, "v0"), frame(1, "v1"), frame(2, "v2"), frame(4, "v4"), frame(5, "v5"));
        var headGapsBefore = StreamPartitionManager.walRecoveryHeadGapsAccepted();

        assertRecoveryRefused(streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), sealedUpTo(2L)), 3L, 4L);
        assertThat(StreamPartitionManager.walRecoveryHeadGapsAccepted() - headGapsBefore).as("not counted as reclaimed")
                                                                                         .isZero();
    }

    @Test
    void rebuild_acceptsLeadingGap_aboveSealedBound() throws IOException {
        writeRawWal(frame(4, "v4"), frame(5, "v5"));

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), sealedUpTo(2L));
        createStream(recovered);

        assertThat(readFrom(recovered, 4)).extracting(RawEvent::offset).containsExactly(4L, 5L);
        assertCursorExpired(recovered, 3);

        recovered.close();
    }

    // === helpers ===

    /// Storage refuses every seal while it is down; once up, it records the segment in the index as
    /// `StorageSegmentSink` does.
    private static Promise<Unit> sealUnlessDown(AtomicBoolean storageDown, SegmentIndex index, SealedSegment segment) {
        return storageDown.get()
               ? Causes.cause("disk full").promise()
               : indexed(index, segment);
    }

    private static Promise<Unit> indexed(SegmentIndex index, SealedSegment segment) {
        index.addSegment(segment.streamName(), segment.partition(), segment.startOffset(), segment.endOffset());

        return Promise.unitPromise();
    }

    private static void createStream(StreamPartitionManager manager, int ringEvents) {
        var retention = RetentionPolicy.retentionPolicy(ringEvents, 64 * 1024, 600_000);

        manager.createStream(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"))
               .onFailure(cause -> fail(cause.message()));
    }

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
               .onFailure(cause -> assertThat(cause.stream().toList()).as("the per-partition refusal, aggregated across partitions")
                                                                      .singleElement()
                                                                      .isInstanceOfSatisfying(StreamError.WalReplayMismatch.class,
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
        assertThat(mismatch.message()).as("names the STREAM, since the whole stream stays unmaterialized")
                                      .contains("stream 'orders'");
        assertThat(mismatch.message()).as("advice that cannot discard a correct tail").contains("do not delete");
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
