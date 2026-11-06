// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.storage.AppendLog;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// Streams overhaul PR-2c, I-04 (#2201): the manager opens a partition's log with the partition's OWN live sealed floor, read from its
/// [LastSealedOffsetSource] at each write, not a captured or shifted value. With the log at offset 2 and the floor raised to 9 after the
/// open, the log admits exactly the forward jump the sealed tier covers (offset 10 = floor + 1) and refuses one past it (11). The order
/// rule itself is pinned at the log ([AppendLog]'s own tests); this pins the manager's wiring to it.
@Timeout(60)
class PartitionWalSealedFloorWiringTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;

    @TempDir
    Path walDir;

    private final AtomicLong sealedFloor = new AtomicLong(-1);
    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void partitionLog_admitsTheJumpItsLiveSealedFloorCovers_andRefusesOnePastIt() throws Exception {
        manager = streamPartitionManager(Long.MAX_VALUE,
                                         Option.some(walDir),
                                         (stream, partition) -> STREAM.equals(stream) && partition == PARTITION
                                                                ? sealedFloor.get()
                                                                : -1L);
        manager.ensureStreamMaterialized(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 3; i++) {
            manager.publishLocal(STREAM, PARTITION, ("e" + i).getBytes(UTF_8), 1L).onFailure(cause -> fail(cause.message()));
        }
        var wal = GatedWalFsync.walOf(manager, STREAM, PARTITION);
        assertThat(wal.lastOffset()).as("control: the log holds offsets 0..2").isEqualTo(2L);

        sealedFloor.set(9);

        wal.write(11, "past-the-floor".getBytes(UTF_8), 1L)
           .onSuccess(_ -> fail("a gap past the sealed floor was admitted"))
           .onFailure(cause -> assertThat(cause).isInstanceOf(AppendLog.WalError.OffsetRegression.class));
        wal.write(10, "floor-plus-one".getBytes(UTF_8), 1L)
           .onFailure(cause -> fail("the jump the live sealed floor covers was refused: " + cause.message()));
        assertThat(wal.lastOffset()).isEqualTo(10L);
    }
}
