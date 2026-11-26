package org.pragmatica.storage;

import org.apache.logging.log4j.Level;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.testlogging.LogCapture;

import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/// #2539: an append that races or follows [AppendLog#close] resolves to a committed record or to the typed `WAL_CLOSED` cause
/// -- never to a generic `WAL append failed: null`, and never by fail-stopping the log (which logs an error and writes
/// `<log>.failstopped` at a stop that is already in progress).
///
/// What an append admitted before close gets: COMMITTED. Close takes `writeLock`, `syncLock` and `markerLock`, so a write in
/// flight finishes first, and the close-time force publishes `syncedSeq` over it; a commit that runs later resolves from
/// `syncedSeq` and touches no file. An append that loses to close, or follows it, gets `WAL_CLOSED`.
///
/// A closed channel (`ClosedChannelException`, `ClosedByInterruptException`, `AsynchronousCloseException`) means a stop only
/// while [AppendLog#close] is in progress -- its intent flag is set before it takes any lock. With no close begun (a stray
/// interrupt, a channel closed beneath the log) it is a fault: the log fail-stops, writes the mark and logs an ERROR whose
/// detail is the exception class name, never null. "Close in progress" is staged for real: the test holds `writeLock`, starts a
/// closer and waits until that closer is BLOCKED on it, which it can only be after setting the flag.
@Timeout(60)
class AppendLogCloseAppendTest {
    private static final String WAL_CLOSED_MESSAGE = AppendLog.WalError.General.WAL_CLOSED.message();
    private static final String INTERRUPT_FAULT = "WAL append failed: ClosedByInterruptException";
    private static final String PLAIN_CLOSED_FAULT = "WAL append failed: ClosedChannelException";

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
        var path = dir.resolve("after.wal");
        var wal = AppendLog.open(path).unwrap();

        wal.close();

        assertRefusedClosed(wal.append(0L, new byte[16], 1L).await(), wal, path);
    }

    /// The close-time force runs with the closer's interrupt cleared, so it does not close the channel beneath itself and the
    /// admitted append is committed; the interrupt is put back afterwards.
    @Test
    void close_onAnInterruptedThread_commitsTheAdmittedWrite_andRestoresTheInterrupt() {
        var wal = AppendLog.open(dir.resolve("interrupted-closer.wal")).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();
        boolean stillInterrupted;

        Thread.currentThread().interrupt();
        try {
            wal.close();
        } finally {
            stillInterrupted = Thread.interrupted();
        }

        assertThat(stillInterrupted).as("the closer's interrupt is restored").isTrue();
        assertThat(wal.commit(seq).await().<String>fold(Cause::message, _ -> "committed")).isEqualTo("committed");
        assertThat(wal.failStopped()).isFalse();
    }

    /// A stray interrupt with no close begun: the dead WAL raises the alarm -- fail-stop, mark, listener and an ERROR with a
    /// typed detail.
    @Test
    void write_onAStrayInterrupt_withNoClose_failStops_withMarkListenerAndTypedError() {
        var path = dir.resolve("stray-write.wal");
        var reported = new AtomicReference<Cause>();
        var wal = AppendLog.open(path).unwrap().onFailStop(reported::set);
        Result<Long> write;

        try (var capture = LogCapture.capture(AppendLog.class, Level.ALL, true)) {
            Thread.currentThread().interrupt();
            try {
                write = wal.write(0L, new byte[16], 1L);
            } finally {
                Thread.interrupted();
            }

            assertThat(capture.messages(Level.ERROR)).as("an ERROR naming the typed detail")
                                                     .anyMatch(m -> m.contains("ClosedByInterruptException"))
                                                     .noneMatch(m -> m.contains("null"));
        }

        assertThat(write.<String>fold(Cause::message, _ -> "accepted")).isEqualTo(INTERRUPT_FAULT);
        assertFailStopped(wal, path);
        assertThat(reported.get()).isEqualTo(new AppendLog.WalError.AppendFailed("ClosedByInterruptException"));
        assertThat(wal.append(1L, new byte[16], 1L).await().<String>fold(Cause::message, _ -> "accepted"))
            .as("later appends are refused as fail-stopped, not closed")
            .isNotEqualTo(WAL_CLOSED_MESSAGE)
            .isNotEqualTo("accepted");
    }

    @Test
    void groupCommit_onAStrayInterrupt_withNoClose_failStops_withATypedDetail() throws Exception {
        var path = dir.resolve("stray-force.wal");
        var wal = AppendLog.open(path).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();
        Result<Unit> commit;

        Thread.currentThread().interrupt();
        try {
            commit = groupCommit(wal, seq);
        } finally {
            Thread.interrupted();
        }

        assertThat(commit.<String>fold(Cause::message, _ -> "accepted")).isEqualTo(INTERRUPT_FAULT);
        assertFailStopped(wal, path);
    }

    /// A writer in flight (holding `writeLock`) is interrupted by the stop while [AppendLog#close] waits for that lock.
    @Test
    void write_interruptedWhileCloseIsInProgress_isRefusedClosed_withoutMarkOrError() throws Exception {
        var path = dir.resolve("stop-write.wal");
        var wal = AppendLog.open(path).unwrap();
        var closer = new Thread(wal::close);
        Result<Long> write;

        try (var capture = LogCapture.capture(AppendLog.class, Level.ALL, true)) {
            synchronized (field(wal, "writeLock")) {
                startAndAwaitBlocked(closer);
                write = writeInterrupted(wal, 0L);
            }
            closer.join(10_000);

            assertThat(capture.messages(Level.ERROR)).as("a stop logs no error").isEmpty();
        }

        assertThat(closer.isAlive()).isFalse();
        assertRefusedClosed(write.mapToUnit(), wal, path);
    }

    @Test
    void groupCommit_interruptedWhileCloseIsInProgress_isRefusedClosed_withoutMark() throws Exception {
        var path = dir.resolve("stop-force.wal");
        var wal = AppendLog.open(path).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();
        var closer = new Thread(wal::close);
        Result<Unit> commit;

        synchronized (field(wal, "writeLock")) {
            startAndAwaitBlocked(closer);
            Thread.currentThread().interrupt();
            try {
                commit = groupCommit(wal, seq);
            } finally {
                Thread.interrupted();
            }
        }
        closer.join(10_000);

        assertRefusedClosed(commit, wal, path);
    }

    /// Lane-1 probe P1 with close in progress: a group commit that passed the `closed` check (it holds `syncLock`) forces a
    /// channel an interrupted writer closed meanwhile -- a PLAIN `ClosedChannelException`, classified by the close intent flag.
    @Test
    void force_onAChannelAnInterruptedWriterClosed_whileCloseIsInProgress_isRefusedClosed() throws Exception {
        var path = dir.resolve("p1-stop.wal");
        var wal = AppendLog.open(path).unwrap();
        wal.write(0L, new byte[16], 1L).unwrap();
        var closer = new Thread(wal::close);
        Result<Unit> force;

        synchronized (field(wal, "writeLock")) {
            startAndAwaitBlocked(closer);
            assertThat(writeInterrupted(wal, 1L).<String>fold(Cause::message, _ -> "accepted")).isEqualTo(WAL_CLOSED_MESSAGE);
            synchronized (field(wal, "syncLock")) {
                force = forceAndPublish(wal);
            }
        }
        closer.join(10_000);

        assertRefusedClosed(force, wal, path);
    }

    /// Lane-1 probe P1 as it was found, with NO close begun: the same plain `ClosedChannelException` is a fault.
    @Test
    void force_onAChannelAStrayInterruptClosed_withNoClose_failStops() throws Exception {
        var path = dir.resolve("p1-stray.wal");
        var wal = AppendLog.open(path).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();
        var writer = new Thread(() -> writeInterrupted(wal, 1L));
        Result<Unit> commit;

        synchronized (field(wal, "syncLock")) {
            writer.start();
            awaitChannelClosed(wal);
            commit = groupCommit(wal, seq);
        }
        writer.join(10_000);

        assertThat(commit.<String>fold(Cause::message, _ -> "accepted")).isEqualTo(PLAIN_CLOSED_FAULT);
        assertFailStopped(wal, path);
    }

    /// Lane-1 probe P2 with close in progress: a group commit is interrupted and closes the channel, then a writer that passed
    /// the in-lock `closed` check (it holds `writeLock`) writes the closed channel -- a plain `ClosedChannelException`.
    @Test
    void write_onAChannelAnInterruptedForceClosed_whileCloseIsInProgress_isRefusedClosed() throws Exception {
        var path = dir.resolve("p2-stop.wal");
        var wal = AppendLog.open(path).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();
        var closer = new Thread(wal::close);
        var forced = new AtomicReference<Result<Unit>>();
        var forcer = new Thread(() -> forced.set(groupCommitInterrupted(wal, seq)));
        Result<Long> write;

        synchronized (field(wal, "writeLock")) {
            startAndAwaitBlocked(closer);
            forcer.start();
            forcer.join(10_000);
            assertThat(channelOf(wal).isOpen()).as("the interrupted force closed the channel").isFalse();
            write = writeNext(wal, 1L);
        }
        closer.join(10_000);

        assertThat(forced.get().<String>fold(Cause::message, _ -> "accepted")).isEqualTo(WAL_CLOSED_MESSAGE);
        assertRefusedClosed(write.mapToUnit(), wal, path);
    }

    /// Lane-2 survivor m2: an asynchronous close that is NOT an interrupt (`AsynchronousCloseException` itself) is classified by
    /// the close intent flag like its interrupt subclass -- closed with close in progress, a typed fault otherwise.
    @Test
    void asynchronousClose_notAnInterrupt_isClosedDuringClose_andATypedFaultOtherwise() throws Exception {
        var idle = AppendLog.open(dir.resolve("async-idle.wal")).unwrap();
        var stopping = AppendLog.open(dir.resolve("async-stop.wal")).unwrap();
        var closer = new Thread(stopping::close);
        Cause duringClose;

        synchronized (field(stopping, "writeLock")) {
            startAndAwaitBlocked(closer);
            duringClose = appendFailure(stopping, new AsynchronousCloseException());
        }
        closer.join(10_000);

        assertThat(duringClose).isSameAs(AppendLog.WalError.General.WAL_CLOSED);
        assertThat(appendFailure(idle, new AsynchronousCloseException()))
            .isEqualTo(new AppendLog.WalError.AppendFailed("AsynchronousCloseException"));
    }

    /// A plain `ClosedChannelException` with no close begun keeps fail-stopping, and its detail is never null.
    @Test
    void commit_whenTheChannelWasClosedBeneathTheLog_failStops_withANonNullDetail() throws Exception {
        var path = dir.resolve("beneath.wal");
        var wal = AppendLog.open(path).unwrap();
        var seq = wal.write(0L, new byte[16], 1L).unwrap();

        channelOf(wal).close();

        var outcome = wal.commit(seq).await().<String>fold(Cause::message, _ -> "accepted");

        assertThat(outcome).isEqualTo(PLAIN_CLOSED_FAULT);
        assertFailStopped(wal, path);
    }

    /// Two logs per iteration. STOP: appenders race a real [AppendLog#close], and an in-flight writer (holding `writeLock`) is
    /// interrupted once the closer is blocked on that lock, i.e. after close began -- every append is committed, `WAL_CLOSED`
    /// or an offset regression between appenders, the interrupted write is `WAL_CLOSED`, and nothing fail-stops. STRAY: appenders
    /// race a writer interrupted with no close at all -- the log fail-stops with the typed detail and its mark, and nothing
    /// reports `WAL_CLOSED`. Before #2539 the stop arm fail-stopped with `WAL append failed: null`.
    @RepeatedTest(250)
    void appendsRacingAStopOrAStrayInterrupt_areClassifiedByTheCloseIntent() throws Exception {
        var stopPath = dir.resolve("stop-" + System.nanoTime() + ".wal");
        var strayPath = dir.resolve("stray-" + System.nanoTime() + ".wal");
        var stop = AppendLog.open(stopPath).unwrap();
        var stray = AppendLog.open(strayPath).unwrap();
        var start = new CountDownLatch(1);
        var stopOutcomes = new ArrayList<String>();
        var strayOutcomes = new ArrayList<String>();
        var stopInterrupted = new AtomicReference<String>();
        var strayInterrupted = new AtomicReference<String>();
        var threads = new ArrayList<Thread>();

        IntStream.range(0, 3).forEach(a -> threads.add(appender(stop, a, 8, start, stopOutcomes)));
        IntStream.range(0, 3).forEach(a -> threads.add(appender(stray, a, 8, start, strayOutcomes)));
        threads.add(new Thread(() -> stopWithAnInFlightInterrupt(stop, start, stopInterrupted)));
        threads.add(new Thread(() -> strayInterrupt(stray, start, strayInterrupted)));
        threads.forEach(Thread::start);
        start.countDown();
        for (var t : threads) {
            t.join(TimeUnit.SECONDS.toMillis(30));
        }

        assertThat(threads).as("every thread finished").noneMatch(Thread::isAlive);
        assertThat(stopInterrupted.get()).as("the write interrupted by the stop").isEqualTo(WAL_CLOSED_MESSAGE);
        assertThat(stopOutcomes).as("stop: committed, refused closed or an offset regression")
                                .allMatch(o -> o.equals("ok") || o.equals(WAL_CLOSED_MESSAGE) || o.equals("OffsetRegression"));
        assertThat(stop.failStopped()).as("a stop never fail-stops").isFalse();
        assertThat(Files.exists(AppendLog.failStopMarkOf(stopPath))).as("a stop writes no mark").isFalse();

        assertThat(strayInterrupted.get()).as("the stray interrupted write").isEqualTo(INTERRUPT_FAULT);
        assertThat(strayOutcomes).as("stray: nothing reports closed, no detail is null")
                                 .noneMatch(o -> o.equals(WAL_CLOSED_MESSAGE) || o.contains("null"));
        assertFailStopped(stray, strayPath);
        stray.close();
    }

    /// The stop: holds `writeLock` as a writer in flight would, starts the closer, and once the closer is blocked on the lock
    /// (close has begun) interrupts itself and writes the next offset (read under the lock, so the order rule admits it).
    private static void stopWithAnInFlightInterrupt(AppendLog wal, CountDownLatch start, AtomicReference<String> outcome) {
        awaitQuietly(start);
        var closer = new Thread(wal::close);

        synchronized (fieldUnchecked(wal, "writeLock")) {
            startAndAwaitBlocked(closer);
            outcome.set(writeInterrupted(wal, nextOffset(wal)).<String>fold(Cause::message, _ -> "accepted"));
        }
        joinQuietly(closer);
    }

    /// A writer in flight with no close at all: interrupted, it writes the next offset under `writeLock`.
    private static void strayInterrupt(AppendLog wal, CountDownLatch start, AtomicReference<String> outcome) {
        awaitQuietly(start);
        synchronized (fieldUnchecked(wal, "writeLock")) {
            outcome.set(writeInterrupted(wal, nextOffset(wal)).<String>fold(Cause::message, _ -> "accepted"));
        }
    }

    private static long nextOffset(AppendLog wal) {
        return wal.lastOffset() + 1;
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

    private static Result<Long> writeInterrupted(AppendLog wal, long offset) {
        Thread.currentThread().interrupt();
        try {
            return wal.write(offset, new byte[16], 1L);
        } finally {
            Thread.interrupted();
        }
    }

    private static Result<Unit> groupCommitInterrupted(AppendLog wal, long seq) {
        Thread.currentThread().interrupt();
        try {
            return groupCommit(wal, seq);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        } finally {
            Thread.interrupted();
        }
    }

    private static void startAndAwaitBlocked(Thread closer) {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        closer.start();
        while (closer.getState() != Thread.State.BLOCKED) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the closer never blocked on writeLock");
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitChannelClosed(AppendLog wal) throws ReflectiveOperationException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (channelOf(wal).isOpen()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the interrupted writer never closed the channel");
            }
            Thread.onSpinWait();
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinQuietly(Thread thread) {
        try {
            thread.join(TimeUnit.SECONDS.toMillis(10));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void assertRefusedClosed(Result<Unit> result, AppendLog wal, Path path) {
        assertThat(result.<String>fold(Cause::message, _ -> "accepted")).isEqualTo(WAL_CLOSED_MESSAGE);
        assertThat(wal.failStopped()).as("a closed log is not fail-stopped").isFalse();
        assertThat(Files.exists(AppendLog.failStopMarkOf(path))).as("a closed log writes no mark").isFalse();
    }

    private static void assertFailStopped(AppendLog wal, Path path) {
        assertThat(wal.failStopped()).as("a closed channel with no close begun fail-stops").isTrue();
        assertThat(Files.exists(AppendLog.failStopMarkOf(path))).as("the fail-stop mark").isTrue();
    }

    private static Result<Unit> groupCommit(AppendLog wal, long seq) throws ReflectiveOperationException {
        return invoke(wal, "groupCommit", new Class<?>[] {long.class}, seq);
    }

    private static Result<Unit> forceAndPublish(AppendLog wal) throws ReflectiveOperationException {
        return invoke(wal, "forceAndPublish", new Class<?>[] {});
    }

    private static Result<Long> writeNext(AppendLog wal, long offset) throws ReflectiveOperationException {
        return invoke(wal, "writeNext", new Class<?>[] {long.class, byte[].class, long.class}, offset, new byte[16], 1L);
    }

    private static Cause appendFailure(AppendLog wal, Throwable failure) throws ReflectiveOperationException {
        return invoke(wal, "appendFailure", new Class<?>[] {Throwable.class}, failure);
    }

    @SuppressWarnings("unchecked")
    private static <T> T invoke(AppendLog wal, String name, Class<?>[] types, Object... args) throws ReflectiveOperationException {
        var method = AppendLog.class.getDeclaredMethod(name, types);

        method.setAccessible(true);
        return (T) method.invoke(wal, args);
    }

    private static FileChannel channelOf(AppendLog wal) throws ReflectiveOperationException {
        return (FileChannel) field(wal, "channel");
    }

    private static Object field(AppendLog wal, String name) throws ReflectiveOperationException {
        var field = AppendLog.class.getDeclaredField(name);

        field.setAccessible(true);
        return field.get(wal);
    }

    private static Object fieldUnchecked(AppendLog wal, String name) {
        try {
            return field(wal, name);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
