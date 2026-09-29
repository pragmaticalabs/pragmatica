// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1555 divergent-tail promotion (the KIP-101 shape). Lineage: owner `A` wrote 11..15 that were never acked,
/// then dropped; `B` became owner, caught up to 0..10 and wrote 11'..12', acked through `C`. `A` returns with its
/// divergent 11..15 (head 15). `B` then dies. No ring or WAL record carries an owner epoch, so HEAD alone would
/// either let `C` pull `A`'s 13..15 on top of its acked 11'..12' (a log belonging to neither lineage), or let `A`
/// serve its divergent 11..15 and lose the acked 11'..12'.
///
/// The gate's overlap verification (#1555 item 7) compares the records both hold before trusting or out-ranking a
/// peer. Either promotion is REFUSED — nothing here can tell which lineage was acknowledged — and reported once,
/// naming the partition, the divergent peer and both heads, on the alarm and on the partition status read.
class DivergentTailPromotionTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("a");
    private static final NodeId C = new NodeId("c");

    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();
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

    /// The source-side check: `C` never appends the higher peer's divergent tail.
    @Test
    void survivorPromoted_neverAppendsTheReturningNodesDivergentTail() {
        var gate = attemptPromotion(C, survivorC, exOwnerA);

        assertThat(payloads(survivorC)).noneMatch(payload -> payload.startsWith("divergent-a"))
                                       .contains("acked-b-0", "acked-b-1");
        assertThat(survivorC.mayServeAsOwner(STREAM, PARTITION)).as("refused, not activated on a partial view").isFalse();
        assertThat(gate.blockOf(STREAM, PARTITION)).isEqualTo(Option.some(divergent(A, 12, 15)));
    }

    /// The refusal: `A`, whose head is highest, is never activated over the lower peer that disagrees with it, and
    /// the stall is reported once, on the alarm and on the partition status read.
    @Test
    void divergentNodePromoted_refusesAndReportsTheDivergenceOnce() {
        var gate = attemptPromotion(A, exOwnerA, survivorC);

        for (var demand = 0; demand < 3; demand++) {
            assertThat(exOwnerA.mayServeAsOwner(STREAM, PARTITION)).as("never serves its divergent tail").isFalse();
            LockSupport.parkNanos(20_000_000L);
        }

        assertThat(alarms).containsExactly(divergent(C, 15, 12));
        assertThat(exOwnerA.ownerActivationBlock(STREAM, PARTITION)).as("partition status read")
                                                                    .isEqualTo(Option.some(divergent(C, 15, 12)));
    }

    private static OwnerActivation.ActivationBlock divergent(NodeId peer, long localHead, long peerHead) {
        return new OwnerActivation.ActivationBlock.DivergentPeer(STREAM, PARTITION, peer, localHead, peerHead);
    }

    /// Install the promotion gate on `promoted`, with `peer` as the only other live member and a committed record
    /// naming it, drive the first demand through the gate, and wait for the attempt to settle.
    private OwnerActivation attemptPromotion(NodeId self, StreamPartitionManager promoted, StreamPartitionManager peer) {
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
                                                   () -> true,
                                                   PromotionTestRanges.over(Map.of(self, promoted, other, peer)),
                                                   this::raise,
                                                   PromotionTestRanges.NEVER_ALARM);

        promoted.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        promoted.ownerServeGate(gate::admit);
        promoted.ownerBlockSource(gate::blockOf);
        assertThat(eventually(() -> promoted.mayServeAsOwner(STREAM, PARTITION) || gate.blockOf(STREAM, PARTITION)
                                                                                       .isPresent())).isTrue();

        return gate;
    }

    private Unit raise(OwnerActivation.ActivationBlock block) {
        alarms.add(block);

        return Unit.unit();
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
