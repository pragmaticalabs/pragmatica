// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.storage.AppendLog;
import org.pragmatica.storage.BlockId;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams overhaul PR-2c commit 4 (the WAL order rule I-04, folded 2b, `know:` `153503d643`), on the manager.
///
/// The CTO's pin: once the WAL refuses gaps, an in-section WAL refusal must not wedge the partition. With the rollback (commit 3) the
/// refused publish leaves no orphan, so after the cause is repaired the next publish lands at the withdrawn offset and the WAL stays
/// contiguous. Without the rollback the ring keeps the refused offset, every later publish is a WAL gap, and the order rule refuses
/// each of them (the wedge `153503d643` predicted). The refusal is induced the only way an owner publish can still meet one after the
/// in-section guard: a stray WAL record at the next offset (`OffsetRegression`, which does not fail-stop), then cut, as a repair would.
/// The EpochRace shape no longer reaches the WAL on the owner path: the guard refuses it before an offset exists.
///
/// The wiring pin: `openPartitionWal` installs the partition's live sealed floor on the log, so a restarted owner whose ring is seeded at
/// the floor above its WAL's last record writes across the floor; with no floor installed the first publish would be refused as a gap.
@Timeout(60)
class WalOrderRuleLivenessTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    @Test
    void refusedInSectionWalWrite_leavesThePartitionLive_afterTheRollback() throws Exception {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        publish(manager, "e0").onFailure(cause -> fail(cause.message()));
        publish(manager, "e1").onFailure(cause -> fail(cause.message()));
        var wal = GatedWalFsync.walOf(manager, STREAM, PARTITION);

        wal.write(2L, bytes("stray"), 9L).onFailure(cause -> fail(cause.message()));
        assertThat(publish(manager, "refused").isFailure()).as("control: the WAL refused the in-section write").isTrue();
        wal.truncateSuffix(1L).onFailure(cause -> fail(cause.message()));

        var after = IntStream.range(0, 3)
                             .mapToObj(i -> describe(publish(manager, "after-" + i)))
                             .toList();

        assertThat(after).as("every publish after the repaired refusal succeeds at the next offset").containsExactly("2", "3", "4");
        assertThat(walOffsets(wal)).as("the WAL is contiguous").containsExactly(0L, 1L, 2L, 3L, 4L);
        manager.close();
    }

    @Test
    void restartedOwner_writesAcrossTheSealedFloor_overAWalBelowIt() throws Exception {
        var first = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        first.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 3; i++) {
            publish(first, "e" + i).onFailure(cause -> fail(cause.message()));
        }
        first.close();

        var index = new SegmentIndex();

        index.rebuildFromRefs(Map.of("stream-floors/" + STREAM + "/" + PARTITION + "/9", BlockId.blockId(new byte[]{1}).unwrap()));
        var restarted = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), index::lastSealedOffset);

        restarted.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        var next = publish(restarted, "across");

        assertThat(describe(next)).as("the first publish lands at floor + 1, across the sealed range").isEqualTo("10");
        assertThat(walOffsets(GatedWalFsync.walOf(restarted, STREAM, PARTITION))).containsExactly(0L, 1L, 2L, 10L);
        restarted.close();
    }

    private static Result<Long> publish(StreamPartitionManager manager, String text) {
        return manager.publishLocal(STREAM, PARTITION, bytes(text), 1L);
    }

    private static String describe(Result<Long> outcome) {
        return outcome.fold(cause -> "refused: " + cause.message(), String::valueOf);
    }

    private static List<Long> walOffsets(AppendLog wal) {
        var records = new ArrayList<AppendLog.WalRecord>();

        wal.replay(-1L, records::add).onFailure(cause -> fail(cause.message()));
        return records.stream().map(AppendLog.WalRecord::offset).toList();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(UTF_8);
    }
}
