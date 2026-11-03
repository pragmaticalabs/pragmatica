package org.pragmatica.storage;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.FileOps;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// PR-2f (#2201 B-04/B-17, I-05): the `<log>.acked` sidecar. NT-42 (a cut lowers it durably before it cuts), NT-43 (the
/// open-time clamp, bounded by the caller's head, S3), NT-44 (a torn or CRC-bad marker is no marker), S6 (a created log is
/// born with a durable `-1`), and the raise's own rules (unforced, attributed, monotone, bounded).
class AppendLogAckedMarkerTest {
    private static final AppendLog.EpochOrder NUMERIC = (later, earlier) -> Long.parseLong(later.token()) > Long.parseLong(earlier.token());
    /// Every record is a 24-byte header and a 1-byte payload.
    private static final int RECORD_BYTES = 25;

    @TempDir
    Path dir;

    // ---- S6: born with a durable -1 -----------------------------------------------------------------------------------

    @Test
    void open_ofANewLog_writesADurableMinusOneMarker_beforeItReturns() {
        var forced = FileForceRecording.forcedFilesDuring(() -> AppendLog.open(file()).onSuccess(AppendLog::close));

        assertThat(AppendLog.readAckedMarker(file())).isEqualTo(Option.some(new AppendLog.AckedMark(Option.none(), -1L)));
        assertThat(forced).as("the marker's temp file is forced").anySatisfy(f -> assertThat(f.path()).isEqualTo(tempOfMarker()));
        assertThat(forced).as("and the directory after the rename").anySatisfy(f -> assertThat(f.path()).isEqualTo(dir.toAbsolutePath()));
        assertThat(Files.exists(tempOfMarker())).as("no temp file is left behind").isFalse();
    }

    @Test
    void open_ofAnExistingLogWithoutAMarker_hasNoMarker_andNothingToRestore() throws IOException {
        AppendLog.open(file()).onSuccess(AppendLog::close);
        Files.delete(marker());

        var wal = AppendLog.open(file()).unwrap();

        assertThat(wal.ackedMarker()).as("an older build's log").isEqualTo(Option.none());
        assertThat(wal.takeAckedRestore()).isEqualTo(Option.none());
        assertThat(Files.exists(marker())).as("reopening an existing log writes no marker").isFalse();
        wal.close();
    }

    // ---- the raise ----------------------------------------------------------------------------------------------------

    @Test
    void recordAckedMarker_writesTheEpochTheHistoryAttributesTheOffsetTo_unforced_andSurvivesAReopen() {
        var wal = filledInTwoEpochs();
        var forced = FileForceRecording.forcedFilesDuring(() -> wal.recordAckedMarker(4).onFailure(c -> fail(c.message())));

        assertThat(wal.ackedMarker()).isEqualTo(Option.some(mark("3", 4)));
        assertThat(AppendLog.readAckedMarker(file())).as("on the volume").isEqualTo(Option.some(mark("3", 4)));
        assertThat(forced).as("a raise forces nothing").noneSatisfy(f -> assertThat(f.path().getFileName().toString()).startsWith("p."));
        wal.recordAckedMarker(2).onFailure(c -> fail(c.message()));
        assertThat(wal.ackedMarker()).as("monotone: a lower raise is a no-op").isEqualTo(Option.some(mark("3", 4)));
        wal.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.ackedMarker()).isEqualTo(Option.some(mark("3", 4)));
        assertThat(reopened.takeAckedRestore()).as("the open-time marker, once").isEqualTo(Option.some(mark("3", 4)));
        assertThat(reopened.takeAckedRestore()).isEqualTo(Option.none());
        reopened.close();
    }

    @Test
    void recordAckedMarker_atAnOffsetTheHistoryDoesNotReach_hasNoEpoch() {
        var wal = AppendLog.open(file()).unwrap();

        wal.write(0, bytes("a"), 0L).onFailure(c -> fail(c.message()));
        wal.recordAckedMarker(0).onFailure(c -> fail(c.message()));
        assertThat(wal.ackedMarker()).isEqualTo(Option.some(new AppendLog.AckedMark(Option.none(), 0L)));
        assertThat(AppendLog.readAckedMarker(file())).isEqualTo(Option.some(new AppendLog.AckedMark(Option.none(), 0L)));
        wal.close();
    }

    @Test
    void recordAckedMarker_isRefusedAboveTheHead_andAdmittedUpToTheSealedFloor() {
        var wal = filledInTwoEpochs().withSealedFloor(() -> 7L);

        wal.recordAckedMarker(8)
           .onSuccess(_ -> fail("a marker above max(lastOffset 5, floor 7) was written"))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.AckedMarkerAboveHead.class));
        assertThat(wal.ackedMarker()).as("nothing changed").isEqualTo(Option.some(new AppendLog.AckedMark(Option.none(), -1L)));
        wal.recordAckedMarker(7).onFailure(c -> fail(c.message()));
        assertThat(wal.ackedMarker().map(AppendLog.AckedMark::offset)).isEqualTo(Option.some(7L));
        wal.close();
    }

    @Test
    void recordAckedMarker_atOrBelowTheBaseline_isANoOp() throws IOException {
        AppendLog.open(file()).onSuccess(AppendLog::close);
        Files.delete(marker());

        var wal = AppendLog.open(file()).unwrap();

        wal.write(0, bytes("a"), 0L).onFailure(c -> fail(c.message()));
        wal.write(1, bytes("b"), 1L).onFailure(c -> fail(c.message()));
        wal.ackedMarkerBaseline(1);
        wal.recordAckedMarker(1).onFailure(c -> fail(c.message()));
        assertThat(Files.exists(marker())).as("the seed of a log with no marker is never written as one").isFalse();
        wal.close();
    }

    @Test
    void recordAckedMarker_onAClosedLog_isRefused() {
        var wal = filledInTwoEpochs();

        wal.close();
        wal.recordAckedMarker(4).onSuccess(_ -> fail("a closed log wrote a marker"));
        assertThat(AppendLog.readAckedMarker(file())).isEqualTo(Option.some(new AppendLog.AckedMark(Option.none(), -1L)));
    }

    // ---- NT-42 / MT-18: a cut lowers the marker durably BEFORE it cuts --------------------------------------------------

    @Test
    void truncateSuffix_lowersTheMarkerDurably_beforeItCuts_soACrashBetweenLeavesItAtOrBelowTheCut() {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(5).onFailure(c -> fail(c.message()));
        wal.cutWindowProbe(() -> Causes.cause("crash between the marker lowering and the cut").result());
        wal.truncateSuffix(1).onSuccess(_ -> fail("control: the probe interrupts the cut"));
        assertThat(AppendLog.readAckedMarker(file())).as("lowered on the volume before anything was cut")
                                                     .isEqualTo(Option.some(mark("1", 1)));
        wal.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.lastOffset()).as("control: the crash left the long file").isEqualTo(5L);
        assertThat(reopened.ackedMarker()).as("a marker at the cut over a longer file").isEqualTo(Option.some(mark("1", 1)));
        reopened.close();
    }

    @Test
    void truncateSuffix_forcesTheMarkerAndItsDirectory_beforeTheShortenedLog() {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(5).onFailure(c -> fail(c.message()));

        var forced = FileForceRecording.forcedFilesDuring(() -> wal.truncateSuffix(1).onFailure(c -> fail(c.message())));
        var paths = forced.stream().map(FileForceRecording.ForcedFile::path).toList();

        assertThat(paths).as("the marker temp, the directory, then the log").containsSubsequence(tempOfMarker(),
                                                                                                dir.toAbsolutePath(),
                                                                                                file().toAbsolutePath());
        assertThat(wal.ackedMarker()).isEqualTo(Option.some(mark("1", 1)));
        wal.close();
    }

    @Test
    void truncateSuffix_thatCannotLowerTheMarker_refusesTheCut() {
        var failing = new AtomicBoolean(false);
        var wal = filledInTwoEpochs(failingWhile(failing));

        wal.recordAckedMarker(5).onFailure(c -> fail(c.message()));
        failing.set(true);
        wal.truncateSuffix(1)
           .onSuccess(_ -> fail("a cut ran over a marker it could not lower"))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.AckedMarkerWriteFailed.class));
        assertThat(wal.lastOffset()).as("nothing was cut").isEqualTo(5L);
        assertThat(AppendLog.readAckedMarker(file()).map(AppendLog.AckedMark::offset)).isEqualTo(Option.some(5L));
        wal.close();
    }

    @Test
    void truncateSuffix_belowAMarkerAlreadyAtOrUnderTheCut_writesNoMarker() {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(1).onFailure(c -> fail(c.message()));

        var forced = FileForceRecording.forcedFilesDuring(() -> wal.truncateSuffix(3).onFailure(c -> fail(c.message())));

        assertThat(forced).noneSatisfy(f -> assertThat(f.path()).isEqualTo(tempOfMarker()));
        assertThat(wal.ackedMarker()).isEqualTo(Option.some(mark("1", 1)));
        wal.close();
    }

    // ---- NT-43 / MT-19: the clamp at open, bounded by the caller's head --------------------------------------------------

    @Test
    void clampAckedMarker_lowersAMarkerAboveTheReplayedHead_andRewritesItDurably() throws IOException {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(5).onFailure(c -> fail(c.message()));
        wal.close();
        shortenFileTo(3);

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.lastOffset()).as("control: the torn tail took 3..5").isEqualTo(2L);
        assertThat(reopened.ackedMarker().map(AppendLog.AckedMark::offset)).as("control: read as written").isEqualTo(Option.some(5L));
        reopened.clampAckedMarker(-1).onFailure(c -> fail(c.message()));
        assertThat(reopened.ackedMarker()).isEqualTo(Option.some(mark("1", 2)));
        assertThat(AppendLog.readAckedMarker(file())).as("rewritten on the volume").isEqualTo(Option.some(mark("1", 2)));
        assertThat(reopened.takeAckedRestore()).as("the restore sees the clamped marker").isEqualTo(Option.some(mark("1", 2)));
        reopened.close();
    }

    @Test
    void clampAckedMarker_overALogThatReopensEmpty_keepsTheMarkerAtTheKnownHead() throws IOException {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(5).onFailure(c -> fail(c.message()));
        wal.close();
        shortenFileTo(0);

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.lastOffset()).as("control: the log holds no record, its data is in sealed blocks").isEqualTo(-1L);
        reopened.clampAckedMarker(7).onFailure(c -> fail(c.message()));
        assertThat(reopened.ackedMarker().map(AppendLog.AckedMark::offset)).as("S3: never clamped to -1 under a known head")
                                                                           .isEqualTo(Option.some(5L));
        reopened.clampAckedMarker(4).onFailure(c -> fail(c.message()));
        assertThat(reopened.ackedMarker().map(AppendLog.AckedMark::offset)).isEqualTo(Option.some(4L));
        reopened.close();
    }

    // ---- NT-44: torn or damaged is no marker ----------------------------------------------------------------------------

    @Test
    void aTornOrDamagedMarker_isNoMarker_andTheLogStillOpens() throws IOException {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(4).onFailure(c -> fail(c.message()));
        wal.close();

        var written = Files.readString(marker());

        for (var damaged : List.of(written.substring(0, written.length() - 1),
                                   written.substring(0, written.length() / 2),
                                   written.replace(" 4 ", " 5 "),
                                   written.replace("v1", "v9"),
                                   "")) {
            Files.writeString(marker(), damaged);

            var reopened = AppendLog.open(file()).unwrap();

            assertThat(reopened.ackedMarker()).as("damaged: '%s'", damaged).isEqualTo(Option.none());
            assertThat(reopened.takeAckedRestore()).isEqualTo(Option.none());
            assertThat(AppendLog.readAckedMarker(file())).isEqualTo(Option.none());
            reopened.close();
        }
    }

    // ---- deleteFiles ----------------------------------------------------------------------------------------------------

    @Test
    void deleteFiles_removesTheMarkerAndAStaleTemp() throws IOException {
        var wal = filledInTwoEpochs();

        wal.recordAckedMarker(4).onFailure(c -> fail(c.message()));
        Files.writeString(tempOfMarker(), "stale");
        wal.close();
        wal.deleteFiles().onFailure(c -> fail(c.message()));
        assertThat(Files.exists(marker())).isFalse();
        assertThat(Files.exists(tempOfMarker())).isFalse();
    }

    // ---- fixture --------------------------------------------------------------------------------------------------------

    /// Offsets 0..2 under epoch `1`, 3..5 under epoch `3`.
    private AppendLog filledInTwoEpochs() {
        return filledInTwoEpochs(FileOps::writeBytesForced);
    }

    private AppendLog filledInTwoEpochs(Fn2<Result<Unit>, Path, byte[]> markerWriter) {
        var wal = AppendLog.open(file(), AppendLog.TornTailSink.logOnly(), FileOps::writeBytesForced, markerWriter).unwrap();

        for (var offset = 0; offset < 6; offset++) {
            wal.write(offset, bytes("x"), offset, key(offset < 3 ? "1" : "3"), NUMERIC).onFailure(c -> fail(c.message()));
        }

        return wal;
    }

    /// A durable marker writer that fails while `failing` is set (so the log can be created first).
    private static Fn2<Result<Unit>, Path, byte[]> failingWhile(AtomicBoolean failing) {
        return (path, content) -> failing.get()
                                  ? Causes.cause("injected write failure").result()
                                  : FileOps.writeBytesForced(path, content);
    }

    private void shortenFileTo(int records) throws IOException {
        try (var channel = FileChannel.open(file(), StandardOpenOption.WRITE)) {
            channel.truncate((long) records * RECORD_BYTES);
        }
    }

    private Path file() {
        return dir.resolve("p.wal");
    }

    private Path marker() {
        return dir.resolve("p.acked");
    }

    private Path tempOfMarker() {
        return dir.resolve("p.acked.tmp").toAbsolutePath();
    }

    private static AppendLog.AckedMark mark(String epoch, long offset) {
        return new AppendLog.AckedMark(Option.some(key(epoch)), offset);
    }

    private static AppendLog.EpochKey key(String token) {
        return AppendLog.EpochKey.epochKey(token).unwrap();
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }
}
