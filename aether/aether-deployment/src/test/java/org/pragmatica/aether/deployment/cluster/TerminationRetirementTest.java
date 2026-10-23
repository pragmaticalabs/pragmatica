// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

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
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.awaitility.Awaitility.await;

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

    /// The node's stop signal for the retried retirements; every attempt reads it once, so `stopChecks` counts the attempts the retry ran.
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicInteger stopChecks = new AtomicInteger();

    private boolean isStopping() {
        stopChecks.incrementAndGet();

        return stopping.get();
    }

    /// Runs once, before the first submitted batch is applied: a concurrent writer that lands between the read and the commit.
    private Runnable beforeFirstApply = () -> {};

    private TerminationRetirement retirement() {
        return TerminationRetirement.directiveAndRecord(store, commands -> Promise.success(apply(commands)), ONE_ATTEMPT, () -> false);
    }

    private TerminationRetirement retried() {
        return TerminationRetirement.directiveAndRecord(store, commands -> Promise.success(applyAfterTheInterloper(commands)), SHORT_RETRY, this::isStopping);
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
                                                               ONE_ATTEMPT,
                                                               () -> false)
                                           .retire(GONE)
                                           .await();

        assertThat(outcome.isFailure()).isTrue();
    }

    // A refused retirement is retried only for the identity first read (CTO rulings c0b4ae539 (2), 4c533461f).
    // Red when: a retirement refused because the record was re-admitted (ADMITTED, a newer epoch) is retried, or removes the record or the
    // directive; a retirement refused because leadership moved is not retried to commit both removals; a retirement whose keys another
    // authority already removed keeps submitting; a retirement whose record alone was removed meanwhile does not retire the directive; or a
    // retirement keeps retrying once the node stops.
    @Test
    void aRetirementRefusedBecauseTheRecordWasReadmitted_endsTheRetry_andKeepsTheRecordAndTheDirective() {
        leaderAndBothKeys();
        var readmitted = new NodeDepartureValue(DepartureState.ADMITTED, 2L, new ProcessIncarnation(2L, 0xAAL), Option.none(), 20L);

        beforeFirstApply = () -> seed(RECORD, readmitted);

        var outcome = retried().retire(GONE).await();

        assertThat(outcome.isFailure()).as("the retry ended: the record is no longer the one first read").isTrue();
        assertThat(submitted).as("the refused attempt only").hasSize(1);
        assertThat(store.get(RECORD)).as("the re-admitted record stays").isEqualTo(Option.some(readmitted));
        assertThat(store.get(DIRECTIVE).isPresent()).as("and so does the directive").isTrue();
    }

    // T6: each identity axis on its own (CTO ruling 5ca85aa4e). Red when the retry goes on over a DEPARTED record of a newer epoch with the same
    // incarnation (the epoch compare dropped), of the same epoch with another incarnation (the incarnation compare dropped), or over an ADMITTED
    // record at the first read's epoch and incarnation (the state check dropped).
    @Test
    void aRetryEnds_onADepartedRecordOfANewerEpoch_withTheSameIncarnation() {
        endsOnARecordThatMovedTo(new NodeDepartureValue(DepartureState.DEPARTED, 2L, DEPARTED.process(), Option.some(DepartureCause.NODE_REMOVED), 20L));
    }

    @Test
    void aRetryEnds_onADepartedRecordOfTheSameEpoch_withAnotherIncarnation() {
        endsOnARecordThatMovedTo(new NodeDepartureValue(DepartureState.DEPARTED, 1L, new ProcessIncarnation(2L, 0xAAL), Option.some(DepartureCause.NODE_REMOVED), 20L));
    }

    @Test
    void aRetryEnds_onAnAdmittedRecordAtTheFirstReadsEpochAndIncarnation() {
        endsOnARecordThatMovedTo(new NodeDepartureValue(DepartureState.ADMITTED, 1L, DEPARTED.process(), Option.none(), 20L));
    }

    private void endsOnARecordThatMovedTo(NodeDepartureValue moved) {
        leaderAndBothKeys();
        beforeFirstApply = () -> seed(RECORD, moved);

        var outcome = retried().retire(GONE).await();

        assertThat(outcome.isFailure()).as("the retry ended").isTrue();
        assertThat(submitted).as("the refused attempt only").hasSize(1);
        assertThat(stopChecks).as("T7: the ending cause is terminal: one attempt after the refused one, not the whole retry budget").hasValue(2);
        assertThat(store.get(RECORD)).as("the moved record stays").isEqualTo(Option.some(moved));
        assertThat(store.get(DIRECTIVE).isPresent()).as("and so does the directive").isTrue();
    }

    // Absent record (CTO ruling 5ca85aa4e): a re-admission writes its record and its directive in separate transactions, so an absent record
    // beside a new directive may be a re-admission. Red when the retry goes on, or removes that directive.
    @Test
    void aRetryEnds_onAnAbsentRecordBesideADirectiveThatIsNotTheOneFirstRead() {
        leaderAndBothKeys();
        var reassigned = ActivationDirectiveValue.worker("east-w-0", "10.0.0.9:9000");

        beforeFirstApply = () -> {
            removeTheRecord();
            seed(DIRECTIVE, reassigned);
        };

        var outcome = retried().retire(GONE).await();

        assertThat(outcome.isFailure()).as("the retry ended").isTrue();
        assertThat(submitted).as("the refused attempt only").hasSize(1);
        assertThat(store.get(DIRECTIVE)).as("the new directive stays").isEqualTo(Option.some(reassigned));
    }

    // T7: Readmitted and Stopped are terminal causes, which the retry honours without retrying.
    @Test
    void theEndingCauses_areTerminal() {
        assertThat(TerminationRetirement.Readmitted.FACTORY.apply(GONE).isTerminal()).isTrue();
        assertThat(TerminationRetirement.Stopped.FACTORY.apply(GONE).isTerminal()).isTrue();
    }

    @Test
    void aRetirementWhoseRecordAloneWasRemovedMeanwhile_isRetried_andRetiresTheDirective() {
        leaderAndBothKeys();
        beforeFirstApply = this::removeTheRecord;

        retried().retire(GONE).await().onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("refused, then retried on the directive alone").hasSize(2);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
    }

    private void removeTheRecord() {
        commit(new KVCommand.LeaderTransaction<>(RECORD,
                                                 "elsewhere",
                                                 LEADER,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(RECORD, store.get(RECORD), Option.none()))));
    }

    @Test
    void aRetirementOfAStoppingNode_endsAfterTheRefusedAttempt() {
        leaderAndBothKeys();
        beforeFirstApply = () -> {
            moveLeadership();
            stopping.set(true);
        };

        var outcome = retried().retire(GONE).await();

        assertThat(outcome.isFailure()).as("the retry ended on the stop").isTrue();
        assertThat(submitted).as("no attempt after the stop").hasSize(1);
        assertThat(stopChecks).as("T7: Stopped is terminal: one attempt after the refused one, not the whole retry budget").hasValue(2);
        assertThat(store.get(RECORD)).isEqualTo(Option.some(DEPARTED));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void moveLeadership() {
        ((KVStore) store).process(((KVStore) store).createBatch(List.of(new KVCommand.Put(LeaderKey.INSTANCE, new LeaderValue(new NodeId("core-2"), 2)))));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void aRetirementRefusedBecauseLeadershipMoved_isRetried_andRetiresBoth() {
        leaderAndBothKeys();
        beforeFirstApply = this::moveLeadership;

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

    /// T5: the production retirement retries a refused attempt (the production policy, `UNTIL_RETIRED`, real backoff from 500 ms). Red when
    /// the production policy makes one attempt only, or the retry does not commit both removals.
    @Test
    void theProductionRetirement_retriesARefusedAttempt_andRetiresBoth() {
        leaderAndBothKeys();
        beforeFirstApply = this::moveLeadership;

        TerminationRetirement.production(store, commands -> Promise.success(applyAfterTheInterloper(commands)), () -> false, OperatorWarningSink.logOnly())
                             .retire(GONE)
                             .await(TimeSpan.timeSpan(30).seconds())
                             .onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("refused under the old leader, then retried by the production policy").hasSize(2);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    // The operator event (owner rule 2026-10-03; CTO ruling 5ca85aa4e): a retirement still refused after its bound raises one overdue
    // warning on entering that state and one resolved warning on leaving it. Red when the overdue warning is not raised, is raised per retry,
    // or is raised before the bound; or when the resolved warning is not raised on the commit or on a terminal end.
    private final List<OperatorWarning> warnings = new CopyOnWriteArrayList<>();
    private final AtomicLong clockNs = new AtomicLong();
    private int refusalsLeft;
    private Runnable afterTheLastRefusal = () -> {};

    private static final Retry MANY_ATTEMPTS = Retry.retry()
                                                   .attempts(20)
                                                   .strategy(BackoffStrategy.fixed()
                                                                            .interval(TimeSpan.timeSpan(1).millis()));

    /// Refuses the first `refusals` attempts, a minute apart on the retirement's clock, then applies normally.
    private TerminationRetirement refusedFor(int refusals) {
        refusalsLeft = refusals;

        return TerminationRetirement.directiveAndRecord(store,
                                                        this::refuseThenApply,
                                                        MANY_ATTEMPTS,
                                                        this::isStopping,
                                                        TerminationRetirement.Overdue.overdue(OperatorWarningSink.handingOffTo(warnings::add),
                                                                                              clockNs::get,
                                                                                              TimeSpan.timeSpan(2).minutes()));
    }

    private Promise<List<Object>> refuseThenApply(List<KVCommand<AetherKey>> commands) {
        if (refusalsLeft-- > 0) {
            clockNs.addAndGet(TimeSpan.timeSpan(1).minutes().nanos());
            if (refusalsLeft == 0) {
                afterTheLastRefusal.run();
            }

            return Promise.success(List.of(new KVCommand.TransactionResult(((KVCommand.LeaderTransaction<?, ?>) commands.getFirst()).transactionId(), false)));
        }

        return Promise.success(apply(commands));
    }

    private List<OperatorWarningCode> codesRaised() {
        return warnings.stream().map(OperatorWarning::code).toList();
    }

    @Test
    void aRetirementRefusedPastItsBound_raisesOneOverdueWarning_andOneResolvedWarningWhenItCommits() {
        leaderAndBothKeys();

        refusedFor(4).retire(GONE).await().onFailure(cause -> fail(cause.message()));

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 2);
        assertThat(codesRaised()).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE, OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED);
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    @Test
    void aRetirementThatEndsTerminallyAfterItsBound_raisesTheResolvedWarning() {
        leaderAndBothKeys();
        var readmitted = new NodeDepartureValue(DepartureState.ADMITTED, 2L, new ProcessIncarnation(2L, 0xAAL), Option.none(), 20L);

        afterTheLastRefusal = () -> seed(RECORD, readmitted);
        var outcome = refusedFor(3).retire(GONE).await();

        assertThat(outcome.isFailure()).as("ended: the record was re-admitted").isTrue();
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 2);
        assertThat(codesRaised()).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE, OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED);
        assertThat(store.get(RECORD)).isEqualTo(Option.some(readmitted));
    }

    @Test
    void aRetirementRefusedWithinItsBound_raisesNothing() {
        leaderAndBothKeys();

        refusedFor(1).retire(GONE).await().onFailure(cause -> fail(cause.message()));

        assertThat(warnings).isEmpty();
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
