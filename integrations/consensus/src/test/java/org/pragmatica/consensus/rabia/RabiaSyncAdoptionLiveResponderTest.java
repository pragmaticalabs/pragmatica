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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #667 — the residual #660 left behind, closed with the responder's engine state on the wire.
///
/// #660 relaxed adoption to `clusterSize / 2` peer responses with self as a FLOOR, sound for a
/// full-cluster cold start where nothing durable survived anywhere. It was weaker than the old
/// bound for ONE node restarting with in-memory persistence into a still-live cluster: its floor is
/// phase 0, the first two responders may be live peers that never witnessed the latest commit, and
/// the floor cannot refuse them — a committed phase held only by the two peers that had not
/// answered yet is silently discarded.
///
/// Round 2 amended the threshold. It is on RESPONSES, not on LIVE responders — thresholding on the
/// live count deadlocked a half-started cluster, because the only nodes that could raise that count
/// were the ones it blocked (see `RabiaSyncAdoptionResponseQuorumTest`). The rule now, re-evaluated on
/// every arrival, never on a timer:
/// - any LIVE responder and `clusterSize / 2 + 1` RESPONSES of any mix: adopt. The response quorum
///   carries the safety argument — responders ALONE are a majority, so they intersect every majority
///   that could have committed anything, without leaning on self's history.
/// - within that quorum the source is the LIVE maximum only when LIVE responders are themselves a
///   majority; otherwise the maximum over every response. Filtering to LIVE inside a mere response
///   quorum is unsafe: the member that intersects the commit quorum may be the COLD one.
/// - no LIVE responder at all: #660's cold rule, unchanged — `clusterSize / 2` responses, self as floor.
/// UNKNOWN (an ordinal this node cannot name) counts as COLD — exactly as COLD, in every arm.
class RabiaSyncAdoptionLiveResponderTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = nodeId("node-4").unwrap();
    private static final long ACTIVATION_TIMEOUT_MILLIS = 5_000;
    private static final long STAYS_INACTIVE_WINDOW_MILLIS = 300;
    private static final byte[] BEHIND_SNAPSHOT = "behind".getBytes(StandardCharsets.UTF_8);
    private static final byte[] AHEAD_SNAPSHOT = "ahead".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SELF_SNAPSHOT = "self".getBytes(StandardCharsets.UTF_8);

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Nested
    class AResponseQuorumDecides {
        /// (i) THE #667 RESIDUAL, and the arm that survived the round-2 amendment unchanged. n=5, self
        /// restarted with in-memory persistence (floor 0). Two LIVE peers answer, both behind the
        /// cluster's latest commit. Before #667 this was `clusterSize / 2` responses and the floor could
        /// not refuse — adopted, commit lost. Two responders plus a history-less self are not a majority
        /// that witnessed anything. Round 2 still refuses it, now because two responses are not a
        /// response quorum rather than because two LIVE are not a live majority.
        @Test
        void twoResponsesOfFive_isNotAResponseQuorum_andMustNotAdopt() throws InterruptedException {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("2 LIVE of 5 is not a live majority — adopting their state can discard a commit the other two hold")
                .isTrue();
            assertThat(stateMachine.lastRestored()).as("nothing installed while waiting").isNull();
        }

        /// SUPERSEDED BEHAVIOUR, kept as the pin for what replaced it. Round 1 made this wait: two LIVE
        /// plus one COLD is a live MINORITY. Round 2 adopts, because three responses at n=5 are a
        /// response QUORUM and the quorum is the threshold. The round-1 assertion was the defect —
        /// waiting here is what wedged a half-started cluster — so the test changed, not the code.
        ///
        /// The source is the whole quorum, not the LIVE pair: with LIVE a minority of the quorum, the
        /// responder that intersects a commit quorum may be the COLD one, and filtering it out is how a
        /// joiner adopts a state behind a commit sitting in its own response set.
        @Test
        void twoLivePlusOneCold_isAResponseQuorum_andAdoptsOverTheWholeQuorum() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(new SyncResponse<>(NODE_4,
                                                         SavedState.savedState(AHEAD_SNAPSHOT, Phase.phase(500), List.of()),
                                                         ResponderState.COLD));

            assertThat(awaitActive(engine)).as("3 of 5 responses is a response quorum").isTrue();
            assertThat(stateMachine.lastRestored())
                .as("LIVE is a minority of this quorum, so the source is every response — the COLD one is ahead")
                .isEqualTo(AHEAD_SNAPSHOT);
        }

        /// UNKNOWN is COLD — and this asserts it as an EQUIVALENCE rather than as one consequence of it,
        /// because "counts as COLD" is the part of #667 that has survived all three versions of the rule.
        /// The same scenario is run twice, changing only the flag; identical outcomes are the claim.
        /// Asserting instead that UNKNOWN "is never adopted from" would be stronger than COLD semantics
        /// and would fail for the same reason a COLD response would.
        @Test
        void unknownResponderState_behavesExactlyAsCold() {
            assertThat(adoptedWithThirdResponder(ResponderState.UNKNOWN))
                .as("UNKNOWN must be indistinguishable from COLD in the adoption decision")
                .isEqualTo(adoptedWithThirdResponder(ResponderState.COLD));
        }

        /// n=5, two LIVE behind plus a third responder ahead carrying `flag`; returns what was installed.
        private byte[] adoptedWithThirdResponder(ResponderState flag) {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(new SyncResponse<>(NODE_4,
                                                         SavedState.savedState(AHEAD_SNAPSHOT, Phase.phase(500), List.of()),
                                                         flag));

            assertThat(awaitActive(engine)).isTrue();

            return stateMachine.lastRestored();
        }
    }

    @Nested
    class AnAllLiveQuorumAdoptsTheLiveMaximum {
        /// (ii) The third LIVE response completes the live majority (3 of 5); the engine adopts the most
        /// advanced LIVE state — the one at phase 99 — not the first two it heard.
        @Test
        void thirdLiveResponse_completesTheMajority_andTheLiveMaximumIsAdopted() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_4, Phase.phase(99), AHEAD_SNAPSHOT));

            assertThat(awaitActive(engine)).as("3 LIVE of 5 is a live majority").isTrue();
            assertThat(stateMachine.lastRestored())
                .as("the live majority's most advanced state is the one adopted")
                .isEqualTo(AHEAD_SNAPSHOT);
        }

        /// A COLD response arriving AFTER the quorum has already decided changes nothing — the engine is
        /// active and ignores it. That is all this case can assert now, and the reason is worth stating
        /// because it retires a claim #667 made.
        ///
        /// **The LIVE filter is currently unreachable AS A FILTER on the arrival path.** Adoption fires
        /// at exactly `clusterSize / 2 + 1` responses, and the LIVE-majority branch needs
        /// `clusterSize / 2 + 1` LIVE among precisely that many responses — so it is taken if and only
        /// if every response in the quorum is already LIVE, where filtering removes nothing. Measured at
        /// n=5 over all four arrival orders of {3 LIVE, 1 COLD}: the decision fired at 3 responses every
        /// time; with the COLD among them the source was the whole quorum.
        ///
        /// So "a COLD snapshot cannot outrank what the live cluster holds" has no implementation here,
        /// and cannot have one without a bounded collection window that re-evaluates on later responses.
        /// The filter is kept because it becomes load-bearing the moment such a window exists.
        @Test
        void aColdResponseArrivingAfterTheQuorumDecided_isIgnored() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_4, Phase.phase(99), AHEAD_SNAPSHOT));

            assertThat(awaitActive(engine)).as("three LIVE responses are the quorum at n=5").isTrue();
            assertThat(stateMachine.lastRestored())
                .as("an all-LIVE quorum adopts its maximum")
                .isEqualTo(AHEAD_SNAPSHOT);

            engine.processSyncResponse(new SyncResponse<>(NODE_1,
                                                         SavedState.savedState(SELF_SNAPSHOT, Phase.phase(500), List.of()),
                                                         ResponderState.COLD));

            assertThat(stateMachine.lastRestored())
                .as("a response delivered to an active engine installs nothing")
                .isEqualTo(AHEAD_SNAPSHOT);
        }
    }

    @Nested
    class ColdRuleUnchanged {
        /// (iii) #660's headline, restated with the flag: no LIVE responder anywhere is the cold
        /// bootstrap, and `clusterSize / 2` COLD responses plus self still activate.
        @Test
        void allColdBareMajority_stillActivates() {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(new SyncResponse<>(NODE_2, SavedState.empty(), ResponderState.COLD));
            engine.processSyncResponse(new SyncResponse<>(NODE_3, SavedState.empty(), ResponderState.COLD));

            assertThat(awaitActive(engine)).as("cold bootstrap: 2 COLD + self = 3 of 5").isTrue();
        }
    }

    @Nested
    class OwnStateFloorLiveArm {
        /// (iv) `ownStateFloor` is the more advanced of the PERSISTED and the LIVE phase. Persistence
        /// here never records anything — `RabiaPersistence.inMemory()` would not do: `applyRestoredState`
        /// saves the adopted phase into it, so the PERSISTED arm would cover this case and the test would
        /// stay green with the live arm deleted (it did, on the first run). The live phase reaches 99 by
        /// adopting a live majority; a far-future Propose forces a resync; a fresh live majority answers
        /// at phase 50 with a different snapshot. The floor is the live 99 alone, so nothing is
        /// installed and the engine activates on its own state.
        @Test
        void resyncFromActive_refusesALiveMajorityBehindTheLivePhase() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, neverPersists());

            engine.processSyncResponse(live(NODE_2, Phase.phase(99), AHEAD_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(99), AHEAD_SNAPSHOT));
            engine.processSyncResponse(live(NODE_4, Phase.phase(99), AHEAD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
            assertThat(stateMachine.lastRestored()).isEqualTo(AHEAD_SNAPSHOT);
            stateMachine.forgetRestored();
            // Far-future Propose: `MAX_PHASE_AHEAD` past the live phase → triggerResync → Syncing.
            engine.processPropose(new Propose<>(NODE_2, Phase.phase(99 + 200), farFutureBatch()));

            assertThat(awaitCondition(() -> !engine.isActive())).as("far-future Propose forces a resync").isTrue();
            engine.processSyncResponse(live(NODE_2, Phase.phase(50), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(50), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_4, Phase.phase(50), BEHIND_SNAPSHOT));

            assertThat(awaitActive(engine)).as("the node re-activates on its own state").isTrue();
            assertThat(stateMachine.lastRestored())
                .as("live phase 99 outranks the responders' 50 — the LIVE arm of the floor must refuse the install")
                .isNull();
        }
    }

    private static Batch<TestCommand> farFutureBatch() {
        return new Batch<>(Batch.Id.randomId(), List.of(CorrelationId.randomCorrelationId()), System.nanoTime(), List.of(new TestCommand("resync")));
    }

    /// Persistence that records nothing: `load()` is always empty, so only the LIVE phase can floor.
    private static RabiaPersistence<TestCommand> neverPersists() {
        record never() implements RabiaPersistence<TestCommand> {
            @Override public org.pragmatica.lang.Result<org.pragmatica.lang.Unit> append(RabiaProtocolMessage message) {
                return org.pragmatica.lang.Result.success(org.pragmatica.lang.Unit.unit());
            }

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

        return new never();
    }

    private static SyncResponse<TestCommand> live(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender, SavedState.savedState(snapshot, phase, List.of()), ResponderState.LIVE);
    }

    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, clusterSize),
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence,
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());

        assertThat(awaitCondition(() -> network.getMessages()
                                               .stream()
                                               .anyMatch(SyncRequest.class::isInstance)))
            .as("engine must have started its sync round before responses are delivered")
            .isTrue();

        return engine;
    }

    private static final class RecordingStateMachine extends TestStateMachine {
        private volatile byte[] lastRestored;

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            lastRestored = snapshot;

            return super.restoreSnapshot(snapshot);
        }

        byte[] lastRestored() {
            return lastRestored;
        }

        void forgetRestored() {
            lastRestored = null;
        }
    }

    private static boolean awaitActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

    /// Bounded observation, sleeping rather than spinning — see `RabiaSyncAdoptionQuorumTest`.
    private static boolean staysInactive(RabiaEngine<TestCommand> engine) throws InterruptedException {
        Thread.sleep(STAYS_INACTIVE_WINDOW_MILLIS);

        return !engine.isActive();
    }

    private static boolean awaitCondition(BooleanSupplier condition) {
        var deadline = System.nanoTime() + MILLISECONDS.toNanos(ACTIVATION_TIMEOUT_MILLIS);

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.onSpinWait();
        }

        return condition.getAsBoolean();
    }
}
