package org.pragmatica.storage;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2201 D-01, CTO ruling on #2615: a fail-stop leaves a mark beside the log, and a later open that finds it reports the recovery at its
/// FIRST successful group commit (not at the open, not at every commit), then deletes the mark. A log with no mark reports nothing.
class AppendLogFailStopMarkTest {
    @TempDir
    Path dir;

    @Test
    void failStop_leavesAMarkBesideTheLog_thatIsNotListedAsALog() throws Exception {
        var wal = AppendLog.open(file()).unwrap();
        wal.append(0, bytes("a"), 0L).await().onFailure(cause -> fail(cause.message()));
        assertThat(Files.exists(AppendLog.failStopMarkOf(file()))).as("control: no mark before a fail-stop").isFalse();

        channelOf(wal).close();
        assertThat(wal.write(1, bytes("b"), 1L).isFailure()).as("control: the I/O failure fail-stops the log").isTrue();
        wal.close();

        assertThat(AppendLog.failStopMarkOf(file())).isEqualTo(dir.resolve("p.failstopped"));
        assertThat(Files.exists(AppendLog.failStopMarkOf(file()))).isTrue();
        assertThat(AppendLog.logNamesUnder(dir).unwrap()).containsExactly("p");
    }

    @Test
    void reopenOverAMark_reportsTheRecoveryOnce_atTheFirstDurableWrite_andDeletesTheMark() throws Exception {
        failStopALog();
        var recoveries = new AtomicInteger();

        var reopened = AppendLog.open(file()).unwrap().onFailStopRecovered(recoveries::incrementAndGet);
        assertThat(recoveries.get()).as("opening is not the recovery").isZero();

        reopened.append(reopened.lastOffset() + 1, bytes("c"), 2L).await().onFailure(cause -> fail(cause.message()));
        assertThat(recoveries.get()).isEqualTo(1);
        assertThat(Files.exists(AppendLog.failStopMarkOf(file()))).as("the recovery deletes the mark").isFalse();

        reopened.append(reopened.lastOffset() + 1, bytes("d"), 3L).await().onFailure(cause -> fail(cause.message()));
        assertThat(recoveries.get()).as("once, not once per commit").isEqualTo(1);
        reopened.close();

        var again = AppendLog.open(file()).unwrap().onFailStopRecovered(recoveries::incrementAndGet);
        again.append(again.lastOffset() + 1, bytes("e"), 4L).await().onFailure(cause -> fail(cause.message()));
        assertThat(recoveries.get()).as("a later open has no mark to close").isEqualTo(1);
        again.close();
    }

    /// The recovery is reported only after a SUCCESSFUL force (v2615 a10/a11): a reopened log whose first force fails fail-stops again,
    /// and reporting the recovery before that force, or on its failure, would close a fail-stop that is still open. The frame write
    /// lands; the force over it fails.
    @Test
    void reopenOverAMark_whoseFirstForceFails_reportsNoRecovery_andKeepsTheMark() throws Exception {
        failStopALog();
        var recoveries = new AtomicInteger();
        var reopened = AppendLog.open(file()).unwrap().onFailStopRecovered(recoveries::incrementAndGet);
        var written = reopened.write(reopened.lastOffset() + 1, bytes("c"), 2L);
        assertThat(written.isSuccess()).as("control: the frame write lands before the channel goes").isTrue();

        channelOf(reopened).close();
        var committed = reopened.commit(written.unwrap()).await();

        assertThat(committed.isFailure()).as("control: the force failed").isTrue();
        assertThat(reopened.failStopped()).as("control: the failed force fail-stopped the log again").isTrue();
        assertThat(recoveries.get()).as("no durable write was accepted: nothing recovered").isZero();
        assertThat(Files.exists(AppendLog.failStopMarkOf(file()))).as("the fail-stop is still open: its mark stays").isTrue();
        reopened.close();
    }

    /// A discarded log takes its mark with it (v2615 item 4): a log created again at the same path never had that fail-stop, so it must
    /// not report its recovery.
    @Test
    void deleteFiles_removesTheMark_soALogCreatedAgainReportsNoRecovery() throws Exception {
        failStopALog();
        var discarded = AppendLog.open(file()).unwrap();
        discarded.close();

        discarded.deleteFiles().onFailure(cause -> fail(cause.message()));

        assertThat(Files.exists(AppendLog.failStopMarkOf(file()))).as("the mark is deleted with the log").isFalse();
        var recoveries = new AtomicInteger();
        var created = AppendLog.open(file()).unwrap().onFailStopRecovered(recoveries::incrementAndGet);
        created.append(0, bytes("a"), 0L).await().onFailure(cause -> fail(cause.message()));
        assertThat(recoveries.get()).isZero();
        created.close();
    }

    @Test
    void openWithoutAMark_neverReportsARecovery() {
        var recoveries = new AtomicInteger();
        var wal = AppendLog.open(file()).unwrap().onFailStopRecovered(recoveries::incrementAndGet);

        wal.append(0, bytes("a"), 0L).await().onFailure(cause -> fail(cause.message()));
        wal.append(1, bytes("b"), 1L).await().onFailure(cause -> fail(cause.message()));

        assertThat(recoveries.get()).isZero();
        wal.close();
    }

    private void failStopALog() throws Exception {
        var wal = AppendLog.open(file()).unwrap();
        wal.append(0, bytes("a"), 0L).await().onFailure(cause -> fail(cause.message()));
        channelOf(wal).close();
        wal.write(1, bytes("b"), 1L);
        wal.close();
        assertThat(Files.exists(AppendLog.failStopMarkOf(file()))).as("control: the fail-stop left its mark").isTrue();
    }

    private static FileChannel channelOf(AppendLog wal) throws Exception {
        Field field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        return (FileChannel) field.get(wal);
    }

    private Path file() {
        return dir.resolve("p.wal");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
