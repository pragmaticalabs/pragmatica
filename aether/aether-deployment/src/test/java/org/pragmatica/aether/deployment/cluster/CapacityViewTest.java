// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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

/// O3-3: the read half of the capacity port, over the live store and over one snapshot, and the three names a reader states its reading with. The two
/// views are asked the same questions of the same data and must answer alike; the snapshot view must keep answering from the map it was given.
class CapacityViewTest {
    private static final NodeId LIVE = NodeId.nodeId("live-1").unwrap();
    private static final NodeId OTHER = NodeId.nodeId("other-1").unwrap();
    private static final NodeId ABSENT = NodeId.nodeId("absent-1").unwrap();
    private static final LeaderValue LEADER = new LeaderValue(NodeId.nodeId("core-1").unwrap(), 5L);
    private static final CapacityReservationValue DISPATCHED = new CapacityReservationValue("east", "bind", "core", CapacityReservationPhase.DISPATCHED);
    private static final CapacityReservationValue OBSERVED = new CapacityReservationValue("east", "bind", "worker", CapacityReservationPhase.OBSERVED);
    private static final CapacityLedgerValue LEDGER = new CapacityLedgerValue(2, 3L, true);

    private KVStore<AetherKey, AetherValue> store;
    private CapacityRecords writes;
    private int transactions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        var raw = new KVStore<StructuredKey, Object>(MessageRouter.mutable(), new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        }, new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        });

        raw.process(raw.createBatch(List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER))));
        store = (KVStore<AetherKey, AetherValue>) (KVStore<?, ?>) raw;
        writes = CapacityRecords.overReservations(store);
        transactions = 0;
    }

    @SuppressWarnings("unchecked")
    private void commit(List<KVCommand.Mutation<AetherKey, AetherValue>> mutations) {
        var command = new KVCommand.LeaderTransaction<AetherKey, AetherValue>(writes.recordKey(LIVE), "tx-" + transactions++, LEADER, List.of(), mutations);
        var results = ((KVStore<StructuredKey, Object>) (KVStore<?, ?>) store).process(((KVStore<StructuredKey, Object>) (KVStore<?, ?>) store).createBatch(List.of((KVCommand<StructuredKey>) (KVCommand<?>) command)));

        assertThat(((KVCommand.TransactionResult) results.getFirst()).accepted()).isTrue();
    }

    private void populate() {
        commit(List.of(writes.create(LIVE, DISPATCHED), writes.create(OTHER, OBSERVED), writes.ledgerChange(Option.none(), LEDGER)));
    }

    private Map<AetherKey, AetherValue> snapshot() {
        return new HashMap<>(store.snapshot());
    }

    @Test
    void bothViews_answerAlike_overTheSameData() {
        populate();

        for (var view : List.of(CapacityView.overStore(store), CapacityView.overSnapshot(snapshot()))) {
            assertThat(view.reservation(LIVE).or((CapacityReservationValue) null)).isEqualTo(DISPATCHED);
            assertThat(view.reservation(OTHER).or((CapacityReservationValue) null)).isEqualTo(OBSERVED);
            assertThat(view.reservation(ABSENT).isEmpty()).isTrue();
            assertThat(view.holds(LIVE)).isTrue();
            assertThat(view.holds(ABSENT)).isFalse();
            assertThat(view.reservations()).containsOnlyKeys(LIVE, OTHER);
            assertThat(view.reservedNodes()).containsExactlyInAnyOrder(LIVE, OTHER);
            assertThat(view.ledger().or((CapacityLedgerValue) null)).isEqualTo(LEDGER);
            assertThat(view.hasLedger()).isTrue();
        }
    }

    @Test
    void emptyData_reportsNothing_inBothViews() {
        for (var view : List.of(CapacityView.overStore(store), CapacityView.overSnapshot(snapshot()))) {
            assertThat(view.reservations()).isEmpty();
            assertThat(view.reservedNodes()).isEmpty();
            assertThat(view.ledger().isEmpty()).isTrue();
            assertThat(view.hasLedger()).isFalse();
            assertThat(view.holds(LIVE)).isFalse();
        }
    }

    @Test
    void liveFreedAbsent_stateOneReadingEach_underImplA() {
        populate();

        var view = CapacityView.overStore(store);

        assertThat(view.isLive(LIVE)).isTrue();
        assertThat(view.isAbsent(LIVE)).isFalse();
        assertThat(view.isFreed(LIVE)).as("nothing is freed under impl A").isFalse();
        assertThat(view.isLive(ABSENT)).isFalse();
        assertThat(view.isAbsent(ABSENT)).isTrue();
        assertThat(view.isFreed(ABSENT)).isFalse();
    }

    @Test
    void snapshotView_keepsAnsweringFromItsMap_afterTheStoreMoves() {
        populate();

        var frozen = CapacityView.overSnapshot(snapshot());
        var live = CapacityView.overStore(store);

        commit(List.of(writes.remove(LIVE, DISPATCHED), writes.ledgerChange(Option.some(LEDGER), new CapacityLedgerValue(1, 4L, true))));

        assertThat(frozen.holds(LIVE)).as("the snapshot still has it").isTrue();
        assertThat(frozen.ledger().or((CapacityLedgerValue) null)).isEqualTo(LEDGER);
        assertThat(live.holds(LIVE)).as("the live view sees the removal").isFalse();
        assertThat(live.ledger().or((CapacityLedgerValue) null).allocated()).isEqualTo(1);
    }

    @Test
    void witness_ofAView_isTheValueItHolds_orAbsence() {
        populate();

        var witness = CapacityView.overSnapshot(snapshot()).witness(LIVE);
        var absent = CapacityView.overSnapshot(snapshot()).witness(ABSENT);

        assertThat(witness.key()).isEqualTo(new AetherKey.CapacityReservationKey(LIVE));
        assertThat(witness.expected().or((Object) null)).isEqualTo(DISPATCHED);
        assertThat(absent.key()).isEqualTo(new AetherKey.CapacityReservationKey(ABSENT));
        assertThat(absent.expected().isEmpty()).isTrue();
        assertThat(CapacityView.overStore(store).witness(OTHER).expected().or((Object) null)).isEqualTo(OBSERVED);
    }

    @Test
    void witness_ofAValueReadEarlier_carriesThatValue() {
        var carried = CapacityView.overStore(store).witness(LIVE, Option.some(OBSERVED));

        assertThat(carried.key()).isEqualTo(new AetherKey.CapacityReservationKey(LIVE));
        assertThat(carried.expected().or((Object) null)).isEqualTo(OBSERVED);
        assertThat(CapacityView.overStore(store).witness(LIVE, Option.none()).expected().isEmpty()).isTrue();
    }

    @Test
    void forBuilding_buildsTheSameWritesAsAStoreBackedPort_andReadsNothing() {
        var building = CapacityRecords.forBuilding();

        assertThat(building.create(LIVE, DISPATCHED)).isEqualTo(writes.create(LIVE, DISPATCHED));
        assertThat(building.remove(LIVE, DISPATCHED)).isEqualTo(writes.remove(LIVE, DISPATCHED));
        assertThat(building.replace(LIVE, DISPATCHED, OBSERVED)).isEqualTo(writes.replace(LIVE, DISPATCHED, OBSERVED));
        assertThat(building.ledgerChange(Option.some(LEDGER), LEDGER)).isEqualTo(writes.ledgerChange(Option.some(LEDGER), LEDGER));
        populate();
        assertThat(building.holds(LIVE)).as("a builder over no store sees nothing").isFalse();
    }
}
