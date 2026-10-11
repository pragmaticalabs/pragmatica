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

/// v1796 probe (verifier-only, never committed): item-1 shapes the author's pins do not reach.
class V1796ProbeTest {
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

    /// Partitioned FIRST (quorum lost -> Paused), the removal committed by the majority reaches the node LATER.
    @Test void partitionedFirst_thenRemovalApplied_isNeverPublishedAsDemotion() {
        engine = activeVoter();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        assertThat(routed.getLast()).as("control: genuine loss published").matches(V1796ProbeTest::isQuorumLoss);

        engine.processDecision(removalDecision());
        settle();

        System.out.println("V1796 PROBE partitionedFirst: observing=" + engine.isObserving() + " routed=" + routed);
        assertThat(engine.isObserving()).as("control: the late removal WAS applied (Paused -> Observing)").isTrue();
        assertThat(routed).as("no demotion may follow a genuine quorum loss").noneMatch(ClusterStateNotification::demoted);
        assertThat(routed.getLast()).matches(V1796ProbeTest::isQuorumLoss);
    }

    /// Demoted, then partitioned, then the partition heals: what does the app layer end on?
    @Test void demoted_thenLoss_thenRegain_sequence() {
        engine = activeVoter();
        engine.processDecision(removalDecision());
        settle();
        engine.clusterState(ClusterStateNotification.passive());
        settle();
        engine.clusterState(ClusterStateNotification.active());
        settle();
        settle();
        System.out.println("V1796 PROBE demote-loss-regain: active=" + engine.isActive() + " observing=" + engine.isObserving()
                           + " routed=" + routed.stream().map(n -> n.state() + (n.demoted() ? "(demoted)" : "")).toList());
        assertThat(routed.get(1).demoted()).isTrue();
        assertThat(routed.get(2)).matches(V1796ProbeTest::isQuorumLoss);
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
