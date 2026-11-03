package org.pragmatica.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// Streams overhaul I-04 (PR-2b, a commit of PR-2c, `know:` `153503d643`), NT-29: the log validates offsets and never assigns them. A
/// write is accepted at `lastOffset + 1`, at any offset when the log holds no record (since open or since a cut to empty), or across the
/// live sealed floor (`lastOffset < o <= floor + 1`); anything else is refused and writes nothing, and the log stays writable.
class AppendLogOrderRuleTest {
    private static final AppendLog.EpochOrder NUMERIC = (later, earlier) -> Long.parseLong(later.token()) > Long.parseLong(earlier.token());

    @TempDir
    Path dir;

    @Disabled("NT-29, RED until PR-2c commit 4 (the WAL order rule, I-04): a gap above lastOffset + 1 must be refused (#2201)")
    @Test
    void write_refusesAGap_writesNothing_andStaysWritable() {
        var wal = filled(3);

        var refused = wal.write(5, bytes("gap"), 5L);

        refused.onSuccess(seq -> fail("a gap write was accepted, seq " + seq))
               .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.OffsetRegression.class));
        assertThat(wal.lastOffset()).isEqualTo(2L);
        assertThat(wal.stats().failStopped()).as("a refused write leaves the log writable").isFalse();
        wal.write(3, bytes("r3"), 3L).onFailure(cause -> fail(cause.message()));
        assertThat(offsets(wal)).containsExactly(0L, 1L, 2L, 3L);
        wal.close();
    }

    @Disabled("NT-29, RED until PR-2c commit 4: an attributed gap write must be refused before its epoch entry is recorded (#2201)")
    @Test
    void attributedWrite_refusesAGap_andRecordsNoEpochEntry() {
        var wal = filled(3);
        var historyBefore = wal.epochHistory();

        wal.write(7, bytes("gap"), 7L, AppendLog.EpochKey.epochKey("9").unwrap(), NUMERIC)
           .onSuccess(seq -> fail("an attributed gap write was accepted, seq " + seq))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.OffsetRegression.class));
        assertThat(wal.epochHistory()).as("nothing written, not even the epoch entry").isEqualTo(historyBefore);
        assertThat(wal.lastOffset()).isEqualTo(2L);
        wal.close();
    }

    @Test
    void write_refusesARegression_andWritesNothing() {
        var wal = filled(3);

        wal.write(1, bytes("again"), 9L)
           .onSuccess(seq -> fail("a regression was accepted, seq " + seq))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.OffsetRegression.class));
        assertThat(offsets(wal)).containsExactly(0L, 1L, 2L);
        wal.close();
    }

    @Test
    void write_acceptsAnyOffset_asTheFirstWriteAfterOpen() {
        var wal = AppendLog.open(file()).unwrap();

        wal.write(41, bytes("first"), 1L).onFailure(cause -> fail(cause.message()));
        wal.write(42, bytes("next"), 2L).onFailure(cause -> fail(cause.message()));
        assertThat(offsets(wal)).containsExactly(41L, 42L);
        wal.close();
    }

    @Test
    void write_acceptsAnyOffset_asTheFirstWriteAfterACutToEmpty() {
        var wal = filled(3);

        wal.truncateSuffix(-1).onFailure(cause -> fail(cause.message()));
        wal.write(20, bytes("after-cut"), 1L).onFailure(cause -> fail(cause.message()));
        assertThat(offsets(wal)).containsExactly(20L);
        wal.close();
    }

    private AppendLog filled(int count) {
        var wal = AppendLog.open(file()).unwrap();

        for (var i = 0; i < count; i++) {
            wal.write(i, bytes("r" + i), i).onFailure(cause -> fail(cause.message()));
        }
        return wal;
    }

    private static List<Long> offsets(AppendLog wal) {
        var records = new ArrayList<AppendLog.WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        return records.stream().map(AppendLog.WalRecord::offset).toList();
    }

    private Path file() {
        return dir.resolve("p.wal");
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}
