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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.IntStream;

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

    private static byte[] payload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }
}
