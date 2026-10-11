// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.EpochStart;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1873: the validated consumer read (`StreamPartitionManager#readServing(..., consumerEpoch)`) checks the cursor against
/// the COMMITTED epoch starts of the partition before it serves a single event, on the one path both the colocated and the
/// forwarded consumer take.
class StreamPartitionManagerEpochReadTest {
    private static final String STREAM = "orders";
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch E1 = Epoch.epoch(1L, 1L, 1L);
    private static final Epoch E2 = Epoch.epoch(1L, 1L, 2L);

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private StreamPartitionManager manager;

    @BeforeEach
    void setUp() {
        manager = streamPartitionManager();
        manager.createStream(StreamConfig.streamConfig(STREAM));
        manager.ownershipRecords((_, _) -> record.get());
        for (var i = 0; i < 5; i++) {
            manager.publishLocal(STREAM, 0, ("r" + i).getBytes(UTF_8), i).unwrap();
        }
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// The #1873 case on the serving side: the consumer read 0..4 under E1 (cursor 5); the owner's E2 began at 3.
    @Test
    void cursorPastTheNewEpochsStart_isRefusedWithTheStartOfTheNewLineage() {
        record.set(Option.some(owned(E2, List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L)))));

        var read = manager.readServing(STREAM, 0, 5L, 10, E1);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOfSatisfying(StreamError.EpochDiverged.class, diverged -> {
            assertThat(diverged.ownerEpoch()).isEqualTo(E2);
            assertThat(diverged.resumeAt()).isEqualTo(3L);
        }));
    }

    @Test
    void cursorBelowTheNewEpochsStart_isServed_andTheConsumerAdoptsTheOwnersEpoch() {
        record.set(Option.some(owned(E2, List.of(new EpochStart(E1, 0L), new EpochStart(E2, 3L)))));

        var read = manager.readServing(STREAM, 0, 2L, 10, E1).unwrap();

        assertThat(read.ownerEpoch()).isEqualTo(E2);
        assertThat(read.events()).extracting(OffHeapRingBuffer.RawEvent::offset).containsExactly(2L, 3L, 4L);
    }

    @Test
    void aConsumerWithNoClaim_isServed_andToldTheEpoch() {
        record.set(Option.some(owned(E2, List.of(new EpochStart(E2, 0L)))));

        var read = manager.readServing(STREAM, 0, 0L, 10, Epoch.ZERO).unwrap();

        assertThat(read.ownerEpoch()).isEqualTo(E2);
        assertThat(read.events()).hasSize(5);
    }

    /// A committed record whose newest start is not for its epoch: the owner has not committed where it begins, so it is
    /// not activated for the epoch and serves nothing.
    @Test
    void anOwnerWhoseEpochHasNoCommittedStart_serveNothing() {
        record.set(Option.some(owned(E2, List.of(new EpochStart(E1, 0L)))));

        var read = manager.readServing(STREAM, 0, 0L, 10, E1);

        assertThat(read.isFailure()).isTrue();
        read.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.OwnerNotActivated.class));
    }

    /// No committed ownership record (legacy, a first owner before the leader minted one): served as before.
    @Test
    void noCommittedRecord_isServedUnvalidated() {
        var read = manager.readServing(STREAM, 0, 0L, 10, E1).unwrap();

        assertThat(read.ownerEpoch()).isEqualTo(Epoch.ZERO);
        assertThat(read.events()).hasSize(5);
    }

    private static StreamPartitionOwnershipValue owned(Epoch epoch, List<EpochStart> starts) {
        return new StreamPartitionOwnershipValue(SELF, epoch, 2L, HlcTimestamp.ZERO, List.of(SELF), 3L, false, List.of(), starts);
    }
}
