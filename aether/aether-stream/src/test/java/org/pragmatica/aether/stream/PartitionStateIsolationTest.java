// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.lang.Option;

import java.util.concurrent.atomic.AtomicReference;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #2201 A-05, the author pre-check of PR-2a: the extraction re-keyed every per-partition row from a `stream#partition` string
/// (or a `PartitionRef`) to the partition number inside the stream's [PartitionState]. Every other test of these rows uses
/// partition 0 only, so a row read or written under the wrong partition went unnoticed. Here state recorded on partition 1 is
/// seen on partition 1 and not on partition 0: the epoch verdict, the owner's visible position, the cut generation and the
/// quarantine marker.
class PartitionStateIsolationTest {
    private static final String STREAM = "pair";
    private static final Epoch EPOCH = Epoch.epoch(1L, 1L, 1L);
    private static final long CUT_MASK = (1L << 24) - 1;

    private final AtomicReference<Role> role = new AtomicReference<>(Role.OWNER);
    private final StreamPartitionManager manager = streamPartitionManager(Long.MAX_VALUE);

    @BeforeEach
    void setUp() {
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.ownerEpochSource((_, _) -> EPOCH);
        manager.epochStarts((_, _, _) -> Option.some(0L));
        manager.createStream(StreamConfig.streamConfig(STREAM, 2, RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 3_600_000L), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        role.set(Role.REPLICA);
        for (var partition = 0; partition < 2; partition++) {
            for (var offset = 0; offset < 3; offset++) {
                manager.appendRecovered(STREAM, partition, offset, ("e" + offset).getBytes(UTF_8), 1000L + offset, EPOCH)
                       .onFailure(cause -> fail(cause.message()));
            }
        }
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    @Test
    void epochVerdict_recordedOnOnePartition_isThatPartitionsOnly() {
        manager.markVerifiedForEpoch(STREAM, 1, EPOCH);

        assertThat(manager.replicaVerified(STREAM, 0)).as("partition 0 holds offsets at the epoch's start and was not compared").isFalse();
        assertThat(manager.replicaVerified(STREAM, 1)).as("partition 1 was verified for the epoch").isTrue();
    }

    @Test
    void ownersVisiblePosition_reportedForOnePartition_exposesThatPartitionOnly() {
        manager.commitAdvanced(STREAM, 1, 2L, EPOCH);

        assertThat(visible(1)).as("partition 1 exposes what the owner reported").isEqualTo(2L);
        assertThat(visible(0)).as("partition 0 was told nothing").isEqualTo(-1L);
    }

    @Test
    void cutGeneration_ofOnePartition_isThatPartitionsOnly() {
        var token = new Object();

        manager.beginCut(STREAM, 1, token);
        manager.endCut(STREAM, 1, token);

        assertThat(manager.cutGeneration(STREAM, 1) & CUT_MASK).as("one closed cut on partition 1").isEqualTo(2L);
        assertThat(manager.cutGeneration(STREAM, 0) & CUT_MASK).as("none on partition 0").isZero();
    }

    @Test
    void quarantine_ofOnePartition_isThatPartitionsOnly() {
        manager.appendRecovered(STREAM, 1, 1, "different".getBytes(UTF_8), 1001L, EPOCH);

        assertThat(manager.quarantinedAt(STREAM, 1)).as("partition 1 holds a divergent entry at offset 1").isEqualTo(Option.some(1L));
        assertThat(manager.quarantinedAt(STREAM, 0).isEmpty()).as("partition 0 is not quarantined").isTrue();
    }

    /// The ring's divergence fence reads the marker of its own partition: past the divergence, partition 1 refuses the next
    /// replicated record, and partition 0 takes it.
    @Test
    void divergenceFence_ofOnePartition_refusesItsAppends_only() {
        manager.appendRecovered(STREAM, 1, 1, "different".getBytes(UTF_8), 1001L, EPOCH);

        assertThat(manager.appendRecovered(STREAM, 1, 3, "e3".getBytes(UTF_8), 1003L, EPOCH).isFailure())
            .as("partition 1 is fenced at and past its divergence").isTrue();
        manager.appendRecovered(STREAM, 1, 3, "e3".getBytes(UTF_8), 1003L, EPOCH)
               .onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaQuarantined.class));
        assertThat(manager.appendRecovered(STREAM, 0, 3, "e3".getBytes(UTF_8), 1003L, EPOCH).isSuccess())
            .as("partition 0 is not fenced").isTrue();
    }

    /// The install list of a partition is also its lock (#1638 N2: partitions never wait on each other's sidecar fsyncs), so it
    /// must be that partition's own.
    @Test
    void pendingInstalls_ofOnePartition_areThatPartitionsOnly() {
        manager.releaseProvenance(StreamPartitionManager.InstalledSlice.installedSlice(STREAM, 1, java.util.List.of(), java.util.List.of()));

        assertThat(manager.heldPartitionRows(STREAM, 1)).as("partition 1's install list").contains("pendingInstalls");
        assertThat(manager.heldPartitionRows(STREAM, 0)).as("partition 0 has none").doesNotContain("pendingInstalls");
    }

    private long visible(int partition) {
        return manager.visibleBounds(STREAM, partition).map(VisibleBounds::visibleHead).or(-2L);
    }
}
