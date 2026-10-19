package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Stale input crossing a pause must not change the state of a `Paused` engine, and a late input must not re-run a settled one.
/// A sync response is accepted only inside an OPEN sync round, which a request opens and a pause or an activation closes: a
/// response to a request sent before the pause that arrives after it can no longer take a paused voter to `Active` while quorum
/// is lost. Likewise a current-slot decision applied by a paused observer does not move it out of `Paused`; only a quorum ACTIVE does.
class RabiaStaleSyncInputTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final ClusterConfig ROSTER = new ClusterConfig(List.of(A, B, C));
    private final TestClusterNetwork network = new TestClusterNetwork();
    private final TestStateMachine machine = new TestStateMachine();
    private final List<ClusterStateNotification> routed = new CopyOnWriteArrayList<>();
    private RabiaEngine<TestCommand> engine;

    @AfterEach void stop() { if (engine != null) { engine.stop().await(); } }

    @Test void lateSyncResponses_afterPause_doNotTakeAPausedVoterToActive() {
        engine = newEngine(false);
        adoptFromPeers();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(engine.isPaused()).as("control: quorum loss paused the voter").isTrue();
        var published = routed.size();

        respond(B);
        respond(C);
        settle();

        assertThat(engine.isPaused()).as("responses to a request sent before the pause cannot resume a paused engine").isTrue();
        assertThat(routed).as("nothing is published: no ACTIVE without a quorum ACTIVE").hasSize(published);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isActive()).as("the quorum ACTIVE resumes it").isTrue();
    }

    @Test void lateSyncResponses_afterAPauseDuringSyncing_doNotTakeTheEngineToActive() {
        engine = newEngine(false);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isActive() || engine.isPaused()).as("control: the engine is syncing, a round is open").isFalse();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(engine.isPaused()).as("control: the pause arrived while syncing").isTrue();

        respond(B);
        respond(C);
        settle();

        assertThat(engine.isPaused()).as("answers to the request sent before the pause cannot activate a paused engine").isTrue();
        assertThat(engine.isActive()).isFalse();
    }

    @Test void lateSyncResponse_afterAnObserverAdopted_doesNotAdoptAgain() {
        engine = newEngine(true);
        adoptFromPeers();
        var settledPhase = engine.currentPhaseForTesting();
        var ahead = RabiaPersistence.SavedState.<TestCommand>savedState(new byte[]{1}, Phase.phase(7), List.of());

        engine.processSyncResponse(new SyncResponse<>(B, ahead, ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, ahead, ResponderState.COLD));
        settle();

        assertThat(engine.currentPhaseForTesting()).as("the round completed at the adoption: later responses are not a new adoption").isEqualTo(settledPhase);
    }

    @Test void currentSlotDecision_onAPausedObserver_leavesItPaused_andTheQuorumActiveResumesObserving() {
        engine = newEngine(true);
        adoptFromPeers();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(engine.isPaused()).as("control: quorum loss paused the observer").isTrue();

        var before = engine.currentPhaseForTesting();

        engine.processDecision(new Decision<>(B, 0, before, StateValue.V0, Batch.emptyBatch(), Option.none()));
        settle();

        assertThat(engine.currentPhaseForTesting().value()).as("control: the decision was applied and the observer advanced").isGreaterThan(before.value());
        assertThat(engine.isPaused()).as("applying a decision does not resume a paused observer").isTrue();
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isObserving()).as("the quorum ACTIVE resumes it as an observer").isTrue();
    }

    @Test void answersCollectedBeforeAPause_doNotCountInTheNextRound() throws Exception {
        engine = newEngine(true);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        respond(B);
        settle();
        assertThat(engine.isObserving()).as("control: one answer is below the adoption threshold").isFalse();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(engine.isPaused()).as("control: paused while syncing, with the earlier answer collected").isTrue();

        var nextEpisode = RabiaEngine.class.getDeclaredMethod("doClusterConnected");

        nextEpisode.setAccessible(true);
        nextEpisode.invoke(engine);
        respond(C);
        settle();

        assertThat(engine.isObserving()).as("B's answer belongs to the request sent before the pause: one fresh answer is not enough").isFalse();
        respond(B);
        settle();
        assertThat(engine.isObserving()).as("control: two fresh answers adopt").isTrue();
    }

    @Test void lateSyncResponse_afterAVoterActivated_doesNotAdoptAgain() {
        engine = newEngine(false);
        adoptFromPeers();
        assertThat(engine.isActive()).as("control: the voter activated").isTrue();
        var settledPhase = engine.currentPhaseForTesting();
        var ahead = RabiaPersistence.SavedState.<TestCommand>savedState(new byte[]{1}, Phase.phase(7), List.of());

        engine.processSyncResponse(new SyncResponse<>(B, ahead, ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, ahead, ResponderState.COLD));
        settle();

        assertThat(engine.currentPhaseForTesting()).as("neither the active state nor the closed round lets a late answer reinstall state").isEqualTo(settledPhase);
    }

    @Test void aSyncRequestGoingOut_opensTheRound_forTheEpisodeStart() throws Exception {
        engine = newEngine(false);
        setSyncRoundOpen(false);

        engine.clusterState(ClusterStateNotification.active());
        settle();
        respond(B);
        respond(C);
        settle();

        assertThat(engine.isActive()).as("the episode start sent a request: its answers are accepted").isTrue();
    }

    @Test void aSyncRequestGoingOut_opensTheRound_forTheRetryTick() throws Exception {
        engine = newEngine(false);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        setSyncRoundOpen(false);

        var tick = RabiaEngine.class.getDeclaredMethod("synchronize");

        tick.setAccessible(true);
        tick.invoke(engine);
        settle();
        respond(B);
        respond(C);
        settle();

        assertThat(engine.isActive()).as("the retry tick sent a request: its answers are accepted").isTrue();
    }

    private void setSyncRoundOpen(boolean open) throws Exception {
        var field = RabiaEngine.class.getDeclaredField("syncRoundOpen");

        field.setAccessible(true);
        field.setBoolean(engine, open);
    }

    private void respond(NodeId peer) {
        engine.processSyncResponse(new SyncResponse<>(peer, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
    }

    private void adoptFromPeers() {
        engine.clusterState(ClusterStateNotification.active());
        respond(B);
        respond(C);
        settle();
    }

    private RabiaEngine<TestCommand> newEngine(boolean observer) {
        var router = MessageRouter.mutable();

        router.addRoute(ClusterStateNotification.class, routed::add);
        var created = new RabiaEngine<>(new TestTopologyManager(A, 5), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
            ConsensusMetrics.noop(), false, RabiaPersistence.inMemory(), RabiaEngine.DEFAULT_PHASE_STALL_CHECK,
            ConsensusBridge.consensusBridge(router));
        assertThat(created.initializeVoters(new VoterConfiguration(0, ROSTER)).isSuccess()).isTrue();
        if (observer) {
            created.authorizeObservation();
        }
        return created;
    }

    private void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }
}
