// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
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
/// Two halves, each pinned by name: (a) truncation never passes the DURABLE watermark ([DurableSealedOffsetSource]
/// — what a restart would rebuild, never the live index), so the WAL still holds every record the lost refs
/// covered and recovery reproduces the original offsets exactly; (b) when the WAL genuinely starts above the
/// rebuilt watermark (a disk that lost the snapshot after the compaction), recovery places the survivors at their
/// STORED offsets — #1258's head-gap acceptance, WARN + `walRecoveryHeadGapsAccepted` — so offset 0 is ABSENT
/// rather than carrying event 196; a hole or duplicate INSIDE the tail refuses with [StreamError.WalReplayMismatch]
/// before anything is appended. The snapshot-derived production source is pinned against a real
/// [SnapshotManager] below.
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
    private static final long SEAL_DRAIN_NANOS = 500_000_000;

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
        // (a) itself: the WAL was NOT compacted — its first stored record is still offset 0. Under a live-index
        // truncation the survivors would still come back at 196..199 (head-gap acceptance, #1258), so the ring
        // alone cannot tell the two apart; the file and the head-gap counter can.
        assertThat(firstStoredOffset()).as("WAL still starts at offset 0: nothing was compacted").isZero();

        var headGapsBefore = StreamPartitionManager.walRecoveryHeadGapsAccepted();
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
        assertThat(StreamPartitionManager.walRecoveryHeadGapsAccepted() - headGapsBefore).as("the whole log replayed from 0: no head gap to accept")
                                                                                          .isZero();
    }

    /// (b) The index IS treated as durable, so the tick compacts the WAL past 195; then the refs are lost
    /// anyway (a snapshot directory restored from before the compaction). The WAL starts at 196 above a rebuilt
    /// watermark of -1. Recovery accepts the head gap as reclaimed history (#1258): 196..199 sit at their stored
    /// offsets, offset 0 is absent (`CursorExpired`, never event 196's payload), one head gap is counted and the
    /// WARN names the range. The sealed history [0, 195] is unreachable — its refs are gone — which is the loss
    /// (a) exists to prevent; nothing is renumbered.
    @Test
    void restartAfterCompaction_walStartsAboveDurableWatermark_survivorsKeepStoredOffsets_headGapWarned() {
        var index = new SegmentIndex();
        var sealedThrough = publishSealAndTruncate(index, DurableSealedOffsetSource.same(index::lastSealedOffset));

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var headGapsBefore = StreamPartitionManager.walRecoveryHeadGapsAccepted();
        var warnings = new CopyOnWriteArrayList<String>();
        var capture = capturingWarnings(warnings);
        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), new SegmentIndex()::lastSealedOffset);

        try {
            createStream(recovered).onFailure(cause -> fail("recovery refused: " + cause.message()));
            assertSurvivorsAtStoredOffsets(recovered, sealedThrough + 1);
            assertThat(StreamPartitionManager.walRecoveryHeadGapsAccepted() - headGapsBefore).isEqualTo(1);
            assertThat(warnings).singleElement()
                                .asString()
                                .contains("starts at offset " + (sealedThrough + 1))
                                .contains("treated as reclaimed history");
        } finally {
            capture.run();
            recovered.close();
        }
    }

    /// A mid-log hole — records 0..5 then 7,8 (nothing in the truncate path produces one, so it is a corruption
    /// signature). The ring would assign 7's record offset 6; recovery must refuse naming exactly that, and it
    /// must refuse BEFORE appending anything (rev1349 F2): the recovered manager carries a recording sealer and
    /// the six records before the hole overflow the 4-slot ring, so a per-record check alone (#1258's
    /// `placeRecord`, which refuses AT the bad record) would already have evicted and sealed `[0-0, 1-1]` from a
    /// recovery about to be refused. Nothing may reach the sink. The hole sits past ring capacity on purpose: a
    /// hole at 3 is refused by the per-record check before any eviction and cannot see the ordering.
    @Test
    void restart_walWithMidLogHole_refusesLoudly_andSealsNothing() {
        var wal = PartitionWal.open(walDir.resolve(STREAM).resolve(PARTITION + ".wal"))
                              .onFailure(cause -> fail(cause.message()))
                              .unwrap();

        LongStream.of(0, 1, 2, 3, 4, 5, 7, 8).forEach(offset -> wal.append(offset, payload((int) offset), 1000L + offset)
                                                                   .await()
                                                                   .onFailure(cause -> fail(cause.message())));
        wal.close();

        var sealed = new CopyOnWriteArrayList<SealedSegment>();
        var recovered = streamPartitionManager(Long.MAX_VALUE,
                                               segmentSealer(segment -> recordSeal(sealed, segment)),
                                               Option.some(walDir),
                                               new SegmentIndex()::lastSealedOffset);
        var create = createStream(recovered);

        // The sealer drains asynchronously; give an (incorrect) append-first ordering time to reach the sink.
        LockSupport.parkNanos(SEAL_DRAIN_NANOS);
        recovered.close();

        assertThat(create.isFailure()).as("a mid-log hole was renumbered silently").isTrue();
        assertThat(sealed).as("a refused recovery handed the sink segments %s",
                              sealed.stream().map(segment -> segment.startOffset() + "-" + segment.endOffset()).toList())
                          .isEmpty();
        create.onFailure(cause -> assertThat(cause.stream().filter(StreamError.WalReplayMismatch.class::isInstance)
                                                  .map(StreamError.WalReplayMismatch.class::cast)
                                                  .toList()).singleElement()
                                                            .satisfies(mismatch -> assertMismatch(mismatch, 6L, 7L)));
    }

    /// #1278 shape, pinned as it stands: the snapshot covered every seal, so the tick legitimately compacted the
    /// WAL to the watermark; then retention reclaimed EVERY ref of the partition and the next snapshot holds none.
    /// The restart rebuilds the index from that snapshot — nothing anchors it, base `-1` — and the WAL starts at
    /// 196. Recovery cannot tell reclaimed history from lost refs, so it ACCEPTS the head gap (#1258): survivors
    /// at their stored offsets, the range WARNed and counted. The WARN is the cost of #1278 being open — a
    /// persisted reclaimed-through floor is what makes the floor durable, so recovery seeds at the reclaimed point
    /// and this WARN stops. When #1278 lands, the WARN/counter assertions here go red: drop them and keep the
    /// stored-offset assertions.
    @Test
    void restartAfterRetentionReclaimedEveryRef_acceptedAsReclaimedHistory_warnedUntil1278() {
        var index = new SegmentIndex();
        var sealedThrough = publishSealAndTruncate(index, DurableSealedOffsetSource.same(index::lastSealedOffset));

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        // Retention reclaimed every ref: the snapshot a restart reads has no `streams/orders/0/*` entries at all.
        var rebuilt = new SegmentIndex();

        rebuilt.rebuildFromRefs(Map.of("streams/other/0/0-9", blockId(9)));
        assertThat(rebuilt.lastSealedOffset(STREAM, PARTITION)).as("#1278: nothing anchors the rebuild").isEqualTo(-1L);

        var headGapsBefore = StreamPartitionManager.walRecoveryHeadGapsAccepted();
        var warnings = new CopyOnWriteArrayList<String>();
        var capture = capturingWarnings(warnings);
        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), rebuilt::lastSealedOffset);

        try {
            createStream(recovered).onFailure(cause -> fail("#1278 shape must be accepted as reclaimed history: " + cause.message()));
            assertSurvivorsAtStoredOffsets(recovered, sealedThrough + 1);
            assertThat(StreamPartitionManager.walRecoveryHeadGapsAccepted() - headGapsBefore).as("#1278: until a persisted reclaimed-through floor seeds recovery at the reclaimed point, "
                                                                                                    + "the head gap is counted and WARNed on every restart — drop this assertion when #1278 lands")
                                                                                                .isEqualTo(1);
            assertThat(warnings).singleElement().asString().contains("treated as reclaimed history");
        } finally {
            capture.run();
            recovered.close();
        }
    }

    /// Control for the tripwire: the snapshot DID cover the seals (durable == live), the tick compacted the WAL
    /// to the watermark, and the restart rebuilds the same watermark. The survivors start exactly at
    /// `base + 1`, so recovery accepts them at their original offsets — the check must not fire here.
    @Test
    void restartAfterCompaction_refsSnapshotted_recoversTailAtOriginalOffsets() {
        var index = new SegmentIndex();
        var sealedThrough = publishSealAndTruncate(index, DurableSealedOffsetSource.same(index::lastSealedOffset));

        assertThat(sealedThrough).as("pre-crash sealed watermark").isGreaterThanOrEqualTo(SEALED_AT_LEAST);

        var headGapsBefore = StreamPartitionManager.walRecoveryHeadGapsAccepted();
        var recovered = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), index::lastSealedOffset);

        createStream(recovered).onFailure(cause -> fail("recovery refused: " + cause.message()));
        assertSurvivorsAtStoredOffsets(recovered, sealedThrough + 1);
        recovered.close();

        assertThat(StreamPartitionManager.walRecoveryHeadGapsAccepted() - headGapsBefore).as("no head gap: the tail starts at base + 1").isZero();
    }

    /// rev1349 F3: reclamation is coupled to the snapshot, so a snapshot that stops advancing must be VISIBLE
    /// from the tick. Live watermark 195, durable stuck at -1 (a snapshot that cannot be written or read): tick 1
    /// only records the bound, tick 2 counts (grace, no WARN), tick 3 counts and WARNs naming the partition and
    /// its WAL bytes; once the snapshot catches up (durable = live) the next tick resets the counter to 0.
    @Test
    void truncateWalsToSealed_snapshotNotAdvancing_countsHeldBackTicks_andWarnsAfterGrace() {
        var index = new SegmentIndex();
        var stuck = new AtomicBoolean(true);
        var manager = streamPartitionManager(Long.MAX_VALUE,
                                             segmentSealer(segment -> indexed(index, segment)),
                                             Option.some(walDir),
                                             index::lastSealedOffset,
                                             () -> stuck.get() ? LastSealedOffsetSource.none() : index::lastSealedOffset);
        var warnings = new CopyOnWriteArrayList<String>();
        var capture = capturingWarnings(warnings);

        try {
            createStream(manager).onFailure(cause -> fail(cause.message()));
            IntStream.range(0, EVENTS).forEach(i -> publish(manager, i));
            awaitSealed(index);
            assertThat(index.lastSealedOffset(STREAM, PARTITION)).isGreaterThanOrEqualTo(SEALED_AT_LEAST);

            manager.truncateWalsToSealed();
            assertThat(manager.walReclamationHeldBackTicks()).as("tick 1 only records the bound").isZero();
            manager.truncateWalsToSealed();
            assertThat(manager.walReclamationHeldBackTicks()).as("tick 2: held back, inside the grace").isEqualTo(1L);
            assertThat(warnings).as("no WARN inside the grace").isEmpty();
            manager.truncateWalsToSealed();
            assertThat(manager.walReclamationHeldBackTicks()).isEqualTo(2L);
            assertThat(warnings).as("first WARN at the second consecutive held-back tick").hasSize(1);
            assertThat(warnings.getFirst()).contains("WAL reclamation held back for 2 consecutive tick(s) on 1 partition(s)")
                                           .contains(STREAM + "/" + PARTITION + " durable=-1 live=" + index.lastSealedOffset(STREAM, PARTITION))
                                           .contains("walBytes=" + walSizeBytes(manager));

            stuck.set(false);
            manager.truncateWalsToSealed();
            assertThat(manager.walReclamationHeldBackTicks()).as("snapshot caught up: counter resets").isZero();
        } finally {
            capture.run();
            manager.close();
        }
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

    private static long walSizeBytes(StreamPartitionManager manager) {
        return manager.walSnapshot()
                      .streams()
                      .getFirst()
                      .partitions()
                      .getFirst()
                      .wal()
                      .map(PartitionWal.WalStats::sizeBytes)
                      .or(-1L);
    }

    /// Capture WARN lines of the manager's logger; the returned runnable detaches the appender.
    private static Runnable capturingWarnings(List<String> sink) {
        var context = (LoggerContext) LogManager.getContext(false);
        var config = context.getConfiguration();
        var loggerConfig = config.getLoggerConfig(StreamPartitionManager.class.getName());
        var appender = new AbstractAppender("held-back-capture", null, PatternLayout.createDefaultLayout(), true, Property.EMPTY_ARRAY) {
            @Override
            public void append(LogEvent event) {
                if (event.getLevel() == Level.WARN) {
                    sink.add(event.getMessage().getFormattedMessage());
                }
            }
        };

        appender.start();
        loggerConfig.addAppender(appender, Level.WARN, null);
        context.updateLoggers();

        return () -> {
            loggerConfig.removeAppender(appender.getName());
            context.updateLoggers();
            appender.stop();
        };
    }

    private static Promise<Unit> recordSeal(List<SealedSegment> sealed, SealedSegment segment) {
        sealed.add(segment);

        return Promise.unitPromise();
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

    /// Head 199, tail `firstSurvivor`, every readable event's offset equal to the index in its payload, and
    /// offset 0 absent (`CursorExpired`) — never event 196's payload.
    private static void assertSurvivorsAtStoredOffsets(StreamPartitionManager recovered, long firstSurvivor) {
        var info = recovered.partitionInfo(STREAM, PARTITION).onFailure(cause -> fail(cause.message())).unwrap();
        var tail = recovered.readLocal(STREAM, PARTITION, firstSurvivor, EVENTS)
                            .onFailure(cause -> fail(cause.message()))
                            .unwrap();

        assertThat(info.headOffset()).as("head=%d tail=%d count=%d", info.headOffset(), info.tailOffset(), info.eventCount())
                                     .isEqualTo(EVENTS - 1L);
        assertThat(info.tailOffset()).isEqualTo(firstSurvivor);
        assertThat(tail).hasSize((int) (EVENTS - firstSurvivor));
        tail.forEach(StreamPartitionManagerRestartAfterCompactionTest::assertOffsetMatchesPayload);
        recovered.readLocal(STREAM, PARTITION, 0, 1)
                 .onSuccess(events -> fail("offset 0 must be absent, but read " + (events.isEmpty() ? "[]" : "payloadIndex=" + payloadIndex(events.getFirst().data()))))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.CursorExpired.class));
    }

    /// Lowest stored offset in the partition WAL file, read back through a fresh [PartitionWal] (`-1` when empty).
    private long firstStoredOffset() {
        var wal = PartitionWal.open(walDir.resolve(STREAM).resolve(PARTITION + ".wal"))
                              .onFailure(cause -> fail(cause.message()))
                              .unwrap();
        var first = new AtomicLong(-1L);

        wal.replay(-1L, record -> first.compareAndSet(-1L, record.offset())).onFailure(cause -> fail(cause.message()));
        wal.close();

        return first.get();
    }

    private static void assertMismatch(StreamError.WalReplayMismatch mismatch, long expected, long found) {
        assertThat(mismatch.streamName()).isEqualTo(STREAM);
        assertThat(mismatch.partition()).isEqualTo(PARTITION);
        assertThat(mismatch.expectedOffset()).isEqualTo(expected);
        assertThat(mismatch.foundOffset()).isEqualTo(found);
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
