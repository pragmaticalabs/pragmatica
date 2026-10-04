// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.node.StreamFailoverAnnouncer;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// `failoverRefusalSeq` through the REAL leader writer: drive it through refusal cycles,
/// including a leader change (a second writer instance reading the same committed record) and a resolution by a
/// move (which mints a fresh record), and collect every failover event id.
class StreamRefusalSeqTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);
    private static final StreamPartitionOwnershipKey KEY = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0);

    private final AtomicReference<List<NodeId>> live = new AtomicReference<>(List.of(A, B));
    private final List<String> ids = new ArrayList<>();
    private final List<Long> refusedSeqs = new ArrayList<>();

    @Test
    void everyRefusalAndResolution_getsADistinctId_seqAdvancesOncePerRefusal_acrossLeaderChange() {
        var leader1 = writer();
        var leader2 = writer();
        var record = leader1.next("orders", 0, Option.none(), A, GENERATION, live.get()).unwrap();

        // cycle 1: owner and the only other ISR member dead -> refused; a repeated reconcile writes nothing
        live.set(List.of(C));
        record = commit(leader1, record, A);
        assertThat(leader1.next("orders", 0, Option.some(record), A, GENERATION, live.get()).isEmpty())
            .as("a still-refused partition commits nothing (no second increment)").isTrue();
        // owner returns: resolved in place
        live.set(List.of(A, C));
        record = commit(leader1, record, A);
        // cycle 2, under a NEW leader reading the committed record
        live.set(List.of(C));
        record = commit(leader2, record, A);
        live.set(List.of(A, C));
        record = commit(leader2, record, A);
        // cycle 3: the owner dies while B is live in the ISR -> moved (resolution by election; the move mints a fresh record, so the count restarts)
        var withB = record.withIsr(List.of(A, B));
        live.set(List.of(B, C));
        record = commit(leader2, withB, A);
        assertThat(record.owner()).isEqualTo(B);
        // cycle 4: the new owner and every ISR member die -> refused in the new term
        live.set(List.of(C));
        record = commit(leader1, record, B);

        assertThat(refusedSeqs).as("seq on each refused commit").containsExactly(1L, 2L, 1L);
        assertThat(ids).as("every failover event id: %s", ids).doesNotHaveDuplicates().hasSize(4 + 1);
    }

    private StreamPartitionOwnershipValue commit(IsrOwnershipWriter writer, StreamPartitionOwnershipValue before, NodeId desired) {
        var after = writer.next("orders", 0, Option.some(before), desired, GENERATION, live.get()).unwrap();

        StreamFailoverAnnouncer.transition(KEY, Option.some(before), after, live.get()).onPresent(event -> ids.add(idOf(event)));
        if (after.failoverRefused() && !before.failoverRefused()) {
            refusedSeqs.add(after.failoverRefusalSeq());
        }

        return after;
    }

    private static String idOf(OperationalEvent event) {
        return switch (event) {
            case OperationalEvent.StreamFailoverRefused refused -> refused.eventId();
            case OperationalEvent.StreamFailoverResolved resolved -> resolved.eventId();
            default -> throw new AssertionError("unexpected " + event);
        };
    }

    private IsrOwnershipWriter writer() {
        return new IsrOwnershipWriter(() -> true, () -> GENERATION, HlcClock.hlcClock(A), (_, _) -> Option.none(),
                                      (_, _) -> Option.none(), new StreamPartitionOwnershipWriter.IsrInputs() {
            @Override
            public List<NodeId> liveMembers() {
                return live.get();
            }

            @Override
            public List<NodeId> initialIsr(String stream, int partition, NodeId owner) {
                return List.of(A, B);
            }
        }, () -> Option.some(new LeaderValue(A, 1L)));
    }
}
