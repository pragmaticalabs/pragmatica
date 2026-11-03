// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.segment.SegmentIndex;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.BlockId;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1580 (spec 14.3 item 2), NT-199: a partition retention emptied completely does not reissue offsets after a restart. Retention
/// persisted its `stream-floors/` ref through the last issued offset and reclaimed every segment and every WAL record; the restarted
/// node rebuilds its segment index from that ref alone and opens an empty WAL. The next publish lands strictly above the last issued
/// offset (`next > lastIssued`, not `>= floor`).
@Timeout(60)
class EmptiedPartitionRestartTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    @Test
    void emptiedByRetentionThenReopenKeepsNextOffsetAboveFloor() throws Exception {
        var first = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        first.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        var lastIssued = -1L;

        for (var i = 0; i < 5; i++) {
            lastIssued = first.publishLocal(STREAM, PARTITION, ("evt-" + i).getBytes(UTF_8), 1L).unwrap();
        }
        first.close();
        emptyTheWal();

        var index = new SegmentIndex();

        index.rebuildFromRefs(Map.of("stream-floors/" + STREAM + "/" + PARTITION + "/" + lastIssued, BlockId.blockId(new byte[]{1}).unwrap()));
        var restarted = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir), index::lastSealedOffset);

        restarted.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        var next = restarted.publishLocal(STREAM, PARTITION, "after-restart".getBytes(UTF_8), 2L);

        next.onFailure(cause -> fail(cause.message()));
        assertThat(next.unwrap()).as("the first offset after the restart is above every offset issued before it").isGreaterThan(lastIssued);
        restarted.close();
    }

    /// Retention reclaimed every record: the log file is empty, as a compaction past the last record leaves it.
    private void emptyTheWal() throws Exception {
        try (var files = Files.walk(walDir)) {
            for (var wal : files.filter(path -> path.toString().endsWith(".wal")).toList()) {
                Files.write(wal, new byte[0]);
            }
        }
    }
}
