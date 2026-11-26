// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.storage;

import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.lang.Option.some;

/// #1569 A4 / T27 (storage side): `inspect` reports the inventory a node sends when it is asked what its copy of a
/// log holds -- `(low, head, lastEpoch, historyDigest, firstStart)` -- read-only. `low` and `head` are the WAL's own
/// extent; the other three come from the epoch-history sidecar exactly as the provenance comparison reads a log: a
/// history entry starting above the head is a ghost and never ranks, but the digest covers every entry.
class AppendLogInventoryTest {
    private static final AppendLog.EpochOrder NUMERIC = (later, earlier) -> number(later) > number(earlier);
    private static final int RECORDS = 10;
    private static final int PAYLOAD_BYTES = 1 << 20;
    private static final String DIGEST_1_AT_0_2_AT_5 = "2cd2c8250fe587a7e381bfc04041c254ddc3396e5caf33fbf3052d2978a04c8f";
    private static final String DIGEST_1_AT_0_2_AT_2_3_AT_3 = "53100b0dde79803ce01f81b27332121bec8a75e01467e23f79b9c6c050a37e09";
    private static final String DIGEST_EMPTY = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    @TempDir
    Path volume;

    /// Seal-truncate through 4 moves the WAL's `low` to 5 while the history keeps both entries. Red under "firstStart is
    /// `low`" and under "the digest covers only the entries in `[low, head]`" (the first entry starts below `low`).
    @Test
    void inspect_sealedAndTruncatedLog_reportsTheWalExtentAndTheWholeHistory() throws Exception {
        writeSealedAndTruncated();

        var extent = AppendLog.inspect(file()).unwrap();

        assertThat(extent.lowOffset()).as("the WAL's on-disk low after the seal-truncate").isEqualTo(5L);
        assertThat(extent.headOffset()).isEqualTo(9L);
        assertThat(extent.validBytes()).as("an intact file: all of it is valid").isEqualTo(Files.size(file())).isPositive();
        assertThat(extent.fileBytes()).isEqualTo(Files.size(file()));
        assertThat(extent.firstStart()).as("the first entry starts below low and still counts").isZero();
        assertThat(extent.lastEpoch()).isEqualTo(Option.some(key(2)));
        assertThat(extent.historyDigest()).isEqualTo(AppendLog.historyDigestOf(List.of(start(1, 0), start(2, 5))));
    }

    /// A ghost entry above the head never ranks the copy, yet changes the digest. Red under "lastEpoch is the last
    /// entry" and under "the digest covers only the entries a record reaches".
    @Test
    void inspect_ghostEntryAboveHead_neverRanks_butChangesTheDigest() throws Exception {
        writeSealedAndTruncated();
        var before = AppendLog.inspect(file()).unwrap();

        recordGhostAbove();

        var after = AppendLog.inspect(file()).unwrap();

        assertThat(after.lastEpoch()).as("the ghost is above head 9").isEqualTo(Option.some(key(2)));
        assertThat(after.historyDigest()).isNotEqualTo(before.historyDigest());
        assertThat(after.historyDigest()).isEqualTo(AppendLog.historyDigestOf(List.of(start(1, 0), start(2, 5), start(3, 20))));
    }

    @Test
    void inspect_logWithoutSidecar_hasNoHistory_andAMissingFileStillEqualsEmpty() {
        var wal = AppendLog.open(file()).unwrap();

        wal.append(0, "a".getBytes(), 1L).await().onFailure(c -> fail(c.message()));
        wal.close();

        var extent = AppendLog.inspect(file()).unwrap();

        assertThat(extent.headOffset()).isZero();
        assertThat(extent.firstStart()).isEqualTo(-1L);
        assertThat(extent.lastEpoch().isPresent()).isFalse();
        assertThat(extent.historyDigest()).isEqualTo(AppendLog.historyDigestOf(List.of()));
        assertThat(AppendLog.inspect(volume.resolve("absent.wal")).unwrap()).isEqualTo(AppendLog.LogExtent.EMPTY);
        assertThat(AppendLog.LogExtent.EMPTY.historyDigest()).isEqualTo(AppendLog.historyDigestOf(List.of()));
    }

    @Test
    void inspect_damagedSidecar_failsAsEpochHistoryCorrupt() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.append(0, "a".getBytes(), 1L).await().onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.close();

        var damaged = Files.readAllBytes(sidecar());

        damaged[damaged.length / 2] ^= 0x5A;
        Files.write(sidecar(), damaged);

        AppendLog.inspect(file())
                 .onSuccess(_ -> fail("a damaged sidecar must not read as an inventory"))
                 .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.EpochHistoryCorrupt.class));
    }

    /// Red under "inspect loads (and so may rewrite) the sidecar": bytes and mtimes of every file are unchanged,
    /// and no stale `.tmp` is created or removed.
    @Test
    void inspect_leavesTheVolumeByteIdentical() throws Exception {
        writeSealedAndTruncated();
        Files.write(sidecar().resolveSibling("0.epochs.tmp"), new byte[] {1, 2, 3});
        var before = snapshot();

        AppendLog.inspect(file()).unwrap();

        assertThat(snapshot()).isEqualTo(before);
    }

    /// Probe A: a full seal-truncate through the head, past the compaction threshold, compacts the WAL to empty. These are
    /// the WAL's facts (know 744c149fe4): no low, no head, no reached epoch -- while the data continues in the sealed tier --
    /// and the history is still read whole. The head rule that ranks such a copy lives above storage (AD7).
    @Test
    void inspect_fullSealTruncateWithCompaction_reportsTheEmptyWal_andTheWholeHistory() throws Exception {
        writeSealedAndTruncatedThrough(9L);

        var extent = AppendLog.inspect(file()).unwrap();

        assertThat(extent.lowOffset()).as("the WAL compacted to empty").isEqualTo(-1L);
        assertThat(extent.headOffset()).isEqualTo(-1L);
        assertThat(extent.lastEpoch().isPresent()).as("no held record reaches an entry").isFalse();
        assertThat(extent.firstStart()).isZero();
        assertThat(extent.historyDigest()).isEqualTo(DIGEST_1_AT_0_2_AT_5);
    }

    /// The WAL low after an eviction-like state, through the real storage entry point: sealed and truncated through 4 below
    /// the compaction threshold moves only the in-memory watermark, so the WAL still holds from 0. Red under "inspectLog
    /// returns EMPTY" (low -1). The producer above storage (AD7) carries its own pin that it reads the WAL, not the ring.
    @Test
    void inspectLog_throughTheStorageInstance_reportsTheWalLowAfterTheWatermarkMoves() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        for (var offset = 0L; offset < 8; offset++) {
            wal.append(offset, new byte[16], 1L).await().onFailure(c -> fail(c.message()));
        }
        wal.markSealed(4L);
        wal.truncate(4L).onFailure(c -> fail(c.message()));
        wal.close();

        var extent = storage().inspectLog("0").unwrap();

        assertThat(extent.lowOffset()).as("the WAL still holds from 0").isZero();
        assertThat(extent.headOffset()).isEqualTo(7L);
        assertThat(extent.validBytes()).isEqualTo(Files.size(file()));
    }

    /// A torn tail beside a history: validBytes is the valid prefix and fileBytes the file. Red under "validBytes and
    /// fileBytes swapped" (they differ only when a tail is torn).
    @Test
    void inspect_tornTailBesideHistory_reportsValidAndFileBytesApart() throws Exception {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.append(0, "a".getBytes(), 1L).await().onFailure(c -> fail(c.message()));
        wal.close();
        var valid = Files.size(file());

        Files.write(file(), new byte[] {0, 0, 0, 40, 1, 2, 3}, StandardOpenOption.APPEND);

        var extent = AppendLog.inspect(file()).unwrap();

        assertThat(extent.validBytes()).isEqualTo(valid);
        assertThat(extent.fileBytes()).isEqualTo(valid + 7);
    }

    /// An entry starting exactly at the head reaches it; one starting at head+1 does not. Red under "<= head" becoming
    /// "< head" (lastEpoch 1) and under "<= head + 1" (lastEpoch 3).
    @Test
    void inspect_entryAtHeadReaches_entryAboveHeadByOneDoesNot() {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        wal.append(0, "a".getBytes(), 1L).await().onFailure(c -> fail(c.message()));
        wal.append(1, "b".getBytes(), 1L).await().onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(2), 2, NUMERIC).onFailure(c -> fail(c.message()));
        wal.append(2, "c".getBytes(), 1L).await().onFailure(c -> fail(c.message()));
        wal.recordEpochStart(key(3), 3, NUMERIC).onFailure(c -> fail(c.message()));
        wal.close();

        var extent = AppendLog.inspect(file()).unwrap();

        assertThat(extent.headOffset()).isEqualTo(2L);
        assertThat(extent.lastEpoch()).isEqualTo(some(key(2)));
        assertThat(extent.historyDigest()).isEqualTo(DIGEST_1_AT_0_2_AT_2_3_AT_3);
    }

    /// The digest is SHA-256 hex over `<token> <startOffset>\n` of every entry, oldest first, pinned as literals (computed
    /// outside this code, so no mutation of the digest input can be mirrored by the expectation). Red under a dropped
    /// start offset, token, newline, a newest-first order or another hash.
    @Test
    void historyDigestOf_isAPinnedSha256_overOldestFirstEntries() {
        assertThat(AppendLog.historyDigestOf(List.of(start(1, 0), start(2, 5)))).isEqualTo(DIGEST_1_AT_0_2_AT_5);
        assertThat(AppendLog.historyDigestOf(List.of(start(1, 0), start(2, 2), start(3, 3)))).isEqualTo(DIGEST_1_AT_0_2_AT_2_3_AT_3);
        assertThat(AppendLog.historyDigestOf(List.of(start(2, 5), start(1, 0)))).as("order matters").isNotEqualTo(DIGEST_1_AT_0_2_AT_5);
        assertThat(AppendLog.historyDigestOf(List.of())).as("the empty input").isEqualTo(DIGEST_EMPTY);
    }

    @Test
    void emptyExtent_hasNoHistory() {
        assertThat(AppendLog.LogExtent.EMPTY.firstStart()).isEqualTo(-1L);
        assertThat(AppendLog.LogExtent.EMPTY.lastEpoch().isPresent()).isFalse();
    }

    private StorageInstance storage() {
        return StorageInstance.storageInstance("streams",
                                               List.of(MemoryTier.memoryTier(1 << 20)),
                                               MetadataStore.inMemoryMetadataStore("streams"),
                                               WritePolicy.WRITE_THROUGH,
                                               some(volume));
    }

    private void writeSealedAndTruncated() throws Exception {
        writeSealedAndTruncatedThrough(4L);
    }

    private void writeSealedAndTruncatedThrough(long through) throws Exception {
        var wal = AppendLog.open(file()).unwrap();
        var payload = new byte[PAYLOAD_BYTES];

        wal.recordEpochStart(key(1), 0, NUMERIC).onFailure(c -> fail(c.message()));
        for (var offset = 0L; offset < RECORDS; offset++) {
            if (offset == 5) {
                wal.recordEpochStart(key(2), 5, NUMERIC).onFailure(c -> fail(c.message()));
            }
            wal.append(offset, payload, 1L).await().onFailure(c -> fail(c.message()));
        }
        // Past the 8 MiB compaction threshold, so the truncate rewrites the file and `low` moves on disk.
        wal.markSealed(through);
        wal.truncate(through).onFailure(c -> fail(c.message()));
        wal.close();
    }

    private void recordGhostAbove() {
        var wal = AppendLog.open(file()).unwrap();

        wal.recordEpochStart(key(3), 20, NUMERIC).onFailure(c -> fail(c.message()));
        wal.close();
    }

    private Path file() {
        return volume.resolve("0.wal");
    }

    private Path sidecar() {
        return volume.resolve("0.epochs");
    }

    private static AppendLog.EpochKey key(int epoch) {
        return AppendLog.EpochKey.epochKey(Integer.toString(epoch)).unwrap();
    }

    private static AppendLog.EpochStart start(int epoch, long offset) {
        return new AppendLog.EpochStart(key(epoch), offset);
    }

    private static long number(AppendLog.EpochKey key) {
        return Long.parseLong(key.token());
    }

    private Map<String, String> snapshot() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256");
        var result = new TreeMap<String, String>();

        try (Stream<Path> paths = Files.walk(volume)) {
            for (var path : paths.toList()) {
                var content = Files.isRegularFile(path)
                              ? HexFormat.of().formatHex(digest.digest(Files.readAllBytes(path)))
                              : "dir";

                result.put(volume.relativize(path).toString(),
                           Files.size(path) + "|" + Files.getLastModifiedTime(path).toMillis() + "|" + content);
            }
        }

        return result;
    }
}
