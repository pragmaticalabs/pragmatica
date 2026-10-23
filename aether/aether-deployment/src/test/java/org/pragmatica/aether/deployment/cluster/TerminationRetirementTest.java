// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

/// Phase-3 design 12 item 4b, on the real applier: a termination authority retires a node's directive and departure record in ONE leader
/// transaction, each expecting the value it read, so they go together or not at all (B18, B19). Red when: one key is removed without the
/// other, a moved key does not refuse the whole transaction, or a node holding neither key submits anything.
class TerminationRetirementTest {
    private static final NodeId CORE = new NodeId("core-1");
    private static final NodeId GONE = new NodeId("worker-9");
    private static final LeaderValue LEADER = new LeaderValue(CORE, 1);
    private static final ActivationDirectiveKey DIRECTIVE = ActivationDirectiveKey.activationDirectiveKey(GONE);
    private static final NodeDepartureKey RECORD = new NodeDepartureKey(GONE);
    private static final NodeDepartureValue DEPARTED = new NodeDepartureValue(DepartureState.DEPARTED,
                                                                              1L,
                                                                              new ProcessIncarnation(1L, 0x99L),
                                                                              Option.some(DepartureCause.NODE_REMOVED),
                                                                              10L);

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final List<List<KVCommand<AetherKey>>> submitted = new ArrayList<>();

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> apply(List<KVCommand<AetherKey>> commands) {
        submitted.add(commands);

        return ((KVStore) store).process(((KVStore) store).createBatch((List) commands));
    }

    private void commit(KVCommand<AetherKey> command) {
        apply(List.of(command));
        submitted.clear();
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader() {
        ((KVStore) store).process(((KVStore) store).createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, LEADER))));
    }

    private void seed(AetherKey key, AetherValue value) {
        commit(new KVCommand.LeaderTransaction<>(key,
                                                 "seed-" + key,
                                                 LEADER,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(key, store.get(key), Option.some(value)))));
    }

    private TerminationRetirement retirement() {
        return TerminationRetirement.directiveAndRecord(store, commands -> Promise.success(apply(commands)));
    }

    private void leaderAndBothKeys() {
        commitLeader();
        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
        seed(RECORD, DEPARTED);
    }

    @Test
    void bothKeys_areRemoved_inOneTransaction() {
        leaderAndBothKeys();

        retirement().retire(GONE).await().onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("one batch").hasSize(1);
        assertThat(submitted.getFirst()).as("one transaction").hasSize(1);
        assertThat(((KVCommand.LeaderTransaction<?, ?>) submitted.getFirst().getFirst()).mutations()
                                                                             .stream()
                                                                             .map(mutation -> (Object) mutation.key())
                                                                             .toList()).as("the record first").containsExactly(RECORD, DIRECTIVE);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    @Test
    void aRecordThatMovedAfterTheRead_refusesTheWhole_andTheDirectiveStays() {
        leaderAndBothKeys();
        var transaction = TerminationRetirement.transactionFor(store, GONE).unwrap();

        seed(RECORD, new NodeDepartureValue(DepartureState.ADMITTED, 2L, new ProcessIncarnation(2L, 0xAAL), Option.none(), 20L));
        var results = apply(List.of(transaction));

        assertThat(results).extracting(result -> ((KVCommand.TransactionResult) result).accepted()).containsExactly(false);
        assertThat(store.get(DIRECTIVE)).as("the directive stays with the record").isNotEqualTo(Option.none());
        assertThat(store.get(RECORD).isPresent()).isTrue();
    }

    @Test
    void aDirectiveThatMovedAfterTheRead_refusesTheWhole_andTheRecordStays() {
        leaderAndBothKeys();
        var transaction = TerminationRetirement.transactionFor(store, GONE).unwrap();

        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", "10.0.0.9:9000"));
        var results = apply(List.of(transaction));

        assertThat(results).extracting(result -> ((KVCommand.TransactionResult) result).accepted()).containsExactly(false);
        assertThat(store.get(RECORD)).as("the record stays with the directive").isEqualTo(Option.some(DEPARTED));
    }

    @Test
    void aRefusal_failsTheRetirement() {
        leaderAndBothKeys();

        var outcome = TerminationRetirement.directiveAndRecord(store,
                                                               commands -> Promise.success(List.<Object>of(new KVCommand.TransactionResult("other", false))))
                                           .retire(GONE)
                                           .await();

        assertThat(outcome.isFailure()).isTrue();
    }

    @Test
    void aNodeWithNeitherKey_submitsNothing() {
        commitLeader();

        retirement().retire(GONE).await();

        assertThat(submitted).isEmpty();
    }

    /// The record alone (a directive already removed by another authority) is still retired; the transaction carries just it.
    @Test
    void aRecordAlone_isRetired() {
        commitLeader();
        seed(RECORD, DEPARTED);

        retirement().retire(GONE).await();

        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }
}
