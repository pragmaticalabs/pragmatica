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

/// #1790: a voter removed from the electorate while the cluster keeps quorum is an observer that still applies
/// committed decisions. The app-routing sink (the `ClusterStateNotification`s the bridge puts on the router) must
/// see that as a DEMOTION, never as quorum loss, while a genuine quorum loss must still arrive as plain PASSIVE.
class RabiaDemotionNotificationTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final NodeId D = new NodeId("node-4");
    private static final ClusterConfig GENESIS_ROSTER = new ClusterConfig(List.of(A, B, C));
    private static final ClusterConfig WITHOUT_SELF = new ClusterConfig(List.of(B, C, D));
    private final TestClusterNetwork network = new TestClusterNetwork();
    private final TestStateMachine machine = new TestStateMachine();
    private final List<ClusterStateNotification> routed = new CopyOnWriteArrayList<>();
    private RabiaEngine<TestCommand> engine;

    @AfterEach void stop() { if (engine != null) { engine.stop().await(); } }

    @Test void removalOfSelf_whileQuorumHolds_isPublishedAsDemotion_notQuorumLoss() {
        engine = activeVoter();
        assertThat(routed).as("control: the voter was published ACTIVE first")
                          .extracting(ClusterStateNotification::state)
                          .containsExactly(ClusterStateNotification.State.ACTIVE);

        engine.processDecision(removalDecision());
        settle();

        assertThat(engine.isObserving()).as("control: the engine really became an observer").isTrue();
        assertThat(routed).as("no quorum-loss signal reaches the app-routing sink").noneMatch(RabiaDemotionNotificationTest::isQuorumLoss);
        assertThat(routed.getLast().demoted()).as("the demotion itself is published").isTrue();
    }

    @Test void quorumLoss_asVoter_isPublishedAsQuorumLoss() {
        engine = activeVoter();

        engine.clusterState(ClusterStateNotification.passive());
        settle();

        assertThat(routed.getLast()).matches(RabiaDemotionNotificationTest::isQuorumLoss);
    }

    @Test void quorumLoss_whileObserving_isPublishedAsQuorumLoss() {
        engine = activeVoter();
        engine.processDecision(removalDecision());
        settle();
        assertThat(routed.getLast().demoted()).as("control: demoted first").isTrue();

        engine.clusterState(ClusterStateNotification.passive());
        settle();

        assertThat(routed.getLast()).as("a demoted node that then loses quorum must quiesce").matches(RabiaDemotionNotificationTest::isQuorumLoss);
    }

    private static boolean isQuorumLoss(ClusterStateNotification notification) {
        return notification.state() == ClusterStateNotification.State.PASSIVE && !notification.demoted();
    }

    private static Decision<TestCommand> removalDecision() {
        var command = ReconfigurationCommand.reconfigurationCommand(0, WITHOUT_SELF);

        return new Decision<>(B, 0, Phase.phase(0), StateValue.V1, Batch.emptyBatch(), Option.some(command));
    }

    private RabiaEngine<TestCommand> activeVoter() {
        var router = MessageRouter.mutable();

        router.addRoute(ClusterStateNotification.class, routed::add);
        var created = new RabiaEngine<>(new TestTopologyManager(A, 5), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
            ConsensusMetrics.noop(), false, RabiaPersistence.inMemory(), RabiaEngine.DEFAULT_PHASE_STALL_CHECK,
            ConsensusBridge.consensusBridge(router));
        assertThat(created.initializeVoters(new VoterConfiguration(0, GENESIS_ROSTER)).isSuccess()).isTrue();
        created.clusterState(ClusterStateNotification.active());
        created.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        created.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        created.settleForTesting().await();
        created.settleForTesting().await();
        assertThat(created.isActive()).isTrue();
        return created;
    }

    private void settle() { engine.settleForTesting().await(); engine.settleForTesting().await(); }
}
