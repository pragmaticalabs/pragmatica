// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.LongStream;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.AppendLog.EpochKey;
import org.pragmatica.storage.AppendLog.EpochStart;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

/// #2080: the recovery segment is the only copy of what a cut removed, so its format is pinned: it round-trips every field, a
/// range the pages do not fully deliver is refused (a segment may not claim a record it lacks), and damage is detected on read.
class RecoverySegmentTest {
    @TempDir
    Path dir;

    private static OffHeapRingBuffer.RawEvent event(long offset) {
        return OffHeapRingBuffer.RawEvent.rawEvent(offset, ("payload-" + offset).getBytes(StandardCharsets.UTF_8), 5000L + offset);
    }

    private static RecoverySegment.Pages pagesOf(long from, long to) {
        return (start, max) -> Result.success(LongStream.rangeClosed(start, Math.min(to, start + max - 1)).mapToObj(RecoverySegmentTest::event).toList());
    }

    private static List<EpochStart> history() {
        return List.of(new EpochStart(EpochKey.epochKey("e1").unwrap(), 0L), new EpochStart(EpochKey.epochKey("e2").unwrap(), 2500L));
    }

    @Test
    void write_thenRead_roundTripsEveryField_acrossPages() {
        var wal = dir.resolve("s-0.wal");
        var preserved = RecoverySegment.write(wal, "orders", 3, history(), pagesOf(2000L, 3099L), 2000L, 3099L, 42L).unwrap();

        assertThat(preserved.records()).isEqualTo(1100L);
        assertThat(preserved.file()).isEqualTo(RecoverySegment.fileFor(wal, 2000L, 3099L, 42L)).exists();
        assertThat(RecoverySegment.temporaryFor(wal)).doesNotExist();
        var contents = RecoverySegment.read(preserved.file()).unwrap();

        assertThat(contents.streamName()).isEqualTo("orders");
        assertThat(contents.partition()).isEqualTo(3);
        assertThat(contents.createdMillis()).isEqualTo(42L);
        assertThat(contents.entries()).hasSize(1100);
        assertThat(contents.entries().getFirst().offset()).isEqualTo(2000L);
        assertThat(contents.entries().getFirst().epoch()).isEqualTo(Option.some("e1"));
        assertThat(contents.entries().get(499).epoch()).as("offset 2499 is before the second epoch").isEqualTo(Option.some("e1"));
        assertThat(contents.entries().get(500).epoch()).as("offset 2500 starts the second").isEqualTo(Option.some("e2"));
        assertThat(contents.entries().getLast().timestampMillis()).isEqualTo(5000L + 3099L);
        assertThat(new String(contents.entries().getLast().payload(), StandardCharsets.UTF_8)).isEqualTo("payload-3099");
    }

    @Test
    void write_withNoEpochHistory_recordsNoEpoch() {
        var wal = dir.resolve("s-1.wal");
        var preserved = RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 4L), 0L, 4L, 1L).unwrap();

        assertThat(RecoverySegment.read(preserved.file()).unwrap().entries()).allSatisfy(entry -> assertThat(entry.epoch().isEmpty()).isTrue());
    }

    @Test
    void write_whenThePagesStopShort_isRefused_andLeavesNoSegmentUnderItsFinalName() {
        var wal = dir.resolve("s-2.wal");
        var refused = RecoverySegment.write(wal, "orders", 0, history(), pagesOf(0L, 6L), 0L, 9L, 7L);

        assertThat(refused.isFailure()).as("records 7..9 were never delivered").isTrue();
        assertThat(RecoverySegment.fileFor(wal, 0L, 9L, 7L)).doesNotExist();
    }

    @Test
    void write_whenTheNameIsTakenByOtherContent_isRefused_andTheEarlierSegmentIsUntouched() throws Exception {
        var wal = dir.resolve("s-3.wal");
        var first = RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 9L).unwrap();
        var before = Files.readAllBytes(first.file());
        RecoverySegment.Pages other = (start, max) -> Result.success(LongStream.rangeClosed(start, Math.min(2L, start + max - 1))
                                                                              .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset, ("other-" + offset).getBytes(StandardCharsets.UTF_8), 1L))
                                                                              .toList());

        assertThat(RecoverySegment.write(wal, "orders", 0, List.of(), other, 0L, 2L, 9L).isFailure()).isTrue();
        assertThat(Files.readAllBytes(first.file())).isEqualTo(before);
    }

    /// An interrupted cut that is run again finds the same records and reuses its segment: same file, no second copy, no leftover
    /// temporary. Records of the same range with other content are another loss and keep their own file (previous test, and a later
    /// clock gives a second name).
    @Test
    void write_sameRecordsAgain_reusesTheExistingSegment_andLeavesNoSecondCopy() throws Exception {
        var wal = dir.resolve("s-7.wal");
        var first = RecoverySegment.write(wal, "orders", 0, history(), pagesOf(0L, 4L), 0L, 4L, 10L).unwrap();
        var again = RecoverySegment.write(wal, "orders", 0, history(), pagesOf(0L, 4L), 0L, 4L, 99L).unwrap();

        assertThat(again.file()).isEqualTo(first.file());
        assertThat(again.records()).isEqualTo(5L);
        try (var files = Files.list(dir)) {
            assertThat(files.map(file -> file.getFileName().toString()).toList()).containsExactly(first.file().getFileName().toString());
        }
    }

    /// Same offsets, timestamps and payloads under a DIFFERENT owner-epoch history are another lineage's loss, not the same one: a
    /// separate file. Mutation: ignoring the epoch in the comparison turns this red.
    @Test
    void write_sameRecordsOtherEpochs_isNotReused() {
        var wal = dir.resolve("s-8.wal");
        var first = RecoverySegment.write(wal, "orders", 0, history(), pagesOf(0L, 4L), 0L, 4L, 10L).unwrap();
        var other = RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 4L), 0L, 4L, 11L).unwrap();

        assertThat(other.file()).isNotEqualTo(first.file());
    }

    /// The reuse check means what the doc says: identical offsets, timestamps, epoch keys AND payloads. Same range and no epochs, but one
    /// payload (or one timestamp) different, is another loss: a separate file. Mutations: ignoring payload or timestamp turns each red.
    @Test
    void write_samePositionsOtherPayloadOrTimestamp_isNotReused() {
        var wal = dir.resolve("s-9.wal");
        var first = RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 10L).unwrap();
        RecoverySegment.Pages otherPayload = (start, max) -> Result.success(LongStream.rangeClosed(start, 2L)
                                                                                      .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset, ("x-" + offset).getBytes(StandardCharsets.UTF_8), 5000L + offset))
                                                                                      .toList());
        RecoverySegment.Pages otherTimestamp = (start, max) -> Result.success(LongStream.rangeClosed(start, 2L)
                                                                                        .mapToObj(offset -> OffHeapRingBuffer.RawEvent.rawEvent(offset, ("payload-" + offset).getBytes(StandardCharsets.UTF_8), 9L))
                                                                                        .toList());
        var byPayload = RecoverySegment.write(wal, "orders", 0, List.of(), otherPayload, 0L, 2L, 11L).unwrap();
        var byTimestamp = RecoverySegment.write(wal, "orders", 0, List.of(), otherTimestamp, 0L, 2L, 12L).unwrap();

        assertThat(byPayload.file()).isNotEqualTo(first.file());
        assertThat(byTimestamp.file()).isNotEqualTo(first.file()).isNotEqualTo(byPayload.file());
    }

    /// A page that does not start where the range continues (a hole, a repeat) is refused, never copied as if it were the range.
    @Test
    void write_aPageThatSkipsTheRequestedOffset_isRefused() {
        RecoverySegment.Pages skipping = (start, max) -> Result.success(LongStream.rangeClosed(start + 1L, 4L).mapToObj(RecoverySegmentTest::event).toList());

        assertThat(RecoverySegment.write(dir.resolve("s-10.wal"), "orders", 0, List.of(), skipping, 0L, 4L, 1L).isFailure()).isTrue();
        assertThat(RecoverySegment.fileFor(dir.resolve("s-10.wal"), 0L, 4L, 1L)).doesNotExist();
    }

    /// The checksum covers the timestamp, and the trailer's count is checked against the header's range (not against what was read).
    @Test
    void read_detectsAFlippedTimestampByte_andAWrongTrailerCount() throws Exception {
        var file = RecoverySegment.write(dir.resolve("s-11.wal"), "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 3L).unwrap().file();
        var bytes = Files.readAllBytes(file);
        var timestampFlipped = bytes.clone();
        var countWrong = bytes.clone();
        // header: magic 8 + UTF "orders" 2+6 + partition 4 + first/last/created 24 = 44; first entry: offset 8, then the timestamp
        timestampFlipped[44 + 8 + 7] ^= 0x01;
        countWrong[bytes.length - 8 - 1] ^= 0x01;
        Files.write(dir.resolve("ts-flipped.seg"), timestampFlipped);
        Files.write(dir.resolve("count-wrong.seg"), countWrong);

        assertThat(RecoverySegment.read(dir.resolve("ts-flipped.seg")).isFailure()).isTrue();
        assertThat(RecoverySegment.read(dir.resolve("count-wrong.seg")).isFailure()).isTrue();
    }

    @Test
    void read_detectsAFlippedByte_aTruncation_andAForeignFile() throws Exception {
        var wal = dir.resolve("s-4.wal");
        var file = RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 4L), 0L, 4L, 3L).unwrap().file();
        var bytes = Files.readAllBytes(file);
        var flipped = bytes.clone();

        flipped[flipped.length / 2] ^= 0x01;
        Files.write(dir.resolve("flipped.seg"), flipped);
        Files.write(dir.resolve("truncated.seg"), java.util.Arrays.copyOf(bytes, bytes.length - 9));
        Files.writeString(dir.resolve("foreign.seg"), "not a segment");

        assertThat(RecoverySegment.read(file).isSuccess()).as("control: the intact segment reads").isTrue();
        assertThat(RecoverySegment.read(dir.resolve("flipped.seg")).isFailure()).isTrue();
        assertThat(RecoverySegment.read(dir.resolve("truncated.seg")).isFailure()).isTrue();
        assertThat(RecoverySegment.read(dir.resolve("foreign.seg")).isFailure()).isTrue();
    }

    /// The checksum covers the PAYLOAD: one flipped payload byte of the last record (the bytes just before its 4-byte checksum and the
    /// 16-byte trailer) is detected. Mutation: a checksum over offset and timestamp only turns this red.
    @Test
    void read_detectsAFlippedPayloadByte() throws Exception {
        var file = RecoverySegment.write(dir.resolve("s-5.wal"), "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 3L).unwrap().file();
        var bytes = Files.readAllBytes(file);

        bytes[bytes.length - 16 - 4 - 1] ^= 0x01;
        Files.write(dir.resolve("payload-flipped.seg"), bytes);

        assertThat(RecoverySegment.read(dir.resolve("payload-flipped.seg")).isFailure()).isTrue();
    }

    /// The trailer is checked on its own: a segment missing exactly its closing magic, and one whose closing magic is wrong, are both
    /// refused (a copy that stopped after the last record is not a complete record of the loss).
    @Test
    void read_requiresTheClosingMagic() throws Exception {
        var file = RecoverySegment.write(dir.resolve("s-6.wal"), "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 3L).unwrap().file();
        var bytes = Files.readAllBytes(file);
        var wrongMagic = bytes.clone();

        wrongMagic[wrongMagic.length - 1] ^= 0x01;
        Files.write(dir.resolve("no-magic.seg"), java.util.Arrays.copyOf(bytes, bytes.length - 8));
        Files.write(dir.resolve("wrong-magic.seg"), wrongMagic);

        assertThat(RecoverySegment.read(file).isSuccess()).as("control").isTrue();
        assertThat(RecoverySegment.read(dir.resolve("no-magic.seg")).isFailure()).isTrue();
        assertThat(RecoverySegment.read(dir.resolve("wrong-magic.seg")).isFailure()).isTrue();
    }
}
