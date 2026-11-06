// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.resource.entity.EntityLogRecord;
import org.pragmatica.aether.resource.entity.EntityLogSubstrate;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.aether.stream.replication.ReplicationManager;
import org.pragmatica.lang.Option;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.node.StreamEntityLogSubstrate.streamEntityLogSubstrate;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2153 (MT-87, the J3 mutation): the sealed floor a checkpoint writer records is exactly the floor it observed, and a claim ABOVE that floor is
/// validated over the records it holds -- never accepted as sealed. `StreamEntityLogSubstrate.lineageAt` records the floor and `lineageHolds`
/// reads it; mutating the write side to treat a claim up to `floor + 5` as floor-sealed was killed only incidentally, by an unrelated test.
/// These tests pin the branch directly: they use no cut, no checkpoint block and no fold.
class StreamEntityLogSubstrateLineageFloorTest {
    private static final String KEYSPACE = "orders";
    private static final int PARTITION = 0;
    private static final long FLOOR = 4L;
    private static final int RECORDS = 12;
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);

    @TempDir
    java.nio.file.Path walDir;

    private StreamPartitionManager manager;
    private EntityLogSubstrate substrate;
    private String arc;
    /// The floor the manager reports; raised only after the records land, because a record at or below the floor cannot be appended.
    private volatile long sealedFloor = -1L;

    @BeforeEach
    void setUp() {
        arc = EntityPartitionArc.arcName(KEYSPACE);
        manager = streamPartitionManager(64L * 1024 * 1024, EvictionListener.NOOP, ReplicationManager.NONE, Option.some(walDir), (_, _) -> sealedFloor);
        manager.placementRoleSupplier((_, _) -> ReplicaSetController.Role.REPLICA);
        substrate = streamEntityLogSubstrate(manager,
                                             (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0, true),
                                             null,
                                             null,
                                             EvictionListener.NOOP,
                                             null,
                                             null,
                                             null);
        substrate.ensureLog(KEYSPACE, 1, new ReplicationFactors(1, 1)).unwrap();
        for (var offset = 0; offset < RECORDS; offset++) {
            assertThat(manager.appendRecovered(arc, PARTITION, offset, record(offset), 1000L + offset, E1).isSuccess()).isTrue();
        }
        manager.syncReplicated(arc, PARTITION).await();
        manager.commitAdvanced(arc, PARTITION, RECORDS - 1L, E1);
        sealedFloor = FLOOR;
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// NT-179: at the floor the identity is the floor and nothing to compare; one above it, and as far as `floor + 5` (where the J3 mutant stops
    /// treating claims as sealed), the identity is the floor plus the window of records, so the claim is validated, not floor-sealed.
    @Test
    void lineageAt_claimAboveFloor_isValidated_notFloorSealed() {
        assertThat(substrate.lineageAt(KEYSPACE, PARTITION, FLOOR).unwrap()).as("at the floor: the floor, no body")
                                                                             .containsExactly(CheckpointFrame.withFloor(FLOOR, new byte[0]));

        for (var above = FLOOR + 1; above <= FLOOR + 6; above++) {
            var identity = substrate.lineageAt(KEYSPACE, PARTITION, above).unwrap();

            assertThat(CheckpointFrame.floorOf(identity).unwrap()).as("the writer records the floor it saw, at claim %s", above).isEqualTo(FLOOR);
            assertThat(CheckpointFrame.bodyOf(identity)).as("a claim %s above the floor carries the window identity", above).isNotEmpty();
        }
    }

    /// NT-180: the reader accepts a claim without comparing only when it is at or below the floor the WRITER recorded. A claim one above that floor
    /// is compared over the local window, and a recording with an empty body does not match it.
    @Test
    void lineageHolds_claimAboveTheRecordedFloor_isCompared_notAccepted() {
        var honest = substrate.lineageAt(KEYSPACE, PARTITION, FLOOR + 1).unwrap();
        var floorOnly = CheckpointFrame.withFloor(FLOOR, new byte[0]);

        assertThat(substrate.lineageHolds(KEYSPACE, PARTITION, FLOOR, floorOnly)).as("control: at the recorded floor, accepted unvalidated").isTrue();
        assertThat(substrate.lineageHolds(KEYSPACE, PARTITION, FLOOR + 1, honest)).as("control: the honest identity of the claim holds").isTrue();
        assertThat(substrate.lineageHolds(KEYSPACE, PARTITION, FLOOR + 1, floorOnly)).as("one above the recorded floor with no window: refused").isFalse();
        assertThat(substrate.lineageHolds(KEYSPACE, PARTITION, FLOOR + 5, floorOnly)).as("five above: refused").isFalse();
        assertThat(substrate.lineageHolds(KEYSPACE, PARTITION, FLOOR + 5, CheckpointFrame.withFloor(FLOOR + 5, new byte[0])))
            .as("a writer that saw a higher floor accepted the claim as sealed").isTrue();
    }

    private static byte[] record(int value) {
        return EntityLogRecord.upsert("k" + value, String.valueOf(value).getBytes(StandardCharsets.UTF_8)).encode();
    }
}
