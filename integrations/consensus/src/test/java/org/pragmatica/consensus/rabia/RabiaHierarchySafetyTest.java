package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.SyncResponse;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Propose;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound2;
import org.pragmatica.consensus.topology.ClusterStateNotification;

import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// The electorate and applied frontier are independent of transport membership and phase-cache retention.
class RabiaHierarchySafetyTest {
    private static final NodeId SELF = nodeId("core-1").unwrap();
    private static final NodeId CORE_2 = nodeId("core-2").unwrap();
    private static final NodeId CORE_3 = nodeId("core-3").unwrap();
    private static final NodeId STAGED_CORE = nodeId("core-4").unwrap();
    private static final NodeId WORKER = nodeId("worker-1").unwrap();
    private static final Set<NodeId> CORES = Set.of(SELF, CORE_2, CORE_3);
    private record Delivery(NodeId recipient, org.pragmatica.consensus.ProtocolMessage message) {}
    private final List<Delivery> delivered = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final TestClusterNetwork network = new TestClusterNetwork() {
        @Override public Set<NodeId> connectedPeers() { return Set.of(CORE_2, CORE_3, STAGED_CORE, WORKER); }
        @Override public <M extends org.pragmatica.consensus.ProtocolMessage> org.pragmatica.lang.Unit send(NodeId recipient, M message) {
            delivered.add(new Delivery(recipient, message));
            return super.send(recipient, message);
        }
    };
    private final TestStateMachine stateMachine = new TestStateMachine();
    private RabiaEngine<TestCommand> engine;

    @AfterEach
    void stopEngine() {
        if (engine != null) {
            engine.stop().await();
        }
    }

    @Test
    void carryForwardPreservesSlotAndAcceptsLateDecisionForThatSlot() {
        activateAt(Phase.ZERO);
        var batch = Batch.create(stateMachine.serializer(), List.of(new TestCommand("agreed")));
        var other = Batch.create(stateMachine.serializer(), List.of(new TestCommand("other")));
        engine.processPropose(new Propose<>(SELF, Phase.ZERO, other));
        engine.processPropose(new Propose<>(CORE_2, Phase.ZERO, batch));
        engine.processVoteRound1(new VoteRound1(CORE_2, Phase.ZERO, StateValue.V1));
        engine.processVoteRound2(new VoteRound2(CORE_2, Phase.ZERO, StateValue.V1));
        await(() -> network.getMessages().stream().anyMatch(message -> message instanceof VoteRound1 vote && vote.round() == 1));
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.ZERO);
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
        engine.processDecision(new Decision<>(CORE_2, Phase.ZERO, StateValue.V1, batch));
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(1)));
        assertThat(stateMachine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("agreed");
    }

    @Test
    void firstRoundMajorityCannotCommitBeforeSecondRoundEvidence() {
        activateAt(Phase.ZERO);
        var batch = Batch.create(stateMachine.serializer(), List.of(new TestCommand("agreed")));
        engine.processPropose(new Propose<>(SELF, Phase.ZERO, batch));
        engine.processPropose(new Propose<>(CORE_2, Phase.ZERO, batch));
        engine.processVoteRound1(new VoteRound1(CORE_2, Phase.ZERO, StateValue.V1));
        engine.settleForTesting().await();
        engine.settleForTesting().await();
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.ZERO);
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
        engine.processVoteRound2(new VoteRound2(CORE_2, Phase.ZERO, StateValue.V1));
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(1)));
        assertThat(stateMachine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("agreed");
    }

    @Test
    void workerResponsesCannotCompleteCoreSynchronization() {
        createEngine(SELF, 5, false);
        startSync();
        respond(CORE_2, Phase.ZERO);
        respond(WORKER, Phase.phase(100));
        remain(() -> !engine.isActive());
        respond(CORE_3, Phase.ZERO);
        await(engine::isActive);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.ZERO);
    }

    @Test
    void selfResponseCannotSupplyMissingPeer() {
        createEngine(SELF, 5, false);
        startSync();
        respond(CORE_2, Phase.ZERO);
        respond(SELF, Phase.phase(100));
        remain(() -> !engine.isActive());
        respond(CORE_3, Phase.ZERO);
        await(engine::isActive);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.ZERO);
    }

    @Test
    void observerRequiresCoreMajorityAndReplaysBufferedDecision() {
        createEngine(WORKER, 3, true);
        engine.authorizeObservation();
        startSync();
        engine.processDecision(decision(CORE_2, 0, "after-snapshot"));
        respond(CORE_2, Phase.ZERO);
        remain(() -> !engine.isObserving());
        respond(CORE_3, Phase.ZERO);
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(1)));
        assertThat(engine.isObserving()).isTrue();
        assertThat(stateMachine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("after-snapshot");
    }

    @Test
    void observerDoesNotServeConsensusSnapshotEvidence() {
        createEngine(WORKER, 3, true);
        engine.authorizeObservation();
        startSync();
        respond(CORE_2, Phase.ZERO);
        respond(CORE_3, Phase.ZERO);
        await(engine::isObserving);
        network.clearMessages();
        engine.handleSyncRequest(new SyncRequest(CORE_2));
        engine.processDecision(emptyDecision(0));
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(1)));
        assertThat(network.getMessages()).noneMatch(SyncResponse.class::isInstance);
    }

    @Test
    void workerDecisionCannotMutateState() {
        activateAt(Phase.ZERO);
        engine.processDecision(decision(WORKER, 0, "unauthorized"));
        engine.processDecision(emptyDecision(0));
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(1)));
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
    }

    @Test
    void pastDecisionWithoutPhaseCacheEntryCannotReplay() {
        activateAt(Phase.phase(20));
        engine.processDecision(decision(CORE_2, 1, "stale"));
        engine.processDecision(emptyDecision(20));
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(21)));
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
    }

    @Test
    void onePhaseGapTriggersSyncWithoutApplyingAheadCommand() {
        activateAt(Phase.ZERO);
        network.clearMessages();
        engine.processDecision(decision(CORE_2, 1, "ahead"));
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.ZERO);
        respond(CORE_2, Phase.phase(1));
        respond(CORE_3, Phase.phase(1));
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(2)));
        assertThat(stateMachine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("ahead");
    }

    @Test
    void pausedGapDefersSyncUntilQuorumReturns() {
        activateAt(Phase.ZERO);
        engine.clusterState(ClusterStateNotification.passive());
        await(() -> !engine.isActive());
        network.clearMessages();
        engine.processDecision(decision(CORE_2, 1, "ahead"));
        remain(() -> network.getMessages().stream().noneMatch(SyncRequest.class::isInstance));
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
        engine.clusterState(ClusterStateNotification.active());
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
        assertThat(stateMachine.getProcessedCommands()).isEmpty();
    }

    @Test
    void fullSnapshotsRejectWorkersAndUnknownPeersButAllowStagedCoreCandidates() {
        activateAt(Phase.ZERO);
        network.clearMessages();
        engine.handleSyncRequest(new SyncRequest(WORKER));
        engine.handleSyncRequest(new SyncRequest(new NodeId("unknown")));
        engine.settleForTesting().await();
        assertThat(network.getMessages()).noneMatch(SyncResponse.class::isInstance);
        engine.handleSyncRequest(new SyncRequest(STAGED_CORE));
        engine.settleForTesting().await();
        assertThat(network.getMessages()).anyMatch(SyncResponse.class::isInstance);
    }

    @Test
    void passiveDirectoryCanFollowANewCoreLeaderWithoutChangingTheElectorate() {
        createEngine(WORKER, 3, true);
        var leader = org.pragmatica.consensus.leader.LeaderManager.leaderManager(WORKER,
            org.pragmatica.messaging.MessageRouter.mutable());
        var original = engine.voterConfiguration().unwrap();
        var history = engine.verifiedVoterHistoryIds();
        assertThat(engine.installPassiveCoreDirectory(List.of(STAGED_CORE), leader::installPassiveCoreDirectory).isFailure()).isTrue();
        assertThat(engine.configurePassiveClient().isSuccess()).isTrue();
        assertThat(engine.installPassiveCoreDirectory(List.of(), leader::installPassiveCoreDirectory).isFailure()).isTrue();
        assertThat(engine.installPassiveCoreDirectory(List.of(STAGED_CORE, STAGED_CORE), leader::installPassiveCoreDirectory).isFailure()).isTrue();
        assertThat(engine.installPassiveCoreDirectory(List.of(WORKER), leader::installPassiveCoreDirectory).isFailure()).isTrue();
        assertThat(engine.installPassiveCoreDirectory(List.of(STAGED_CORE), leader::installPassiveCoreDirectory).isSuccess()).isTrue();
        leader.onLeaderCommitted(STAGED_CORE, 10);
        assertThat(leader.leader().unwrap()).isEqualTo(STAGED_CORE);
        leader.onLeaderCommitted(WORKER, 11);
        leader.watchClusterState(ClusterStateNotification.active());
        leader.watchClusterState(ClusterStateNotification.passive());
        leader.triggerElection();
        assertThat(leader.leader().unwrap()).isEqualTo(STAGED_CORE);
        assertThat(leader.isLeader()).isFalse();
        assertThat(engine.isActive()).isFalse();
        assertThat(engine.isObserving()).isFalse();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(original);
        assertThat(engine.verifiedVoterHistoryIds()).isEqualTo(history);
        leader.stop();
    }

    @Test
    void cachedDecisionReplayNamesTheAuthenticatedRespondingCore() {
        activateAt(Phase.ZERO);
        var committed = decision(CORE_2, 0, "retained");
        engine.processDecision(committed);
        await(() -> engine.currentPhaseForTesting().equals(Phase.phase(1)));
        delivered.clear();
        engine.handleRoundRequest(new org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.RoundRequest(
            CORE_3, 0, Phase.ZERO, 0));
        engine.settleForTesting().await();
        assertThat(delivered).anySatisfy(delivery -> {
            assertThat(delivery.recipient()).isEqualTo(CORE_3);
            assertThat(delivery.message()).isInstanceOf(Decision.class);
            var replay = (Decision<?>) delivery.message();
            assertThat(replay.sender()).isEqualTo(SELF);
            assertThat(replay.value()).isEqualTo(committed.value());
            assertThat(org.pragmatica.consensus.net.InboundMessageAuthority.isBound(SELF, replay, _ -> false)).isTrue();
        });
    }

    @Test
    void activeCoreCannotTransitionToPassiveClient() {
        activateAt(Phase.ZERO);
        assertThat(engine.configurePassiveClient().isFailure()).isTrue();
        assertThat(engine.isActive()).isTrue();
    }

    @Test
    void messagesAfterShutdownCannotEnqueueOnTheClosedExecutor() {
        createEngine(SELF, 3, true);
        engine.stop().await();
        org.assertj.core.api.Assertions.assertThatCode(() -> engine.handleSyncRequest(new SyncRequest(CORE_2)))
                                      .doesNotThrowAnyException();
        assertThat(engine.configurePassiveClient().isFailure()).isTrue();
    }

    @Test
    void startingEvenBeforeQuorumSealsParticipationRole() {
        createEngine(SELF, 3, true);
        engine.start();
        assertThat(engine.configurePassiveClient().isFailure()).isTrue();
    }

    @Test
    void consensusPayloadFanoutExcludesConnectedWorkersAndRetainsStagedCoreObservers() {
        activateAt(Phase.ZERO);
        var batch = Batch.create(stateMachine.serializer(), List.of(new TestCommand("core-only")));
        engine.handleNewBatch(new org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch<>(CORE_2, batch));
        engine.processPropose(new Propose<>(CORE_2, Phase.ZERO, batch));
        engine.processVoteRound1(new VoteRound1(CORE_2, Phase.ZERO, StateValue.V1));
        engine.processVoteRound2(new VoteRound2(CORE_2, Phase.ZERO, StateValue.V1));
        engine.settleForTesting().await();
        engine.settleForTesting().await();
        assertThat(delivered).noneMatch(delivery -> delivery.recipient().equals(WORKER));
        assertThat(delivered).anyMatch(delivery -> delivery.recipient().equals(STAGED_CORE) && delivery.message() instanceof Decision<?>);
        assertThat(delivered).anyMatch(delivery -> delivery.recipient().equals(CORE_2) && delivery.message() instanceof VoteRound1);
    }

    @Test
    void refusedSourceDoesNotPreventRecoveryFromOtherValidResponses() {
        createEngine(SELF, 3, false);
        startSync();
        engine.handleSyncRejected(new RabiaProtocolMessage.Asynchronous.SyncRejected(CORE_2, 0));
        await(() -> engine.stateTransferFailure().isPresent());
        respond(CORE_2, Phase.ZERO);
        respond(CORE_3, Phase.ZERO);
        await(engine::isActive);
        assertThat(engine.stateTransferFailure().isEmpty()).isTrue();
    }

    private void createEngine(NodeId self, int size, boolean gated) {
        var topology = new TestTopologyManager(self, size) {
            @Override
            public java.util.Set<NodeId> coreNodes() { return java.util.stream.IntStream.rangeClosed(1, size).mapToObj(index -> nodeId("core-" + index).unwrap()).collect(java.util.stream.Collectors.toSet()); }
            @Override
            public boolean isConsensusMember(NodeId id) {
                return CORES.contains(id) || id.equals(STAGED_CORE);
            }
        };
        engine = new RabiaEngine<>(topology, network, stateMachine, ProtocolConfig.consensusConfig(org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds(), org.pragmatica.lang.io.TimeSpan.timeSpan(60).seconds()), ConsensusMetrics.noop(), gated);
    }

    private void startSync() {
        engine.clusterState(ClusterStateNotification.active());
        await(() -> network.getMessages().stream().anyMatch(SyncRequest.class::isInstance));
    }

    private void activateAt(Phase phase) {
        createEngine(SELF, 3, false);
        startSync();
        respond(CORE_2, phase);
        respond(CORE_3, phase);
        await(engine::isActive);
    }

    private void respond(NodeId sender, Phase phase) {
        engine.processSyncResponse(new SyncResponse<>(sender, SavedState.savedState(new byte[0], phase, List.of()), ResponderState.COLD));
    }

    private Decision<TestCommand> decision(NodeId sender, long phase, String value) {
        return new Decision<>(sender, Phase.phase(phase), StateValue.V1,
                              Batch.create(stateMachine.serializer(), List.of(new TestCommand(value))));
    }

    private Decision<TestCommand> emptyDecision(long phase) {
        return new Decision<>(CORE_2, Phase.phase(phase), StateValue.V0, Batch.emptyBatch());
    }

    private static void await(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 5_000_000_000L;
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(1_000_000L);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private static void remain(BooleanSupplier condition) {
        var deadline = System.nanoTime() + 100_000_000L;
        while (System.nanoTime() < deadline) {
            assertThat(condition.getAsBoolean()).isTrue();
            LockSupport.parkNanos(1_000_000L);
        }
    }
}
