package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaEngineTest.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.consensus.topology.ObserverResumedNotification;
import org.pragmatica.messaging.MessageRouter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A joining observer (an auto-heal replacement) activates while a sync-retry tick is already queued behind
/// the adoption. The late tick must find the engine settled: it must not re-enter `Syncing`, because the
/// resulting PASSIVE reaches the app layer as a QuorumLost that the cluster never had.
class RabiaObserverStaleSyncTickTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final ClusterConfig ROSTER = new ClusterConfig(List.of(A, B, C));
    private final TestClusterNetwork network = new TestClusterNetwork();
    private final TestStateMachine machine = new TestStateMachine();
    private final List<ClusterStateNotification> routed = new CopyOnWriteArrayList<>();
    private final List<ObserverResumedNotification> resumed = new CopyOnWriteArrayList<>();
    private RabiaEngine<TestCommand> engine;

    @AfterEach void stop() { if (engine != null) { engine.stop().await(); } }

    @Test void queuedSyncRetryTick_afterObserverActivation_doesNotPublishQuorumLoss() throws Exception {
        engine = observerEngine();
        adoptFromPeers();
        assertThat(engine.isObserving()).as("control: the engine really activated as an observer").isTrue();
        assertThat(routed).as("control: nothing was published while joining").isEmpty();

        runQueuedSyncTick();

        assertThat(engine.isObserving()).as("the late tick leaves the observer observing").isTrue();
        assertThat(routed).as("no PASSIVE reaches the app layer: the observer never had a quorum to lose").isEmpty();
    }

    @Test void syncRetryTick_thatAdoptsAsObserver_doesNotFallThroughToSyncing() throws Exception {
        engine = observerEngine();
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isObserving()).as("control: still syncing before the tick").isFalse();
        seedSyncResponses();

        runQueuedSyncTick();

        assertThat(engine.isObserving()).as("the tick adopted the responses and the observer stays observing").isTrue();
        assertThat(routed).as("adopting as an observer publishes nothing, and the tail of the tick must not undo it").isEmpty();
    }

    @Test void syncRetryTick_whileObserverStillSyncing_stillRetries() throws Exception {
        engine = observerEngine();
        engine.clusterState(ClusterStateNotification.active());
        settle();
        network.clearMessages();

        runQueuedSyncTick();

        assertThat(engine.isObserving()).as("no responses yet: the observer has not adopted anything").isFalse();
        assertThat(network.getMessages()).as("the retry still asks the cluster for state")
                                         .anyMatch(RabiaProtocolMessage.Asynchronous.SyncRequest.class::isInstance);
    }

    @Test void queuedSyncRetryTick_onActiveVoter_leavesItActive() throws Exception {
        engine = voterEngine();
        adoptFromPeers();
        assertThat(engine.isActive()).as("control: the voter really activated").isTrue();
        var published = routed.size();

        runQueuedSyncTick();

        assertThat(engine.isActive()).as("the late tick leaves the voter active").isTrue();
        assertThat(routed).as("nothing further is published").hasSize(published);
    }

    @Test void queuedSyncRetryTick_onSettledObserver_adoptsNothingFromLeftoverResponses() throws Exception {
        engine = observerEngine();
        adoptFromPeers();
        var settledPhase = engine.currentPhaseForTesting();
        seedSyncResponses(RabiaPersistence.SavedState.<TestCommand>savedState(new byte[]{1}, Phase.phase(7), List.of()));

        runQueuedSyncTick();

        assertThat(engine.currentPhaseForTesting()).as("a settled observer does not re-adopt from responses a tick finds").isEqualTo(settledPhase);
    }

    @Test void queuedSyncRetryTick_onPausedEngine_leavesItPaused_andTheNextActiveResumesIt() throws Exception {
        engine = voterEngine();
        adoptFromPeers();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(engine.isPaused()).as("control: quorum loss paused the voter").isTrue();
        var published = routed.size();

        runQueuedSyncTick();

        assertThat(engine.isPaused()).as("a tick queued before the pause finds nothing to synchronize: a paused engine resumes on ACTIVE, not through a sync round").isTrue();
        assertThat(routed).as("the stale tick publishes nothing").hasSize(published);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isActive()).as("the legitimate ACTIVE lands: the engine resumes").isTrue();
    }

    @Test void observerPausedForQuorumLoss_thenResumed_signalsObservingAgainOnce() {    @Test void observerPausedForQuorumLoss_thenResumed_signalsObservingAgainOnce() {
        engine = observerEngine();
        adoptFromPeers();
        resumed.clear();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(routed).as("control: the pause published PASSIVE").extracting(ClusterStateNotification::state)
                          .containsExactly(ClusterStateNotification.State.PASSIVE);
        assertThat(resumed).as("control: nothing is signalled while the observer is out of Observing").isEmpty();

        engine.clusterState(ClusterStateNotification.active());
        settle();

        assertThat(engine.isObserving()).as("the observer resumed to Observing").isTrue();
        assertThat(resumed).as("the recovery edge of its PASSIVE").hasSize(1);
    }

    @Test void observerAdoptedWithoutAnyPriorPassive_signalsObservingOnce_andPublishesNoClusterState() {
        engine = observerEngine();

        adoptFromPeers();

        assertThat(resumed).as("the engine signals Observing; the aggregator drops it because no warning is open").hasSize(1);
        assertThat(routed).as("an observer's adoption publishes no ACTIVE/PASSIVE").isEmpty();
    }

    private void seedSyncResponses() throws Exception {
        seedSyncResponses(RabiaPersistence.SavedState.empty());
    }

    private void seedSyncResponses(RabiaPersistence.SavedState<TestCommand> state) throws Exception {
        var field = RabiaEngine.class.getDeclaredField("syncResponses");

        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var responses = (Map<NodeId, SyncResponse<TestCommand>>) field.get(engine);

        responses.put(B, new SyncResponse<>(B, state, ResponderState.COLD));
        responses.put(C, new SyncResponse<>(C, state, ResponderState.COLD));
    }

    private void runQueuedSyncTick() throws Exception {
        var tick = RabiaEngine.class.getDeclaredMethod("synchronize");

        tick.setAccessible(true);
        tick.invoke(engine);
        settle();
    }

    private void adoptFromPeers() {
        engine.clusterState(ClusterStateNotification.active());
        engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle();
    }

    private RabiaEngine<TestCommand> observerEngine() {
        var created = engineWithRoster();

        created.authorizeObservation();
        return created;
    }

    private RabiaEngine<TestCommand> voterEngine() {
        return engineWithRoster();
    }

    private RabiaEngine<TestCommand> engineWithRoster() {
        var router = MessageRouter.mutable();

        router.addRoute(ClusterStateNotification.class, routed::add);
        router.addRoute(ObserverResumedNotification.class, resumed::add);
        var created = new RabiaEngine<>(new TestTopologyManager(A, 5), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
            ConsensusMetrics.noop(), false, RabiaPersistence.inMemory(), RabiaEngine.DEFAULT_PHASE_STALL_CHECK,
            ConsensusBridge.consensusBridge(router));
        assertThat(created.initializeVoters(new VoterConfiguration(0, ROSTER)).isSuccess()).isTrue();
        return created;
    }

    private void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }
}
