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
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
            assertThat(stats.failStopped()).as("a healthy WAL reports not fail-stopped").isFalse();
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

    /// #634-7: fsync-failure injection. The channel is swapped (reflection, same package) for a
    /// delegate whose `force` throws — the one I/O primitive the durability claim rests on. The
    /// pins: no ack over a failed fsync, no retry of a failed force, fail-stop until reopen.
    @Nested
    class FsyncFailure {

        @Test
        void append_resolvesFailure_whenGroupCommitFsyncThrows() {
            var wal = open("fsync-fail.wal");
            var wrapper = injectForceFailingChannel(wal);

            wal.append(0L, payload(0), 1L)
               .await()
               .onSuccess(_ -> fail("append must not ack over a failed fsync"))
               .onFailure(cause -> assertThat(cause.message()).contains("injected fsync failure"));

            assertThat(wal.stats().fsyncCount()).as("a failed force is not a completed group commit").isZero();
            restoreChannel(wal, wrapper);
            wal.close();
        }

        // fsyncgate: after one failed force the OS may drop the covered dirty pages while clearing
        // the error, so a RETRIED force can falsely report durability. The channel is deliberately
        // RESTORED — a retry WOULD succeed here — and the append must still be refused, without
        // writing a byte or forcing anything.
        @Test
        void append_isRefused_afterFsyncFailure_evenWhenRetryWouldSucceed() {
            var wal = open("fail-stop.wal");

            appendSync(wal, 0L, payload(0), 1L);

            var wrapper = injectForceFailingChannel(wal);

            wal.append(1L, payload(1), 1L).await().onSuccess(_ -> fail("fsync was injected to fail"));
            restoreChannel(wal, wrapper);

            var sizeBefore = fileSize("fail-stop.wal");

            wal.append(2L, payload(2), 1L)
               .await()
               .onSuccess(_ -> fail("fail-stopped WAL must refuse appends"))
               .onFailure(cause -> assertThat(cause.message()).contains("fail-stopped"));

            assertThat(fileSize("fail-stop.wal")).as("a refused append writes nothing — on DISK, not per the accounting")
                                                 .isEqualTo(sizeBefore);
            assertThat(wal.stats().fsyncCount()).as("only the pre-failure commit completed").isEqualTo(1L);
            assertThat(wal.stats().failStopped()).as("the state is operator-visible").isTrue();
            wal.close();
        }

        // The fail-stop holds across the covered group, and specifically via the guard INSIDE
        // forceUpTo — the interleaving is forced: the first append parks INSIDE force (holding
        // syncLock, fail-stop not yet recorded), so the second append's entry check deterministically
        // passes; it writes and queues on syncLock. Releasing the gate makes the first force throw
        // and fail-stop; the second then acquires syncLock and can only have been refused by the
        // in-lock guard. Both fail, `force` is attempted exactly ONCE — never retried.
        @Test
        void pipelinedAppends_underFsyncFailure_bothFail_withSingleForceAttempt() {
            var wal = open("single-force.wal");
            var wrapper = injectGatedForceFailingChannel(wal);

            var first = wal.append(0L, payload(0), 1L);

            await(wrapper.forceEntered);
            var second = wal.append(1L, payload(1), 1L);

            wrapper.forceProceed.countDown();

            first.await().onSuccess(_ -> fail("first append must not ack"));
            second.await()
                  .onSuccess(_ -> fail("second append must not ack"))
                  .onFailure(cause -> assertThat(cause.message()).as("refused by the in-lock fail-stop guard")
                                                                 .contains("fail-stopped"));
            assertThat(wrapper.forceCalls.get()).as("a failed force is never retried").isEqualTo(1);
            restoreChannel(wal, wrapper);
            wal.close();
        }

        // A fail-stopped WAL still serves reads (a read is not a durability claim), and reopening
        // the file — the operator recovery action — recovers acked records and accepts appends.
        // close() on a fail-stopped WAL SKIPS the close-time force (that would be the forbidden
        // retry) and must still close the channel. Offset 1's FAILED append reappears on reopen
        // and that is CORRECT: its bytes were written (only the fsync failed), it was never acked,
        // and unacked-but-recovered records are at-least-once territory the layers above tolerate.
        @Test
        void reopen_afterFailStop_recoversAckedRecords_andAcceptsAppends() {
            var wal = open("fail-stop-reopen.wal");

            appendSync(wal, 0L, payload(0), 1L);

            var wrapper = injectForceFailingChannel(wal);

            wal.append(1L, payload(1), 1L).await().onSuccess(_ -> fail("fsync was injected to fail"));

            assertThat(offsetsOf(replayAll(wal, -1L))).as("reads still serve").containsExactly(0L, 1L);
            wal.close();
            assertThat(wrapper.forceCalls.get()).as("fail-stopped close skips the close-time force — the forbidden retry")
                                                .isEqualTo(1);
            assertThat(wrapper.delegate.isOpen()).as("the channel is still closed, not leaked").isFalse();

            var reopened = open("fail-stop-reopen.wal");

            assertThat(offsetsOf(replayAll(reopened, -1L))).as("acked record 0 survives; unacked 1 legitimately resurrects")
                                                           .containsExactly(0L, 1L);
            assertThat(reopened.stats().failStopped()).as("fail-stop is per-instance, cleared by reopen").isFalse();
            appendSync(reopened, 5L, payload(5), 1L);
            assertThat(reopened.lastOffset()).isEqualTo(5L);
            reopened.close();
        }

        // Close-time fsync failure on a HEALTHY (not fail-stopped) WAL: the force fails and the
        // channel must STILL be closed — the old force→flatMap→close chain skipped close() on a
        // force failure and leaked the channel.
        @Test
        void close_onHealthyWal_stillClosesChannel_whenCloseTimeFsyncFails() {
            var wal = open("close-leak.wal");

            appendSync(wal, 0L, payload(0), 1L);

            var wrapper = injectForceFailingChannel(wal);

            wal.close();

            assertThat(wrapper.forceCalls.get()).as("a healthy close still attempts the final fsync").isEqualTo(1);
            assertThat(wrapper.delegate.isOpen()).as("a failed close-time fsync must not leak the channel").isFalse();
        }

        // MAJOR review catch: without this gate, a threshold-crossing truncate would compact and
        // republish `syncedSeq = writtenSeq` — un-freezing the fail-stop and letting an in-flight
        // append ack over bytes the failed fsync may have dropped. Truncate must refuse instead.
        @Test
        void truncate_isRefused_afterFailStop_soCompactionCannotUnfreezeIt() {
            var wal = open("truncate-poisoned.wal");

            appendSync(wal, 0L, payload(0), 1L);

            var wrapper = injectForceFailingChannel(wal);

            wal.append(1L, payload(1), 1L).await().onSuccess(_ -> fail("fsync was injected to fail"));
            restoreChannel(wal, wrapper);

            wal.truncate(0L)
               .onSuccess(_ -> fail("a fail-stopped WAL must refuse truncate"))
               .onFailure(cause -> assertThat(cause.message()).contains("fail-stopped"));
            wal.close();
        }

        // Compaction I/O failure is LOUD and leaves the live file intact — and does NOT fail-stop
        // the append path: a reclamation failure is not a durability failure. The temp path is made
        // unopenable (a directory sits on it), failing the compaction before any rename.
        @Test
        void truncate_failsLoudly_liveFileIntact_appendsUnaffected_whenTempUnwritable() throws IOException {
            var big = new byte[64 * 1024];
            var wal = open("compact-fail.wal");

            IntStream.range(0, 200).forEach(i -> appendSync(wal, i, big, 1L));
            Files.createDirectory(file("compact-fail.wal.compact"));

            var sizeBefore = Files.size(file("compact-fail.wal"));

            wal.truncate(99L)
               .onSuccess(_ -> fail("compaction with an unwritable temp must fail"))
               .onFailure(cause -> assertThat(cause.message()).contains("WAL truncate failed"));

            assertThat(Files.size(file("compact-fail.wal"))).as("live file untouched").isEqualTo(sizeBefore);
            assertThat(offsetsOf(replayAll(wal, -1L))).as("watermark still filters replay").hasSize(100).first().isEqualTo(100L);
            appendSync(wal, 200L, payload(200), 1L);
            wal.close();
        }
    }

    /// #634-7: crash-mid-compaction. A live JVM cannot be killed inside `truncate`, so each test
    /// CONSTRUCTS the exact post-crash disk state of one window (the artifacts a SIGKILL would
    /// leave) and drives recovery over it — the same technique as the torn-write Recovery group.
    @Nested
    class CrashMidCompaction {

        // Window 1 — crash AFTER the temp is fully written+synced, BEFORE the rename. The temp
        // here is a complete VALID WAL (a decoy with different offsets): recovery must read only
        // the live file, no matter how plausible the temp looks.
        @Test
        void open_readsOnlyLiveFile_ignoringCompleteValidTemp() throws IOException {
            var wal = open("window1.wal");

            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 1L));
            wal.close();

            var decoy = open("decoy.wal");

            IntStream.range(50, 53).forEach(i -> appendSync(decoy, i, payload(i), 1L));
            decoy.close();
            Files.copy(file("decoy.wal"), file("window1.wal.compact"));

            var reopened = open("window1.wal");

            assertThat(offsetsOf(replayAll(reopened, -1L))).containsExactly(0L, 1L, 2L, 3L, 4L);
            reopened.close();
        }

        // Window 2 — crash MID-temp-write: a torn temp beside the intact live file.
        @Test
        void open_unaffected_byTornTemp() throws IOException {
            var wal = open("window2.wal");

            IntStream.range(0, 5).forEach(i -> appendSync(wal, i, payload(i), 1L));
            wal.close();

            Files.write(file("window2.wal.compact"), new byte[]{0x01, 0x02, 0x03});

            var reopened = open("window2.wal");

            assertThat(offsetsOf(replayAll(reopened, -1L))).containsExactly(0L, 1L, 2L, 3L, 4L);
            assertThat(reopened.lastOffset()).isEqualTo(4L);
            reopened.close();
        }

        // A stale temp from a crashed compaction must not block the NEXT one: it is overwritten
        // (TRUNCATE_EXISTING) and consumed by the rename.
        @Test
        void nextCompaction_overwritesStaleTemp_andCompletes() throws IOException {
            var big = new byte[64 * 1024];
            var wal = open("stale-then-compact.wal");

            IntStream.range(0, 200).forEach(i -> appendSync(wal, i, big, 1L));
            Files.write(file("stale-then-compact.wal.compact"), new byte[]{0x0A, 0x0B});

            wal.truncate(99L).onFailure(c -> fail(c.message()));

            assertThat(Files.notExists(file("stale-then-compact.wal.compact"))).as("temp consumed by the rename").isTrue();
            assertThat(offsetsOf(replayAll(wal, -1L))).hasSize(100).first().isEqualTo(100L);
            wal.close();

            assertThat(offsetsOf(replayAll(open("stale-then-compact.wal"), -1L))).hasSize(100);
        }

        // Window 3 — crash right AFTER the rename: the survivors must already be durable WITHOUT
        // the instance's close(), because the temp was force(true)'d BEFORE the rename. Simulated
        // per the Durability group's precedent: an independent WAL on the same file, first never
        // closed.
        @Test
        void survivors_durableImmediatelyAfterCompaction_withoutClose() {
            var big = new byte[64 * 1024];
            var wal = open("window3.wal");

            IntStream.range(0, 200).forEach(i -> appendSync(wal, i, big, 1L));
            wal.truncate(99L).onFailure(c -> fail(c.message()));

            var independent = open("window3.wal");
            var survivors = offsetsOf(replayAll(independent, -1L));

            assertThat(survivors).hasSize(100).first().isEqualTo(100L);
            assertThat(survivors).last().isEqualTo(199L);
            assertThat(independent.lastOffset()).isEqualTo(199L);
            independent.close();
            wal.close();
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

    private long fileSize(String name) {
        try {
            return Files.size(file(name));
        } catch (IOException e) {
            return fail("file size read failed: " + e);
        }
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

    // === fsync-failure injection (reflection: the channel is the WAL's only I/O seam) ===

    private static ForceFailingChannel injectForceFailingChannel(PartitionWal wal) {
        return injectChannel(wal, false);
    }

    private static ForceFailingChannel injectGatedForceFailingChannel(PartitionWal wal) {
        return injectChannel(wal, true);
    }

    private static ForceFailingChannel injectChannel(PartitionWal wal, boolean gated) {
        try {
            var field = channelField();
            var wrapper = new ForceFailingChannel((FileChannel) field.get(wal), gated);

            field.set(wal, wrapper);
            return wrapper;
        } catch (ReflectiveOperationException e) {
            return fail("channel injection failed: " + e);
        }
    }

    private static void restoreChannel(PartitionWal wal, ForceFailingChannel wrapper) {
        try {
            channelField().set(wal, wrapper.delegate);
        } catch (ReflectiveOperationException e) {
            fail("channel restore failed: " + e);
        }
    }

    private static java.lang.reflect.Field channelField() throws NoSuchFieldException {
        var field = PartitionWal.class.getDeclaredField("channel");

        field.setAccessible(true);
        return field;
    }

    /// Delegates every operation to the real channel except `force`, which counts the attempt and
    /// throws — so writes land normally and ONLY the durability step fails, the exact shape of a
    /// real fsync failure. `forceCalls` pins that a failed force is never retried. A GATED wrapper
    /// additionally parks inside `force` (holding the WAL's syncLock) until released — the handle
    /// tests need to arm a specific interleaving deterministically.
    private static final class ForceFailingChannel extends FileChannel {
        final FileChannel delegate;
        final AtomicInteger forceCalls = new AtomicInteger();
        final CountDownLatch forceEntered = new CountDownLatch(1);
        final CountDownLatch forceProceed;

        ForceFailingChannel(FileChannel delegate, boolean gated) {
            this.delegate = delegate;
            this.forceProceed = new CountDownLatch(gated ? 1 : 0);
        }

        @Override
        public void force(boolean metaData) throws IOException {
            forceCalls.incrementAndGet();
            forceEntered.countDown();
            await(forceProceed);
            throw new IOException("injected fsync failure");
        }

        @Override
        public int read(ByteBuffer dst) throws IOException {
            return delegate.read(dst);
        }

        @Override
        public long read(ByteBuffer[] dsts, int offset, int length) throws IOException {
            return delegate.read(dsts, offset, length);
        }

        @Override
        public int read(ByteBuffer dst, long position) throws IOException {
            return delegate.read(dst, position);
        }

        @Override
        public int write(ByteBuffer src) throws IOException {
            return delegate.write(src);
        }

        @Override
        public long write(ByteBuffer[] srcs, int offset, int length) throws IOException {
            return delegate.write(srcs, offset, length);
        }

        @Override
        public int write(ByteBuffer src, long position) throws IOException {
            return delegate.write(src, position);
        }

        @Override
        public long position() throws IOException {
            return delegate.position();
        }

        @Override
        public FileChannel position(long newPosition) throws IOException {
            return delegate.position(newPosition);
        }

        @Override
        public long size() throws IOException {
            return delegate.size();
        }

        @Override
        public FileChannel truncate(long size) throws IOException {
            return delegate.truncate(size);
        }

        @Override
        public long transferTo(long position, long count, WritableByteChannel target) throws IOException {
            return delegate.transferTo(position, count, target);
        }

        @Override
        public long transferFrom(ReadableByteChannel src, long position, long count) throws IOException {
            return delegate.transferFrom(src, position, count);
        }

        @Override
        public MappedByteBuffer map(MapMode mode, long position, long size) throws IOException {
            return delegate.map(mode, position, size);
        }

        @Override
        public FileLock lock(long position, long size, boolean shared) throws IOException {
            return delegate.lock(position, size, shared);
        }

        @Override
        public FileLock tryLock(long position, long size, boolean shared) throws IOException {
            return delegate.tryLock(position, size, shared);
        }

        @Override
        protected void implCloseChannel() throws IOException {
            delegate.close();
        }
    }
}
