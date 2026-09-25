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
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
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

/// #667 round 2 — adoption thresholds on RESPONSES, and liveness only chooses the SOURCE.
///
/// The first cut of #667 thresholded on LIVE responders (`clusterSize / 2 + 1` of them). That is a
/// quantity the blocked nodes cannot increase: once the first node activates it answers LIVE, every
/// remaining joiner sees `live > 0`, flips to the stricter bound, and they answer each other COLD.
/// The live count can never grow because the nodes that would grow it are exactly the ones blocked,
/// and no path exits `Syncing` — `syncRounds` only WARNs. A half-started cluster could never finish.
///
/// Cold start was never the defective arm: at t=0 every responder is COLD, `live == 0`, and #660's
/// rule is reached. The defect is the MIXED state moments later.
///
/// The amended rule, which this class pins:
/// - adoption needs a quorum of RESPONSES — `clusterSize / 2 + 1` responders, counted regardless of
///   LIVE/COLD/UNKNOWN. A response quorum proves the node is not acting on a minority partition's view.
/// - within that quorum, at least one LIVE responder → adopt the maximum over the LIVE responders,
///   with `ownStateFloor` as the belt.
/// - no LIVE responder → #660's cold-bootstrap rule, unchanged: `clusterSize / 2` responses, self floor.
/// - UNKNOWN counts as COLD when choosing the source, and counts as a response toward the quorum.
class RabiaSyncAdoptionResponseQuorumTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();
    private static final NodeId NODE_4 = nodeId("node-4").unwrap();
    private static final NodeId NODE_5 = nodeId("node-5").unwrap();
    private static final long ACTIVATION_TIMEOUT_MILLIS = 5_000;
    private static final long STAYS_INACTIVE_WINDOW_MILLIS = 300;
    private static final byte[] LIVE_SNAPSHOT = "live".getBytes(StandardCharsets.UTF_8);
    private static final byte[] COLD_SNAPSHOT = "cold".getBytes(StandardCharsets.UTF_8);
    private static final byte[] LIVE_AHEAD_SNAPSHOT = "live-ahead".getBytes(StandardCharsets.UTF_8);

    private final List<RabiaEngine<TestCommand>> engines = new CopyOnWriteArrayList<>();

    @AfterEach
    void stopEngines() {
        engines.forEach(engine -> engine.stop().await());
    }

    @Nested
    class AHalfStartedClusterFinishesStarting {
        /// THE BLOCKING ARM. n=3, one peer already LIVE and the other still COLD — every peer answered.
        /// Under the live-responder bound this needed 2 LIVE of 2 peers, which the COLD peer could only
        /// supply by activating first, which it could not do for the same reason. Both joiners wedged.
        @Test
        void n3_oneLivePeerAndOneColdPeer_everyPeerAnswered_activates() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(3, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine))
                .as("2 of 3 responded: a response quorum with one LIVE responder adopts and activates")
                .isTrue();
            assertThat(stateMachine.lastRestored())
                .as("liveness chooses the source: the LIVE responder's state, not the COLD one's")
                .isEqualTo(LIVE_SNAPSHOT);
        }

        /// Same shape at n=5: one LIVE peer, three COLD peers, every peer answering.
        @Test
        void n5_oneLivePeerAndThreeColdPeers_everyPeerAnswered_activates() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_4, Phase.phase(4), COLD_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_5, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).as("4 of 5 responded — a response quorum").isTrue();
            assertThat(stateMachine.lastRestored()).isEqualTo(LIVE_SNAPSHOT);
        }

        /// The quorum is reached by the THIRD response at n=5, and adoption happens on arrival rather
        /// than on a timer: two responses are not enough, the third is.
        @Test
        void n5_theThirdResponseCompletesTheQuorum() throws InterruptedException {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(staysInactive(engine)).as("2 of 5 responses is not a response quorum").isTrue();

            engine.processSyncResponse(cold(NODE_4, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).as("the third response completes the quorum").isTrue();
        }
    }

    @Nested
    class AResponseMinorityStillWaits {
        /// The arm the old live bound existed to protect, restated on responses: a single stale LIVE
        /// responder in a minority partition is not a quorum and must not be adopted from.
        @Test
        void n5_aLoneStaleLiveResponder_isNotAQuorum_andWaits() throws InterruptedException {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("1 of 5 is a minority partition's view — adopting it can discard a commit the majority holds")
                .isTrue();
            assertThat(stateMachine.lastRestored()).as("nothing installed while waiting").isNull();
        }

        /// Two responders out of five are still a minority, LIVE or not.
        @Test
        void n5_twoResponders_isStillAMinority_andWaits() throws InterruptedException {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine)).as("2 of 5 responses is not a response quorum").isTrue();
        }
    }

    @Nested
    class ColdRuleUnchanged {
        /// #660's bare-majority cold start: no LIVE responder anywhere, `clusterSize / 2` responses,
        /// self completes the majority as the floor.
        @Test
        void n5_allColdBareMajority_stillActivates() {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(cold(NODE_2, Phase.phase(4), COLD_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).as("cold bootstrap: 2 COLD + self = 3 of 5").isTrue();
        }

        /// All responders UNKNOWN is the cold path, not a blocked one: UNKNOWN is COLD.
        @Test
        void n5_allUnknownResponders_takeTheColdRule() {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(unknown(NODE_2, Phase.phase(4)));
            engine.processSyncResponse(unknown(NODE_3, Phase.phase(4)));

            assertThat(awaitActive(engine)).as("no LIVE responder — UNKNOWN is COLD, so the cold rule applies").isTrue();
        }

        /// Mixed UNKNOWN and COLD is still the cold path.
        @Test
        void n5_mixedUnknownAndCold_takeTheColdRule() {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(unknown(NODE_2, Phase.phase(4)));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
        }

        /// An UNKNOWN responder counts as a RESPONSE toward the quorum and never toward the live
        /// majority — i.e. exactly as COLD, which is the one part of #667 that survived every revision
        /// of the rule. Asserted as an EQUIVALENCE: the same scenario twice, changing only the flag.
        ///
        /// I first wrote this asserting that UNKNOWN is "never the adoption source". That is stronger
        /// than COLD semantics and the rule never said it: when LIVE responders are a minority of the
        /// quorum the source is every response, COLD ones included, and an UNKNOWN is one of those. The
        /// expectation was wrong, not the code — confirmed by running the COLD arm and getting the same
        /// bytes.
        @Test
        void n3_unknownIsIndistinguishableFromCold() {
            assertThat(adoptedWithSecondResponder(ResponderState.UNKNOWN))
                .as("UNKNOWN must behave exactly as COLD in the adoption decision")
                .isEqualTo(adoptedWithSecondResponder(ResponderState.COLD));
        }

        /// n=3, one LIVE peer at phase 10 and a second responder ahead at 500 carrying `flag`.
        private byte[] adoptedWithSecondResponder(ResponderState flag) {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(3, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(new SyncResponse<>(NODE_3,
                                                         SavedState.savedState(COLD_SNAPSHOT, Phase.phase(500), List.of()),
                                                         flag));

            assertThat(awaitActive(engine)).isTrue();

            return stateMachine.lastRestored();
        }
    }

    @Nested
    class ClusterSizes {
        /// n=1: no peers, no responses, self is the whole majority. No response can ever arrive, so
        /// activation here rides the sync RETRY tick rather than the arrival path — hence the short
        /// retry interval; with the 60s the other cases use, this test would time out against a
        /// perfectly healthy engine.
        @Test
        void n1_activatesWithNoPeers() {
            var engine = coldStarted(1, new RecordingStateMachine(), RabiaPersistence.inMemory(), timeSpan(50).millis());

            assertThat(awaitActive(engine)).as("a single-node cluster has no peer to adopt from").isTrue();
        }

        /// n=2 with the one peer COLD: `clusterSize / 2` is 1, the cold rule activates.
        @Test
        void n2_theOneColdPeer_activatesUnderTheColdRule() {
            var engine = coldStarted(2, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(cold(NODE_2, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
        }

        /// n=2 with the one peer LIVE: the response quorum is 2 and only one peer exists, so the node
        /// waits. Even sizes are not the supported topology; this pins the behaviour rather than
        /// endorsing it.
        @Test
        void n2_theOneLivePeer_cannotFormAResponseQuorum_andWaits() throws InterruptedException {
            var engine = coldStarted(2, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine)).as("clusterSize/2+1 = 2 responders, and n=2 has one peer").isTrue();
        }
    }

    @Nested
    class ResponderFlips {
        /// A responder answering twice replaces its own entry rather than adding one: a peer that was
        /// LIVE and answers again COLD must not leave a phantom LIVE response behind.
        @Test
        void aResponderFlippingLiveToCold_doesNotDoubleCount_andDropsTheLiveSource() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(3, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_2, Phase.phase(4), COLD_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).as("two responders, both now COLD — the cold rule applies").isTrue();
            assertThat(stateMachine.lastRestored())
                .as("the flipped responder's LIVE state must not survive as the adoption source")
                .isEqualTo(COLD_SNAPSHOT);
        }
    }

    @Nested
    class SelfVouchesForItsOwnHistory {
        /// OWNER RULING (c). n=3 with one node permanently down and the survivor LIVE: only one peer can
        /// ever answer. A self that holds durable state counts toward the adoption majority, so
        /// `clusterSize / 2` = 1 response admits it and the cluster regains a quorum.
        ///
        /// This is the case that kept `NodeLifecyclePeriodicArmingForgeTest` red: `emberCluster(3, …)`
        /// holds the third node back, so once one node activates the joiner sees a LIVE responder and
        /// can never collect a second response.
        @Test
        void n3_oneLiveSurvivor_selfDurable_activates() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(3, stateMachine, durableAt(Phase.phase(5)));

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(awaitActive(engine))
                .as("self holds durable state, so self + one responder is a majority of three")
                .isTrue();
            assertThat(stateMachine.lastRestored()).isEqualTo(LIVE_SNAPSHOT);
        }

        /// The control that keeps #667 shut: the SAME topology with an amnesiac self must still wait.
        /// Its floor is `Phase.ZERO`, so it contributes nothing to the majority it would be counted in.
        @Test
        void n3_oneLiveSurvivor_selfAmnesiac_stillWaits() throws InterruptedException {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(3, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("an amnesiac self cannot vouch, so one responder of three is a minority view")
                .isTrue();
            assertThat(stateMachine.lastRestored()).isNull();
        }

        /// n=5: a vouching self needs `clusterSize / 2` = 2 responses …
        @Test
        void n5_selfDurable_twoResponsesSuffice() {
            var engine = coldStarted(5, new RecordingStateMachine(), durableAt(Phase.phase(5)));

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
        }

        /// … and an amnesiac self still needs three. This is the #667 residual, preserved verbatim.
        @Test
        void n5_selfAmnesiac_twoResponsesAreStillAMinority() throws InterruptedException {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_3, Phase.phase(4), COLD_SNAPSHOT));

            assertThat(staysInactive(engine)).isTrue();
        }
    }

    @Nested
    class AShrinkingClusterExercisesTheLiveFilter {
        /// The LIVE filter SELECTS only when the collected set is larger than the requirement, and on
        /// the arrival path the set is normally exactly the requirement — so "a live majority among
        /// them" reduces to "all of them are LIVE", where filtering removes nothing. The one way the
        /// set outgrows the requirement is `clusterSize()` falling mid-round.
        ///
        /// n=5, amnesiac self: two LIVE responses are short of the requirement of three, so nothing
        /// happens. The cluster then shrinks to three — requirement two, live majority two — and a COLD
        /// response arrives carrying a far higher phase. LIVE is now a majority of the collected set,
        /// so the COLD response must NOT be the source.
        @Test
        void desiredCapacityShrinkingMidRound_doesNotChangeElectorate() {
            var stateMachine = new RecordingStateMachine();
            var topology = new ShrinkingTopology(NODE_1, 5);
            var engine = coldStarted(topology, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(20), LIVE_AHEAD_SNAPSHOT));

            topology.shrinkTo(3);
            engine.processSyncResponse(cold(NODE_4, Phase.phase(500), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
            assertThat(stateMachine.lastRestored())
                .as("desired capacity changes cannot discard evidence from the fixed five-voter electorate")
                .isEqualTo(COLD_SNAPSHOT);
        }

        /// The control, and the whole point of the pair: WITHOUT the shrink the identical three
        /// responses are exactly the requirement at n=5, LIVE is 2 of 3 and not a majority of five, so
        /// the source is every response and the COLD one at phase 500 wins. Same inputs, opposite
        /// outcome — which is what makes the filter observable rather than inert.
        @Test
        void withoutTheShrink_theSameThreeResponsesAdoptTheColdOne() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), LIVE_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(20), LIVE_AHEAD_SNAPSHOT));
            engine.processSyncResponse(cold(NODE_4, Phase.phase(500), COLD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
            assertThat(stateMachine.lastRestored())
                .as("LIVE is a minority of five, so the source is the whole quorum")
                .isEqualTo(COLD_SNAPSHOT);
        }
    }

    /// Persistence reporting a fixed durable snapshot: a node that restarted from disk.
    private static RabiaPersistence<TestCommand> durableAt(Phase phase) {
        record durable(Phase phase) implements RabiaPersistence<TestCommand> {
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
                return Option.some(SavedState.savedState(COLD_SNAPSHOT, phase, List.of()));
            }
        }

        return new durable(phase);
    }

    /// `clusterSize()` is a derived cell fed from the KV `coreCount`; it can fall while a sync round is
    /// collecting.
    private static final class ShrinkingTopology extends TestTopologyManager {
        private volatile int size;

        ShrinkingTopology(NodeId self, int size) {
            super(self, size);
            this.size = size;
        }

        @Override
        public int clusterSize() {
            return size;
        }

        void shrinkTo(int newSize) {
            size = newSize;
        }
    }

    private static SyncResponse<TestCommand> live(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender, SavedState.savedState(snapshot, phase, List.of()), ResponderState.LIVE);
    }

    private static SyncResponse<TestCommand> cold(NodeId sender, Phase phase, byte[] snapshot) {
        return new SyncResponse<>(sender, SavedState.savedState(snapshot, phase, List.of()), ResponderState.COLD);
    }

    private static SyncResponse<TestCommand> unknown(NodeId sender, Phase phase) {
        return new SyncResponse<>(sender,
                                  SavedState.savedState(COLD_SNAPSHOT, phase, List.of()),
                                  ResponderState.UNKNOWN);
    }

    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence) {
        return coldStarted(clusterSize, stateMachine, persistence, timeSpan(60).seconds());
    }

    private RabiaEngine<TestCommand> coldStarted(TestTopologyManager topology,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence) {
        return coldStarted(topology, stateMachine, persistence, timeSpan(60).seconds(), true);
    }

    private RabiaEngine<TestCommand> coldStarted(int clusterSize,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence,
                                                 TimeSpan syncRetryInterval) {
        return coldStarted(new TestTopologyManager(NODE_1, clusterSize),
                           stateMachine,
                           persistence,
                           syncRetryInterval,
                           clusterSize > 1);
    }

    private RabiaEngine<TestCommand> coldStarted(TestTopologyManager topology,
                                                 StateMachine<TestCommand> stateMachine,
                                                 RabiaPersistence<TestCommand> persistence,
                                                 TimeSpan syncRetryInterval,
                                                 boolean awaitSyncRequest) {
        var network = new TestClusterNetwork();
        var engine = new RabiaEngine<>(topology,
                                       network,
                                       stateMachine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), syncRetryInterval),
                                       ConsensusMetrics.noop(),
                                       false,
                                       persistence,
                                       timeSpan(50).millis());

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());

        if (awaitSyncRequest) {
            assertThat(awaitCondition(() -> network.getMessages()
                                                   .stream()
                                                   .anyMatch(SyncRequest.class::isInstance)))
                .as("engine must have started its sync round before responses are delivered")
                .isTrue();
        }

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
    }

    private static boolean awaitActive(RabiaEngine<TestCommand> engine) {
        return awaitCondition(engine::isActive);
    }

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
