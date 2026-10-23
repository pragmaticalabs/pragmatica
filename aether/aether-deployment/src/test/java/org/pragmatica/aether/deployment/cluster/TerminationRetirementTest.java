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
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
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

    /// One attempt only: the single-transaction behaviour, without the retry.
    private static final Retry ONE_ATTEMPT = Retry.retry()
                                                 .attempts(1)
                                                 .strategy(BackoffStrategy.fixed()
                                                                          .interval(TimeSpan.timeSpan(1).millis()));
    /// A short retry for the refused-first tests; real time, a few milliseconds per attempt.
    private static final Retry SHORT_RETRY = Retry.retry()
                                                 .attempts(5)
                                                 .strategy(BackoffStrategy.fixed()
                                                                          .interval(TimeSpan.timeSpan(5).millis()));

    /// Runs once, before the first submitted batch is applied: a concurrent writer that lands between the read and the commit.
    private Runnable beforeFirstApply = () -> {};

    private TerminationRetirement retirement() {
        return TerminationRetirement.directiveAndRecord(store, commands -> Promise.success(apply(commands)), ONE_ATTEMPT);
    }

    private TerminationRetirement retried() {
        return TerminationRetirement.directiveAndRecord(store, commands -> Promise.success(applyAfterTheInterloper(commands)), SHORT_RETRY);
    }

    private List<Object> applyAfterTheInterloper(List<KVCommand<AetherKey>> commands) {
        var interloper = beforeFirstApply;

        beforeFirstApply = () -> {};
        interloper.run();

        return apply(commands);
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
                                                               commands -> Promise.success(List.<Object>of(new KVCommand.TransactionResult("other", false))),
                                                               ONE_ATTEMPT)
                                           .retire(GONE)
                                           .await();

        assertThat(outcome.isFailure()).isTrue();
    }

    // A refused retirement is retried until it commits or nothing is left (CTO ruling 2026-10-23, P3-F review).
    // Red when: a retirement refused because the record moved, or because leadership moved, is not retried to commit both removals; or a
    // retirement whose keys another authority already removed keeps submitting.
    @Test
    void aRetirementRefusedBecauseTheRecordMoved_isRetried_andRetiresBoth() {
        leaderAndBothKeys();
        beforeFirstApply = () -> seed(RECORD, new NodeDepartureValue(DepartureState.ADMITTED, 2L, new ProcessIncarnation(2L, 0xAAL), Option.none(), 20L));

        retried().retire(GONE).await().onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("refused, then retried").hasSize(2);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aRetirementRefusedBecauseLeadershipMoved_isRetried_andRetiresBoth() {
        leaderAndBothKeys();
        beforeFirstApply = () -> ((KVStore) store).process(((KVStore) store).createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE,
                                                                                                               new LeaderValue(new NodeId("core-2"), 2)))));

        retried().retire(GONE).await().onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("refused under the old leader, then retried under the new one").hasSize(2);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    @Test
    void aRetirementWhoseKeysWereRemovedMeanwhile_stopsRetrying() {
        leaderAndBothKeys();
        beforeFirstApply = this::removeBoth;

        retried().retire(GONE).await().onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("the refused attempt only: nothing left to retire").hasSize(1);
    }

    private void removeBoth() {
        commit(new KVCommand.LeaderTransaction<>(RECORD,
                                                 "elsewhere",
                                                 LEADER,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(RECORD, store.get(RECORD), Option.none()),
                                                         new KVCommand.Mutation<>(DIRECTIVE, store.get(DIRECTIVE), Option.none()))));
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
