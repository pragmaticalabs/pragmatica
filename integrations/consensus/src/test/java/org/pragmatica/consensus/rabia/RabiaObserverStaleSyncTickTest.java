package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaEngineTest.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
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
    private RabiaEngine<TestCommand> engine;

    @AfterEach void stop() { if (engine != null) { engine.stop().await(); } }

    @Test void queuedSyncRetryTick_afterObserverActivation_doesNotPublishQuorumLoss() throws Exception {
        engine = observerEngine();
        adoptAsObserver();
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

    private void seedSyncResponses() throws Exception {
        var field = RabiaEngine.class.getDeclaredField("syncResponses");

        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        var responses = (Map<NodeId, SyncResponse<TestCommand>>) field.get(engine);

        responses.put(B, new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        responses.put(C, new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
    }

    private void runQueuedSyncTick() throws Exception {
        var tick = RabiaEngine.class.getDeclaredMethod("synchronize");

        tick.setAccessible(true);
        tick.invoke(engine);
        settle();
    }

    private void adoptAsObserver() {
        engine.clusterState(ClusterStateNotification.active());
        engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle();
    }

    private RabiaEngine<TestCommand> observerEngine() {
        var router = MessageRouter.mutable();

        router.addRoute(ClusterStateNotification.class, routed::add);
        var created = new RabiaEngine<>(new TestTopologyManager(A, 5), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
            ConsensusMetrics.noop(), false, RabiaPersistence.inMemory(), RabiaEngine.DEFAULT_PHASE_STALL_CHECK,
            ConsensusBridge.consensusBridge(router));
        assertThat(created.initializeVoters(new VoterConfiguration(0, ROSTER)).isSuccess()).isTrue();
        created.authorizeObservation();
        return created;
    }

    private void settle() {
        engine.settleForTesting().await();
        engine.settleForTesting().await();
    }
}
