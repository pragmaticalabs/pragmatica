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
/// The rule now, per response (re-evaluated on every arrival, never on a timer):
/// - LIVE responders ≥ ⌊n/2⌋+1: adopt the most advanced LIVE state. A live majority intersects every
///   majority that could have committed anything, so its maximum is at or past every commit.
/// - no LIVE responder at all: #660's cold rule, unchanged — `clusterSize / 2` responses, self as floor.
/// - some LIVE responders but fewer than a majority: keep collecting. A node rejoining a cluster that
///   has no live majority WAITS by design — that cluster has no quorum either.
/// UNKNOWN (an ordinal this node cannot name) counts as COLD, so an unreadable flag never loosens the bound.
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
    class LiveMinorityMustWait {
        /// (i) THE #667 RESIDUAL. n=5, self restarted with in-memory persistence (floor 0). Two LIVE
        /// peers answer, both behind the cluster's latest commit. Before #667 this was `clusterSize / 2`
        /// responses and the floor could not refuse — adopted, commit lost. Two live peers plus a
        /// history-less self are not a majority that witnessed anything.
        @Test
        void twoLiveResponsesOfFive_isALiveMinority_andMustNotAdopt() throws InterruptedException {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));

            assertThat(staysInactive(engine))
                .as("2 LIVE of 5 is not a live majority — adopting their state can discard a commit the other two hold")
                .isTrue();
            assertThat(stateMachine.lastRestored()).as("nothing installed while waiting").isNull();
        }

        /// A COLD response does not count toward the live majority: two LIVE plus one COLD is still a
        /// live minority even though three responses would have satisfied the old count.
        @Test
        void twoLivePlusOneCold_isStillALiveMinority() throws InterruptedException {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(new SyncResponse<>(NODE_4, SavedState.empty(), ResponderState.COLD));

            assertThat(staysInactive(engine))
                .as("a COLD responder carries no live history and cannot complete a LIVE majority")
                .isTrue();
        }

        /// UNKNOWN is COLD for adoption: two LIVE plus one UNKNOWN must wait like two LIVE plus one COLD.
        @Test
        void unknownResponderState_countsAsCold() throws InterruptedException {
            var engine = coldStarted(5, new RecordingStateMachine(), RabiaPersistence.inMemory());

            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(new SyncResponse<>(NODE_4, SavedState.empty(), ResponderState.UNKNOWN));

            assertThat(staysInactive(engine)).as("an unreadable flag must never loosen the bound").isTrue();
        }
    }

    @Nested
    class LiveMajorityAdoptsItsMaximum {
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

        /// A COLD response cannot outrank the live majority's maximum, even if its snapshot claims a
        /// higher phase: cold state is a persisted picture of unknown age, live state is the cluster.
        @Test
        void coldResponseAhead_isNotAdoptedOverTheLiveMajority() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

            engine.processSyncResponse(new SyncResponse<>(NODE_1, SavedState.savedState(BEHIND_SNAPSHOT, Phase.phase(500), List.of()), ResponderState.COLD));
            engine.processSyncResponse(live(NODE_2, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_3, Phase.phase(10), BEHIND_SNAPSHOT));
            engine.processSyncResponse(live(NODE_4, Phase.phase(99), AHEAD_SNAPSHOT));

            assertThat(awaitActive(engine)).isTrue();
            assertThat(stateMachine.lastRestored()).isEqualTo(AHEAD_SNAPSHOT);
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
        /// (iv) `ownStateFloor` is the more advanced of the PERSISTED and the LIVE phase. Persistence is
        /// in-memory here (persisted phase stays 0); the live phase reaches 99 by adopting a live
        /// majority; then a far-future Propose forces a resync, and a fresh live majority answers at
        /// phase 50 with a different snapshot. The floor is the live 99, so nothing is installed and
        /// the engine activates on its own state.
        @Test
        void resyncFromActive_refusesALiveMajorityBehindTheLivePhase() {
            var stateMachine = new RecordingStateMachine();
            var engine = coldStarted(5, stateMachine, RabiaPersistence.inMemory());

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
