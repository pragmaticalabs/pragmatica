// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.file.Path;
import java.util.List;
import java.util.stream.IntStream;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.stream.provenance.ProvenanceEntry;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// I-25 (#1938, PR-6a): the provenance a catch-up is attributed to when its source vouches an empty history, by the #1569
/// §7.5.1a rule (`coversFrom_i == epoch_i`) and in the encoding an owner with a log writes.
class CommittedProvenanceTest {
    private static final Epoch E1 = Epoch.epoch(0, 1, 0);
    private static final Epoch E2 = Epoch.epoch(0, 2, 0);
    private static final Epoch E3 = Epoch.epoch(0, 3, 0);

    @TempDir
    Path ownerWal;

    /// F7: the attributed entries equal the sidecar entries an owner with a log wrote for the same epochs.
    @Test
    void attributedEntries_equalTheOwnersSidecar() {
        try (var owner = streamPartitionManager(Long.MAX_VALUE, Option.some(ownerWal))) {
            owner.createStream(StreamConfig.streamConfig("s")).onFailure(cause -> fail(cause.message()));
            publish(owner, E1, 3);
            publish(owner, E2, 2);

            var attributed = StreamPartitionManager.committedProvenance(List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E2, 3L, Option.none())), 4L);

            assertThat(attributed).isEqualTo(owner.epochHistory("s", 0).unwrap());
        }
    }

    /// A folded entry's start belongs to its `coversFrom`: it gets no entry (its range reads UNDEFINED).
    @Test
    void foldedEntry_getsNoEntry() {
        var attributed = StreamPartitionManager.committedProvenance(List.of(EpochStart.epochStart(E1, 0L, Option.none()), new EpochStart(E3, 5L, E2, Option.none())), 9L);

        assertThat(attributed).containsExactly(entry(E1, 0L));
    }

    /// An epoch whose range is empty wrote nothing and is skipped; an epoch beginning above `toOffset` attributes nothing.
    @Test
    void emptyRange_andStartsAboveTheSlice_areDropped() {
        var attributed = StreamPartitionManager.committedProvenance(List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E2, 3L, Option.none()), EpochStart.epochStart(E3, 3L, Option.none())), 2L);

        assertThat(attributed).containsExactly(entry(E1, 0L));
        assertThat(StreamPartitionManager.committedProvenance(List.of(EpochStart.epochStart(E1, 0L, Option.none()), EpochStart.epochStart(E2, 3L, Option.none()), EpochStart.epochStart(E3, 3L, Option.none())), 5L))
                .containsExactly(entry(E1, 0L), entry(E3, 3L));
        assertThat(StreamPartitionManager.committedProvenance(List.of(), 5L)).isEmpty();
    }

    private static void publish(StreamPartitionManager owner, Epoch epoch, int count) {
        IntStream.range(0, count)
                 .forEach(_ -> owner.publishLocal("s", 0, "e".getBytes(UTF_8), 1L, epoch).onFailure(cause -> fail(cause.message())));
    }

    private static ProvenanceEntry entry(Epoch epoch, long start) {
        return ProvenanceEntry.provenanceEntry(epoch, Option.none(), start);
    }
}
