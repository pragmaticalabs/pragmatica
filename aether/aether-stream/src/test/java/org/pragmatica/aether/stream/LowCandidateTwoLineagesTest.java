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

/// #1555, found by v1555: a promoted candidate `D` that LAGS both lineages. `A` holds common 0..10 plus a divergent
/// 11..15, `C` holds common 0..10 plus the acked 11'..12', and `D` holds common 0..10 only. Compared against `D`'s
/// own log, both peers agree (their shared window ends at `D`'s head 10), so without a pairwise check `D` pulls
/// `A`'s divergent 11..15 over `C`'s acknowledged 11'..12'. The source is compared with every other responder, so
/// the promotion is refused and reported, naming both peers. Peers that merely lag differently along ONE lineage
/// never disagree, because only offsets both hold are compared.
class LowCandidateTwoLineagesTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("a");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");

    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();
    private StreamPartitionManager ringA;
    private StreamPartitionManager ringC;
    private StreamPartitionManager candidate;

    @BeforeEach
    void setUp() {
        ringA = ring();
        ringC = ring();
        candidate = ring();
        publishAll(ringA, "common", 11);
        publishAll(ringC, "common", 11);
        publishAll(candidate, "common", 11);
    }

    @AfterEach
    void tearDown() {
        ringA.close();
        ringC.close();
        candidate.close();
    }

    @Test
    void lowCandidate_twoLineagesAboveIt_refusesAndReportsBothPeers() {
        publishAll(ringA, "divergent-a", 5);
        publishAll(ringC, "acked-b", 2);

        var gate = attemptPromotion();

        assertThat(candidate.mayServeAsOwner(STREAM, PARTITION)).as("refused, never activated").isFalse();
        assertThat(payloads(candidate)).noneMatch(payload -> payload.startsWith("divergent-a"));
        assertThat(gate.blockOf(STREAM, PARTITION)).isEqualTo(Option.some(new OwnerActivation.ActivationBlock.DivergentPeers(STREAM,
                                                                                                                             PARTITION,
                                                                                                                             A,
                                                                                                                             15,
                                                                                                                             C,
                                                                                                                             12)));
        assertThat(alarms).hasSize(1);
    }

    /// Positive control: the highest peer carries the acked lineage, so the candidate activates holding it.
    @Test
    void lowCandidate_highestPeerCarriesTheAckedLineage_activatesWithIt() {
        publishAll(ringA, "acked-b", 5);
        publishAll(ringC, "acked-b", 2);

        var gate = attemptPromotion();

        assertThat(candidate.mayServeAsOwner(STREAM, PARTITION)).isTrue();
        assertThat(payloads(candidate)).endsWith("acked-b-0", "acked-b-1", "acked-b-2", "acked-b-3", "acked-b-4");
        assertThat(gate.blockOf(STREAM, PARTITION)).isEqualTo(Option.none());
        assertThat(alarms).isEmpty();
    }

    /// Peers lagging differently along ONE lineage — one of them below the candidate — never falsely disagree.
    @Test
    void peersLaggingDifferentlyOnOneLineage_neverDisagree() {
        publishAll(ringA, "acked-b", 5);
        ringC.close();
        ringC = ring();
        publishAll(ringC, "common", 7);

        var gate = attemptPromotion();

        assertThat(candidate.mayServeAsOwner(STREAM, PARTITION)).isTrue();
        assertThat(payloads(candidate)).hasSize(16)
                                       .endsWith("acked-b-4");
        assertThat(gate.blockOf(STREAM, PARTITION)).isEqualTo(Option.none());
    }

    /// Install the promotion gate on the candidate `D`, with `A` and `C` as the other live members and a committed
    /// record naming `D`, drive the first demand through the gate, and wait for the attempt to settle.
    private OwnerActivation attemptPromotion() {
        var record = Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(D,
                                                                                             Epoch.epoch(3, 0),
                                                                                             3,
                                                                                             HlcTimestamp.ZERO));
        var peers = Map.of(A, ringA, C, ringC);
        var gate = OwnerActivation.ownerActivation(D,
                                                   (_, _) -> record,
                                                   (_, _) -> true,
                                                   Option.some((_, _) -> Promise.success(Unit.unit())),
                                                   () -> List.of(D, A, C),
                                                   (target, _, _) -> Promise.success(head(peers.get(target))),
                                                   (_, _) -> head(candidate),
                                                   (stream, partition, source, tail) -> copyFrom(peers.get(source), tail),
                                                   () -> true,
                                                   PromotionTestRanges.over(Map.of(D, candidate, A, ringA, C, ringC)),
                                                   this::raise,
                                                   PromotionTestRanges.NEVER_ALARM);

        candidate.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        candidate.ownerServeGate(gate::admit);
        candidate.ownerBlockSource(gate::blockOf);
        assertThat(eventually(() -> candidate.mayServeAsOwner(STREAM, PARTITION) || gate.blockOf(STREAM, PARTITION)
                                                                                        .isPresent())).isTrue();

        return gate;
    }

    private Unit raise(OwnerActivation.ActivationBlock block) {
        alarms.add(block);

        return Unit.unit();
    }

    private Promise<Long> copyFrom(StreamPartitionManager source, long tail) {
        var from = head(candidate) + 1;

        source.readLocal(STREAM, PARTITION, from, (int) (tail - from + 1))
              .unwrap()
              .forEach(event -> candidate.appendRecovered(STREAM, PARTITION, event.offset(), event.data(), event.timestamp(), Epoch.ZERO)
                                         .unwrap());

        return Promise.success(tail);
    }

    private static StreamPartitionManager ring() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        assertThat(manager.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();

        return manager;
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
