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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.pragmatica.aether.stream.segment.SegmentSealer.segmentSealer;

/// #1345: a restart after the WAL was compacted but before the metadata snapshot that holds the new segment
/// refs. Refs reach disk only through the storage metadata snapshot, while `truncateWalsToSealed` ran off
/// the IN-MEMORY index and `PartitionWal` compacted once the file passed 8 MiB. The restarted index is
/// modelled as EMPTY (the snapshot predates every seal), so the rebuilt watermark is `-1`. Before the fix
/// recovery appended the compaction survivors (196..199) at FRESH offsets 0..3 — measured
/// `head=3 tail=0 count=4`, offset 0 carrying event 196's payload — with no error and no log line.
///
/// Two outcomes are acceptable and each is pinned by name: (a) truncation never passes the DURABLE watermark,
/// so the WAL still holds every record the lost refs covered and recovery reproduces the original offsets
/// exactly; (b) when the WAL genuinely starts above the durable watermark (a disk that lost the snapshot after
/// the compaction), recovery REFUSES with a typed cause instead of renumbering.
class StreamPartitionManagerRestartAfterCompactionTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final int RING_EVENTS = 4;
    /// 200 × 70 KiB ≈ 13.7 MiB > PartitionWal.COMPACTION_THRESHOLD_BYTES (8 MiB): a truncate past the sealed
    /// watermark physically rewrites the file, which is the only way the survivors' offsets can be lost.
    private static final int EVENTS = 200;
    private static final int PAYLOAD = 70 * 1024;
    private static final int SEALED_AT_LEAST = EVENTS - RING_EVENTS - 1;
    private static final long AWAIT_MS = 10_000;
    private static final long POLL_NANOS = 10_000_000;

    @TempDir
    Path walDir;

    /// (a) Refs never snapshotted, WAL truncated off the in-memory index, restart against an empty index:
    /// the recovered partition must carry the original offsets — head 199, and every readable event's offset
    /// equal to the index encoded in its payload.
    @Test
    void restartAfterCompaction_refsNotYetSnapshotted_recoversOriginalOffsets() {
        var sealedThrough = publishSealAndTruncate(new SegmentIndex());

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), new SegmentIndex()::lastSealedOffset);

        createStream(recovered).onFailure(cause -> fail("recovery refused: " + cause.message()));
        var info = recovered.partitionInfo(STREAM, PARTITION).onFailure(cause -> fail(cause.message())).unwrap();
        var tail = recovered.readLocal(STREAM, PARTITION, info.tailOffset(), EVENTS)
                            .onFailure(cause -> fail(cause.message()))
                            .unwrap();

        recovered.close();

        assertThat(info.headOffset()).as("head=%d tail=%d count=%d", info.headOffset(), info.tailOffset(), info.eventCount())
                                     .isEqualTo(EVENTS - 1L);
        assertThat(tail).isNotEmpty();
        tail.forEach(StreamPartitionManagerRestartAfterCompactionTest::assertOffsetMatchesPayload);
    }

    /// (b) The WAL genuinely starts above the durable watermark (its records below 196 are gone and so are
    /// the refs). Recovery must refuse — a failed `createStream` — never seed at `-1` and renumber.
    @Test
    void restartAfterCompaction_walStartsAboveDurableWatermark_refusesLoudly() {
        var sealedThrough = publishSealAndTruncate(new SegmentIndex());

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), new SegmentIndex()::lastSealedOffset);
        var create = createStream(recovered);
        var observed = recovered.partitionInfo(STREAM, PARTITION)
                                .map(pi -> "head=" + pi.headOffset() + " tail=" + pi.tailOffset() + " count=" + pi.eventCount())
                                .or("no partition");
        var offset0 = recovered.readLocal(STREAM, PARTITION, 0, 1)
                               .map(events -> events.isEmpty() ? "empty" : "offset0 payloadIndex=" + payloadIndex(events.getFirst().data()))
                               .or("unreadable");

        recovered.close();

        assertThat(create.isFailure()).as("recovery silently renumbered: %s, %s", observed, offset0).isTrue();
    }

    /// Publish [#EVENTS], let the sealer drain to the in-memory `index`, truncate the WALs off that index and
    /// close (the crash). Returns the in-memory sealed watermark at the moment of the truncate.
    private long publishSealAndTruncate(SegmentIndex index) {
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(segment -> indexed(index, segment)),
                                             Option.some(walDir),
                                             index::lastSealedOffset);

        createStream(manager).onFailure(cause -> fail(cause.message()));
        IntStream.range(0, EVENTS).forEach(i -> publish(manager, i));
        awaitSealed(index);

        var sealedThrough = index.lastSealedOffset(STREAM, PARTITION);

        manager.truncateWalsToSealed();
        manager.close();

        return sealedThrough;
    }

    private static void awaitSealed(SegmentIndex index) {
        var deadline = System.currentTimeMillis() + AWAIT_MS;

        while (index.lastSealedOffset(STREAM, PARTITION) < SEALED_AT_LEAST && System.currentTimeMillis() < deadline) {
            LockSupport.parkNanos(POLL_NANOS);
        }
    }

    private static Promise<Unit> indexed(SegmentIndex index, SealedSegment segment) {
        index.addSegment(segment.streamName(), segment.partition(), segment.startOffset(), segment.endOffset());

        return Promise.unitPromise();
    }

    private static Result<?> createStream(StreamPartitionManager manager) {
        var retention = RetentionPolicy.retentionPolicy(RING_EVENTS, 1024 * 1024, 600_000);

        return manager.createStream(StreamConfig.streamConfig(STREAM, 1, retention, "earliest"));
    }

    private static void publish(StreamPartitionManager manager, int i) {
        manager.publishLocal(STREAM, PARTITION, payload(i), 1000L + i)
               .onFailure(cause -> fail(cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private static void assertOffsetMatchesPayload(RawEvent event) {
        assertThat(event.offset()).as("offset %d carries event %d's payload", event.offset(), payloadIndex(event.data()))
                                  .isEqualTo(payloadIndex(event.data()));
    }

    private static byte[] payload(int i) {
        var bytes = new byte[PAYLOAD];

        Arrays.fill(bytes, (byte) 'x');
        bytes[0] = (byte) (i >> 8);
        bytes[1] = (byte) i;

        return bytes;
    }

    private static int payloadIndex(byte[] data) {
        return ((data[0] & 0xff) << 8) | (data[1] & 0xff);
    }
}
