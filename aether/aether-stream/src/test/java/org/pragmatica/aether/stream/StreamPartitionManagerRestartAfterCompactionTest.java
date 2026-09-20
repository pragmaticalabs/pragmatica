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
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.storage.BlockId;
import org.pragmatica.storage.MetadataStore;
import org.pragmatica.storage.SnapshotConfig;
import org.pragmatica.storage.SnapshotManager;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.locks.LockSupport;
import java.util.stream.IntStream;
import java.util.stream.LongStream;

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
/// Two outcomes are acceptable and each is pinned by name: (a) truncation never passes the DURABLE watermark
/// ([DurableSealedOffsetSource] — what a restart would rebuild, never the live index), so the WAL still holds
/// every record the lost refs covered and recovery reproduces the original offsets exactly; (b) when the WAL
/// genuinely starts above the durable watermark (a disk that lost the snapshot after the compaction), recovery
/// REFUSES with [StreamError.WalRecoveryGap] instead of renumbering. The snapshot-derived production source is
/// pinned against a real [SnapshotManager] below.
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

    /// (a) Refs never snapshotted (the durable view stays EMPTY while the live index seals to 195), the
    /// truncation tick runs, restart against the empty index: the recovered partition must carry the original
    /// offsets — head 199, and every readable event's offset equal to the index encoded in its payload.
    @Test
    void restartAfterCompaction_refsNotYetSnapshotted_recoversOriginalOffsets() {
        var neverSnapshotted = new SegmentIndex();
        var sealedThrough = publishSealAndTruncate(new SegmentIndex(), DurableSealedOffsetSource.same(neverSnapshotted::lastSealedOffset));

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), neverSnapshotted::lastSealedOffset);

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

    /// (b) The index IS treated as durable, so the tick compacts the WAL past 195; then the refs are lost
    /// anyway (a snapshot directory restored from before the compaction). The WAL genuinely starts at 196
    /// above a rebuilt watermark of -1: recovery must refuse with [StreamError.WalRecoveryGap] naming the gap
    /// — never seed at `-1` and renumber.
    @Test
    void restartAfterCompaction_walStartsAboveDurableWatermark_refusesLoudly() {
        var index = new SegmentIndex();
        var sealedThrough = publishSealAndTruncate(index, DurableSealedOffsetSource.same(index::lastSealedOffset));

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
        // createStream folds every partition's recovery through Result.allOf, so the refusal arrives inside a
        // composite; the typed cause is the leaf.
        create.onFailure(cause -> assertThat(cause.stream().filter(StreamError.WalRecoveryGap.class::isInstance)
                                                  .map(StreamError.WalRecoveryGap.class::cast)
                                                  .toList()).as("typed refusal inside %s", cause.message())
                                                            .singleElement()
                                                            .satisfies(gap -> assertGap(gap, 0L, sealedThrough + 1)));
    }

    /// The tripwire past the first record: a WAL whose records run 0,1,2 then 4,5 (a mid-log hole — nothing
    /// in the truncate path produces one, so it is a corruption signature). The ring would assign 4's record
    /// offset 3; recovery must refuse naming exactly that.
    @Test
    void restart_walWithMidLogHole_refusesLoudly() {
        var wal = PartitionWal.open(walDir.resolve(STREAM).resolve(PARTITION + ".wal"))
                              .onFailure(cause -> fail(cause.message()))
                              .unwrap();

        LongStream.of(0, 1, 2, 4, 5).forEach(offset -> wal.append(offset, payload((int) offset), 1000L + offset)
                                                          .await()
                                                          .onFailure(cause -> fail(cause.message())));
        wal.close();

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), new SegmentIndex()::lastSealedOffset);
        var create = createStream(recovered);

        recovered.close();

        assertThat(create.isFailure()).as("a mid-log hole was renumbered silently").isTrue();
        create.onFailure(cause -> assertThat(cause.stream().filter(StreamError.WalRecoveryGap.class::isInstance)
                                                  .map(StreamError.WalRecoveryGap.class::cast)
                                                  .toList()).singleElement()
                                                            .satisfies(gap -> assertGap(gap, 3L, 4L)));
    }

    /// Control for the tripwire: the snapshot DID cover the seals (durable == live), the tick compacted the WAL
    /// to the watermark, and the restart rebuilds the same watermark. The survivors start exactly at
    /// `base + 1`, so recovery accepts them at their original offsets — the check must not fire here.
    @Test
    void restartAfterCompaction_refsSnapshotted_recoversTailAtOriginalOffsets() {
        var index = new SegmentIndex();
        var sealedThrough = publishSealAndTruncate(index, DurableSealedOffsetSource.same(index::lastSealedOffset));

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), index::lastSealedOffset);

        createStream(recovered).onFailure(cause -> fail("recovery refused: " + cause.message()));
        var info = recovered.partitionInfo(STREAM, PARTITION).onFailure(cause -> fail(cause.message())).unwrap();
        var tail = recovered.readLocal(STREAM, PARTITION, sealedThrough + 1, EVENTS)
                            .onFailure(cause -> fail(cause.message()))
                            .unwrap();

        recovered.close();

        assertThat(info.headOffset()).isEqualTo(EVENTS - 1L);
        assertThat(info.tailOffset()).isEqualTo(sealedThrough + 1);
        assertThat(tail).hasSize((int) (EVENTS - 1 - sealedThrough));
        tail.forEach(StreamPartitionManagerRestartAfterCompactionTest::assertOffsetMatchesPayload);
    }

    /// The production source: the watermark of the latest metadata snapshot ON DISK, rebuilt the way boot
    /// rebuilds it. Before any snapshot it is `-1` whatever the store holds (nothing may be truncated); after a
    /// snapshot it is that snapshot's contiguous watermark, and a ref added since is NOT counted until the next.
    @Test
    void fromLatestSnapshot_reportsOnlyRefsOnDisk() {
        var store = MetadataStore.inMemoryMetadataStore("streams");
        var snapshots = SnapshotManager.snapshotManager(store, SnapshotConfig.snapshotConfig(walDir.resolve("snapshots"), "node-1"));
        var durable = DurableSealedOffsetSource.fromLatestSnapshot(snapshots);

        store.putRef("streams/" + STREAM + "/" + PARTITION + "/0-99", blockId(1));
        assertThat(durable.current().lastSealedOffset(STREAM, PARTITION)).as("no snapshot yet").isEqualTo(-1L);

        snapshots.forceSnapshot();
        store.putRef("streams/" + STREAM + "/" + PARTITION + "/100-199", blockId(2));
        assertThat(durable.current().lastSealedOffset(STREAM, PARTITION)).as("only the snapshotted ref").isEqualTo(99L);

        snapshots.forceSnapshot();
        assertThat(durable.current().lastSealedOffset(STREAM, PARTITION)).as("both refs on disk").isEqualTo(199L);
    }

    /// Publish [#EVENTS], let the sealer drain to the in-memory `index`, truncate the WALs off that index and
    /// close (the crash). Returns the in-memory sealed watermark at the moment of the truncate.
    private long publishSealAndTruncate(SegmentIndex index, DurableSealedOffsetSource durable) {
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(segment -> indexed(index, segment)),
                                             Option.some(walDir),
                                             index::lastSealedOffset,
                                             durable);

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

    /// The rebuilt watermark is `-1` in every refusal here (an empty index), so `expected` names the offset the
    /// ring would have assigned and `found` the record's own.
    private static void assertGap(StreamError.WalRecoveryGap gap, long expected, long found) {
        assertThat(gap.streamName()).isEqualTo(STREAM);
        assertThat(gap.partition()).isEqualTo(PARTITION);
        assertThat(gap.base()).isEqualTo(-1L);
        assertThat(gap.expected()).isEqualTo(expected);
        assertThat(gap.found()).isEqualTo(found);
    }

    private static BlockId blockId(int seed) {
        return BlockId.blockId(new byte[] {(byte) seed}).unwrap();
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
