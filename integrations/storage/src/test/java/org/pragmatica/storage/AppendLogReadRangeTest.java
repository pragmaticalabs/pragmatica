package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Result;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1759 (I-27, CTO D2/D3/D4): [AppendLog#readRange], the READ-ONLY scan a held-unmaterialized replica serves catch-up from.
/// Five records, offset i holding payload "event-i" (frame = 24-byte header + payload). A page continues from the previous
/// page's end position; a frame that cannot be validated before the page is full fails the whole page; nothing on disk
/// changes.
class AppendLogReadRangeTest {
    private static final int HEADER = 24;
    private static final int RECORDS = 5;

    @TempDir
    Path dir;

    private Path file;
    private long[] ends;

    @BeforeEach
    void writeFiveRecords() {
        file = dir.resolve("p.wal");
        ends = new long[RECORDS];
        var log = AppendLog.open(file).unwrap();
        var position = 0L;

        for (var i = 0; i < RECORDS; i++) {
            log.append(i, payload(i), 1_000L + i).await().onFailure(c -> fail(c.message()));
            position += HEADER + payload(i).length;
            ends[i] = position;
        }
        log.close();
    }

    @Test
    void readRange_pagesTheLogInOffsetOrder_eachPageContinuingFromTheLastOnesEndPosition() throws Exception {
        var first = AppendLog.readRange(file, 0, 2, 0).unwrap();
        var second = AppendLog.readRange(file, 2, 2, first.endPosition()).unwrap();
        var third = AppendLog.readRange(file, 4, 2, second.endPosition()).unwrap();

        assertThat(offsets(first)).containsExactly(0L, 1L);
        assertThat(first.endPosition()).isEqualTo(ends[1]);
        assertThat(offsets(second)).containsExactly(2L, 3L);
        assertThat(second.endPosition()).isEqualTo(ends[3]);
        assertThat(offsets(third)).containsExactly(4L);
        assertThat(third.endPosition()).isEqualTo(Files.size(file));
        assertThat(third.records().getFirst().payload()).isEqualTo(payload(4));
        assertThat(third.records().getFirst().timestampMillis()).isEqualTo(1_004L);
    }

    @Test
    void readRange_fromAnOffset_skipsTheRecordsBelowIt() {
        assertThat(offsets(AppendLog.readRange(file, 3, 10, 0).unwrap())).containsExactly(3L, 4L);
        assertThat(offsets(AppendLog.readRange(file, 9, 10, 0).unwrap())).isEmpty();
    }

    @Test
    void readRange_aCrcMismatchBeforeThePageIsFull_failsTheWholePage_neverAValidPrefix() throws Exception {
        flipByteAt(ends[1] + HEADER + 1);

        var damaged = AppendLog.readRange(file, 0, 10, 0);

        assertDamagedAt(damaged, ends[1]);
        assertThat(offsets(AppendLog.readRange(file, 0, 2, 0).unwrap())).as("a page filled before the damage is served")
                                                                     .containsExactly(0L, 1L);
    }

    @Test
    void readRange_aHeaderCrcMismatch_failsThePage() throws Exception {
        flipByteAt(ends[2] + 5);

        assertDamagedAt(AppendLog.readRange(file, 0, 10, 0), ends[2]);
    }

    @Test
    void readRange_aTornHeaderAtTheTail_failsThePage() throws Exception {
        Files.write(file, new byte[]{0, 0, 0, 40, 1, 2, 3}, StandardOpenOption.APPEND);

        assertDamagedAt(AppendLog.readRange(file, 3, 10, 0), ends[4]);
    }

    @Test
    void readRange_aPayloadCutShortAtTheTail_failsThePage() throws Exception {
        var bytes = Files.readAllBytes(file);

        Files.write(file, Arrays.copyOf(bytes, (int) ends[4] - 2));

        assertDamagedAt(AppendLog.readRange(file, 0, 10, 0), ends[3]);
    }

    /// The length is not covered by the frame CRC: a negative one on an empty-payload frame passes the CRC and must be
    /// refused by the length guard, not allocated.
    @Test
    void readRange_aNegativeLengthOnAnEmptyPayloadFrame_failsThePage_notAnAllocationError() throws Exception {
        var log = AppendLog.open(file).unwrap();

        log.append(RECORDS, new byte[0], 2_000L).await().onFailure(c -> fail(c.message()));
        log.close();
        var bytes = Files.readAllBytes(file);

        Arrays.fill(bytes, (int) ends[RECORDS - 1], (int) ends[RECORDS - 1] + 4, (byte) 0xFF);
        Files.write(file, bytes);

        assertDamagedAt(AppendLog.readRange(file, RECORDS, 10, ends[RECORDS - 1]), ends[RECORDS - 1]);
    }

    @Test
    void rangePage_copiesItsRecords_soALaterChangeToTheSourceListDoesNotShowThrough() {
        var source = new ArrayList<AppendLog.WalRecord>();

        source.add(new AppendLog.WalRecord(0, 1L, payload(0)));
        var page = new AppendLog.RangePage(source, 7L);

        source.clear();

        assertThat(page.records()).hasSize(1);
    }

    @Test
    void readRange_aLogThatWasNeverCreated_isAnEmptyPage_andStaysUncreated() {
        var missing = dir.resolve("never.wal");

        assertThat(AppendLog.readRange(missing, 0, 10, 0).unwrap()).isEqualTo(AppendLog.RangePage.EMPTY);
        assertThat(Files.exists(missing)).isFalse();
    }

    @Test
    void readRange_changesNothingOnDisk_evenOverATornTail() throws Exception {
        Files.write(file, new byte[]{0, 0, 0, 40, 1}, StandardOpenOption.APPEND);
        var before = Files.readAllBytes(file);
        var modified = Files.getLastModifiedTime(file);
        var listing = listing();

        AppendLog.readRange(file, 0, 2, 0).onFailure(c -> fail(c.message()));
        AppendLog.readRange(file, 0, 10, 0).onSuccess(_ -> fail("the torn tail must fail the page"));

        assertThat(Files.readAllBytes(file)).isEqualTo(before);
        assertThat(Files.getLastModifiedTime(file)).isEqualTo(modified);
        assertThat(listing()).isEqualTo(listing);
    }

    private void assertDamagedAt(Result<AppendLog.RangePage> page, long position) throws Exception {
        var size = Files.size(file);

        page.onSuccess(read -> fail("expected LogDamaged, got " + offsets(read)))
            .onFailure(cause -> assertThat(cause).isEqualTo(new AppendLog.WalError.LogDamaged(file, position, size)));
    }

    private void flipByteAt(long position) throws Exception {
        var bytes = Files.readAllBytes(file);

        bytes[(int) position] ^= 0x5A;
        Files.write(file, bytes);
    }

    private List<String> listing() throws Exception {
        try (var entries = Files.list(dir)) {
            return entries.map(path -> path.getFileName().toString()).sorted().toList();
        }
    }

    private static List<Long> offsets(AppendLog.RangePage page) {
        return page.records().stream().map(AppendLog.WalRecord::offset).toList();
    }

    private static byte[] payload(int i) {
        return ("event-" + i).getBytes();
    }
}
