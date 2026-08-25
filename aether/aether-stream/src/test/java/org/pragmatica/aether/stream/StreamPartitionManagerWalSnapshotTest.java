// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.stream.StreamPartitionManager.PartitionWalView;
import org.pragmatica.aether.stream.StreamPartitionManager.StreamWalView;
import org.pragmatica.aether.stream.StreamPartitionManager.WalSnapshot;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.aether.stream.wal.PartitionWal.WalStats;
import org.pragmatica.lang.Option;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #634-3: the per-partition WAL/floors snapshot the node-side retention assembler joins against.
///
/// The load-bearing property is that ABSENCE is data. A partition this node never materialized must be
/// missing from the snapshot rather than reported as zeros — zeros are indistinguishable from a real
/// empty WAL, and the tri-floor invariant built on top would read them as "this node holds history
/// starting at 0" and stay silent about a partition it cannot serve at all.
class StreamPartitionManagerWalSnapshotTest {

    private static final String STREAM = "orders";
    /// A stream this node is placed NONE for on every partition — materialized nowhere locally.
    private static final String UNHELD = "archived";
    private static final int PARTITION = 0;
    private static final int DECLARED_PARTITIONS = 4;
    private static final int EVENTS = 3;

    @TempDir
    Path walDir;

    @Test
    void walSnapshot_reportsOnlyMaterializedPartitions_withWalStatsAndFloors() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        try {
            manager.placementRoleSupplier(StreamPartitionManagerWalSnapshotTest::roleFor);
            createStream(manager, STREAM);
            createStream(manager, UNHELD);

            IntStream.range(0, EVENTS).forEach(i -> publishOne(manager, i));

            var snapshot = manager.walSnapshot();

            assertThat(manager.streamInfo(UNHELD).isPresent())
                .as("the unheld stream WAS created — its absence below is the materialization gate, not a failed create")
                .isTrue();
            assertThat(streamNames(snapshot))
                .as("a stream with no materialized partition has nothing local to report")
                .containsExactly(STREAM);
            assertThat(partitionsOf(snapshot, STREAM)).hasSize(DECLARED_PARTITIONS);

            var view = partitionView(snapshot, STREAM, PARTITION);
            var stats = statsOf(view);

            assertThat(stats.sizeBytes()).as("the published events are on disk in this partition's WAL")
                                         .isPositive();
            assertThat(stats.lastOffset()).isEqualTo(EVENTS - 1L);
            assertThat(view.ringTailOffset()).as("the ring holds every published event, so its tail is a real offset")
                                             .isNotNegative();
            assertThat(view.sealedThroughOffset())
                .as("the standalone factory binds the floor last-sealed source — nothing is sealed")
                .isEqualTo(-1L);
        } finally {
            manager.close();
        }
    }

    /// The review's catch, pinned: `tailOffset()` is raw slot state and reads `0` on a ring that never
    /// held a record — a value that satisfies any covered-from check and would declare a
    /// restarted-empty partition healthy under a committed checkpoint. Partitions 1-3 here are exactly
    /// that shape (materialized by the create, never published to), and partition 0 is the arming
    /// contrast: a ring that DID receive records still reports a real tail, so the `-1` below is
    /// emptiness rather than a blanket change.
    @Test
    void walSnapshot_reportsRingTailMinusOne_forMaterializedButUnwrittenPartition() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.some(walDir));

        try {
            createStream(manager, STREAM);
            IntStream.range(0, EVENTS).forEach(i -> publishOne(manager, i));

            var snapshot = manager.walSnapshot();

            assertThat(partitionView(snapshot, STREAM, PARTITION).ringTailOffset())
                .as("partition 0 received every publish — its ring holds real history")
                .isNotNegative();
            IntStream.range(1, DECLARED_PARTITIONS)
                     .forEach(partition -> assertUnwrittenRingReportsMinusOne(snapshot, partition));
        } finally {
            manager.close();
        }
    }

    @Test
    void walSnapshot_reportsAbsentWal_onNoWalPath() {
        var manager = streamPartitionManager(Long.MAX_VALUE, Option.none());

        try {
            createStream(manager, STREAM);
            publishOne(manager, 0);

            var view = partitionView(manager.walSnapshot(), STREAM, PARTITION);

            assertThat(view.wal().isEmpty()).as("a wall-less deployment has no WAL counters to report")
                                            .isTrue();
            assertThat(view.ringTailOffset())
                .as("the partition IS materialized and holds the published record — so the absence above is the"
                    + " no-WAL path, not a missing or empty partition")
                .isNotNegative();
        } finally {
            manager.close();
        }
    }

    // === helpers ===

    private static void assertUnwrittenRingReportsMinusOne(WalSnapshot snapshot, int partition) {
        assertThat(partitionView(snapshot, STREAM, partition).ringTailOffset())
            .as("partition %d is materialized but never written: a raw tail of 0 would satisfy any covered-from"
                + " check and silently declare a restarted-empty partition healthy", partition)
            .isEqualTo(-1L);
    }

    private static Role roleFor(String stream, int partition) {
        return stream.equals(UNHELD)
               ? Role.NONE
               : Role.OWNER;
    }

    private static void createStream(StreamPartitionManager manager, String name) {
        manager.createStream(StreamConfig.streamConfig(name))
               .onFailure(cause -> fail(cause.message()));
    }

    private static void publishOne(StreamPartitionManager manager, int i) {
        manager.publishLocal(STREAM, PARTITION, payload(i), 1000L + i)
               .onFailure(cause -> fail(cause.message()));
    }

    private static byte[] payload(int i) {
        return ("evt-" + i).getBytes(UTF_8);
    }

    private static List<String> streamNames(WalSnapshot snapshot) {
        return snapshot.streams()
                       .stream()
                       .map(StreamWalView::stream)
                       .toList();
    }

    private static List<PartitionWalView> partitionsOf(WalSnapshot snapshot, String stream) {
        return snapshot.streams()
                       .stream()
                       .filter(view -> view.stream().equals(stream))
                       .flatMap(view -> view.partitions().stream())
                       .toList();
    }

    private static PartitionWalView partitionView(WalSnapshot snapshot, String stream, int partition) {
        return partitionsOf(snapshot, stream).stream()
                                             .filter(view -> view.partition() == partition)
                                             .findFirst()
                                             .orElseThrow(() -> new AssertionError("partition not in snapshot: "
                                                                                   + stream + "/" + partition));
    }

    /// Extracted so the fold's type variable is pinned by the return type — nested directly inside
    /// `assertThat` the poly expression is ambiguous to javac.
    private static WalStats statsOf(PartitionWalView view) {
        return view.wal()
                   .fold(() -> fail("partition " + view.partition() + " must report WAL stats"), stats -> stats);
    }
}
