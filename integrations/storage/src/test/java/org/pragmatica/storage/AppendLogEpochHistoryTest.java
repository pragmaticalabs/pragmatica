package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog.EpochStart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1567 A11: the durable owner-epoch history beside each append log.
class AppendLogEpochHistoryTest {
    @TempDir
    Path dir;

    /// The temp file is forced with its metadata, and AFTER it the directory the rename installs the history
    /// into; the history the log reports changes only after the whole sequence -- observed from inside the
    /// write itself, where the new entry must not yet be visible.
    @Test
    void recordEpochStart_forcesTempThenDirectory_beforeTheEntryIsVisible() {
        var logRef = new AtomicReference<AppendLog>();
        var visibleDuringWrite = new AtomicReference<List<EpochStart>>();
        Fn2<Result<Unit>, Path, byte[]> observing = (path, bytes) -> observeThenWrite(logRef, visibleDuringWrite, path, bytes);
        var wal = AppendLog.open(file(), AppendLog.TornTailListener.NONE, observing).unwrap();

        logRef.set(wal);

        var forced = FileForceRecording.forcedFilesDuring(() -> wal.recordEpochStart(3, 10).onFailure(c -> fail(c.message())));
        var forcedNames = forced.stream().map(f -> f.path().getFileName().toString()).toList();

        assertThat(forcedNames).containsSubsequence("p.epochs.tmp", dir.getFileName().toString());
        assertThat(forced).filteredOn(f -> f.path().getFileName().toString().equals("p.epochs.tmp"))
                          .allSatisfy(f -> assertThat(f.metaData()).isTrue());
        assertThat(visibleDuringWrite.get()).as("not visible while the write is in progress").isEmpty();
        assertThat(wal.epochHistory()).containsExactly(new EpochStart(3, 10));
        wal.close();

        assertThat(AppendLog.readEpochHistory(file()).unwrap()).containsExactly(new EpochStart(3, 10));
    }

    @Test
    void recordEpochStart_isMonotonic_refusalsLeaveTheHistoryAndTheFileUntouched() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(5, 100).onFailure(c -> fail(c.message()));
        var before = Files.readAllBytes(sidecar());

        assertRefused(wal.recordEpochStart(4, 200), "a lower epoch");
        assertRefused(wal.recordEpochStart(5, 150), "the same epoch at another start");
        assertRefused(wal.recordEpochStart(6, 99), "a start below the last start");
        wal.recordEpochStart(5, 100).onFailure(c -> fail("an exact repeat is a no-op: " + c.message()));

        assertThat(wal.epochHistory()).containsExactly(new EpochStart(5, 100));
        assertThat(Files.readAllBytes(sidecar())).isEqualTo(before);

        wal.recordEpochStart(6, 100).onFailure(c -> fail(c.message()));
        assertThat(wal.epochHistory()).containsExactly(new EpochStart(5, 100), new EpochStart(6, 100));
        wal.close();
    }

    @Test
    void truncateEpochsAbove_dropsLaterStarts_durably() {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(1, 0).onFailure(c -> fail(c.message()));
        wal.recordEpochStart(2, 50).onFailure(c -> fail(c.message()));
        wal.recordEpochStart(3, 90).onFailure(c -> fail(c.message()));
        wal.truncateEpochsAbove(60).onFailure(c -> fail(c.message()));
        wal.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.epochHistory()).containsExactly(new EpochStart(1, 0), new EpochStart(2, 50));
        reopened.close();
    }

    /// A crash after the temp file was (partly) written and before the rename: the history under the
    /// sidecar's name is the OLD one, intact; the stale temp is ignored, and the next record overwrites it.
    @Test
    void crashBetweenTempAndRename_leavesTheOldHistoryIntact() {
        var tornWrite = (Fn2<Result<Unit>, Path, byte[]>) AppendLogEpochHistoryTest::writeHalfThenFail;
        var first = AppendLog.open(file()).unwrap();

        first.recordEpochStart(1, 0).onFailure(c -> fail(c.message()));
        first.close();

        var crashing = AppendLog.open(file(), AppendLog.TornTailListener.NONE, tornWrite).unwrap();

        crashing.recordEpochStart(2, 40).onSuccess(_ -> fail("the temp write was injected to fail"));
        assertThat(crashing.epochHistory()).containsExactly(new EpochStart(1, 0));
        crashing.close();

        assertThat(Files.exists(sidecar().resolveSibling("p.epochs.tmp"))).as("the torn temp is left behind").isTrue();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.epochHistory()).containsExactly(new EpochStart(1, 0));
        reopened.recordEpochStart(2, 40).onFailure(c -> fail(c.message()));
        assertThat(reopened.epochHistory()).containsExactly(new EpochStart(1, 0), new EpochStart(2, 40));
        reopened.close();
    }

    /// A sidecar damaged on the device: reading it fails, and neither the read nor a refused open changes
    /// any byte or timestamp of the sidecar or the log (#1569 A3).
    @Test
    void tornSidecar_isRefusedReadOnly_andTheOpenIsRefusedWithoutTouchingTheLog() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.append(0, "a".getBytes(StandardCharsets.UTF_8), 1L).await().onFailure(c -> fail(c.message()));
        wal.recordEpochStart(1, 0).onFailure(c -> fail(c.message()));
        wal.close();

        var damaged = Files.readAllBytes(sidecar());

        damaged[damaged.length / 2] ^= 0x5A;
        Files.write(sidecar(), damaged);
        var sidecarTime = Files.getLastModifiedTime(sidecar());
        var logBytes = Files.readAllBytes(file());
        var logTime = Files.getLastModifiedTime(file());

        AppendLog.readEpochHistory(file())
                 .onSuccess(_ -> fail("a damaged sidecar must not read as a history"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.EpochHistoryCorrupt.class));
        AppendLog.open(file())
                 .onSuccess(_ -> fail("a log whose epoch history is damaged must not open"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.EpochHistoryCorrupt.class));

        assertThat(Files.readAllBytes(sidecar())).isEqualTo(damaged);
        assertThat(Files.getLastModifiedTime(sidecar())).isEqualTo(sidecarTime);
        assertThat(Files.readAllBytes(file())).isEqualTo(logBytes);
        assertThat(Files.getLastModifiedTime(file())).isEqualTo(logTime);
    }

    private static Result<Unit> observeThenWrite(AtomicReference<AppendLog> logRef,
                                                 AtomicReference<List<EpochStart>> visible,
                                                 Path path,
                                                 byte[] bytes) {
        visible.set(logRef.get().epochHistory());

        return FileOps.writeBytesForced(path, bytes);
    }

    private static Result<Unit> writeHalfThenFail(Path path, byte[] bytes) {
        return FileOps.writeBytesForced(path, Arrays.copyOf(bytes, bytes.length / 2))
                      .flatMap(_ -> Causes.cause("injected crash before rename").result());
    }

    private static void assertRefused(Result<Unit> result, String what) {
        result.onSuccess(_ -> fail(what + " must be refused"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.EpochRegression.class));
    }

    private Path file() {
        return dir.resolve("p.wal");
    }

    private Path sidecar() {
        return dir.resolve("p.epochs");
    }
}
