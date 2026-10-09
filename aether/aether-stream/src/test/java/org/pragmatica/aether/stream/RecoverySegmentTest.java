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
    void write_whenTheNameIsTaken_isRefused_andTheEarlierSegmentIsUntouched() throws Exception {
        var wal = dir.resolve("s-3.wal");
        var first = RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 9L).unwrap();
        var before = Files.readAllBytes(first.file());

        assertThat(RecoverySegment.write(wal, "orders", 0, List.of(), pagesOf(0L, 2L), 0L, 2L, 9L).isFailure()).isTrue();
        assertThat(Files.readAllBytes(first.file())).isEqualTo(before);
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
