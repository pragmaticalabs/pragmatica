// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.util.List;

import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController.Role;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// C5 (v1873 F5), closed by the divergent-tail repair of #1730 phase 2 (PR-B's B5): a REPLICA, or a demoted owner, whose ring still holds
/// the OLD lineage at offsets above the new epoch's start must not serve it stamped with the NEW epoch. The epoch check binds the cursor
/// to the committed record; B5 (`StreamPartitionManager#servedIfVerified`, applied on the validated read too) binds the serving copy.
///
/// The manager is wired as `AetherNode` wires it: `ownerEpochSource` and `ownershipRecords` both read the one committed record. A manager
/// wired with `ownershipRecords` alone sees `Epoch.ZERO` as the committed epoch, doubts nothing, and passes these vacuously.
class ReplicaServesUnrepairedLineageTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch E1 = Epoch.epoch(1L, 2L, 1L);

    private final java.util.concurrent.atomic.AtomicReference<Option<StreamPartitionOwnershipValue>> record = new java.util.concurrent.atomic.AtomicReference<>(Option.none());
    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        if (manager != null) {
            manager.close();
        }
    }

    @Test
    void aReplicaHoldingTheOldLineage_doesNotServeItUnderTheNewEpoch() {
        assertRefusedAsNotVerified(rereadAfterDivergence(), "old-");
    }

    /// The refusal must be the B5 gate's typed cause: any other refusal (an owner not activated, a stale epoch) would also keep the old
    /// lineage out of the answer, and would pass these tests without B5 doing anything.
    private static void assertRefusedAsNotVerified(Result<StreamPartitionManager.EpochRead> read, String oldPrefix) {
        assertThat(read.isFailure()).as("the unverified copy refuses the re-read, got %s", read).isTrue();
        read.onFailure(cause -> assertThat(cause).as("the refusal is B5's, not another cause").isInstanceOf(StreamError.ReplicaNotVerified.class));
        read.onSuccess(served -> assertThat(served.events()).noneMatch(event -> new String(event.data(), UTF_8).startsWith(oldPrefix)));
    }

    /// Control for the assertion above: a refusal for a different reason fails it.
    @Test
    void aRefusalForAnotherCause_failsTheNotVerifiedAssertion() {
        var other = new StreamError.OwnerNotActivated(STREAM, PARTITION).<StreamPartitionManager.EpochRead> result();

        assertThatThrownBy(() -> assertRefusedAsNotVerified(other, "old-")).isInstanceOf(AssertionError.class);
    }

    /// A replica holds the OLD lineage at 0..4 (not yet truncated); the committed record says E2 began at 3. A consumer at
    /// E1/cursor 5 is correctly diverged to 3, re-reads with E2, and gets whatever the replica serves.
    private Result<StreamPartitionManager.EpochRead> rereadAfterDivergence() {
        manager = streamPartitionManager();
        manager.placementRoleSupplier((_, _) -> Role.REPLICA);
        manager.ownerEpochSource((_, _) -> record.get().map(StreamPartitionOwnershipValue::ownerEpoch).or(Epoch.ZERO));
        manager.createStream(StreamConfig.streamConfig(STREAM, 1, RetentionPolicy.retentionPolicy(10_000, 1024 * 1024, 60_000), "earliest"))
               .onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 5; i++) {
            var at = i;

            manager.appendRecovered(STREAM, PARTITION, at, ("old-" + at).getBytes(UTF_8), 1000L + at)
                   .onFailure(cause -> fail("append " + at + ": " + cause.message()));
        }

        var e2 = new StreamPartitionOwnershipValue(SELF, E1, 1L, HlcTimestamp.ZERO, List.of(SELF), 1L, false, List.of(), List.of())
                     .withEpochStart(0L)
                     .restarted(3L, HlcTimestamp.ZERO);

        record.set(Option.some(e2));
        manager.ownershipRecords((_, _) -> record.get());
        assertThat(manager.readServing(STREAM, PARTITION, 5L, 10, E1).isFailure()).as("control: the cursor is diverged to 3").isTrue();

        return manager.readServing(STREAM, PARTITION, 3L, 10, e2.ownerEpoch());
    }

    @Test
    void aDemotedOwner_doesNotServeItsOldTailUnderTheNewEpoch() {
        assertRefusedAsNotVerified(demotedOwnerReread(), "x-old-");
    }

    private Result<StreamPartitionManager.EpochRead> demotedOwnerReread() {
        var role = new java.util.concurrent.atomic.AtomicReference<>(Role.OWNER);

        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.ownershipRecords((_, _) -> record.get());
        manager.ownerEpochSource((_, _) -> record.get().map(StreamPartitionOwnershipValue::ownerEpoch).or(Epoch.ZERO));
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 10; i++) {
            assertThat(manager.publishLocal(STREAM, PARTITION, ("x-old-" + i).getBytes(UTF_8), 1L).isSuccess()).isTrue();
        }

        var e2 = Epoch.epoch(1L, 2L, 2L);

        role.set(Role.REPLICA);
        record.set(Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(new NodeId("y"), e2, 2L, HlcTimestamp.ZERO, List.of(new NodeId("y")), 1L)
                                                           .withEpochStart(5L)));
        assertThat(manager.readServing(STREAM, PARTITION, 7L, 10, E1).isFailure()).as("control: the cursor is diverged to 5").isTrue();

        return manager.readServing(STREAM, PARTITION, 5L, 10, e2);
    }
}
