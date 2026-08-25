// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream.wal;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.stream.wal.PartitionWal.WalRecord;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

class PartitionWalTest {

    @TempDir
    Path dir;

    @Nested
    class Roundtrip {

        @Test
        void replay_returnsAllRecords_inAppendOrderWithFields() {
            var wal = open("roundtrip.wal");

            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 1000L + i));
            wal.close();

            var records = replayAll(open("roundtrip.wal"), -1L);

            assertThat(records).hasSize(5);
            IntStream.range(0, 5).forEach(i -> assertRecord(records.get(i), i, 1000L + i));
        }

        @Test
        void replay_skipsOffsetsAtOrBelowAfterOffset() {
            var wal = open("skip.wal");

            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 2000L + i));

            var records = replayAll(wal, 2L);

            assertThat(offsetsOf(records)).containsExactly(3L, 4L);
            wal.close();
        }

        @Test
        void replay_returnsNothing_onFreshFile_andAppendWorksAfterOpen() {
            var wal = open("fresh.wal");

            assertThat(replayAll(wal, -1L)).isEmpty();

            appendSync(wal, 0L, payload(0), 7L);

            assertThat(offsetsOf(replayAll(wal, -1L))).containsExactly(0L);
            wal.close();
        }

        @Test
        void lastOffset_tracksLastAppendedRecord() {
            var wal = open("last.wal");

            assertThat(wal.lastOffset()).isEqualTo(-1L);
            appendSync(wal, 41L, payload(41), 1L);
            appendSync(wal, 99L, payload(99), 1L);

            assertThat(wal.lastOffset()).isEqualTo(99L);
            wal.close();
        }
    }

    @Nested
    class Durability {

        // Proves the append fsync, not mere buffering: a SECOND WAL opened on the same file while
        // the first is still open (and never closed) must see every acked record.
        @Test
        void appendedRecords_surviveReopen_withoutClosingFirstWal() {
            var first = open("durable.wal");

            IntStream.range(0, 8).forEach(i -> appendSync(first, i, payload(i), 5000L + i));

            var second = open("durable.wal");
            var records = replayAll(second, -1L);

            assertThat(offsetsOf(records)).containsExactly(0L, 1L, 2L, 3L, 4L, 5L, 6L, 7L);
            first.close();
            second.close();
        }
    }

    @Nested
    class Recovery {

        @Test
        void tornTrailingBytes_droppedOnReopen_validRecordsKept() throws IOException {
            var wal = open("torn.wal");
            IntStream.range(0, 3).forEach(i -> appendSync(wal, i, payload(i), 1L));
            wal.close();

            Files.write(file("torn.wal"), new byte[]{(byte) 0xAB, (byte) 0xCD, (byte) 0xEF, 0x12, 0x34, 0x56},
                        StandardOpenOption.APPEND);

            var reopened = open("torn.wal");
            assertThat(offsetsOf(replayAll(reopened, -1L))).containsExactly(0L, 1L, 2L);
            assertThat(reopened.lastOffset()).isEqualTo(2L);

            appendSync(reopened, 3L, payload(3), 1L);
            assertThat(offsetsOf(replayAll(reopened, -1L))).containsExactly(0L, 1L, 2L, 3L);
            reopened.close();
        }

        @Test
        void recordTruncatedMidPayload_droppedOnReopen_earlierKept() throws IOException {
            var wal = open("midcut.wal");
            IntStream.range(0, 3).forEach(i -> appendSync(wal, i, payload(i), 1L));
            wal.close();

            setFileLength(file("midcut.wal"), Files.size(file("midcut.wal")) - 5);

            var reopened = open("midcut.wal");
            assertThat(offsetsOf(replayAll(reopened, -1L))).containsExactly(0L, 1L);
            reopened.close();
        }

        @Test
        void flippedPayloadByte_stopsReplayAtCorruptedRecord_earlierIntact() throws IOException {
            var wal = open("crc.wal");
            IntStream.range(0, 3).forEach(i -> appendSync(wal, i, fixedPayload(i), 1L));
            wal.close();

            var recordSize = WAL_HEADER_BYTES + fixedPayload(0).length;
            flipByte(file("crc.wal"), (long) recordSize + WAL_HEADER_BYTES); // record 1 payload start

            var records = replayAll(open("crc.wal"), -1L);

            assertThat(offsetsOf(records)).containsExactly(0L);
        }
    }

    @Nested
    class Truncate {

        @Test
        void truncate_dropsAtOrBelow_keepsAbove_lazyBelowThreshold() throws IOException {
            var wal = open("truncate.wal");
            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 1L));

            var sizeBefore = Files.size(file("truncate.wal"));
            wal.truncate(2L).onFailure(c -> fail(c.message()));

            assertThat(offsetsOf(replayAll(wal, -1L))).containsExactly(3L, 4L);
            assertThat(Files.size(file("truncate.wal"))).isEqualTo(sizeBefore); // lazy: no rewrite
            wal.close();
        }

        // Crosses COMPACTION_THRESHOLD_BYTES (8 MiB) with large payloads so truncate physically
        // rewrites: surviving records replay correctly and the file shrinks on disk.
        @Test
        void truncate_compactsAndShrinks_pastThreshold() throws IOException {
            var big = new byte[64 * 1024];
            var wal = open("compact.wal");
            IntStream.range(0, 200).forEach(i -> appendSync(wal, i, big, 1L));

            var sizeBefore = Files.size(file("compact.wal"));
            wal.truncate(99L).onFailure(c -> fail(c.message()));

            var survivors = offsetsOf(replayAll(wal, -1L));
            assertThat(survivors).hasSize(100).first().isEqualTo(100L);
            assertThat(survivors).last().isEqualTo(199L);
            assertThat(Files.size(file("compact.wal"))).isLessThan(sizeBefore);
            wal.close();

            assertThat(offsetsOf(replayAll(open("compact.wal"), -1L))).hasSize(100);
        }
    }

    /// #634-3: the observability surface. Every value here is read from a counter the append/truncate
    /// path already maintained — the point of the assertions is that the snapshot reports what the file
    /// and the watermark ACTUALLY are, since an accounting-only counter that drifts from disk is exactly
    /// the lying sensor an operator would then act on.
    @Nested
    class Stats {

        @Test
        void stats_reportsSizeAndOffsets_afterAppends() throws IOException {
            var wal = open("stats-size.wal");

            assertThat(wal.stats().sizeBytes()).as("a fresh WAL holds no bytes").isZero();
            assertThat(wal.stats().lastOffset()).isEqualTo(-1L);

            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 1L));

            var stats = wal.stats();

            assertThat(stats.sizeBytes()).as("sizeBytes is the write position — with no compaction yet that IS the file length")
                                         .isEqualTo(Files.size(file("stats-size.wal")));
            assertThat(stats.sizeBytes()).isPositive();
            assertThat(stats.lastOffset()).isEqualTo(4L);
            assertThat(stats.truncatedUpto()).isEqualTo(-1L);
            assertThat(stats.lastCompactedUpto()).isEqualTo(-1L);
            wal.close();
        }

        // Each append is AWAITED before the next fires, so no two can share a group commit and the
        // count is exact rather than merely non-zero.
        @Test
        void stats_countsFsyncs_andAccumulatesLatency() {
            var wal = open("stats-fsync.wal");

            assertThat(wal.stats().fsyncCount()).as("nothing has been forced yet").isZero();
            assertThat(wal.stats().fsyncTotalNanos()).isZero();

            IntStream.range(0, 4).forEach(i -> appendSync(wal, i, payload(i), 1L));

            var stats = wal.stats();

            assertThat(stats.fsyncCount()).isEqualTo(4L);
            assertThat(stats.fsyncTotalNanos()).as("a real force() spans at least one nanoTime tick")
                                               .isPositive();
            assertThat(stats.fsyncMaxNanos() * stats.fsyncCount())
                .as("max must dominate the mean the reader derives as total/count")
                .isGreaterThanOrEqualTo(stats.fsyncTotalNanos());
            wal.close();
        }

        @Test
        void stats_reflectsTruncationWatermark() throws IOException {
            var wal = open("stats-truncate.wal");

            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 1L));

            var sizeBefore = wal.stats().sizeBytes();

            assertThat(wal.stats().truncatedUpto()).as("nothing truncated yet — else the bump below proves nothing")
                                                   .isEqualTo(-1L);
            wal.truncate(2L).onFailure(c -> fail(c.message()));

            var stats = wal.stats();

            assertThat(stats.truncatedUpto()).isEqualTo(2L);
            assertThat(stats.sizeBytes()).as("below the compaction threshold a truncate is a watermark bump; bytes are NOT"
                                             + " reclaimed, and reporting a shrunken size would hide real disk usage")
                                         .isEqualTo(sizeBefore);
            assertThat(Files.size(file("stats-truncate.wal"))).isEqualTo(sizeBefore);
            assertThat(stats.lastCompactedUpto()).as("no physical compaction ran").isEqualTo(-1L);
            wal.close();
        }
    }

    @Nested
    class Concurrency {

        @Test
        void concurrentAppends_allDurableAndReplayable() throws InterruptedException {
            var wal = open("concurrent.wal");
            var threads = 4;
            var perThread = 25;
            var promises = new CopyOnWriteArrayList<Promise<Unit>>();
            var pool = Executors.newFixedThreadPool(threads);
            var start = new CountDownLatch(1);
            var done = new CountDownLatch(threads);

            IntStream.range(0, threads).forEach(t -> pool.submit(() -> fireBatch(wal, t * perThread, perThread, promises, start, done)));
            start.countDown();
            done.await(30, TimeUnit.SECONDS);
            promises.forEach(p -> p.await().onFailure(c -> fail(c.message())));
            pool.shutdownNow();
            wal.close();

            var records = replayAll(open("concurrent.wal"), -1L);
            assertThat(offsetsOf(records)).hasSize(threads * perThread)
                                          .containsAll(IntStream.range(0, threads * perThread).mapToObj(Long::valueOf).toList());
            records.forEach(r -> assertThat(new String(r.payload(), UTF_8)).isEqualTo("v" + r.offset()));
        }
    }

    // === helpers ===

    private static final int WAL_HEADER_BYTES = 24;

    private PartitionWal open(String name) {
        return PartitionWal.open(file(name)).unwrap();
    }

    private Path file(String name) {
        return dir.resolve(name);
    }

    private static void appendSync(PartitionWal wal, long offset, byte[] payload, long ts) {
        wal.append(offset, payload, ts).await().onFailure(c -> fail(c.message()));
    }

    private static void fireBatch(PartitionWal wal,
                                  int base,
                                  int count,
                                  List<Promise<Unit>> sink,
                                  CountDownLatch start,
                                  CountDownLatch done) {
        await(start);
        IntStream.range(0, count).forEach(i -> sink.add(wal.append(base + i, ("v" + (base + i)).getBytes(UTF_8), 1L)));
        done.countDown();
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting on latch");
        }
    }

    private static List<WalRecord> replayAll(PartitionWal wal, long afterOffset) {
        var records = new ArrayList<WalRecord>();
        wal.replay(afterOffset, records::add).onFailure(c -> fail(c.message()));
        return records;
    }

    private static List<Long> offsetsOf(List<WalRecord> records) {
        return records.stream().map(WalRecord::offset).collect(Collectors.toList());
    }

    private static void assertRecord(WalRecord record, long offset, long ts) {
        assertThat(record.offset()).isEqualTo(offset);
        assertThat(record.timestampMillis()).isEqualTo(ts);
        assertThat(record.payload()).isEqualTo(payload((int) offset));
    }

    private static byte[] payload(int i) {
        return ("payload-" + i).getBytes(UTF_8);
    }

    private static byte[] fixedPayload(int i) {
        return "payload-%02d".formatted(i).getBytes(UTF_8); // constant length for CRC byte targeting
    }

    private static void setFileLength(Path path, long length) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.truncate(length);
        }
    }

    private static void flipByte(Path path, long position) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE)) {
            var buf = ByteBuffer.allocate(1);
            channel.read(buf, position);
            buf.array()[0] ^= (byte) 0xFF;
            buf.flip();
            channel.write(buf, position);
        }
    }
}
