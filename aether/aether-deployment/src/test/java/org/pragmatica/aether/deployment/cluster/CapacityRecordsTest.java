// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityLedgerValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.CapacityReservationValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.cluster.state.kvstore.StructuredKey;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// O3-2: impl A of the [CapacityRecords] port reproduces, one for one, what `CapacityControlledLifecycle` used to do inline against
/// `CapacityReservationKey` and `CapacityLedgerKey`. Each operation is run against a real [KVStore] with a committed leader, so a
/// mutation or witness the port builds is judged by the applier, not by its shape alone.
class CapacityRecordsTest {
    private static final NodeId NODE = NodeId.nodeId("heal-7").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("heal-8").unwrap();
    private static final LeaderValue LEADER = new LeaderValue(NodeId.nodeId("core-1").unwrap(), 5L);
    private static final CapacityReservationValue DISPATCHED = new CapacityReservationValue("east", "bind", "core", CapacityReservationPhase.DISPATCHED);
    private static final CapacityReservationValue OBSERVED = new CapacityReservationValue("east", "bind", "core", CapacityReservationPhase.OBSERVED);

    private KVStore<StructuredKey, Object> store;
    private CapacityRecords records;
    private int transactions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });
        store.process(store.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
        records = CapacityRecords.overReservations((KVStore<AetherKey, AetherValue>) (KVStore<?, ?>) store);
        transactions = 0;
    }

    private boolean commit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        return commit(List.of(), mutations);
    }

    private boolean commit(List<KVCommand.ReadWitness<AetherKey>> guards, List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        var raw = new java.util.ArrayList<KVCommand.Mutation<StructuredKey, Object>>();
        var raws = new java.util.ArrayList<KVCommand.ReadWitness<StructuredKey>>();

        mutations.forEach(m -> raw.add(new KVCommand.Mutation<StructuredKey, Object>(m.key(), m.expected().map(Object.class::cast), m.replacement().map(Object.class::cast))));
        guards.forEach(g -> raws.add(new KVCommand.ReadWitness<StructuredKey>(g.key(), g.expected())));

        var command = new KVCommand.LeaderTransaction<StructuredKey, Object>(records.recordKey(NODE), "tx-" + transactions++, LEADER, raws, raw);

        return ((KVCommand.TransactionResult) store.process(store.createBatch(List.of(command))).getFirst()).accepted();
    }

    @Test
    void keys_areTheReservationAndLedgerKeysOfToday() {
        assertThat(records.recordKey(NODE)).isEqualTo(new AetherKey.CapacityReservationKey(NODE));
        assertThat(records.recordKey(NODE).asString()).isEqualTo("capacity-reservation/heal-7");
        assertThat(records.ledgerKey()).isEqualTo(AetherKey.CapacityLedgerKey.INSTANCE);
    }

    @Test
    void create_expectsAbsent_andCommitsTheReservation() {
        var mutation = records.create(NODE, DISPATCHED);

        assertThat(mutation.key()).isEqualTo(records.recordKey(NODE));
        assertThat(mutation.expected().isEmpty()).isTrue();
        assertThat(mutation.replacement().or((AetherValue) null)).isEqualTo(DISPATCHED);
        assertThat(commit(List.of(mutation))).isTrue();
        assertThat(records.reservation(NODE).or((CapacityReservationValue) null)).isEqualTo(DISPATCHED);
        assertThat(commit(List.of(records.create(NODE, OBSERVED)))).as("a second create finds it present").isFalse();
    }

    @Test
    void replace_expectsBefore_andRemove_deletesBeforeOnly() {
        assertThat(commit(List.of(records.create(NODE, DISPATCHED)))).isTrue();

        assertThat(commit(List.of(records.replace(NODE, OBSERVED, DISPATCHED)))).as("replace expects the exact before").isFalse();
        assertThat(commit(List.of(records.replace(NODE, DISPATCHED, OBSERVED)))).isTrue();
        assertThat(records.reservation(NODE).or((CapacityReservationValue) null)).isEqualTo(OBSERVED);

        var removal = records.remove(NODE, OBSERVED);

        assertThat(removal.replacement().isEmpty()).isTrue();
        assertThat(commit(List.of(records.remove(NODE, DISPATCHED)))).as("remove expects the exact before").isFalse();
        assertThat(commit(List.of(removal))).isTrue();
        assertThat(records.reservation(NODE).isEmpty()).isTrue();
        assertThat(records.holds(NODE)).isFalse();
    }

    @Test
    void put_carriesTheExpectedValueAsGiven() {
        assertThat(records.put(NODE, Option.none(), DISPATCHED).expected().isEmpty()).isTrue();
        assertThat(records.put(NODE, Option.some(DISPATCHED), OBSERVED).expected().or((AetherValue) null)).isEqualTo(DISPATCHED);
        assertThat(commit(List.of(records.put(NODE, Option.none(), DISPATCHED)))).isTrue();
        assertThat(commit(List.of(records.put(NODE, Option.some(DISPATCHED), OBSERVED)))).isTrue();
        assertThat(commit(List.of(records.put(NODE, Option.none(), DISPATCHED)))).as("expected-absent over a present value").isFalse();
    }

    @Test
    void witness_guardsAnExactValueOrAbsence() {
        var other = records.create(OTHER, DISPATCHED);

        assertThat(commit(List.of(records.witness(NODE, Option.none())), List.of(other))).as("absent, and it is absent").isTrue();
        assertThat(commit(List.of(records.witness(NODE, Option.some(DISPATCHED))), List.of(records.create(NODE, DISPATCHED)))).as("expects a value that is not there").isFalse();
        assertThat(commit(List.of(records.create(NODE, DISPATCHED)))).isTrue();
        assertThat(commit(List.of(records.witness(NODE, Option.some(DISPATCHED))), List.of(records.replace(OTHER, DISPATCHED, OBSERVED)))).isTrue();
        assertThat(commit(List.of(records.witness(NODE, Option.some(OBSERVED))), List.of(records.replace(OTHER, OBSERVED, DISPATCHED)))).as("wrong value").isFalse();
        assertThat(commit(List.of(records.witness(NODE, Option.none())), List.of(records.replace(OTHER, OBSERVED, DISPATCHED)))).as("absent, but present").isFalse();
    }

    @Test
    void reservations_listsEveryReservationAndNothingElse() {
        assertThat(records.reservations()).isEmpty();

        assertThat(commit(List.of(records.create(NODE, DISPATCHED), records.create(OTHER, OBSERVED), records.ledgerChange(Option.none(), new CapacityLedgerValue(2, 1L, true))))).isTrue();

        var listed = records.reservations();

        assertThat(listed).containsOnlyKeys(NODE, OTHER);
        assertThat(listed.get(NODE)).isEqualTo(DISPATCHED);
        assertThat(listed.get(OTHER)).isEqualTo(OBSERVED);
        assertThat(records.holds(NODE)).isTrue();
        assertThat(records.holds(NodeId.nodeId("absent-1").unwrap())).isFalse();
    }

    @Test
    void ledger_readsTheCounter_andLedgerChangeMovesItFromBeforeToAfter() {
        assertThat(records.ledger().isEmpty()).isTrue();

        var first = new CapacityLedgerValue(1, 1L, true);
        var second = new CapacityLedgerValue(0, 2L, true);

        assertThat(records.ledgerChange(Option.none(), first).key()).isEqualTo(records.ledgerKey());
        assertThat(commit(List.of(records.ledgerChange(Option.none(), first)))).isTrue();
        assertThat(records.ledger().or((CapacityLedgerValue) null)).isEqualTo(first);
        assertThat(commit(List.of(records.ledgerChange(Option.none(), second)))).as("expected-absent over a present counter").isFalse();
        assertThat(commit(List.of(records.ledgerChange(Option.some(first), second)))).isTrue();
        assertThat(records.ledger().or((CapacityLedgerValue) null)).isEqualTo(second);
    }
}
