// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.aether.stream.replication.ReplicaSetController;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1555 divergent-tail promotion (the KIP-101 shape). Lineage: owner `A` wrote 11..15 that were never acked,
/// then dropped; `B` became owner, caught up to 0..10 and wrote 11'..12', acked through `C`. `A` returns with its
/// divergent 11..15 (head 15). `B` then dies. The promotion gate ranks candidates by HEAD alone — no ring or WAL
/// record carries an owner epoch — so:
///
///   - if `C` is promoted, it probes `A` (head 15 > 12) and pulls `A`'s 13..15 on top of its acked 11'..12',
///     producing a log that belongs to neither lineage;
///   - if `A` is promoted, it probes `C` (12 < 15), pulls nothing, and serves its divergent 11..15: the acked
///     11'..12' are lost.
///
/// The two `@Disabled` tests hold the intended assertions. The enabled TRIPWIRE asserts TODAY'S wrong
/// behaviour, so the fix cannot land unnoticed: when it reddens, delete it and enable the two below.
class DivergentTailPromotionTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("a");
    private static final NodeId C = new NodeId("c");

    private StreamPartitionManager exOwnerA;
    private StreamPartitionManager survivorC;

    @BeforeEach
    void setUp() {
        exOwnerA = streamPartitionManager(Long.MAX_VALUE);
        survivorC = streamPartitionManager(Long.MAX_VALUE);
        assertThat(exOwnerA.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        assertThat(survivorC.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        publishAll(exOwnerA, "common", 11);
        publishAll(survivorC, "common", 11);
        publishAll(exOwnerA, "divergent-a", 5);
        publishAll(survivorC, "acked-b", 2);
    }

    @AfterEach
    void tearDown() {
        exOwnerA.close();
        survivorC.close();
    }

    @Test
    void tripwire_survivorPromoted_pullsTheDivergentTailOntoItsAckedEntries() {
        promote(C, survivorC, exOwnerA);

        assertThat(payloads(survivorC)).as("TRIPWIRE (#1555 KIP-101): the promoted survivor no longer mixes the returning node's "
                                           + "divergent tail into its log — the divergent-tail fix landed. Delete this tripwire "
                                           + "and enable the two tests below.")
                                       .containsSubsequence("acked-b-0", "acked-b-1", "divergent-a-2");
    }

    @Test
    @Disabled("#1555 KIP-101: head-only ranking; enable when promotion ranks by owner-epoch lineage (tripwire above)")
    void survivorPromoted_neverAppendsTheReturningNodesDivergentTail() {
        promote(C, survivorC, exOwnerA);

        assertThat(payloads(survivorC)).noneMatch(payload -> payload.startsWith("divergent-a"));
    }

    @Test
    @Disabled("#1555 KIP-101: head-only ranking; enable when promotion ranks by owner-epoch lineage (tripwire above)")
    void divergentNodePromoted_servesTheAckedEntries() {
        promote(A, exOwnerA, survivorC);

        assertThat(payloads(exOwnerA)).contains("acked-b-0", "acked-b-1")
                                      .noneMatch(payload -> payload.startsWith("divergent-a"));
    }

    /// Install the promotion gate on `promoted`, with `peer` as the only other live member, commit a record naming
    /// it, and drive the first append through the gate.
    private static void promote(NodeId self, StreamPartitionManager promoted, StreamPartitionManager peer) {
        var record = Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(self,
                                                                                             Epoch.epoch(3, 0),
                                                                                             3,
                                                                                             HlcTimestamp.ZERO));
        var other = self.equals(A) ? C : A;
        var gate = OwnerActivation.ownerActivation(self,
                                                   (_, _) -> record,
                                                   (_, _) -> true,
                                                   Option.some((_, _) -> Promise.success(Unit.unit())),
                                                   () -> List.of(self, other),
                                                   (_, _, _) -> Promise.success(head(peer)),
                                                   (_, _) -> head(promoted),
                                                   (stream, partition, source, tail) -> copyFrom(peer, promoted, tail),
                                                   () -> true);

        promoted.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        promoted.ownerServeGate(gate::admit);
        assertThat(eventually(() -> promoted.mayServeAsOwner(STREAM, PARTITION))).isTrue();
    }

    private static Promise<Long> copyFrom(StreamPartitionManager source, StreamPartitionManager target, long tail) {
        var from = head(target) + 1;

        source.readLocal(STREAM, PARTITION, from, (int) (tail - from + 1))
              .unwrap()
              .forEach(event -> target.appendRecovered(STREAM, PARTITION, event.offset(), event.data(), event.timestamp(), Epoch.ZERO)
                                      .unwrap());

        return Promise.success(tail);
    }

    private static long head(StreamPartitionManager manager) {
        return manager.partitionInfo(STREAM, PARTITION)
                      .map(StreamPartitionManager.PartitionInfo::headOffset)
                      .or(-1L);
    }

    private static List<String> payloads(StreamPartitionManager manager) {
        return manager.readLocal(STREAM, PARTITION, 0, 100)
                      .unwrap()
                      .stream()
                      .map(event -> new String(event.data(), StandardCharsets.UTF_8))
                      .toList();
    }

    private static void publishAll(StreamPartitionManager manager, String tag, int count) {
        for (var i = 0; i < count; i++) {
            assertThat(manager.publishLocal(STREAM, PARTITION, (tag + "-" + i).getBytes(StandardCharsets.UTF_8), 1L)
                              .isSuccess()).isTrue();
        }
    }

    private static boolean eventually(BooleanSupplier condition) {
        for (var attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }

            LockSupport.parkNanos(20_000_000L);
        }

        return false;
    }
}
