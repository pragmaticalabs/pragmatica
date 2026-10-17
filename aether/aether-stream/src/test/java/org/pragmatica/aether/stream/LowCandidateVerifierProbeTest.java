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

/// #1555, adopted from verifier v1555's probe (assertions verbatim). The KIP-101 sequence of DivergentTailPromotionTest, but the promoted
/// candidate D is BELOW both lineages: A holds a divergent 11..15, C holds the acked 11'..12', D holds only 0..10.
/// D's overlap with each peer is 0..10 (agree), so a gate that verifies peers only against the candidate's own log
/// pulls A's divergent 11..15 as the highest source and activates -- the acked 11'..12' are lost.
/// Correct outcomes: refuse (A and C disagree above D's head), or hold C's acked lineage. Never A's tail.
class LowCandidateVerifierProbeTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId A = new NodeId("a");
    private static final NodeId C = new NodeId("c");
    private static final NodeId D = new NodeId("d");

    private final List<OwnerActivation.ActivationBlock> alarms = new CopyOnWriteArrayList<>();
    private StreamPartitionManager a;
    private StreamPartitionManager c;
    private StreamPartitionManager d;

    @BeforeEach
    void setUp() {
        a = streamPartitionManager(Long.MAX_VALUE);
        c = streamPartitionManager(Long.MAX_VALUE);
        d = streamPartitionManager(Long.MAX_VALUE);
        for (var m : List.of(a, c, d)) {
            assertThat(m.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
            publishAll(m, "common", 11);
        }
        publishAll(a, "divergent-a", 5);
        publishAll(c, "acked-b", 2);
    }

    @AfterEach
    void tearDown() {
        a.close();
        c.close();
        d.close();
    }

    /// Control: A carries the SAME acked lineage as C (no divergence). The gate must activate D holding acked-b.
    @Test
    void control_noDivergence_lowCandidateActivatesOnTheAckedLineage() {
        a.close();
        a = streamPartitionManager(Long.MAX_VALUE);
        assertThat(a.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();
        publishAll(a, "common", 11);
        publishAll(a, "acked-b", 2);
        var outcome = promote();
        assertThat(outcome).as("control: activated holding acked 11'..12'").isTrue();
        assertThat(payloads(d)).contains("acked-b-0", "acked-b-1");
    }

    @Test
    void lowCandidate_neverActivatesOnTheDivergentLineage() {
        var activated = promote();
        var held = payloads(d);
        assertThat(activated && held.stream().anyMatch(p -> p.startsWith("divergent-a")))
            .as("activated on A's unacked divergent tail, dropping C's acked 11'..12': d=%s", held)
            .isFalse();
    }

    private boolean promote() {
        var rings = Map.of(A, a, C, c, D, d);
        var record = Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(D, Epoch.epoch(0L, 3, 0), 3, HlcTimestamp.ZERO));
        var gate = OwnerActivation.ownerActivation(D,
                                                   (_, _) -> record,
                                                   (_, _) -> true,
                                                   Option.some((_, _) -> Promise.success(Unit.unit())),
                                                   () -> List.of(D, A, C),
                                                   (node, _, _) -> Promise.success(head(rings.get(node))),
                                                   (_, _) -> head(d),
                                                   (stream, partition, source, tail) -> copyFrom(rings.get(source), d, tail),
                                                   () -> true,
                                                   PromotionTestRanges.over(rings),
                                                   this::raise,
                                                   PromotionTestRanges.NEVER_ALARM);
        d.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        d.ownerServeGate(gate::admit);
        d.ownerBlockSource(gate::blockOf);
        assertThat(eventually(() -> d.mayServeAsOwner(STREAM, PARTITION) || gate.blockOf(STREAM, PARTITION).isPresent())).isTrue();

        var activated = d.mayServeAsOwner(STREAM, PARTITION);
        return activated;
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
        return manager.partitionInfo(STREAM, PARTITION).map(StreamPartitionManager.PartitionInfo::headOffset).or(-1L);
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
            assertThat(manager.publishLocal(STREAM, PARTITION, (tag + "-" + i).getBytes(StandardCharsets.UTF_8), 1L).isSuccess()).isTrue();
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
