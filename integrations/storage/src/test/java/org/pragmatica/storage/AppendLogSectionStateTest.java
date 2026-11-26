package org.pragmatica.storage;

import java.lang.reflect.Field;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.utils.Causes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #2201 A-01: the O(1) state the ordered section's guard reads from the log -- the cached last epoch key and the log's health. D-01: the
/// fail-stop listener an owner installs replaces the instance sink's report, and the transition is reported once.
class AppendLogSectionStateTest {
    private static final AppendLog.EpochOrder NUMERIC = (later, earlier) -> Long.parseLong(later.token()) > Long.parseLong(earlier.token());

    @TempDir
    Path dir;

    @Test
    void lastEpochKey_followsEveryChangeOfTheHistory_andSurvivesAReopen() {
        var wal = AppendLog.open(file()).unwrap();

        assertThat(wal.lastEpochKey()).as("an empty history has no last key").isEqualTo(Option.none());
        wal.write(0, bytes("a"), 0L, key("1"), NUMERIC).onFailure(cause -> fail(cause.message()));
        wal.write(1, bytes("b"), 1L, key("1"), NUMERIC).onFailure(cause -> fail(cause.message()));
        assertThat(wal.lastEpochKey()).isEqualTo(Option.some(key("1")));
        wal.write(2, bytes("c"), 2L, key("3"), NUMERIC).onFailure(cause -> fail(cause.message()));
        assertThat(wal.lastEpochKey()).as("a new epoch replaces the cached key").isEqualTo(Option.some(key("3")));
        wal.truncateSuffix(1).onFailure(cause -> fail(cause.message()));
        assertThat(wal.lastEpochKey()).as("a cut that drops the last entry lowers the cached key").isEqualTo(Option.some(key("1")));
        wal.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(reopened.lastEpochKey()).as("loaded with the history").isEqualTo(Option.some(key("1")));
        reopened.close();
    }

    @Test
    void writable_isRefusedOnceFailStopped_andOnceClosed() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        assertThat(wal.writable().isSuccess()).isTrue();
        assertThat(wal.failStopped()).isFalse();
        channelOf(wal).close();
        assertThat(wal.write(0, bytes("a"), 0L).isFailure()).as("control: the I/O failure fail-stops the log").isTrue();
        assertThat(wal.failStopped()).isTrue();
        wal.writable()
           .onSuccess(_ -> fail("a fail-stopped log reads as writable"))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.FailStopped.class));
        wal.close();

        var closed = AppendLog.open(dir.resolve("q.wal")).unwrap();

        closed.close();
        closed.writable()
              .onSuccess(_ -> fail("a closed log reads as writable"))
              .onFailure(cause -> assertThat(cause).isEqualTo(AppendLog.WalError.General.WAL_CLOSED));
        assertThat(closed.failStopped()).as("closed is not fail-stopped").isFalse();
    }

    @Test
    void onFailStop_replacesTheSinkReport_andIsInvokedOnceForTheTransition() throws Exception {
        var sink = new RecordingSink();
        var heard = new CopyOnWriteArrayList<Cause>();
        var wal = AppendLog.open(file(), sink).unwrap().onFailStop(heard::add);

        channelOf(wal).close();
        assertThat(wal.write(0, bytes("a"), 0L).isFailure()).as("control: the I/O failure fail-stops the log").isTrue();
        assertThat(wal.write(0, bytes("a"), 0L).isFailure()).as("control: a later write is refused").isTrue();
        secondFailure(wal);
        assertThat(heard).as("the listener hears the transition once").hasSize(1);
        assertThat(sink.failStops).as("the instance sink is not told when an owner listens").isEmpty();
        wal.close();
    }

    @Test
    void withoutAListener_theSinkReportsTheFailStop_once() throws Exception {
        var sink = new RecordingSink();
        var wal = AppendLog.open(file(), sink).unwrap();

        channelOf(wal).close();
        assertThat(wal.write(0, bytes("a"), 0L).isFailure()).as("control: the I/O failure fail-stops the log").isTrue();
        secondFailure(wal);
        assertThat(sink.failStops).hasSize(1);
        wal.close();
    }

    /// A second failure reaching the fail-stop after the first: two writers that both passed the health check before the first failure each
    /// fail their frame. Driven directly, under the sync lock as the log drives it, because the race itself is not deterministic.
    private static void secondFailure(AppendLog wal) throws Exception {
        var failStop = AppendLog.class.getDeclaredMethod("failStopOnWriteFailure", Cause.class);

        failStop.setAccessible(true);
        failStop.invoke(wal, Causes.cause("second frame write failed"));
    }

    private static final class RecordingSink implements AppendLog.TornTailSink {
        private final List<Cause> failStops = new CopyOnWriteArrayList<>();

        @Contract
        @Override
        public void accept(AppendLog.TornTail tornTail) {}

        @Contract
        @Override
        public void failStopped(Path file, Cause cause) {
            failStops.add(cause);
        }
    }

    private static FileChannel channelOf(AppendLog wal) throws Exception {
        Field field = AppendLog.class.getDeclaredField("channel");

        field.setAccessible(true);
        return (FileChannel) field.get(wal);
    }

    private static AppendLog.EpochKey key(String token) {
        return AppendLog.EpochKey.epochKey(token).unwrap();
    }

    private Path file() {
        return dir.resolve("p.wal");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
