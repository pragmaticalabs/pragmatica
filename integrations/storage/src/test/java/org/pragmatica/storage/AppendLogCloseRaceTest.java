package org.pragmatica.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2859: a mutator that passed its `closed` check and then waited on a lock while [AppendLog#close] completed must be
/// refused, not run against the closed log (a cut or compaction would fail-stop it and write `<log>.failstopped`; a marker
/// raise or clamp would rewrite `<log>.acked`).
///
/// Deterministic, no sleeps-as-sync: the test thread holds the lock the mutator needs, waits (deadline-bounded) until the
/// mutator thread is BLOCKED on it, then calls [AppendLog#close] -- re-entrant on the held lock, so it completes -- and
/// releases. Each test pins one `closed` re-check; the snapshot taken at close must equal the one after the mutator resumes.
@Timeout(60)
class AppendLogCloseRaceTest {
    private static final String WAL_CLOSED_MESSAGE = AppendLog.WalError.General.WAL_CLOSED.message();

    @TempDir
    Path dir;

    @Test
    void truncateSuffix_passingTheEntryCheckThenLosingToClose_isRefusedClosed_withoutFailStop() throws Exception {
        var wal = logWithRecords("suffix.wal", 3, 16);

        var race = closeWhileBlockedOn(wal, "writeLock", () -> wal.truncateSuffix(0L));

        assertRefusedClosedAndNothingMutated(wal, race);
    }

    @Test
    void truncateCompaction_passingTheEntryCheckThenLosingToClose_isRefusedClosed_withoutFailStop() throws Exception {
        var wal = logWithRecords("compact.wal", 200, 64 * 1024);

        wal.markSealed(99L);
        var race = closeWhileBlockedOn(wal, "writeLock", () -> wal.truncate(99L));

        assertRefusedClosedAndNothingMutated(wal, race);
    }

    @Test
    void clampAckedMarker_losingToClose_isRefusedClosed_andRewritesNoMarker() throws Exception {
        var wal = logWithRecords("clamp.wal", 3, 16).withSealedFloor(() -> 10L);

        wal.recordAckedMarker(8L).unwrap();
        var race = closeWhileBlockedOn(wal, "writeLock", () -> wal.clampAckedMarker(0L));

        assertRefusedClosedAndNothingMutated(wal, race);
    }

    @Test
    void recordAckedMarker_passingTheEntryCheckThenLosingToClose_isRefusedClosed_andWritesNoMarker() throws Exception {
        var wal = logWithRecords("raise.wal", 3, 16);

        var race = closeWhileBlockedOn(wal, "markerLock", () -> wal.recordAckedMarker(2L));

        assertRefusedClosedAndNothingMutated(wal, race);
    }

    /// `close()` takes `markerLock` last, so a marker raise in flight finishes before the log reports closed.
    @Test
    void close_blocksWhileAMarkerChangeHoldsMarkerLock_andClosesAfterRelease() throws Exception {
        var wal = logWithRecords("close-marker.wal", 3, 16);
        var closer = new Thread(wal::close);
        Thread.State whileHeld;
        boolean writableWhileHeld;

        synchronized (lockOf(wal, "markerLock")) {
            closer.start();
            whileHeld = awaitSettled(closer);
            writableWhileHeld = wal.writable().isSuccess();
        }

        closer.join();

        assertThat(whileHeld).as("close() waits for the marker change in flight").isEqualTo(Thread.State.BLOCKED);
        assertThat(writableWhileHeld).as("the log is not closed while the marker change is in flight").isTrue();
        assertThat(wal.writable().isFailure()).isTrue();
    }

    private record Race(Result<Unit> result, List<String> atClose) {}

    private void assertRefusedClosedAndNothingMutated(AppendLog wal, Race race) throws IOException {
        var outcome = race.result().<String>fold(cause -> cause.message(), _ -> "accepted");

        assertThat(outcome).isEqualTo(WAL_CLOSED_MESSAGE);
        assertThat(wal.failStopped()).as("a cleanly closed log is not fail-stopped").isFalse();
        assertThat(snapshot()).as("no file changed after close").isEqualTo(race.atClose());
    }

    private Race closeWhileBlockedOn(AppendLog wal, String lockName, Supplier<Result<Unit>> mutator) throws Exception {
        var result = new AtomicReference<Result<Unit>>();
        var thread = new Thread(() -> result.set(mutator.get()));
        List<String> atClose;

        synchronized (lockOf(wal, lockName)) {
            thread.start();
            assertThat(awaitSettled(thread)).as("the mutator waits for %s", lockName).isEqualTo(Thread.State.BLOCKED);
            wal.close();
            atClose = snapshot();
        }

        thread.join();
        return new Race(result.get(), atClose);
    }

    private AppendLog logWithRecords(String name, int count, int payloadBytes) {
        var wal = AppendLog.open(dir.resolve(name)).unwrap();
        var payload = new byte[payloadBytes];

        IntStream.range(0, count).forEach(i -> wal.append(i, payload, 1L).await().unwrap());
        return wal;
    }

    /// Name, length and content hash of every file in the directory.
    private List<String> snapshot() throws IOException {
        try (var files = Files.list(dir)) {
            return files.map(AppendLogCloseRaceTest::describe).sorted().toList();
        }
    }

    private static String describe(Path file) {
        try {
            return file.getFileName() + ":" + Files.size(file) + ":" + Arrays.hashCode(Files.readAllBytes(file));
        } catch (IOException e) {
            return file.getFileName() + ":unreadable";
        }
    }

    private static Object lockOf(AppendLog wal, String name) throws ReflectiveOperationException {
        var field = AppendLog.class.getDeclaredField(name);

        field.setAccessible(true);
        return field.get(wal);
    }

    /// The thread's state once it has parked on a monitor or finished; fails the test past the deadline.
    private static Thread.State awaitSettled(Thread thread) throws InterruptedException {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (thread.getState() != Thread.State.BLOCKED && thread.getState() != Thread.State.TERMINATED) {
            if (System.nanoTime() > deadline) {
                fail("thread neither blocked nor finished: " + thread.getState());
            }
            Thread.sleep(2);
        }
        return thread.getState();
    }
}
