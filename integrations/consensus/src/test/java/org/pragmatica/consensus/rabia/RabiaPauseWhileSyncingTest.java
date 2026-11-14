/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
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
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaPausedSyncResponseTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaPausedSyncResponseTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaPausedSyncResponseTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaPausedSyncResponseTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3211 / #2287: an engine paused while `Syncing` (quorum lost before it activated) must resume into a fresh sync
/// round when quorum returns, never straight into `Idle`. Before the fix the resume entered `Idle` with no sync round,
/// no adoption, no restart self-vouch and no participation record, so a restarted node behind a decided slot could
/// vote on that slot again.
class RabiaPauseWhileSyncingTest {
    private static final NodeId NODE_1 = nodeId("node-1").unwrap();
    private static final NodeId NODE_2 = nodeId("node-2").unwrap();
    private static final NodeId NODE_3 = nodeId("node-3").unwrap();

    private final List<RabiaEngine<TestCommand>> engines = new ArrayList<>();
    private final List<ScheduledCluster> clusters = new ArrayList<>();

    @AfterEach
    void tearDown() {
        engines.forEach(engine -> engine.stop().await());
        clusters.forEach(ScheduledCluster::stop);
    }

    /// (a) Syncing -> quorum loss -> quorum return: the engine is not active, starts a fresh sync round, and casts no
    /// ballot on a peer's proposal until synchronisation completes. RED at base: the resume entered `Idle`.
    @Test
    void pausedWhileSyncing_resumesIntoSynchronisation_andDoesNotVoteBeforeSyncCompletes() {
        var network = new TestClusterNetwork();
        var engine = syncingEngine(network, new CountingMarker());

        pauseAndResume(engine, network);

        assertThat(engine.isPaused()).as("quorum return must leave Paused").isFalse();
        assertThat(engine.isActive()).as("#3211: a node paused before it activated must not resume as an active voter").isFalse();
        assertThat(network.getMessages()).as("#3211: the resume must start a fresh sync round")
                                         .anyMatch(SyncRequest.class::isInstance);

        var batch = Batch.<TestCommand>create(new TestStateMachine().serializer(), List.of(new TestCommand("peer-proposal")));

        engine.processPropose(new Propose<>(NODE_2, engine.currentPhaseForTesting(), batch));
        settle(engine);

        assertThat(network.getMessages()).as("#3211: no ballot before synchronisation completes")
                                         .noneMatch(RabiaPauseWhileSyncingTest::isBallotOrDecision);
    }

    /// (c) #2287: the resumed engine reaches `Idle` only through activation, which writes the participation record
    /// before the node serves. RED at base: the resume entered `Idle` without ever calling `recordParticipation`.
    @Test
    void pausedWhileSyncing_activatesOnlyThroughTheParticipationRecord() {
        var network = new TestClusterNetwork();
        var marker = new CountingMarker();
        var engine = syncingEngine(network, marker);

        pauseAndResume(engine, network);

        assertThat(marker.records.get()).as("control: nothing activated yet, so no participation record").isZero();

        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle(engine);

        assertThat(engine.isActive()).as("the fresh sync round completes and activates the engine").isTrue();
        assertThat(marker.records.get()).as("#2287: the activation after the resume writes the participation record")
                                        .isPositive();
    }

    /// (b) Three engines. Slot 0 is decided by all; then A and C crash and restart behind the decided slot while B is cut
    /// off. Every SyncResponse is withheld, so neither restarted node can adopt and both stay `Syncing`: this isolates the
    /// resume path from adoption (two in-memory restarts that DID exchange responses would adopt each other's COLD state on
    /// the all-COLD bound; that is the majority-amnesia limitation, not #3211). Both pause while Syncing and quorum returns.
    /// They must not decide slot 0 a second time: no ballot or Decision from either, and every applied log stays a prefix
    /// of B's. RED at base: both resume `Idle` without adopting and decide slot 0 again with a different value.
    @Test
    void twoRestartedNodesPausedWhileSyncing_neverReDecideTheDecidedSlot() {
        var cluster = new ScheduledCluster(3, 0);

        clusters.add(cluster);
        cluster.start();

        var first = new RabiaEngineTest.TestCommand("first");

        cluster.engines.getFirst().apply(List.of(first));
        cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().contains(first)));
        cluster.settle();

        cluster.blocked = delivery -> delivery.source() == 1 || delivery.target() == 1
                                      || delivery.message() instanceof SyncResponse<?>;
        cluster.restart(0);
        cluster.restart(2);
        cluster.sent.clear();

        restarted(cluster).forEach(engine -> engine.clusterState(ClusterStateNotification.active()));
        cluster.pumpBounded(() -> false, 2_000);
        assertThat(restarted(cluster)).as("control: with every SyncResponse withheld neither restarted node adopts")
                                      .noneMatch(RabiaEngine::isActive);

        restarted(cluster).forEach(engine -> engine.clusterState(ClusterStateNotification.passive()));
        cluster.settle();
        assertThat(restarted(cluster)).as("control: quorum loss pauses both while Syncing").allMatch(RabiaEngine::isPaused);

        restarted(cluster).forEach(engine -> engine.clusterState(ClusterStateNotification.active()));
        cluster.settle();
        cluster.engines.getFirst().apply(List.of(new RabiaEngineTest.TestCommand("second")));
        cluster.pumpBounded(() -> false, 5_000);

        cluster.verifyPrefixes();
        assertThat(List.copyOf(cluster.sent)).as("#3211: neither restarted node votes or decides before adoption")
                                             .noneMatch(RabiaPauseWhileSyncingTest::isBallotOrDecision);
    }

    /// (d) #3211: an engine paused before it activated holds no adopted state, so it answers a peer's SyncRequest COLD,
    /// exactly as it did while Syncing. RED if the answer is LIVE (a peer could adopt its empty state as authoritative).
    @Test
    void pausedWhileSyncing_answersSyncRequestsCold() {
        var network = new TestClusterNetwork();
        var engine = syncingEngine(network, new CountingMarker());

        engine.clusterState(ClusterStateNotification.passive());
        settle(engine);
        assertThat(engine.isPaused()).as("control: paused while Syncing").isTrue();
        network.clearMessages();

        engine.handleSyncRequest(new SyncRequest(NODE_2));
        settle(engine);

        assertThat(responderStates(network)).as("a never-activated Paused engine answers COLD")
                                            .containsExactly(ResponderState.COLD);
    }

    /// (e) The other side of (d): an engine that had activated keeps answering LIVE while Paused.
    @Test
    void pausedAfterActivation_stillAnswersSyncRequestsLive() {
        var network = new TestClusterNetwork();
        var engine = syncingEngine(network, new CountingMarker());

        engine.processSyncResponse(new SyncResponse<>(NODE_2, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(NODE_3, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle(engine);
        assertThat(engine.isActive()).as("control: activated").isTrue();
        engine.clusterState(ClusterStateNotification.passive());
        settle(engine);
        assertThat(engine.isPaused()).as("control: paused after activation").isTrue();
        network.clearMessages();

        engine.handleSyncRequest(new SyncRequest(NODE_2));
        settle(engine);

        assertThat(responderStates(network)).as("an activated Paused engine answers LIVE")
                                            .containsExactly(ResponderState.LIVE);
    }

    private static List<ResponderState> responderStates(TestClusterNetwork network) {
        return network.getMessages()
                      .stream()
                      .filter(SyncResponse.class::isInstance)
                      .map(message -> ((SyncResponse<?>) message).responder())
                      .toList();
    }

    private static List<RabiaEngine<RabiaEngineTest.TestCommand>> restarted(ScheduledCluster cluster) {
        return List.of(cluster.engines.get(0), cluster.engines.get(2));
    }

    private RabiaEngine<TestCommand> syncingEngine(TestClusterNetwork network, ParticipationMarker marker) {
        var engine = new RabiaEngine<>(new TestTopologyManager(NODE_1, 3),
                                       network,
                                       new TestStateMachine(),
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(),
                                                                      timeSpan(60).seconds(),
                                                                      Option.some(marker)));

        engines.add(engine);
        engine.clusterState(ClusterStateNotification.active());
        settle(engine);
        assertThat(engine.isActive()).as("control: with no sync responses the engine stays Syncing").isFalse();

        return engine;
    }

    private static void pauseAndResume(RabiaEngine<TestCommand> engine, TestClusterNetwork network) {
        engine.clusterState(ClusterStateNotification.passive());
        settle(engine);
        assertThat(engine.isPaused()).as("control: quorum loss pauses a Syncing engine").isTrue();

        network.clearMessages();
        engine.clusterState(ClusterStateNotification.active());
        settle(engine);
    }

    private static boolean isBallotOrDecision(ProtocolMessage message) {
        return message instanceof VoteRound1 || message instanceof VoteRound2 || message instanceof Decision<?>;
    }

    private static void settle(RabiaEngine<TestCommand> engine) {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }

    /// Counts participation records; reads as UNKNOWN, exactly as the default marker does.
    private static final class CountingMarker implements ParticipationMarker {
        private final AtomicInteger records = new AtomicInteger();

        @Override
        public Participation resolve() {
            return Participation.UNKNOWN;
        }

        @Override
        public Result<Unit> recordParticipation() {
            records.incrementAndGet();

            return Result.unitResult();
        }
    }
}
