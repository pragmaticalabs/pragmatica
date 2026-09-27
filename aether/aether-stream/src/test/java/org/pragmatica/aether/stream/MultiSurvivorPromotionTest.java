// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
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

/// #411 as reframed on #1555 (know 17cddabf3). RF=3, min-sync 2: record X (offset 11) was acked through follower
/// F1 and record Y (offset 12) through follower F2. Replica rings are CONTIGUOUS prefixes of the owner's log, so a
/// follower that acked Y necessarily holds X as well — pinned here — and the survivor with the HIGHEST head holds
/// every acked record. The owner dies; F1 is promoted. The promotion gate must find F2 by probing every live
/// member's actual head, not by the first peer or a registry value that is blind to peer watermarks.
class MultiSurvivorPromotionTest {
    private static final String STREAM = "orders";
    private static final int PARTITION = 0;
    private static final NodeId F1 = new NodeId("f1");
    private static final NodeId F2 = new NodeId("f2");
    private static final NodeId F3 = new NodeId("f3");

    private StreamPartitionManager f1;
    private StreamPartitionManager f2;
    private StreamPartitionManager f3;

    @BeforeEach
    void setUp() {
        f1 = ring(11);
        f2 = ring(12);
        f3 = ring(8);
    }

    @AfterEach
    void tearDown() {
        f1.close();
        f2.close();
        f3.close();
    }

    /// The property the union argument rests on: a replica refuses an offset whose predecessors it lacks.
    @Test
    void appendRecovered_offsetAheadOfTheLocalPrefix_isRefused() {
        var skipped = f3.appendRecovered(STREAM, PARTITION, 12, bytes("y"), 1L, Epoch.ZERO);

        assertThat(skipped.isFailure()).isTrue();
        skipped.onFailure(cause -> assertThat(cause).isInstanceOf(StreamError.ReplicaOffsetGap.class));
        assertThat(head(f3)).isEqualTo(8L);
    }

    /// (a′) The promoted F1 (holds X) catches up from F2 (holds X and Y) although the first listed peer, F3, is
    /// behind both, and no registry value is consulted.
    @Test
    void promotion_recordsAckedThroughDifferentFollowers_promotedOwnerHoldsBoth() {
        var peers = Map.of(F3, f3, F2, f2);
        var gate = OwnerActivation.ownerActivation(F1,
                                                   (_, _) -> Option.some(StreamPartitionOwnershipValue.streamPartitionOwnershipValue(F1,
                                                                                                                                    Epoch.epoch(2, 0),
                                                                                                                                    2,
                                                                                                                                    HlcTimestamp.ZERO)),
                                                   (_, _) -> true,
                                                   Option.some((_, _) -> Promise.success(Unit.unit())),
                                                   () -> List.of(F1, F3, F2),
                                                   (target, _, _) -> Promise.success(head(peers.get(target))),
                                                   (_, _) -> head(f1),
                                                   (stream, partition, source, tail) -> copy(peers.get(source), tail),
                                                   () -> true);

        f1.placementRoleSupplier((_, _) -> ReplicaSetController.Role.OWNER);
        f1.ownerServeGate(gate::admit);

        assertThat(eventually(() -> f1.mayServeAsOwner(STREAM, PARTITION))).isTrue();
        assertThat(payloads(f1)).contains("rec-11", "rec-12");
    }

    private Promise<Long> copy(StreamPartitionManager source, long tail) {
        var from = head(f1) + 1;

        source.readLocal(STREAM, PARTITION, from, (int) (tail - from + 1))
              .unwrap()
              .forEach(event -> f1.appendRecovered(STREAM, PARTITION, event.offset(), event.data(), event.timestamp(), Epoch.ZERO)
                                  .unwrap());

        return Promise.success(tail);
    }

    /// A ring holding `rec-0 .. rec-<head>`: the shared, contiguous owner log up to that follower's watermark.
    private static StreamPartitionManager ring(int head) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        assertThat(manager.createStream(StreamConfig.streamConfig(STREAM)).isSuccess()).isTrue();

        for (var offset = 0; offset <= head; offset++) {
            assertThat(manager.appendRecovered(STREAM, PARTITION, offset, bytes("rec-" + offset), 1L, Epoch.ZERO)
                              .isSuccess()).isTrue();
        }

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

    private static byte[] bytes(String payload) {
        return payload.getBytes(StandardCharsets.UTF_8);
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
