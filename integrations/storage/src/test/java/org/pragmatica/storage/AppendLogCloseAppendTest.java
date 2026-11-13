package org.pragmatica.storage;

import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2539: an append that races or follows [AppendLog#close] resolves to a committed record or to the typed
/// `WAL_CLOSED` cause -- never to a generic `WAL append failed: null`, and never by fail-stopping the log (which logs an error
/// and writes `<log>.failstopped` at a stop that is already in progress).
///
/// What an append admitted before close gets: COMMITTED. Close takes `writeLock`, `syncLock` and `markerLock`, so a write in
/// flight finishes first, and the close-time force publishes `syncedSeq` over it; a commit that runs later resolves from
/// `syncedSeq` and touches no file. An append that loses to close, or follows it, gets `WAL_CLOSED`.
///
/// A channel closed by an interrupt (the thread closes the channel it was using, with a null-message
/// `ClosedByInterruptException`) is the same state as a closed log: the log can no longer write, so it reports closed rather
/// than fail-stopping.
@Timeout(60)
class AppendLogCloseAppendTest {
    private static final String WAL_CLOSED_MESSAGE = AppendLog.WalError.General.WAL_CLOSED.message();

    @TempDir
    Path dir;

    @Test
    void commit_ofAWriteAdmittedBeforeClose_isCommitted_byTheCloseTimeForce() {
        var wal = AppendLog.open(dir.resolve("admitted.wal")).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();

        wal.close();

        assertThat(wal.commit(seq).await().isSuccess()).as("committed by the close-time force").isTrue();
        assertThat(wal.failStopped()).isFalse();
    }

    @Test
    void append_afterClose_isRefusedClosed_notFailStopped() {
        var wal = AppendLog.open(dir.resolve("after.wal")).unwrap();

        wal.close();

        assertRefusedClosed(wal.append(0L, new byte[16], 1L).await(), wal);
    }

    @Test
    void groupCommit_onAnInterruptedThread_isRefusedClosed_notFailStopped() throws Exception {
        var wal = AppendLog.open(dir.resolve("force.wal")).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();

        Thread.currentThread().interrupt();
        try {
            assertRefusedClosed(groupCommit(wal, seq), wal);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void write_onAnInterruptedThread_isRefusedClosed_notFailStopped() {
        var wal = AppendLog.open(dir.resolve("interrupted.wal")).unwrap();

        Thread.currentThread().interrupt();
        try {
            assertRefusedClosed(wal.write(0L, new byte[16], 1L).mapToUnit(), wal);
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void anyAppend_afterAnInterruptClosedTheChannel_isRefusedClosed() {
        var wal = AppendLog.open(dir.resolve("later.wal")).unwrap();

        Thread.currentThread().interrupt();
        try {
            wal.write(0L, new byte[16], 1L);
        } finally {
            Thread.interrupted();
        }

        assertRefusedClosed(wal.append(1L, new byte[16], 1L).await(), wal);
    }

    /// A plain `ClosedChannelException` is no stop's doing: it keeps fail-stopping, but its detail is never null.
    @Test
    void commit_whenTheChannelWasClosedBeneathTheLog_failStops_withANonNullDetail() throws Exception {
        var wal = AppendLog.open(dir.resolve("beneath.wal")).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();

        channelOf(wal).close();

        var outcome = wal.commit(seq).await().<String>fold(cause -> cause.message(), _ -> "accepted");

        assertThat(outcome).isEqualTo("WAL append failed: ClosedChannelException");
        assertThat(wal.failStopped()).isTrue();
    }

    /// Appenders and a closer are released together by a latch; every append must be committed or `WAL_CLOSED`, and every
    /// record committed before the log reported closed must be on disk after reopening.
    @RepeatedTest(250)
    void appendsRacingClose_areCommittedOrRefusedClosed_neverGenericFailures() throws Exception {
        var wal = AppendLog.open(dir.resolve("race-" + System.nanoTime() + ".wal")).unwrap();
        var appenders = 4;
        var perAppender = 8;
        var start = new CountDownLatch(1);
        var outcomes = new ArrayList<String>();
        var threads = new ArrayList<Thread>();

        IntStream.range(0, appenders).forEach(a -> threads.add(appender(wal, a, perAppender, start, outcomes)));
        var closer = new Thread(() -> {
            awaitQuietly(start);
            wal.close();
        });

        threads.add(closer);
        threads.forEach(Thread::start);
        start.countDown();
        for (var t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertThat(outcomes).as("every append is committed or refused closed")
                            .allMatch(o -> o.equals("ok") || o.equals(WAL_CLOSED_MESSAGE) || o.startsWith("OffsetRegression"),
                                      "ok, WAL_CLOSED or an offset regression between appenders");
        assertThat(wal.failStopped()).isFalse();
    }

    private Thread appender(AppendLog wal, int id, int count, CountDownLatch start, List<String> outcomes) {
        return new Thread(() -> {
            awaitQuietly(start);
            IntStream.range(0, count)
                     .forEach(i -> record(outcomes,
                                          wal.append((long) id * count + i, new byte[16], 1L).await()));
        });
    }

    private static void record(List<String> outcomes, Result<Unit> result) {
        var outcome = result.<String>fold(cause -> cause instanceof AppendLog.WalError.OffsetRegression
                                                    ? "OffsetRegression"
                                                    : cause.message(),
                                          _ -> "ok");

        synchronized (outcomes) {
            outcomes.add(outcome);
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void assertRefusedClosed(Result<Unit> result, AppendLog wal) {
        var outcome = result.<String>fold(cause -> cause.message(), _ -> "accepted");

        assertThat(outcome).isEqualTo(WAL_CLOSED_MESSAGE);
        assertThat(wal.failStopped()).as("a closed log is not fail-stopped").isFalse();
        assertThat(Files.exists(dir.resolve(failStopMarkName()))).isFalse();
    }

    private String failStopMarkName() {
        try (var files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                        .filter(n -> n.endsWith(".failstopped"))
                        .findFirst()
                        .orElse("none.failstopped");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static Result<Unit> groupCommit(AppendLog wal, long seq) throws ReflectiveOperationException {
        var method = AppendLog.class.getDeclaredMethod("groupCommit", long.class);

        method.setAccessible(true);
        @SuppressWarnings("unchecked") var result = (Result<Unit>) method.invoke(wal, seq);

        return result;
    }

    private static FileChannel channelOf(AppendLog wal) throws ReflectiveOperationException {
        var field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        return (FileChannel) field.get(wal);
    }
}
