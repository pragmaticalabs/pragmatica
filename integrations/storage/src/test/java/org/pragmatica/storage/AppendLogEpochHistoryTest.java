package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.CRC32;

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

/// #1567 A11: the durable owner-epoch history beside each append log; #1596: opaque keys, caller order.
class AppendLogEpochHistoryTest {
    /// Keys are decimal numbers here, ordered numerically -- the log itself never interprets a token.
    private static final AppendLog.EpochOrder NUMERIC = (later, earlier) -> number(later) > number(earlier);

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
        var wal = AppendLog.open(file(), AppendLog.TornTailSink.logOnly(), observing).unwrap();

        logRef.set(wal);

        var forced = FileForceRecording.forcedFilesDuring(() -> wal.recordEpochStart(key(3), 10, NUMERIC).onFailure(c -> fail(c.message())));
        var forcedNames = forced.stream().map(f -> f.path().getFileName().toString()).toList();

        assertThat(forcedNames).containsSubsequence("p.epochs.tmp", dir.getFileName().toString());
        assertThat(forced).filteredOn(f -> f.path().getFileName().toString().equals("p.epochs.tmp"))
                          .allSatisfy(f -> assertThat(f.metaData()).isTrue());
        assertThat(visibleDuringWrite.get()).as("not visible while the write is in progress").isEmpty();
        assertThat(wal.epochHistory()).containsExactly(start(3, 10));
        wal.close();

        assertThat(AppendLog.readEpochHistory(file()).unwrap()).containsExactly(start(3, 10));
    }

    @Test
    void recordEpochStart_isMonotonic_refusalsLeaveTheHistoryAndTheFileUntouched() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(key(5), 100, NUMERIC).onFailure(c -> fail(c.message()));
        var before = Files.readAllBytes(sidecar());

        assertRefused(wal.recordEpochStart(key(4), 200, NUMERIC), "a lower epoch");
        assertRefused(wal.recordEpochStart(key(5), 150, NUMERIC), "the same epoch at another start");
        assertRefused(wal.recordEpochStart(key(6), 99, NUMERIC), "a start below the last start");
        wal.recordEpochStart(key(5), 100, NUMERIC).onFailure(c -> fail("an exact repeat is a no-op: " + c.message()));

        assertThat(wal.epochHistory()).containsExactly(start(5, 100));
        assertThat(Files.readAllBytes(sidecar())).isEqualTo(before);

        wal.recordEpochStart(key(6), 100, NUMERIC).onFailure(c -> fail(c.message()));
        assertThat(wal.epochHistory()).containsExactly(start(5, 100), start(6, 100));
        wal.close();
    }

    @Test
    void truncateEpochsAbove_dropsLaterStarts_durably() {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(2), 50, NUMERIC).onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(3), 90, NUMERIC).onFailure(c -> fail(c.message()));
        wal.truncateEpochsAbove(60).onFailure(c -> fail(c.message()));
        wal.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.epochHistory()).containsExactly(start(1, 0), start(2, 50));
        reopened.close();
    }

    /// A crash after the temp file was (partly) written and before the rename: the history under the
    /// sidecar's name is the OLD one, intact; the stale temp is ignored, and the next record overwrites it.
    @Test
    void crashBetweenTempAndRename_leavesTheOldHistoryIntact() {
        var tornWrite = (Fn2<Result<Unit>, Path, byte[]>) AppendLogEpochHistoryTest::writeHalfThenFail;
        var first = AppendLog.open(file()).unwrap();

        first.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        first.close();

        var crashing = AppendLog.open(file(), AppendLog.TornTailSink.logOnly(), tornWrite).unwrap();

        crashing.recordEpochStart(key(2), 40, NUMERIC).onSuccess(_ -> fail("the temp write was injected to fail"));
        assertThat(crashing.epochHistory()).containsExactly(start(1, 0));
        crashing.close();

        assertThat(Files.exists(sidecar().resolveSibling("p.epochs.tmp"))).as("the torn temp is left behind").isTrue();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.epochHistory()).containsExactly(start(1, 0));
        reopened.recordEpochStart(key(2), 40, NUMERIC).onFailure(c -> fail(c.message()));
        assertThat(reopened.epochHistory()).containsExactly(start(1, 0), start(2, 40));
        reopened.close();
    }

    /// A sidecar damaged on the device: reading it fails, and neither the read nor a refused open changes
    /// any byte or timestamp of the sidecar or the log (#1569 A3).
    @Test
    void tornSidecar_isRefusedReadOnly_andTheOpenIsRefusedWithoutTouchingTheLog() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.append(0, "a".getBytes(StandardCharsets.UTF_8), 1L).await().onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
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

    /// Two keys neither of which follows the other (the caller's order is partial): the second is refused, so
    /// a history can never interleave epochs its caller cannot order (#1596, the #1625 collision shape).
    @Test
    void recordEpochStart_refusesAKeyTheCallersOrderCannotPlaceAfterTheLast() {
        var wal = AppendLog.open(file()).unwrap();
        AppendLog.EpochOrder sameTermOnly = (later, earlier) -> later.token().compareTo(earlier.token()) > 0
                                                                && later.token().charAt(0) == earlier.token().charAt(0);

        wal.recordEpochStart(key("a1"), 0, sameTermOnly).onFailure(c -> fail(c.message()));
        assertRefused(wal.recordEpochStart(key("b2"), 10, sameTermOnly), "an unordered key");
        wal.recordEpochStart(key("a2"), 10, sameTermOnly).onFailure(c -> fail(c.message()));

        assertThat(wal.epochHistory()).containsExactly(new EpochStart(key("a1"), 0), new EpochStart(key("a2"), 10));
        wal.close();
    }

    /// Format v1 held one number per epoch and no production path wrote it: it reads as an EMPTY history, so a
    /// log carrying it has records without provenance -- flagged by the divergence rule, never trusted (#1596).
    @Test
    void legacyV1Sidecar_readsAsAnEmptyHistory_andTheNextRecordWritesV2() throws Exception {
        var body = EpochHistory.LEGACY_HEADER + "\n3 10\n";
        var crc = new CRC32();

        crc.update(body.getBytes(StandardCharsets.UTF_8));
        Files.writeString(sidecar(), body + "crc " + Long.toHexString(crc.getValue()) + "\n");

        assertThat(AppendLog.readEpochHistory(file()).unwrap()).isEmpty();

        var wal = AppendLog.open(file()).unwrap();

        assertThat(wal.epochHistory()).isEmpty();
        wal.recordEpochStart(key(4), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.close();

        assertThat(Files.readString(sidecar())).startsWith(EpochHistory.HEADER + "\n");
        assertThat(AppendLog.readEpochHistory(file()).unwrap()).containsExactly(start(4, 0));
    }

    /// A key token that is blank or carries a space cannot be framed in the sidecar and is refused up front.
    @Test
    void epochKey_refusesBlankAndSpacedTokens() {
        AppendLog.EpochKey.epochKey("").onSuccess(_ -> fail("a blank token must be refused"));
        AppendLog.EpochKey.epochKey("a b").onSuccess(_ -> fail("a spaced token must be refused"));
        AppendLog.EpochKey.epochKey("7.3.-").onFailure(c -> fail(c.message()));
    }

    /// #1596: an attributed write records the epoch start before the frame, once per epoch; later records of the
    /// same epoch leave the sidecar alone.
    @Test
    void attributedWrite_recordsTheEpochStartOnce_beforeTheFirstFrameOfTheEpoch() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.write(0, "a".getBytes(StandardCharsets.UTF_8), 1L, key(1), NUMERIC).onFailure(c -> fail(c.message()));
        var afterFirst = Files.readAllBytes(sidecar());

        wal.write(1, "b".getBytes(StandardCharsets.UTF_8), 1L, key(1), NUMERIC).onFailure(c -> fail(c.message()));
        assertThat(Files.readAllBytes(sidecar())).as("a same-epoch record does not rewrite the history").isEqualTo(afterFirst);

        wal.write(2, "c".getBytes(StandardCharsets.UTF_8), 1L, key(3), NUMERIC).onFailure(c -> fail(c.message()));
        assertThat(wal.epochHistory()).containsExactly(start(1, 0), start(3, 2));
        wal.close();

        assertThat(AppendLog.readEpochHistory(file()).unwrap()).containsExactly(start(1, 0), start(3, 2));
    }

    /// A key that does not follow the last epoch is refused WITHOUT writing the frame and without fail-stopping:
    /// the next attributed write of the current epoch still lands.
    @Test
    void attributedWrite_olderEpoch_isRefusedUnwritten_andTheLogStaysWritable() {
        var wal = AppendLog.open(file()).unwrap();

        wal.write(0, "a".getBytes(StandardCharsets.UTF_8), 1L, key(5), NUMERIC).onFailure(c -> fail(c.message()));
        assertRefused(wal.write(1, "b".getBytes(StandardCharsets.UTF_8), 1L, key(4), NUMERIC).mapToUnit(), "an older epoch");
        wal.write(1, "c".getBytes(StandardCharsets.UTF_8), 1L, key(5), NUMERIC).onFailure(c -> fail(c.message()));

        var replayed = new ArrayList<String>();

        wal.replay(-1, r -> replayed.add(new String(r.payload(), StandardCharsets.UTF_8))).onFailure(c -> fail(c.message()));
        assertThat(replayed).containsExactly("a", "c");
        wal.close();
    }

    /// A history entry that cannot be made durable fail-stops the log before the frame is written: no record is
    /// ever on disk without the entry that attributes it.
    @Test
    void attributedWrite_historyWriteFails_failStopsTheLogWithoutWritingTheFrame() {
        Fn2<Result<Unit>, Path, byte[]> failing = (_, _) -> Causes.cause("injected sidecar failure").result();
        var wal = AppendLog.open(file(), AppendLog.TornTailSink.logOnly(), failing).unwrap();

        wal.write(0, "a".getBytes(StandardCharsets.UTF_8), 1L, key(1), NUMERIC)
           .onSuccess(_ -> fail("the history write was injected to fail"));
        wal.write(1, "b".getBytes(StandardCharsets.UTF_8), 1L)
           .onSuccess(_ -> fail("the log must be fail-stopped"))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.FailStopped.class));
        wal.close();
        assertThat(AppendLog.inspect(file()).unwrap().headOffset()).as("no frame was written").isEqualTo(-1L);
    }

    /// #1638 S1 (v1638 probe2): a crash between an entry's durable record and the first frame of its epoch leaves the
    /// entry above the head. Trimming at the head drops it, bounded by the larger of the caller's head and the last
    /// written offset, and does nothing when neither is known.
    @Test
    void truncateEpochsAboveHead_dropsEntriesNoRecordReaches() {
        var wal = AppendLog.open(file()).unwrap();

        wal.write(0, "a".getBytes(StandardCharsets.UTF_8), 1L, key(1), NUMERIC).onFailure(c -> fail(c.message()));
        wal.write(1, "b".getBytes(StandardCharsets.UTF_8), 1L).onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(2), 5, NUMERIC).onFailure(c -> fail("the crash left this entry: " + c.message()));
        wal.recordEpochStart(key(3), 9, NUMERIC).onFailure(c -> fail(c.message()));

        wal.truncateEpochsAboveHead(6).onFailure(c -> fail(c.message()));
        assertThat(wal.epochHistory()).as("a caller head above the last write keeps what it reaches")
                                      .containsExactly(start(1, 0), start(2, 5));

        wal.truncateEpochsAboveHead(-1).onFailure(c -> fail(c.message()));
        assertThat(wal.epochHistory()).as("bounded by the last written offset, 1").containsExactly(start(1, 0));
        wal.close();

        assertThat(AppendLog.readEpochHistory(file()).unwrap()).as("durably").containsExactly(start(1, 0));
    }

    @Test
    void truncateEpochsAboveHead_withNoHeadKnown_dropsNothing() {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.truncateEpochsAboveHead(-1).onFailure(c -> fail(c.message()));

        assertThat(wal.epochHistory()).containsExactly(start(1, 0));
        wal.close();
    }

    private static AppendLog.EpochKey key(long epoch) {
        return key(Long.toString(epoch));
    }

    private static AppendLog.EpochKey key(String token) {
        return AppendLog.EpochKey.epochKey(token).unwrap();
    }

    private static EpochStart start(long epoch, long startOffset) {
        return new EpochStart(key(epoch), startOffset);
    }

    private static long number(AppendLog.EpochKey key) {
        return Long.parseLong(key.token());
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
