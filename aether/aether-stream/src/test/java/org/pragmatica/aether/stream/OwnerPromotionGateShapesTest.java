// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

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

/// #1555 in-JVM pins of the two ex-owner shapes the v1555 verifier found, with no failure detection involved:
/// two real [StreamPartitionManager] rings — the ex-owner `X` and the current holder `Y` — and the owner promotion
/// gate installed on `X`. Probe and catch-up read `Y`'s ring directly; the committed ownership record and the
/// fresh-view round are driven by the test.
///
///   - **Reclaim** (nl1): the record hands ownership back to `X`, whose ring stopped at 20 while `Y` holds 25. `X`
///     must not append at 20 (over `Y`'s acked 20..24); it appends only after catching up, at 25.
///   - **Zombie** (nl3): `X`'s local record still names `X`, but a newer record naming `Y` was committed while it
///     was away. The fresh-view round applies it; `X` must neither append, claim `servedByOwner`, nor serve reads.
///   - **Hand-back after a move**: `X` was activated, ownership moved to `Y` (which appended more) and came back.
///     The old activation must not be reused: `X` catches up again before appending.
class OwnerPromotionGateShapesTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId X = new NodeId("x");
    private static final NodeId Y = new NodeId("y");

    private final AtomicReference<Option<StreamPartitionOwnershipValue>> record = new AtomicReference<>(Option.none());
    private final AtomicReference<Runnable> duringRound = new AtomicReference<>(() -> {});
    private StreamPartitionManager exOwner;
    private StreamPartitionManager holder;

    @BeforeEach
    void setUp() {
        exOwner = streamPartitionManager(Long.MAX_VALUE);
        holder = streamPartitionManager(Long.MAX_VALUE);
        assertThat(exOwner.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        assertThat(holder.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        publishAll(exOwner, "pre", 20);
        publishAll(holder, "pre", 20);
        publishAll(holder, "post", 5);
        exOwner.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        exOwner.ownerWriteAdmission((_, _) -> record.get()
                                                     .map(StreamPartitionOwnershipValue::owner)
                                                     .filter(owner -> !owner.equals(X)));
        exOwner.ownerServeGate(gate()::admit);
    }

    @AfterEach
    void tearDown() {
        exOwner.close();
        holder.close();
    }

    @Test
    void reclaim_exOwnerWithShortRing_appendsOnlyAfterCatchingUpToTheHolder() {
        record.set(Option.some(ownedBy(X, 3)));

        var refused = exOwner.publishLocal(STREAM, PARTITION, bytes("heal-0"), 1L);

        assertThat(refused.isFailure()).as("no append at offset 20 over the holder's acked 20..24").isTrue();
        assertThat(eventually(() -> exOwner.publishLocal(STREAM, PARTITION, bytes("heal-0"), 1L)
                                           .isSuccess())).isTrue();
        assertThat(payloads(exOwner)).containsExactlyElementsOf(expected("heal-0"));
    }

    @Test
    void zombie_staleLocalRecordRefreshedByRound_neverActsAsOwner() {
        record.set(Option.some(ownedBy(X, 1)));
        duringRound.set(() -> record.set(Option.some(ownedBy(Y, 2))));

        assertThat(exOwner.publishLocal(STREAM, PARTITION, bytes("zombie-0"), 1L)
                          .isFailure()).isTrue();
        LockSupport.parkNanos(200_000_000L);

        assertThat(exOwner.publishLocal(STREAM, PARTITION, bytes("zombie-1"), 1L)
                          .isFailure()).as("still refused once the round revealed the newer record").isTrue();
        assertThat(exOwner.mayServeAsOwner(STREAM, PARTITION)).as("no servedByOwner claim").isFalse();
        assertThat(exOwner.readServing(STREAM, PARTITION, 0, 30)
                          .isFailure()).as("no owner read from the stale ring").isTrue();
        assertThat(payloads(exOwner)).hasSize(20);
    }

    @Test
    void handBack_afterOwnershipMovedAndReturned_catchesUpAgainBeforeAppending() {
        record.set(Option.some(ownedBy(X, 3)));
        assertThat(eventually(() -> exOwner.mayServeAsOwner(STREAM, PARTITION))).isTrue();

        record.set(Option.some(ownedBy(Y, 4)));
        publishAll(holder, "moved", 5);
        record.set(Option.some(ownedBy(X, 5)));

        assertThat(exOwner.publishLocal(STREAM, PARTITION, bytes("back-0"), 1L)
                          .isFailure()).as("the activation for term 3 is not reused for term 5").isTrue();
        assertThat(eventually(() -> exOwner.publishLocal(STREAM, PARTITION, bytes("back-0"), 1L)
                                           .isSuccess())).isTrue();
        assertThat(payloads(exOwner)).endsWith("moved-4", "back-0")
                                     .hasSize(31);
    }

    private OwnerActivation gate() {
        return OwnerActivation.ownerActivation(X,
                                               (_, _) -> record.get(),
                                               (_, _) -> true,
                                               Option.some(this::round),
                                               () -> List.of(X, Y),
                                               (_, _, _) -> Promise.success(head(holder)),
                                               (_, _) -> head(exOwner),
                                               this::catchUpFromHolder,
                                               () -> true,
                                               PromotionTestRanges.over(Map.of(X, exOwner, Y, holder)),
                                               PromotionTestRanges.NO_ALARM,
                                               PromotionTestRanges.NEVER_ALARM);
    }

    private Promise<Unit> round(String stream, int partition) {
        duringRound.get()
                   .run();

        return Promise.success(Unit.unit());
    }

    /// Copy `(local head + 1) .. tail` from the holder's ring into the ex-owner's ring at the same offsets.
    private Promise<Long> catchUpFromHolder(String stream, int partition, NodeId source, long tail) {
        var from = head(exOwner) + 1;
        var events = holder.readLocal(stream, partition, from, (int) (tail - from + 1))
                           .unwrap();

        events.forEach(event -> exOwner.appendRecovered(stream, partition, event.offset(), event.data(), event.timestamp(), Epoch.ZERO)
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

    private static List<String> expected(String last) {
        var events = new java.util.ArrayList<String>();

        java.util.stream.IntStream.range(0, 20).forEach(i -> events.add("pre-" + i));
        java.util.stream.IntStream.range(0, 5).forEach(i -> events.add("post-" + i));
        events.add(last);

        return events;
    }

    private static void publishAll(StreamPartitionManager manager, String tag, int count) {
        for (var i = 0; i < count; i++) {
            assertThat(manager.publishLocal(STREAM, PARTITION, bytes(tag + "-" + i), 1L)
                              .isSuccess()).isTrue();
        }
    }

    private static StreamPartitionOwnershipValue ownedBy(NodeId owner, long term) {
        return StreamPartitionOwnershipValue.streamPartitionOwnershipValue(owner, Epoch.epoch(0L, term, 0), term, HlcTimestamp.ZERO);
    }

    private static byte[] bytes(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    /// The first refusal starts the promotion asynchronously; poll briefly for the action to be admitted.
    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        for (var attempt = 0; attempt < 100; attempt++) {
            if (condition.getAsBoolean()) {
                return true;
            }

            LockSupport.parkNanos(20_000_000L);
        }

        return false;
    }
}
