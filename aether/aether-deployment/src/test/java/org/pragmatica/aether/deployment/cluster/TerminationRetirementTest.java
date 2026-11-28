// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import io.netty.buffer.ByteBuf;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.cluster.fsm2.Admission;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.DirectiveReceiptKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeDepartureKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.DirectiveReceiptValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeDepartureValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
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
    private static final DirectiveReceiptKey RECEIPT = new DirectiveReceiptKey(GONE);
    private static final DirectiveReceiptValue RECEIPT_VALUE = DirectiveReceiptValue.directiveReceiptValue("worker");
    private static final ProcessIncarnation READMITTED_PROCESS = new ProcessIncarnation(2L, 0xAAL);
    /// A live re-admitted node: ADMITTED at epoch 2, the shape of the three failure inputs of the design's 3.4.
    private static final NodeDepartureValue ADMITTED_AT_2 = new NodeDepartureValue(DepartureState.ADMITTED, 2L, READMITTED_PROCESS, Option.none(), 20L);
    /// The departure of the re-admitted node, recorded by a leader that does not hold its admission: the process of `Admission.UNKNOWN`.
    private static final NodeDepartureValue DEPARTED_AT_2 = new NodeDepartureValue(DepartureState.DEPARTED, 2L, Admission.UNKNOWN.process(), Option.some(DepartureCause.NODE_REMOVED), 30L);
    private static final NodeDepartureValue ADMITTED_AT_3 = new NodeDepartureValue(DepartureState.ADMITTED, 3L, new ProcessIncarnation(3L, 0xBBL), Option.none(), 40L);
    /// The first departure of a node that never departed: epoch 0, as `Departures.epochOf` counts an absent record.
    private static final NodeDepartureValue DEPARTED_AT_0 = new NodeDepartureValue(DepartureState.DEPARTED, 0L, DEPARTED.process(), Option.some(DepartureCause.NODE_REMOVED), 30L);

    private final KVStore<AetherKey, AetherValue> store = new KVStore<>(MessageRouter.mutable(), new Serializer() {
        @Override public <T> void write(ByteBuf buffer, T value) {}
    }, new Deserializer() {
        @Override public <T> T read(ByteBuf buffer) { return null; }
    });
    private final List<List<KVCommand<AetherKey>>> submitted = new ArrayList<>();

    private List<Object> apply(List<KVCommand<AetherKey>> commands) {
        ReceiptInertness.assertInert(commands);
        submitted.add(commands);

        return process(commands);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private List<Object> process(List<KVCommand<AetherKey>> commands) {
        return ((KVStore) store).process(((KVStore) store).createBatch((List) commands));
    }

    /// A fixture write: it goes straight to the store, never through `apply`, so the receipt inertness check (which guards what production
    /// submits) does not see the receipts the tests seed.
    private void commit(KVCommand<AetherKey> command) {
        process(List.of(command));
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

    /// The bound of every retirement await. An attempt that throws on a virtual thread (a retiring unit with no removal, `getFirst` of an empty
    /// list) never completes its promise, and an unbounded `await()` turns that into a hang the suite can only kill by timeout. With the bound
    /// the await returns a failure, so the mutant fails on the test's own assertion. A retirement here completes in milliseconds (the retries
    /// back off 1 to 5 ms); the bound is a guess at "far more than needed, far less than a build timeout".
    private static final TimeSpan BOUND = TimeSpan.timeSpan(2).seconds();

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

    /// Test-local hooks keyed by the n-th read of the stop signal. The signal is read once per attempt, before the rule is asked (3.4 of the
    /// design), so a hook at n is a write that lands before the n-th attempt, the first included, whether or not that attempt submits.
    private final Map<Integer, Runnable> beforeAttempt = new HashMap<>();

    private boolean isStopping() {
        beforeAttempt.getOrDefault(stopChecks.incrementAndGet(), () -> {}).run();

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

    /// Batches that the retirement itself submitted. `seed` clears `submitted`, so a hook that writes would hide an earlier batch from it.
    private final AtomicInteger retirementBatches = new AtomicInteger();

    private List<Object> applyAfterTheInterloper(List<KVCommand<AetherKey>> commands) {
        retirementBatches.incrementAndGet();

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

    private void leaderAndAllThreeKeys() {
        leaderAndBothKeys();
        seed(RECEIPT, RECEIPT_VALUE);
    }

    /// The committed leader, a re-admitted node's ADMITTED record at epoch 2, its directive and its receipt.
    private void leaderAndALiveReadmittedUnit() {
        commitLeader();
        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
        seed(RECORD, ADMITTED_AT_2);
        seed(RECEIPT, RECEIPT_VALUE);
    }

    /// The committed leader, a directive and a receipt, and no record: the unit of a node that never departed.
    private void leaderDirectiveAndReceiptWithNoRecord() {
        commitLeader();
        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
        seed(RECEIPT, RECEIPT_VALUE);
    }

    private static Cause causeOf(Result<Unit> outcome) {
        return outcome.fold(cause -> cause, _ -> fail("the retirement succeeded, an end cause was expected"));
    }

    /// The wait of the design's 3.4 (`AwaitingDeparture`, row 4) is a cause of its own, and not terminal, so the retry asks again.
    private static void assertAwaitingDeparture(Cause cause) {
        assertThat(cause).as("the wait is a cause of its own: AwaitingDeparture").isInstanceOf(TerminationRetirement.AwaitingDeparture.class);
        assertThat(cause.isTerminal()).as("and it is not terminal, so the retry asks again").isFalse();
    }

    private List<AetherKey> mutatedKeysOfTheOnlyTransaction() {
        assertThat(submitted).as("one batch").hasSize(1);
        assertThat(submitted.getFirst()).as("one transaction").hasSize(1);

        return ((KVCommand.LeaderTransaction<?, ?>) submitted.getFirst().getFirst()).mutations()
                                                                                  .stream()
                                                                                  .map(mutation -> (AetherKey) mutation.key())
                                                                                  .toList();
    }

    private void assertAllThreeStandAsSeeded(NodeDepartureValue record) {
        assertThat(store.get(RECORD)).as("the record stands").isEqualTo(Option.some(record));
        assertThat(store.get(DIRECTIVE).isPresent()).as("the directive stands").isTrue();
        assertThat(store.get(RECEIPT)).as("the receipt stands").isEqualTo(Option.some(RECEIPT_VALUE));
    }

    @Test
    void bothKeys_areRemoved_inOneTransaction() {
        leaderAndBothKeys();

        retirement().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

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
                                           .await(BOUND);

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

        var outcome = retried().retire(GONE).await(BOUND);

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

    /// Inverted by lane 4 (design 3.4, fix round 3 and `decide` row 2): the process comparison is deleted, and the epoch alone is the identity.
    /// A departure recorded by a leader that does not hold the admission carries another process, and it is still the departure of the node
    /// first read. Red when the retry ends over it (the process comparison restored), or removes anything but the value it read.
    @Test
    void aRetryGoesOn_onADepartedRecordOfTheSameEpoch_withAnotherIncarnation_andRemovesBothKeys() {
        var moved = new NodeDepartureValue(DepartureState.DEPARTED, 1L, new ProcessIncarnation(2L, 0xAAL), Option.some(DepartureCause.NODE_REMOVED), 20L);
        leaderAndBothKeys();
        beforeFirstApply = () -> seed(RECORD, moved);

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("the refused attempt, then the attempt on the record now").hasSize(2);
        assertThat(mutationOf(submitted.getLast(), RECORD).expected()).as("the second transaction expects the record as read at that attempt").isEqualTo(Option.some(moved));
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
    }

    private static KVCommand.Mutation<?, ?> mutationOf(List<KVCommand<AetherKey>> batch, AetherKey key) {
        return ((KVCommand.LeaderTransaction<?, ?>) batch.getFirst()).mutations()
                                                                     .stream()
                                                                     .filter(mutation -> mutation.key().equals(key))
                                                                     .toList()
                                                                     .getFirst();
    }

    /// Changed by lane 4 (design 3.4, `decide` row 4): an ADMITTED record at the epoch first read is waited on, not ended on. The batch count
    /// stays 1 and both keys stay, but the retry runs its whole budget and ends on the non-terminal `AwaitingDeparture`. No product writer
    /// produces this move (an admission raises the epoch); the test pins the state arm alone.
    @Test
    void aRetryWaits_onAnAdmittedRecordAtTheFirstReadsEpoch() {
        var admitted = new NodeDepartureValue(DepartureState.ADMITTED, 1L, DEPARTED.process(), Option.none(), 20L);
        leaderAndBothKeys();
        beforeFirstApply = () -> seed(RECORD, admitted);

        var outcome = retried().retire(GONE).await(BOUND);

        assertAwaitingDeparture(causeOf(outcome));
        assertThat(submitted).as("the refused attempt only").hasSize(1);
        assertThat(stopChecks).as("the whole retry budget of SHORT_RETRY: five attempts").hasValue(5);
        assertThat(store.get(RECORD)).as("the record stays").isEqualTo(Option.some(admitted));
        assertThat(store.get(DIRECTIVE).isPresent()).as("and so does the directive").isTrue();
    }

    private void endsOnARecordThatMovedTo(NodeDepartureValue moved) {
        leaderAndBothKeys();
        beforeFirstApply = () -> seed(RECORD, moved);

        var outcome = retried().retire(GONE).await(BOUND);

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

        var outcome = retried().retire(GONE).await(BOUND);

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

    /// Inverted by lane 4 (design 3.4, R11 symmetric, `decide` row 1): the first read held epoch 1 and the record now is absent, which is
    /// epoch 0, so the epochs differ and the retirement ends `Readmitted`. It may invert because no product writer removes the record alone
    /// while its directive stands (R6): C17b is whole or nothing, R-4.3.3 runs when the directive is already gone, and the two out-of-FSM
    /// retirers remove what the store holds in one transaction. Red when the retry goes on and removes the directive.
    @Test
    void aRetirementWhoseRecordAloneWasRemovedMeanwhile_endsReadmitted_andLeavesTheDirective() {
        leaderAndBothKeys();
        beforeFirstApply = this::removeTheRecord;

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(submitted).as("the refused attempt only").hasSize(1);
        assertThat(store.get(DIRECTIVE).isPresent()).as("the directive stands").isTrue();
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

        var outcome = retried().retire(GONE).await(BOUND);

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

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("refused under the old leader, then retried under the new one").hasSize(2);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    @Test
    void aRetirementWhoseKeysWereRemovedMeanwhile_stopsRetrying() {
        leaderAndBothKeys();
        beforeFirstApply = this::removeBoth;

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

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
        return refusedFor(refusals, this::isStopping);
    }

    private TerminationRetirement refusedFor(int refusals, java.util.function.BooleanSupplier stopped) {
        refusalsLeft = refusals;

        return TerminationRetirement.directiveAndRecord(store,
                                                        this::refuseThenApply,
                                                        MANY_ATTEMPTS,
                                                        stopped,
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

        refusedFor(4).retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 2);
        assertThat(codesRaised()).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE, OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED);
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    @Test
    void aRetirementThatEndsTerminallyAfterItsBound_raisesTheResolvedWarning() {
        leaderAndBothKeys();
        var readmitted = new NodeDepartureValue(DepartureState.ADMITTED, 2L, new ProcessIncarnation(2L, 0xAAL), Option.none(), 20L);

        afterTheLastRefusal = () -> seed(RECORD, readmitted);
        var outcome = refusedFor(3).retire(GONE).await(BOUND);

        assertThat(outcome.isFailure()).as("ended: the record was re-admitted").isTrue();
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 2);
        assertThat(codesRaised()).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE, OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED);
        assertThat(store.get(RECORD)).isEqualTo(Option.some(readmitted));
    }

    /// T8: a terminal end is never a refusal. The only call past the bound is the attempt that ends on the stop (the stop check itself moves
    /// the clock past the bound), so nothing is raised. Red when a terminal cause counts as a refusal (the `isTerminal` guard dropped).
    @Test
    void aTerminalEndPastTheBound_raisesNothing() {
        leaderAndBothKeys();

        var outcome = refusedFor(1, this::stopAfterTheBound).retire(GONE).await(BOUND);

        assertThat(outcome.isFailure()).as("ended by the stop").isTrue();
        assertThat(clockNs.get()).as("the stop check moved the clock past the bound").isGreaterThanOrEqualTo(TimeSpan.timeSpan(2).minutes().nanos());
        await().during(java.time.Duration.ofMillis(500)).atMost(java.time.Duration.ofSeconds(2)).until(warnings::isEmpty);
    }

    /// The node stops on the attempt after the refused one, and reading the signal takes the clock five minutes on.
    private boolean stopAfterTheBound() {
        var stop = stopChecks.incrementAndGet() > 1;

        if (stop) {
            clockNs.addAndGet(TimeSpan.timeSpan(5).minutes().nanos());
        }

        return stop;
    }

    /// One emitter: two retirements of one node on one leader (a confirmed reap and an operator --force clear) share the node's `Overdue`, so
    /// the overdue warning is raised once and closed once. Red when each retirement raises its own.
    @Test
    void twoRetirementsOfOneNode_raiseOneOverdueWarning_andOneResolved() {
        var overdue = TerminationRetirement.Overdue.overdue(OperatorWarningSink.handingOffTo(warnings::add), clockNs::get, TimeSpan.timeSpan(2).minutes());
        var reap = overdue.watch(GONE);
        var forcedClear = overdue.watch(GONE);
        var refusal = TerminationRetirement.Refused.FACTORY.apply(GONE);

        clockNs.addAndGet(TimeSpan.timeSpan(3).minutes().nanos());
        reap.refused(refusal);
        forcedClear.refused(refusal);
        reap.ended(org.pragmatica.lang.Result.unitResult());
        forcedClear.ended(org.pragmatica.lang.Result.unitResult());

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 2);
        assertThat(codesRaised()).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE, OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED);
    }

    /// T9: a resolved warning closes the node's subject, so a later retirement of the same node that is refused past the bound raises a new
    /// overdue warning. Red when the subject stays open after the first retirement ended (`open.remove` a no-op).
    @Test
    void aNodeRetiredAgainAfterAResolvedWarning_raisesASecondOverdueWarning() {
        var overdue = TerminationRetirement.Overdue.overdue(OperatorWarningSink.handingOffTo(warnings::add), clockNs::get, TimeSpan.timeSpan(2).minutes());
        var refusal = TerminationRetirement.Refused.FACTORY.apply(GONE);
        var first = overdue.watch(GONE);

        clockNs.addAndGet(TimeSpan.timeSpan(3).minutes().nanos());
        first.refused(refusal);
        first.ended(org.pragmatica.lang.Result.unitResult());
        var second = overdue.watch(GONE);

        clockNs.addAndGet(TimeSpan.timeSpan(3).minutes().nanos());
        second.refused(refusal);

        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 3);
        assertThat(codesRaised()).containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE,
                                                  OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED,
                                                  OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE);
    }

    @Test
    void aRetirementRefusedWithinItsBound_raisesNothing() {
        leaderAndBothKeys();

        refusedFor(1).retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        await().during(java.time.Duration.ofMillis(500)).atMost(java.time.Duration.ofSeconds(2)).until(warnings::isEmpty);
    }

    @Test
    void aNodeWithNeitherKey_submitsNothing() {
        commitLeader();

        retirement().retire(GONE).await(BOUND);

        assertThat(submitted).isEmpty();
    }

    /// The record alone (a directive already removed by another authority) is still retired; the transaction carries just it.
    @Test
    void aRecordAlone_isRetired() {
        commitLeader();
        seed(RECORD, DEPARTED);

        retirement().retire(GONE).await(BOUND);

        assertThat(store.get(RECORD)).isEqualTo(Option.none());
    }

    // Lane 4 of #3828 (design 3.4): the retirement's one rule, `decide`, on the real applier. Every test below seeds a committed leader
    // (check K3: without one the unconditional first attempt fails `NoCommittedLeader`, and mutant and rule then run the same code). A hook is
    // a write at the n-th read of the stop signal (`beforeAttempt`), or `beforeFirstApply` where the first attempt submits.

    /// One test per retirer, TR: the record, the directive and the receipt leave in ONE transaction, the record first, each at the value read.
    /// Red when the receipt is left behind (the unit not asked, or `removals` without the receipt).
    @Test
    void aTermination_retiresRecordDirectiveAndReceipt_inOneTransaction() {
        leaderAndAllThreeKeys();

        retirement().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(mutatedKeysOfTheOnlyTransaction()).as("the record first, then the directive, then the receipt").containsExactly(RECORD, DIRECTIVE, RECEIPT);
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// One test per retirer, TR: an ADMITTED record is a live node, so nothing leaves, receipt included (row 4: WAIT). Red when the first
    /// attempt removes it (the `first ||` of today's code).
    @Test
    void aTerminationOfAnAdmittedRecord_removesNothing_andLeavesAllThree() {
        leaderAndALiveReadmittedUnit();

        var outcome = retirement().retire(GONE).await(BOUND);

        assertAwaitingDeparture(causeOf(outcome));
        assertThat(submitted).as("no batch").isEmpty();
        assertAllThreeStandAsSeeded(ADMITTED_AT_2);
    }

    /// One test per retirer, TR (check F6; row 5 and the empty-unit test of row 0): a termination of an id with a receipt and no record and
    /// no directive removes the receipt. Seeded with the receipt alone, so the mutant "`isEmpty` ignores the receipt" answers DONE and dies.
    @Test
    void aTerminationWithoutARecord_removesTheReceipt() {
        commitLeader();
        seed(RECEIPT, RECEIPT_VALUE);

        retirement().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(mutatedKeysOfTheOnlyTransaction()).as("the receipt alone").containsExactly(RECEIPT);
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// Failure input 1 (row 2 after a wait): a re-admitted CORE whose confirmation precedes the departure commit. The first read holds an
    /// ADMITTED record, so TR waits; the departure commits DEPARTED at the same epoch (the process of `Admission.UNKNOWN`: the departure is
    /// recorded by a leader that does not hold the admission); the next attempt removes the unit. Red when the first attempt is exempt (its
    /// batch expects the ADMITTED value), when ADMITTED ends the retry, or when the process comparison is restored.
    @Test
    void aTerminationConfirmedBeforeTheDepartureCommits_waits_thenRemovesTheUnit() {
        leaderAndALiveReadmittedUnit();
        beforeAttempt.put(2, () -> seed(RECORD, DEPARTED_AT_2));

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(retirementBatches).as("the waiting attempt submits nothing: one batch in all").hasValue(1);
        assertThat(mutationOf(submitted.getFirst(), RECORD).expected()).as("the record mutation expects the DEPARTED value, never the ADMITTED one")
                                                                       .isEqualTo(Option.some(DEPARTED_AT_2));
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
        assertThat(stopChecks).as("one wait, one removal").hasValue(2);
    }

    /// Failure input 2 (row 1): a new admission between attempts. The epoch is now the first read's plus one; the retirement ends
    /// `Readmitted` and removes nothing. Red when any epoch counts as the first read's (it waits the whole budget instead).
    @Test
    void anAdmissionBetweenAttempts_endsTheRetirementReadmitted_andRemovesNothing() {
        leaderAndALiveReadmittedUnit();
        beforeAttempt.put(2, () -> seed(RECORD, ADMITTED_AT_3));

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("no batch").hasValue(0);
        assertAllThreeStandAsSeeded(ADMITTED_AT_3);
        assertThat(stopChecks).hasValue(2);
    }

    /// Failure input 3 (row 4): a wrong termination of a live node that has an ADMITTED record. Nothing moves; every attempt waits, nothing is
    /// removed, and the overdue event is raised once. The test's retry is bounded, so its watch closes with one resolved event when the attempts
    /// are spent; production's retry has no bound, and there the event stays open. The stop supplier moves the clock a minute per call, because
    /// a waiting attempt never reaches the clock step of `refuseThenApply`.
    @Test
    void aTerminationOfALiveReadmittedNode_removesNothing_keepsWaiting_andRaisesTheOverdueEvent() {
        leaderAndALiveReadmittedUnit();

        var outcome = refusedFor(0, this::stopCheckThatTakesAMinute).retire(GONE).await(BOUND);

        assertAwaitingDeparture(causeOf(outcome));
        assertThat(submitted).as("no batch").isEmpty();
        assertAllThreeStandAsSeeded(ADMITTED_AT_2);
        assertThat(stopChecks).as("the retry's 20 attempts").hasValue(20);
        await().atMost(java.time.Duration.ofSeconds(5)).until(() -> warnings.size() >= 2);
        assertThat(codesRaised()).as("one overdue event, closed once when the bounded retry is spent")
                                 .containsExactly(OperatorWarningCode.TERMINATION_RETIREMENT_OVERDUE, OperatorWarningCode.TERMINATION_RETIREMENT_RESOLVED);
    }

    private boolean stopCheckThatTakesAMinute() {
        stopChecks.incrementAndGet();
        clockNs.addAndGet(TimeSpan.timeSpan(1).minutes().nanos());

        return false;
    }

    /// The directive clause at the first attempt (row 6): a directive that moved after the first read, with no record. The retirement ends
    /// `Readmitted`, and the new directive stands. Red when the first attempt is exempt (it removes the new directive).
    @Test
    void aTerminationWhoseDirectiveMovedBeforeTheFirstAttempt_removesNothing() {
        leaderDirectiveAndReceiptWithNoRecord();
        var reassigned = ActivationDirectiveValue.worker("east-w-0", "10.0.0.9:9000");
        beforeAttempt.put(1, () -> seed(DIRECTIVE, reassigned));

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("no batch").hasValue(0);
        assertThat(store.get(DIRECTIVE)).as("the new directive stands").isEqualTo(Option.some(reassigned));
        assertThat(store.get(RECEIPT)).as("and so does the receipt").isEqualTo(Option.some(RECEIPT_VALUE));
        assertThat(stopChecks).hasValue(1);
    }

    /// Failure input 4 (R11; row 2 at epoch 0): a departure commits inside the commit window of a termination whose first read held no record.
    /// The unit witnesses the record absent, so the first transaction is refused (today's has no guard and commits); the departure wrote
    /// DEPARTED at epoch 0; the next attempt compares 0 with 0 and removes the unit. Red when a first read without a record matches no epoch
    /// (the retirement ends), or when the first batch commits unguarded.
    @Test
    void aDepartureInsideTheCommitWindow_afterAFirstReadWithoutARecord_isRemovedAtTheNextAttempt() {
        leaderDirectiveAndReceiptWithNoRecord();
        beforeFirstApply = () -> seed(RECORD, DEPARTED_AT_0);

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(submitted).as("the refused first batch, then the batch on the record now").hasSize(2);
        assertThat(mutationOf(submitted.getLast(), RECORD).expected()).as("the second batch expects the DEPARTED value that landed")
                                                                      .isEqualTo(Option.some(DEPARTED_AT_0));
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
        assertThat(stopChecks).as("two attempts").hasValue(2);
    }

    /// Its counterpart (row 1 after a first read without a record): a record at epoch 1 differs from the absent record's epoch 0. It pins
    /// "absent is epoch 0", not "absent matches any epoch". Red when a first read without a record matches any epoch.
    @Test
    void aRecordAtEpochOneAfterAFirstReadWithoutARecord_endsTheRetirement() {
        leaderDirectiveAndReceiptWithNoRecord();
        beforeAttempt.put(1, () -> seed(RECORD, DEPARTED));

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("no batch").hasValue(0);
        assertAllThreeStandAsSeeded(DEPARTED);
        assertThat(stopChecks).hasValue(1);
    }

    /// Failure input 5 (check P1; row 1 with an absent record now): a waiting twin whose unit was retired and issued again. The unit is not
    /// empty, so row 0 does not answer; epoch 0 is not the first read's 2; it ends, and the fresh directive and receipt of a live core stay.
    /// Red when an absent record now is exempt from the epoch comparison.
    @Test
    void aWaitingRetirementThatFindsItsRecordGone_ends_andLeavesAnEqualDirectiveAndItsReceipt() {
        leaderAndALiveReadmittedUnit();
        beforeAttempt.put(2, this::retireTheUnitThenIssueItAgain);

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("no batch").hasValue(0);
        assertThat(store.get(RECORD)).as("no record").isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).as("the directive of equal value stands").isEqualTo(Option.some(ActivationDirectiveValue.worker("east-w-0", "")));
        assertThat(store.get(RECEIPT)).as("and so does the receipt").isEqualTo(Option.some(RECEIPT_VALUE));
        assertThat(stopChecks).hasValue(2);
    }

    /// The sibling of a confirmed reap retires the unit, and the node restarts under its configured id: a directive of the same value and a
    /// receipt are issued, with no record (a first join writes none).
    private void retireTheUnitThenIssueItAgain() {
        commit(new KVCommand.LeaderTransaction<>(RECORD,
                                                 "sibling-reap",
                                                 LEADER,
                                                 List.of(),
                                                 List.of(new KVCommand.Mutation<>(RECORD, store.get(RECORD), Option.none()),
                                                         new KVCommand.Mutation<>(DIRECTIVE, store.get(DIRECTIVE), Option.none()),
                                                         new KVCommand.Mutation<>(RECEIPT, store.get(RECEIPT), Option.none()))));
        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
        seed(RECEIPT, RECEIPT_VALUE);
    }

    /// Failure input 6 (CTO ruling of 11-27; row 3): the record now is DEPARTED at the epoch first read, and the directive moved. The
    /// retirement ends and removes nothing. Red when the directive clause is asked only when no record is present.
    @Test
    void aDirectiveThatMovedBesideADepartedRecordAtTheEpochFirstRead_endsTheRetirement_andRemovesNothing() {
        commitLeader();
        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
        seed(RECORD, DEPARTED_AT_2);
        seed(RECEIPT, RECEIPT_VALUE);
        var reassigned = ActivationDirectiveValue.worker("east-w-0", "10.0.0.9:9000");
        beforeAttempt.put(1, () -> seed(DIRECTIVE, reassigned));

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("no batch").hasValue(0);
        assertThat(store.get(RECORD)).as("the record stands").isEqualTo(Option.some(DEPARTED_AT_2));
        assertThat(store.get(DIRECTIVE)).as("the new directive stands").isEqualTo(Option.some(reassigned));
        assertThat(store.get(RECEIPT)).as("and so does the receipt").isEqualTo(Option.some(RECEIPT_VALUE));
        assertThat(stopChecks).hasValue(1);
    }

    /// Row 2 at an absent directive now (f3860b, the killer of mutant ru16): the record now is DEPARTED at the epoch first read, and the
    /// directive first read is gone. The directive clause holds (an absent directive now), so the retirement REMOVEs: record and receipt in
    /// ONE transaction, the vanished directive witnessed absent. Red when an absent directive now ENDs the retirement (the clause asked as
    /// "equal to the one first read" only), which leaves the record and the receipt behind.
    @Test
    void aDirectiveThatVanishedBesideADepartedRecordAtTheEpochFirstRead_isRetired_withTheRecordAndTheReceipt_inOneTransaction() {
        leaderAndAllThreeKeys();
        beforeAttempt.put(1, () -> commit(new KVCommand.LeaderTransaction<>(DIRECTIVE,
                                                                            "directive-vanished",
                                                                            LEADER,
                                                                            List.of(),
                                                                            List.of(new KVCommand.Mutation<>(DIRECTIVE, store.get(DIRECTIVE), Option.none())))));

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(retirementBatches).as("one batch, accepted").hasValue(1);
        assertThat(mutatedKeysOfTheOnlyTransaction()).as("record and receipt in one transaction, the absent directive not removed").containsExactly(RECORD, RECEIPT);
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
        assertThat(stopChecks).hasValue(1);
    }

    /// Row 7 (check P4): a record in a state that is neither DEPARTED nor ADMITTED. No product writer produces it; the test pins the default
    /// arm. Red when any other state answers REMOVE or WAIT.
    @Test
    void aRecordInAStateThatIsNeitherDepartedNorAdmitted_endsTheRetirement() {
        commitLeader();
        seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
        seed(RECORD, DEPARTED_AT_2);
        seed(RECEIPT, RECEIPT_VALUE);
        var unknown = new NodeDepartureValue(DepartureState.UNKNOWN, 2L, DEPARTED_AT_2.process(), Option.none(), 50L);
        beforeAttempt.put(1, () -> seed(RECORD, unknown));

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("no batch").hasValue(0);
        assertAllThreeStandAsSeeded(unknown);
        assertThat(stopChecks).hasValue(1);
    }

    // v3860d: the two interleavings of Invariant R against one attempt, driven through `retire` in production order: the attempt reads the
    // unit, builds its transaction, and a concurrent writer lands between that read and the commit (`beforeFirstApply`).

    /// (a) A directive re-issued at another value between the attempt's read and its commit: the directive's exact-value removal refuses the
    /// whole transaction (EXPECTED), and the retry ends on a directive that is not the one first read (row 3). Red when the unit's removal
    /// does not expect the exact value, or when the directive clause is not asked.
    @Test
    void v3860d_aDirectiveReissuedBetweenTheReadAndTheCommit_refusesTheWhole_andTheRetryEnds_removingNothing() {
        leaderAndAllThreeKeys();
        var reissued = ActivationDirectiveValue.worker("east-w-0", "10.0.0.9:9000");
        beforeFirstApply = () -> {
            commit(new KVCommand.LeaderTransaction<>(DIRECTIVE,
                                                     "reissue-remove",
                                                     LEADER,
                                                     List.of(),
                                                     List.of(new KVCommand.Mutation<>(DIRECTIVE, store.get(DIRECTIVE), Option.none()))));
            seed(DIRECTIVE, reissued);
        };

        var outcome = retried().retire(GONE).await(BOUND);

        assertThat(causeOf(outcome)).isInstanceOf(TerminationRetirement.Readmitted.class);
        assertThat(retirementBatches).as("one refused batch").hasValue(1);
        assertThat(store.get(RECORD)).isEqualTo(Option.some(DEPARTED));
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.some(reissued));
        assertThat(store.get(RECEIPT)).isEqualTo(Option.some(RECEIPT_VALUE));
    }

    /// (b) A receipt committed between the attempt's read and its commit: the unit's witness "receipt absent" refuses the whole transaction
    /// (WITNESS), and the retry reads the unit again and retires all three. Red when the retiring form does not witness an absent receipt.
    @Test
    void v3860d_aReceiptCommittedBetweenTheReadAndTheCommit_refusesTheWhole_andTheRetryRetiresAllThree() {
        leaderAndBothKeys();
        beforeFirstApply = () -> seed(RECEIPT, RECEIPT_VALUE);

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(retirementBatches).as("the refused batch, then the one that commits").hasValue(2);
        assertThat(store.get(RECORD)).isEqualTo(Option.none());
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }

    /// The bound of (a), characterised: a directive and receipt removed and re-issued at EQUAL values between the read and the commit of a
    /// unit with no record are removed by the transaction (exact-value removal cannot tell equal issues apart; design 10.5, the declared
    /// residue of a first read with no record). This pins today's behaviour; it is not a guarantee.
    @Test
    void v3860d_characterisation_anEqualReissueBetweenTheReadAndTheCommit_ofAUnitWithNoRecord_isRemoved() {
        leaderDirectiveAndReceiptWithNoRecord();
        beforeFirstApply = () -> {
            commit(new KVCommand.LeaderTransaction<>(DIRECTIVE,
                                                     "sibling-retire",
                                                     LEADER,
                                                     List.of(),
                                                     List.of(new KVCommand.Mutation<>(DIRECTIVE, store.get(DIRECTIVE), Option.none()),
                                                             new KVCommand.Mutation<>(RECEIPT, store.get(RECEIPT), Option.none()))));
            seed(DIRECTIVE, ActivationDirectiveValue.worker("east-w-0", ""));
            seed(RECEIPT, RECEIPT_VALUE);
        };

        retried().retire(GONE).await(BOUND).onFailure(cause -> fail(cause.message()));

        assertThat(retirementBatches).as("one batch, accepted").hasValue(1);
        assertThat(store.get(DIRECTIVE)).isEqualTo(Option.none());
        assertThat(store.get(RECEIPT)).isEqualTo(Option.none());
    }
}
