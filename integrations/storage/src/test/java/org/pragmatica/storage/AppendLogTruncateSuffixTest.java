package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.storage.AppendLog.EpochStart;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1730 phase 2 (KIP-101): a log whose tail diverged from the owner's is cut back to the last common offset,
/// durably, so a restart cannot bring the divergent records back. Suffix truncation is the mirror of the seal-gated
/// prefix `truncate`: it removes records ABOVE an offset, the records the owner never acknowledged.
class AppendLogTruncateSuffixTest {
    private static final AppendLog.EpochOrder NUMERIC = (later, earlier) -> Long.parseLong(later.token()) > Long.parseLong(earlier.token());

    @TempDir
    Path dir;

    @Test
    void truncateSuffix_removesEveryRecordAboveTheCut_andTheLogAcceptsTheOwnersRecordsAtThoseOffsets() {
        var wal = filled(10);

        wal.truncateSuffix(5).onFailure(c -> fail(c.message()));

        assertThat(replayed(wal)).containsExactly("r0", "r1", "r2", "r3", "r4", "r5");
        assertThat(wal.lastOffset()).isEqualTo(5L);
        assertThat(wal.durableOffset()).as("what remains is on disk").isEqualTo(5L);
        wal.append(6, "owner-6".getBytes(StandardCharsets.UTF_8), 6L).await().onFailure(c -> fail(c.message()));
        wal.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(replayed(reopened)).as("a restart brings back only the kept records and the owner's").containsExactly("r0", "r1", "r2", "r3", "r4", "r5", "owner-6");
        reopened.close();
    }

    /// The log file is forced WITH its metadata after the cut: the shortened length is a metadata change, and an
    /// un-forced truncate could resurrect the divergent tail after a power loss.
    @Test
    void truncateSuffix_forcesTheLogFileWithItsMetadata() {
        var wal = filled(10);
        var forced = FileForceRecording.forcedFilesDuring(() -> wal.truncateSuffix(5).onFailure(c -> fail(c.message())));

        assertThat(forced).anySatisfy(f -> {
            assertThat(f.path().getFileName().toString()).isEqualTo("p.wal");
            assertThat(f.metaData()).isTrue();
        });
        wal.close();
    }

    @Test
    void truncateSuffix_dropsEpochStartsAboveTheCut_durably() {
        var wal = filled(10);

        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(2), 7, NUMERIC).onFailure(c -> fail(c.message()));
        wal.truncateSuffix(5).onFailure(c -> fail(c.message()));

        assertThat(wal.epochHistory()).containsExactly(new EpochStart(key(1), 0));
        wal.close();
        assertThat(AppendLog.readEpochHistory(file()).unwrap()).containsExactly(new EpochStart(key(1), 0));
    }

    @Test
    void truncateSuffix_atOrAboveTheHead_changesNothing() {
        var wal = filled(4);

        wal.truncateSuffix(3).onFailure(c -> fail(c.message()));
        wal.truncateSuffix(99).onFailure(c -> fail(c.message()));

        assertThat(replayed(wal)).containsExactly("r0", "r1", "r2", "r3");
        assertThat(wal.lastOffset()).isEqualTo(3L);
        wal.close();
    }

    @Test
    void truncateSuffix_belowTheFirstRecord_emptiesTheLog() {
        var wal = filled(4);

        wal.truncateSuffix(-1).onFailure(c -> fail(c.message()));

        assertThat(replayed(wal)).isEmpty();
        assertThat(wal.lastOffset()).isEqualTo(-1L);
        wal.append(0, "again".getBytes(StandardCharsets.UTF_8), 1L).await().onFailure(c -> fail(c.message()));
        assertThat(replayed(wal)).containsExactly("again");
        wal.close();
    }

    /// The prefix discard watermark would hide a re-appended offset from replay, so a cut below it is refused.
    @Test
    void truncateSuffix_belowTheDiscardedPrefix_isRefused() {
        var wal = filled(10);

        wal.markSealed(7);
        wal.truncate(7).onFailure(c -> fail(c.message()));

        wal.truncateSuffix(5).onSuccess(_ -> fail("a cut below the discarded prefix must be refused"))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.TruncateFailed.class));
        assertThat(wal.lastOffset()).isEqualTo(9L);
        wal.close();
    }

    @Test
    void truncateSuffix_onAClosedLog_isRefused() {
        var wal = filled(3);

        wal.close();

        wal.truncateSuffix(1).onSuccess(_ -> fail("a closed log must refuse"));
    }

    /// The crash window between the shortened file and the epoch history: the records are gone, the epoch entry that
    /// began above the cut is still there. Reopening leaves that ghost, and the head-bounded trim the partition runs at
    /// open removes it, so the pair converges.
    @Test
    void crashBetweenTheCutAndTheEpochHistory_leavesAGhostThatTheHeadTrimRemoves() {
        var first = filled(10);

        first.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        first.recordEpochStart(key(2), 7, NUMERIC).onFailure(c -> fail(c.message()));
        first.close();

        Fn2<Result<Unit>, Path, byte[]> failing = (_, _) -> Causes.cause("injected: crash before the epoch history is rewritten").result();
        var crashing = AppendLog.open(file(), AppendLog.TornTailSink.logOnly(), failing).unwrap();

        crashing.truncateSuffix(5).onSuccess(_ -> fail("the history write was injected to fail"));
        crashing.close();

        var reopened = AppendLog.open(file()).unwrap();

        assertThat(replayed(reopened)).as("the cut itself was durable").containsExactly("r0", "r1", "r2", "r3", "r4", "r5");
        assertThat(reopened.epochHistory()).as("the ghost survives the crash").containsExactly(new EpochStart(key(1), 0), new EpochStart(key(2), 7));
        reopened.truncateEpochsAboveHead(reopened.lastOffset()).onFailure(c -> fail(c.message()));
        assertThat(reopened.epochHistory()).containsExactly(new EpochStart(key(1), 0));
        reopened.close();
    }

    private AppendLog filled(int count) {
        var wal = AppendLog.open(file()).unwrap();

        for (var i = 0; i < count; i++) {
            wal.append(i, ("r" + i).getBytes(StandardCharsets.UTF_8), i).await().onFailure(c -> fail(c.message()));
        }

        return wal;
    }

    private static List<String> replayed(AppendLog wal) {
        var out = new ArrayList<String>();

        wal.replay(-1, record -> out.add(new String(record.payload(), StandardCharsets.UTF_8))).onFailure(c -> fail(c.message()));

        return out;
    }

    private static AppendLog.EpochKey key(long epoch) {
        return AppendLog.EpochKey.epochKey(Long.toString(epoch)).unwrap();
    }

    private Path file() {
        return dir.resolve("p.wal");
    }
}
