/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.utility.warning.OperatorWarningCode.CONSENSUS_RESTORE_RECOVERED;
import static org.pragmatica.utility.warning.OperatorWarningCode.CONSENSUS_RESTORE_REFUSED;

/// #2316 (engine half, L-4) — a responder snapshot that cannot be restored.
///
/// Owner rulings 2026-10-28 (`know:` d29ef6723c): Q-3 fail closed, the snapshot is refused as a whole and loudly, never
/// decoded row by row; Q-16 the fallback adopts another responder only at the SAME OR A HIGHER phase, never a lower one,
/// and with no usable candidate the engine stays in `Syncing` with the event and the status raised.
///
/// Fixture: a five-voter cluster with no own history and every responder COLD, so a round adopts once two responses are
/// in (`clusterSize / 2`). The sync retry is 60 s, so every round here is one the test drives by delivering responses,
/// and each round waits for its restore attempt before the next one starts.
class RabiaRestoreRefusalTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final int CLUSTER_SIZE = 5;
    private static final long BUDGET_MILLIS = 2_000;
    private static final long QUIET_MILLIS = 200;
    private static final long SETTLE_MILLIS = 100;
    private static final String BAD_PREFIX = "undecodable";
    private static final byte[] BAD_SNAPSHOT = BAD_PREFIX.getBytes(StandardCharsets.UTF_8);
    private static final byte[] OTHER_BAD_SNAPSHOT = (BAD_PREFIX + " too").getBytes(StandardCharsets.UTF_8);
    private static final byte[] GOOD_SNAPSHOT = "readable".getBytes(StandardCharsets.UTF_8);
    private static final Phase HIGH_PHASE = Phase.phase(10);
    private static final Phase LOW_PHASE = Phase.phase(8);
    private static final Cause UNDECODABLE = Causes.cause("snapshot row 17 does not decode");
    private static final OperatorWarning SENTINEL = OperatorWarning.operatorWarning(OperatorWarningCode.OBSERVER_CONSENSUS_RESUMED,
                                                                                    "sentinel",
                                                                                    "flush");

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();
    private final List<OperatorWarning> published = new CopyOnWriteArrayList<>();
    private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(published::add);
    private final SelectiveStateMachine stateMachine = new SelectiveStateMachine();

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop()
                                        .await());
    }

    /// RED at the base: the status carried no trace of a responder-snapshot failure (`failure()` was `""`). After three
    /// consecutive failed rounds it names the refusal and the cause; after two it does not yet (N=3 -> 1 goes red here).
    @Test
    void undecodableResponderSnapshot_threeRounds_surfacesTypedFailureInStatus() {
        var engine = coldStarted();

        failingRounds(engine, 2);
        assertThat(engine.voterReconfigurationStatus().failure()).as("two failures are not yet a refusal")
                                                                 .doesNotContain(UNDECODABLE.message());

        failingRounds(engine, 1);
        assertThat(engine.voterReconfigurationStatus().failure()).as("#2316: the refusal is in the status, typed and with its cause")
                                                                 .contains("Responder snapshots refused")
                                                                 .contains(UNDECODABLE.message())
                                                                 .contains(HIGH_PHASE.toString());
        assertThat(engine.isActive()).as("Q-3: fail closed").isFalse();
    }

    /// RED at the base: no event. Exactly one `consensus-restore-refused` for the third failure, none before it, and no
    /// second one on later rounds.
    @Test
    void undecodableResponderSnapshot_threeRounds_emitsRestoreRefusedOnce() {
        var engine = coldStarted();

        failingRounds(engine, 2);
        assertThat(published(CONSENSUS_RESTORE_REFUSED)).as("two failures raise nothing").isEmpty();

        failingRounds(engine, 1);
        assertThat(published(CONSENSUS_RESTORE_REFUSED)).as("#2316: the third failed round raises the refusal once")
                                                        .hasSize(1)
                                                        .allMatch(warning -> warning.subject().equals(NODE_1.id())
                                                                             && warning.message().contains(NODE_2.toString())
                                                                             && warning.message().contains(UNDECODABLE.message()));

        heldRounds(engine, 3, () -> deliver(engine, response(NODE_2, HIGH_PHASE, BAD_SNAPSHOT),
                                            response(NODE_3, HIGH_PHASE, BAD_SNAPSHOT)));
        assertThat(published(CONSENSUS_RESTORE_REFUSED)).as("later rounds do not raise it again").hasSize(1);
        assertThat(published(CONSENSUS_RESTORE_RECOVERED)).as("nothing was restored, so nothing recovers").isEmpty();
    }

    /// RED at the base: `adoptCollectedState` took `getLast()` of the phase-sorted responses, and among equal phases that
    /// is the response the `ConcurrentHashMap` iterates last. The bad responder is CHOSEN to be that one (the precondition
    /// computes it from an identical map), so the base retries the bad snapshot forever and never restores the good one.
    /// At the head the bad digest is refused after three rounds and the readable snapshot at the same phase is adopted.
    @Test
    void undecodableHighest_equalPhaseOtherResponder_isAdopted() {
        var order = new ConcurrentHashMap<NodeId, Boolean>();

        order.put(NODE_2, true);
        order.put(NODE_3, true);
        var bad = List.copyOf(order.keySet()).getLast();
        var good = bad.equals(NODE_2)
                   ? NODE_3
                   : NODE_2;
        var engine = coldStarted();

        for (int round = 1; round <= 4; round++) {
            var attemptsBefore = stateMachine.attempts();

            deliver(engine, response(good, HIGH_PHASE, GOOD_SNAPSHOT), response(bad, HIGH_PHASE, BAD_SNAPSHOT));
            assertThat(awaitCondition(() -> stateMachine.attempts() > attemptsBefore)).as("round %s attempted a restore", round)
                                                                                      .isTrue();
            assertThat(round > 1 || stateMachine.badAttempts() == 1).as("precondition: the bad responder (%s) is the one getLast() returns",
                                                                        bad)
                                                                    .isTrue();
        }

        assertThat(stateMachine.restored()).as("Q-16 (i): a readable snapshot at the same phase is adopted")
                                           .containsExactly(GOOD_SNAPSHOT);
    }

    /// RED at the base: the refused digest was restored again on every round. After the refusal the count stays at three.
    @Test
    void refusedDigest_isNotRestoredAgain() {
        var engine = coldStarted();

        failingRounds(engine, 3);
        heldRounds(engine, 4, () -> deliver(engine, response(NODE_2, HIGH_PHASE, BAD_SNAPSHOT),
                                            response(NODE_3, HIGH_PHASE, BAD_SNAPSHOT)));

        assertThat(stateMachine.badAttempts()).as("#2316: the refused digest is excluded from candidate selection").isEqualTo(3);
        assertThat(engine.isActive()).isFalse();
    }

    /// SAFETY PIN, green at the base and at the head (owner ruling Q-16): with the highest snapshot refused, a LOWER-phase
    /// readable candidate is never adopted, because it can lack a commit only the refused responder saw. Adding a
    /// lower-phase fallback turns this red.
    @Test
    void undecodableHighest_lowerPhaseCandidatePresent_isNotAdopted() {
        var engine = coldStarted();

        for (int round = 1; round <= 3; round++) {
            var attemptsBefore = stateMachine.attempts();

            deliver(engine, response(NODE_2, HIGH_PHASE, BAD_SNAPSHOT), response(NODE_3, LOW_PHASE, GOOD_SNAPSHOT));
            assertThat(awaitCondition(() -> stateMachine.attempts() > attemptsBefore)).as("round %s attempted a restore", round)
                                                                                      .isTrue();
        }

        heldRounds(engine, 3, () -> deliver(engine, response(NODE_2, HIGH_PHASE, BAD_SNAPSHOT),
                                            response(NODE_3, LOW_PHASE, GOOD_SNAPSHOT)));

        assertThat(stateMachine.restored()).as("Q-16 (ii): the lower-phase snapshot is never restored").isEmpty();
        assertThat(engine.isActive()).as("the engine stays in Syncing").isFalse();
    }

    /// A refusal clears when any restore succeeds: a readable snapshot at a higher phase is adopted, the status drops the
    /// refusal and `consensus-restore-recovered` closes the event. Not clearing on success turns this red.
    @Test
    void readableSnapshotAfterRefusal_clearsTheRefusalAndEmitsRecovered() {
        var engine = coldStarted();

        failingRounds(engine, 3);
        assertThat(engine.voterReconfigurationStatus().failure()).as("precondition: refused").contains(UNDECODABLE.message());

        deliver(engine, response(NODE_2, HIGH_PHASE, BAD_SNAPSHOT), response(NODE_3, Phase.phase(12), GOOD_SNAPSHOT));
        assertThat(awaitCondition(() -> !stateMachine.restored().isEmpty())).as("the higher readable snapshot is restored").isTrue();

        assertThat(awaitCondition(() -> !engine.voterReconfigurationStatus().failure().contains(UNDECODABLE.message())))
            .as("#2316: a successful restore clears the refusal from the status")
            .isTrue();
        // The status clears just before the recovery is handed to the sink, on the engine's thread: wait for the event.
        assertThat(awaitCondition(() -> !published(CONSENSUS_RESTORE_RECOVERED).isEmpty())).as("the recovery is published")
                                                                                           .isTrue();
        assertThat(published(CONSENSUS_RESTORE_RECOVERED)).as("and closes the event once").hasSize(1)
                                                          .allMatch(warning -> warning.subject().equals(NODE_1.id()));
    }

    /// Two undecodable snapshots at ONE phase, alternated between the two responders so that the attempted digest alternates
    /// every round, over 12 rounds. The refusal is raised exactly once, and each digest is excluded after its third failure, so
    /// both are tried three times and then the engine holds. Keeping one refused digest instead of a set (a second refusal
    /// overwrites the first, which is then retried) turns the attempt counts red.
    @Test
    void alternatingUndecodableDigests_atOnePhase_raiseOnceAndAreBothExcluded() {
        var engine = coldStarted();

        for (int round = 1; round <= 12; round++) {
            var odd = round % 2 == 1;

            attemptedOrHeldRound(engine,
                                 response(NODE_2, HIGH_PHASE, odd ? BAD_SNAPSHOT : OTHER_BAD_SNAPSHOT),
                                 response(NODE_3, HIGH_PHASE, odd ? OTHER_BAD_SNAPSHOT : BAD_SNAPSHOT));
        }

        assertThat(published(CONSENSUS_RESTORE_REFUSED)).as("#2316: alternating digests raise the refusal exactly once").hasSize(1);
        assertThat(stateMachine.badAttempts(BAD_SNAPSHOT)).as("the first digest is excluded after its third failure").isEqualTo(3);
        assertThat(stateMachine.badAttempts(OTHER_BAD_SNAPSHOT)).as("and so is the second: both stay excluded").isEqualTo(3);
        assertThat(published(CONSENSUS_RESTORE_RECOVERED)).as("nothing was restored, so nothing recovers").isEmpty();
        assertThat(engine.isActive()).as("Q-3: fail closed").isFalse();
    }

    /// CTO ruling on #2675: the refusal counts consecutive failed rounds IN TOTAL. Eight undecodable snapshots, each at a
    /// higher phase than the last and so each with its own digest, raise the CRITICAL exactly once, at the third round, and
    /// the status names the latest failure. A later successful restore closes it with exactly one recovery. Resetting the
    /// count on a phase (or digest) change turns this red, and so does not clearing on success.
    @Test
    void undecodableSnapshots_phaseAdvancingEveryRound_raiseOnceAtTheThirdRound_thenRecoverOnce() {
        var engine = coldStarted();

        for (int round = 1; round <= 8; round++) {
            var phase = Phase.phase(HIGH_PHASE.value() + round);

            failingRound(engine,
                         response(NODE_2, phase, ("undecodable at " + phase).getBytes(StandardCharsets.UTF_8)),
                         response(NODE_3, LOW_PHASE, GOOD_SNAPSHOT));
            assertThat(published(CONSENSUS_RESTORE_REFUSED)).as("after round %s", round)
                                                            .hasSize(round < 3 ? 0 : 1);
        }

        assertThat(engine.voterReconfigurationStatus().failure()).as("the status names the latest failure")
                                                                 .contains("after 8 consecutive failed restores")
                                                                 .contains(Phase.phase(HIGH_PHASE.value() + 8).toString())
                                                                 .contains(UNDECODABLE.message());
        assertThat(stateMachine.restored()).as("Q-16: the lower-phase snapshot is never adopted meanwhile").isEmpty();

        deliver(engine, response(NODE_2, Phase.phase(20), BAD_SNAPSHOT), response(NODE_3, Phase.phase(30), GOOD_SNAPSHOT));
        assertThat(awaitCondition(() -> !stateMachine.restored().isEmpty())).as("the higher readable snapshot is restored").isTrue();
        assertThat(awaitCondition(() -> !published(CONSENSUS_RESTORE_RECOVERED).isEmpty())).as("the recovery is published")
                                                                                           .isTrue();
        assertThat(published(CONSENSUS_RESTORE_RECOVERED)).as("exactly once").hasSize(1);
        assertThat(published(CONSENSUS_RESTORE_REFUSED)).as("and the refusal was never raised again").hasSize(1);
        assertThat(engine.voterReconfigurationStatus().failure()).as("the status is cleared").doesNotContain(UNDECODABLE.message());
    }

    /// One round that restores the bad snapshot and fails, waited for by its attempt count. The count rises inside
    /// `restoreSnapshot`, just BEFORE the engine records the failure on the same executor thread, so each round also
    /// settles for [#SETTLE_MILLIS]: a status or event read right after the count would race the recording.
    private void failingRounds(RabiaEngine<TestCommand> engine, int rounds) {
        for (int round = 0; round < rounds; round++) {
            failingRound(engine, response(NODE_2, HIGH_PHASE, BAD_SNAPSHOT), response(NODE_3, LOW_PHASE, GOOD_SNAPSHOT));
        }
    }

    private void failingRound(RabiaEngine<TestCommand> engine, SyncResponse<TestCommand> first, SyncResponse<TestCommand> second) {
        var expected = stateMachine.badAttempts() + 1;

        deliver(engine, first, second);
        assertThat(awaitCondition(() -> stateMachine.badAttempts() == expected)).as("failing round reached the restore")
                                                                                .isTrue();
        awaitCondition(() -> false, SETTLE_MILLIS);
    }

    /// A round that may restore or may be held: it waits up to [#QUIET_MILLIS] for an attempt, then settles as
    /// [#failingRound] does.
    private void attemptedOrHeldRound(RabiaEngine<TestCommand> engine,
                                      SyncResponse<TestCommand> first,
                                      SyncResponse<TestCommand> second) {
        var attemptsBefore = stateMachine.attempts();

        deliver(engine, first, second);
        awaitCondition(() -> stateMachine.attempts() > attemptsBefore, QUIET_MILLIS);
        awaitCondition(() -> false, SETTLE_MILLIS);
    }

    /// Rounds that are expected NOT to restore anything, followed by a quiet interval in which a restore would have shown
    /// up. Their control is the base behaviour: without the refusal each of these rounds restores the bad snapshot again.
    private void heldRounds(RabiaEngine<TestCommand> engine, int rounds, Runnable round) {
        for (int index = 0; index < rounds; index++) {
            round.run();
        }

        awaitCondition(() -> false, QUIET_MILLIS);
    }

    private void deliver(RabiaEngine<TestCommand> engine, SyncResponse<TestCommand> first, SyncResponse<TestCommand> second) {
        engine.processSyncResponse(first);
        engine.processSyncResponse(second);
    }

    private List<OperatorWarning> published(OperatorWarningCode code) {
        var before = published.size();

        sink.accept(SENTINEL);
        awaitCondition(() -> published.size() > before && published.contains(SENTINEL));

        return published.stream()
                        .filter(warning -> warning.code() == code)
                        .toList();
    }

    private static SyncResponse<TestCommand> response(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender, SavedState.savedState(snapshot, phase, List.of()), ResponderState.COLD);
    }

    private RabiaEngine<TestCommand> coldStarted() {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, CLUSTER_SIZE),
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       false,
                                       noOwnHistory(),
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.useOperatorWarningSink(sink);
        engine.clusterState(ClusterStateNotification.active());
        assertThat(awaitCondition(() -> network.getMessages()
                                               .stream()
                                               .anyMatch(SyncRequest.class::isInstance))).as("the sync round is open before responses arrive")
                                                                                         .isTrue();

        return engine;
    }

    /// Fails to restore any snapshot that starts with [#BAD_PREFIX] and restores anything else, counting both.
    private static final class SelectiveStateMachine extends TestStateMachine {
        private final AtomicInteger attempts = new AtomicInteger();
        private final AtomicInteger badAttempts = new AtomicInteger();
        private final Map<String, Integer> badAttemptsBySnapshot = new ConcurrentHashMap<>();
        private final List<byte[]> restored = new CopyOnWriteArrayList<>();

        /// `attempts` rises last, so a reader that saw it rise also sees `badAttempts` and `restored`.
        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            var text = new String(snapshot, StandardCharsets.UTF_8);
            var outcome = text.startsWith(BAD_PREFIX)
                          ? failBad(text)
                          : restoreGood(snapshot);

            attempts.incrementAndGet();

            return outcome;
        }

        private Result<Unit> failBad(String text) {
            badAttemptsBySnapshot.merge(text, 1, Integer::sum);
            badAttempts.incrementAndGet();

            return UNDECODABLE.result();
        }

        private Result<Unit> restoreGood(byte[] snapshot) {
            restored.add(snapshot);

            return super.restoreSnapshot(snapshot);
        }

        int attempts() {
            return attempts.get();
        }

        int badAttempts() {
            return badAttempts.get();
        }

        int badAttempts(byte[] snapshot) {
            return badAttemptsBySnapshot.getOrDefault(new String(snapshot, StandardCharsets.UTF_8), 0);
        }

        List<byte[]> restored() {
            return restored;
        }
    }

    private static RabiaPersistence<TestCommand> noOwnHistory() {
        record noOwnHistory() implements RabiaPersistence<TestCommand> {
            @Override
            public Result<Unit> save(StateMachine<TestCommand> stateMachine,
                                     Phase lastCommittedPhase,
                                     Collection<Batch<TestCommand>> pendingBatches) {
                return Result.success(Unit.unit());
            }

            @Override
            public Option<SavedState<TestCommand>> load() {
                return Option.none();
            }
        }

        return new noOwnHistory();
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        return awaitCondition(condition, BUDGET_MILLIS);
    }

    private static boolean awaitCondition(BooleanSupplier condition, long budgetMillis) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(budgetMillis);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }

            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
