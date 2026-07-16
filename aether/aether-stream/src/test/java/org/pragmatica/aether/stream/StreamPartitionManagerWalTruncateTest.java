// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.wal.PartitionWal;
import org.pragmatica.lang.Option;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Proves streaming-persistence W5 (periodic WAL truncation to the durable sealed offset): the manager's
/// `truncateWalsToSealed()` discards each partition's WAL records `offset <= lastSealedOffset` (already
/// durable in cold segments) while keeping the un-sealed tail (`offset > lastSealedOffset`), reclaiming
/// disk. A fresh [PartitionWal] opened on the same file replays only the survivors. A `-1` bound (nothing
/// sealed) is a no-op, and the no-WAL path is untouched.
class StreamPartitionManagerWalTruncateTest {

    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    /// 256 KiB payloads × 40 events ≈ 10 MiB > PartitionWal.COMPACTION_THRESHOLD_BYTES (8 MiB), so the
    /// truncate physically compacts the file (not just the in-memory watermark) — the only way a FRESH
    /// WAL replay observes the reclamation.
    private static final int PAYLOAD_BYTES = 256 * 1024;
    private static final int EVENTS = 40;
    private static final int SEALED_BOUND = 19;
    private static final int SMALL_EVENTS = 6;

    @TempDir
    Path walDir;

    @Test
    void truncateWalsToSealed_dropsRecordsAtOrBelowBound_keepsTailAndReclaimsDisk() throws IOException {
        var sealedBound = new AtomicLong(-1L);
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), (_, _) -> sealedBound.get());

        createStream(manager);
        IntStream.range(0, EVENTS).forEach(i -> publish(manager, i, bigPayload(i)));

        var walFile = partitionWalFile();
        var sizeBefore = Files.size(walFile);

        sealedBound.set(SEALED_BOUND);
        manager.truncateWalsToSealed();

        var sizeAfter = Files.size(walFile);
        manager.close();

        assertThat(sizeAfter).isLessThan(sizeBefore);

        var survivors = replayedOffsets(walFile);

        assertThat(survivors).hasSize(EVENTS - (SEALED_BOUND + 1))
                             .first()
                             .isEqualTo((long) (SEALED_BOUND + 1));
        assertThat(survivors).last().isEqualTo((long) (EVENTS - 1));
    }

    @Test
    void truncateWalsToSealed_isNoOp_whenNothingSealed() {
        var sealedBound = new AtomicLong(-1L);
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), (_, _) -> sealedBound.get());

        createStream(manager);
        IntStream.range(0, SMALL_EVENTS).forEach(i -> publish(manager, i, smallPayload(i)));

        // Bound stays -1: truncate is never invoked, so every record remains.
        manager.truncateWalsToSealed();
        manager.close();

        assertThat(replayedOffsets(partitionWalFile())).containsExactlyElementsOf(offsets(SMALL_EVENTS));
    }

    @Test
    void truncateWalsToSealed_isNoOp_whenNoWalBaseDir() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.none());

        createStream(manager);
        IntStream.range(0, SMALL_EVENTS).forEach(i -> publish(manager, i, smallPayload(i)));

        // No WAL configured ⇒ nothing to truncate; the call is a harmless no-op and writes no WAL files.
        manager.truncateWalsToSealed();
        manager.close();

        assertThat(Files.exists(walDir.resolve(STREAM))).isFalse();
    }

    // === helpers ===

    private static void createStream(StreamPartitionManager manager) {
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
    }

    private static void publish(StreamPartitionManager manager, int i, byte[] payload) {
        manager.publishLocal(STREAM, PARTITION, payload, 1000L + i)
               .onFailure(cause -> fail(cause.message()))
               .onSuccess(offset -> assertThat(offset).isEqualTo((long) i));
    }

    private Path partitionWalFile() {
        return walDir.resolve(STREAM).resolve(PARTITION + ".wal");
    }

    private static List<Long> replayedOffsets(Path walFile) {
        var wal = PartitionWal.open(walFile).onFailure(cause -> fail(cause.message())).unwrap();
        var offsets = new ArrayList<Long>();

        wal.replay(-1L, record -> offsets.add(record.offset())).onFailure(cause -> fail(cause.message()));
        wal.close();

        return offsets;
    }

    private static List<Long> offsets(int count) {
        return IntStream.range(0, count).mapToObj(Long::valueOf).toList();
    }

    private static byte[] bigPayload(int i) {
        var payload = new byte[PAYLOAD_BYTES];

        payload[0] = (byte) i;

        return payload;
    }

    private static byte[] smallPayload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }
}
