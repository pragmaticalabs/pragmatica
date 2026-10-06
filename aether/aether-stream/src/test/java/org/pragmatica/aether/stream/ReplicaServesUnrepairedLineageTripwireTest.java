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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// C5 (v1873 F5), a stated limit of #1873 that the divergent-tail repair of #1730 phase 2 (PR-B) has to close: a REPLICA whose ring
/// still holds the OLD lineage at offsets above the new epoch's start serves it stamped with the NEW epoch. The check binds the
/// cursor to the committed record, never the serving ring to the lineage it claims.
///
/// Ruling (CTO, 2026-10-04): the fix is PR-B's B5 (B owns replica verification); both PRs merge before the cloud runs, so this is not a
/// shipped limit. Whichever of B and C lands SECOND flips these tripwires to the real assertions.
///
/// Measured on a local merge of PR-B (ad19bbe5c) and this PR: still RED, because PR-B's `unverifiedReplicas` is set only when a
/// ring is materialized as a REPLICA with a recovered tail, not when a live owner is demoted or the committed epoch advances.
///
/// Per the workspace rule, a test that cannot pass yet is an ENABLED TRIPWIRE, not a disabled one: the first test asserts the
/// CURRENT behaviour and reddens the moment the repair lands, with the instruction to delete it and enable the real assertion.
class ReplicaServesUnrepairedLineageTripwireTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId SELF = new NodeId("self");
    private static final Epoch E1 = Epoch.epoch(1L, 2L, 1L);

    private StreamPartitionManager manager;

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /// TRIPWIRE: asserts the limit. When this goes red the replica no longer serves the old lineage under the new epoch: delete
    /// this test, enable the one below, and remove the `[limit: ...]` for the unrepaired replica from `guarantees.md`.
    @Test
    void currently_aReplicaServesItsOldLineageUnderTheNewEpoch_untilTheRepairVerifiesIt() {
        var served = rereadAfterDivergence();

        assertThat(served).as("B5 landed: flip me. Delete this tripwire, enable `aReplicaHoldingTheOldLineage_doesNotServeItUnderTheNewEpoch` "
                              + "and `aDemotedOwner_doesNotServeItsOldTailUnderTheNewEpoch`, and remove the [limit: ... closed by PR-B B5] from guarantees.md")
                          .anyMatch(payload -> payload.startsWith("old-"));
    }

    /// The real assertion. Disabled, not absent: while the tripwire above is green this one would FAIL, and it must not pass
    /// vacuously either.
    @Disabled("C5: needs the replica's verified-for-epoch state from the divergent-tail repair (#1730 phase 2, PR-B); see the tripwire above")
    @Test
    void aReplicaHoldingTheOldLineage_doesNotServeItUnderTheNewEpoch() {
        assertThat(rereadAfterDivergence()).noneMatch(payload -> payload.startsWith("old-"));
    }

    /// A replica holds the OLD lineage at 0..4 (not yet truncated); the committed record says E2 began at 3. A consumer at
    /// E1/cursor 5 is correctly diverged to 3, re-reads with E2, and gets whatever the replica serves.
    private List<String> rereadAfterDivergence() {
        manager = streamPartitionManager();
        manager.placementRoleSupplier((_, _) -> Role.REPLICA);
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

        manager.ownershipRecords((_, _) -> Option.some(e2));
        assertThat(manager.readServing(STREAM, PARTITION, 5L, 10, E1).isFailure()).as("control: the cursor is diverged to 3").isTrue();

        return manager.readServing(STREAM, PARTITION, 3L, 10, e2.ownerEpoch())
                      .map(read -> read.events()
                                       .stream()
                                       .map(event -> new String(event.data(), UTF_8))
                                       .toList())
                      .or(List.of());
    }

    /// TRIPWIRE (the demoted-owner shape, `C5DemotedOwnerServesOldLineageProbeTest` on the B+C merge): X owned E1 and wrote 0..9, then
    /// ownership moved to Y (E2, started at 5). X is now a REPLICA that has not been repaired.
    @Test
    void currently_aDemotedOwnerServesItsOldTailUnderTheNewEpoch_untilTheRepairVerifiesIt() {
        assertThat(demotedOwnerReread()).as("B5 landed: flip me. Delete this tripwire and enable `aDemotedOwner_doesNotServeItsOldTailUnderTheNewEpoch`")
                                        .anyMatch(payload -> payload.startsWith("x-old-"));
    }

    @Disabled("C5: needs PR-B's B5 (replica verified-for-epoch gating); see the tripwire above")
    @Test
    void aDemotedOwner_doesNotServeItsOldTailUnderTheNewEpoch() {
        assertThat(demotedOwnerReread()).noneMatch(payload -> payload.startsWith("x-old-"));
    }

    private List<String> demotedOwnerReread() {
        var role = new java.util.concurrent.atomic.AtomicReference<>(Role.OWNER);
        var record = new java.util.concurrent.atomic.AtomicReference<Option<StreamPartitionOwnershipValue>>(Option.none());

        manager = streamPartitionManager(Long.MAX_VALUE);
        manager.placementRoleSupplier((_, _) -> role.get());
        manager.ownershipRecords((_, _) -> record.get());
        manager.createStream(StreamConfig.streamConfig(STREAM)).onFailure(cause -> fail(cause.message()));
        for (var i = 0; i < 10; i++) {
            assertThat(manager.publishLocal(STREAM, PARTITION, ("x-old-" + i).getBytes(UTF_8), 1L).isSuccess()).isTrue();
        }

        var e2 = Epoch.epoch(1L, 2L, 2L);

        role.set(Role.REPLICA);
        record.set(Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(new NodeId("y"), e2, 2L, HlcTimestamp.ZERO, List.of(new NodeId("y")), 1L)
                                                           .withEpochStart(5L)));
        assertThat(manager.readServing(STREAM, PARTITION, 7L, 10, E1).isFailure()).as("control: the cursor is diverged to 5").isTrue();

        return manager.readServing(STREAM, PARTITION, 5L, 10, e2)
                      .map(read -> read.events().stream().map(event -> new String(event.data(), UTF_8)).toList())
                      .or(List.of());
    }
}
