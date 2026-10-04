// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.replication;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.node.StreamIsrAnnouncer;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamPartitionOwnershipKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamPartitionOwnershipValue;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Option;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// Feed the announcer the records the REAL leader writer produces, not
/// hand-built ones, over the ordinary paths (first mint, one replica death, owner failover, refusal, rejoin) at the
/// built-in factors RF 3 / CF 2. None may announce. Control: the genuine breach (two of three gone) announces once.
class IsrWriterToAnnouncerTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final Epoch GENERATION = Epoch.epoch(1L, 2L, 0L);
    private static final StreamPartitionOwnershipKey KEY = StreamPartitionOwnershipKey.streamPartitionOwnershipKey("orders", 0);
    private static final int CF = 2;

    private final AtomicReference<List<NodeId>> live = new AtomicReference<>(List.of(A, B, C));
    private final IsrOwnershipWriter writer = new IsrOwnershipWriter(() -> true, () -> GENERATION, HlcClock.hlcClock(A),
                                                                     (_, _) -> Option.none(), (_, _) -> Option.none(),
                                                                     new StreamPartitionOwnershipWriter.IsrInputs() {
                                                                         @Override
                                                                         public List<NodeId> liveMembers() {
                                                                             return live.get();
                                                                         }

                                                                         @Override
                                                                         public List<NodeId> initialIsr(String stream, int partition, NodeId owner) {
                                                                             return List.of(A, B, C);
                                                                         }
                                                                     }, () -> Option.some(new LeaderValue(A, 1L)));

    @Test
    void ordinaryPaths_announceNothing_atTheBuiltInFactors() {
        var first = step(Option.none(), A);
        assertThat(first.event().isEmpty()).as("first mint, placement of three").isTrue();

        live.set(List.of(A, B));
        var replicaDied = step(Option.some(first.record()), A);
        assertThat(replicaDied.record().isr()).containsExactly(A, B);
        assertThat(replicaDied.event().isEmpty()).as("one replica dies, ISR stays at CF").isTrue();

        live.set(List.of(A, B, C));
        var rejoined = step(Option.some(replicaDied.record()), A);
        assertThat(rejoined.record().fenced()).isEmpty();
        assertThat(rejoined.event().isEmpty()).as("unfence on rejoin").isTrue();

        var expanded = rejoined.record().withIsr(List.of(A, B, C));
        live.set(List.of(B, C));
        var failover = step(Option.some(expanded), B);
        assertThat(failover.record().owner()).isIn(B, C);
        assertThat(failover.record().isr()).hasSize(2);
        assertThat(failover.event().isEmpty()).as("ordinary owner failover").isTrue();
    }

    @Test
    void refusal_announcesNoIsrEvent() {
        var current = step(Option.none(), A).record();

        live.set(List.of());
        var refused = step(Option.some(current), A);

        assertThat(refused.record().failoverRefused()).isTrue();
        assertThat(refused.event().isEmpty()).as("the refusal has its own event; the ISR is unchanged").isTrue();
    }

    @Test
    void control_twoOfThreeGone_announcesTheBreachOnce_andTheRestorationOnce() {
        var current = step(Option.none(), A).record();

        live.set(List.of(A));
        var breach = step(Option.some(current), A);
        assertThat(breach.event().unwrap()).isInstanceOf(OperationalEvent.StreamIsrBelowMinimum.class);

        var settled = step(Option.some(breach.record()), A);
        assertThat(settled.event().isEmpty()).as("settled: no commit, no event").isTrue();

        live.set(List.of(A, B));
        var unfenced = step(Option.some(breach.record()), A);
        assertThat(unfenced.event().isEmpty()).as("unfence alone does not restore").isTrue();

        var expanded = unfenced.record().withIsr(List.of(A, B));
        assertThat(StreamIsrAnnouncer.transition(KEY, Option.some(unfenced.record()), expanded, CF).unwrap())
            .isInstanceOf(OperationalEvent.StreamIsrRestored.class);
    }

    private record Step(StreamPartitionOwnershipValue record, Option<OperationalEvent> event) {}

    private Step step(Option<StreamPartitionOwnershipValue> before, NodeId desired) {
        return writer.next("orders", 0, before, desired, GENERATION, live.get())
                     .map(after -> new Step(after, StreamIsrAnnouncer.transition(KEY, before, after, CF)))
                     .or(() -> new Step(before.unwrap(), Option.none()));
    }
}
