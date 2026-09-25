package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RabiaVoterRecoveryTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final NodeId D = new NodeId("node-4");
    private static final VoterConfiguration BEFORE = new VoterConfiguration(0, new ClusterConfig(List.of(A, B, C)));
    private static final VoterConfiguration AFTER = new VoterConfiguration(1, new ClusterConfig(List.of(B, C, D)));
    private final TestClusterNetwork network = new TestClusterNetwork();
    private final TestStateMachine machine = new TestStateMachine();
    private RabiaPersistence<TestCommand> persistence = RabiaPersistence.inMemory();
    private RabiaEngine<TestCommand> engine;

    @AfterEach void stop() { if (engine != null) { engine.stop().await(); } }

    @Test void restartAtBarrierNeverReopensOldEpoch() {
        var handoff = new ConfigurationHandoff<TestCommand>(BEFORE, AFTER, Phase.phase(8), new byte[0], List.of());
        assertThat(persistence.save(machine, Phase.phase(8), List.of(), new VoterAuthority<>(BEFORE, Option.some(handoff))).isSuccess()).isTrue();
        engine = create(A);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isActive()).isFalse();
        assertThat(network.getMessages()).anyMatch(ConfigurationTransfer.class::isInstance);
        network.clearMessages();
        var value = Batch.create(machine.serializer(), List.of(new TestCommand("old")));
        engine.processPropose(new Propose<>(B, 0, Phase.phase(8), value));
        engine.processDecision(new Decision<>(B, 0, Phase.phase(8), StateValue.V1, value));
        settle();
        assertThat(machine.getProcessedCommands()).isEmpty();
        assertThat(network.getMessages()).noneMatch(message -> message instanceof VoteRound1 || message instanceof VoteRound2);
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.phase(8));
        var status = engine.voterReconfigurationStatus();
        assertThat(status.stage()).isEqualTo("CHECKPOINT_COLLECTION");
        assertThat(status.installedEpoch().unwrap()).isZero();
        assertThat(status.barrierSlot().unwrap()).isEqualTo(8);
        assertThat(status.targetVoters()).containsExactly("node-2", "node-3", "node-4");
        assertThat(status.certifiedCheckpointWitnesses()).isZero();
    }

    @Test void repeatedHandoffRequestsAdvertiseWithoutRewritingTheFrozenCheckpoint() {
        var counting = new FailingPersistence();
        persistence = counting;
        var handoff = new ConfigurationHandoff<TestCommand>(BEFORE, AFTER, Phase.phase(8), new byte[0], List.of());
        assertThat(persistence.save(machine, Phase.phase(8), List.of(),
            new VoterAuthority<>(BEFORE, Option.some(handoff))).isSuccess()).isTrue();
        engine = create(A);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        // The first executor-delivered request completes lazy WAL recovery and checkpoints that
        // recovered prefix. Measure repeated handoff advertisements only after that required save.
        engine.handleSyncRequest(new SyncRequest(B));
        settle();
        var saved = counting.authoritySaves;
        network.clearMessages();
        for (int request = 0; request < 5; request++) {
            engine.handleSyncRequest(new SyncRequest(B));
            settle();
        }
        assertThat(counting.authoritySaves).isEqualTo(saved);
        assertThat(network.getMessages()).anyMatch(ConfigurationTransfer.class::isInstance);
        assertThat(engine.isActive()).isFalse();
    }

    @Test void failedHandoffReloadFailsStartupAndCannotResumeVoting() {
        var failing = new FailingPersistence();
        persistence = failing;
        var handoff = new ConfigurationHandoff<TestCommand>(BEFORE, AFTER, Phase.phase(8), new byte[0], List.of());
        assertThat(persistence.save(machine, Phase.phase(8), List.of(),
            new VoterAuthority<>(BEFORE, Option.some(handoff))).isSuccess()).isTrue();
        engine = create(A);
        var started = engine.start();
        assertThat(started.isResolved()).isFalse();
        failing.failLoads = true;
        network.clearMessages();

        engine.clusterState(ClusterStateNotification.active());

        assertThat(started.isResolved()).as("reload failure must fail the pending startup, not disappear").isTrue();
        assertThat(started.await().isFailure()).isTrue();
        assertThat(engine.isObserving()).isTrue();
        assertThat(engine.isActive()).isFalse();
        failing.failLoads = false;
        engine.clusterState(ClusterStateNotification.active());
        engine.processPropose(new Propose<>(B, 0, Phase.phase(8), Batch.emptyBatch()));
        settle();
        assertThat(engine.isActive()).isFalse();
        assertThat(network.getMessages()).noneMatch(message -> message instanceof VoteRound1 || message instanceof VoteRound2);
    }

    @Test void persistedInstalledEpochOverridesChangedDiscoveryRoster() {
        var handoff = new ConfigurationHandoff<TestCommand>(BEFORE, AFTER, Phase.phase(8), new byte[0], List.of());
        var proof = new ConfigurationCertificate(BEFORE, AFTER, Phase.phase(8), List.of(A, B));
        var authority = new VoterAuthority<>(AFTER, Option.some(handoff), List.of(proof));
        assertThat(persistence.save(machine, Phase.phase(12), List.of(), authority).isSuccess()).isTrue();
        engine = create(B);
        assertThat(engine.initializeVoters(new VoterConfiguration(0, AFTER.roster())).isSuccess()).isTrue();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(AFTER);
        assertThat(engine.genesisVoters().unwrap()).isEqualTo(BEFORE);
        assertThat(engine.verifiedVoterHistoryIds()).containsExactlyInAnyOrder(A, B, C, D);
        assertThat(engine.voterReconfigurationStatus().stage()).isEqualTo("INSTALLATION_PENDING");
        assertThat(engine.voterReconfigurationStatus().certifiedCheckpointWitnesses()).isEqualTo(2);
        assertThat(engine.voterReconfigurationStatus().certifiedInstallationWitnesses()).isZero();
        var old = Batch.create(machine.serializer(), List.of(new TestCommand("old")));
        engine.processDecision(new Decision<>(C, 0, Phase.phase(12), StateValue.V1, old));
        settle();
        assertThat(machine.getProcessedCommands()).isEmpty();
    }

    @Test void partialHandoffEvidenceDoesNotInstallNewEpoch() {
        engine = create(D);
        engine.authorizeObservation();
        var handoff = new ConfigurationHandoff<TestCommand>(BEFORE, AFTER, Phase.phase(8), new byte[0], List.of());
        engine.configurationTransfer(new ConfigurationTransfer<>(A, handoff));
        engine.configurationTransfer(new ConfigurationTransfer<>(A, handoff));
        engine.configurationTransfer(new ConfigurationTransfer<>(D, handoff));
        settle();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(BEFORE);
        assertThat(network.getMessages()).noneMatch(ConfigurationInstalled.class::isInstance);
        engine.configurationTransfer(new ConfigurationTransfer<>(B, handoff));
        settle();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(AFTER);
        assertThat(persistence.load().unwrap().authority().unwrap().configuration()).isEqualTo(AFTER);
        assertThat(network.getMessages()).anyMatch(ConfigurationInstalled.class::isInstance);
    }

    @Test void newInstallationFailureCannotVoteOrAcknowledgeAndCanRetry() {
        var failing = new FailingPersistence();
        failing.failEpoch = 1;
        persistence = failing;
        engine = create(D);
        engine.authorizeObservation();
        var handoff = new ConfigurationHandoff<TestCommand>(BEFORE, AFTER, Phase.phase(8), new byte[0], List.of());
        engine.configurationTransfer(new ConfigurationTransfer<>(A, handoff));
        engine.configurationTransfer(new ConfigurationTransfer<>(B, handoff));
        settle();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(BEFORE);
        assertThat(engine.isActive()).isFalse();
        assertThat(network.getMessages()).noneMatch(ConfigurationInstalled.class::isInstance);
        engine.clusterState(ClusterStateNotification.active());
        settle();
        assertThat(engine.isActive()).isFalse();
        failing.failEpoch = -1;
        engine.configurationTransfer(new ConfigurationTransfer<>(B, handoff));
        settle();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(AFTER);
        assertThat(engine.retirementSafeVoters().isEmpty()).isTrue();
        var completion = engine.reconfigure(AFTER.roster());
        settle();
        assertThat(completion.isResolved()).isFalse();
        engine.configurationInstalled(new ConfigurationInstalled(B, AFTER, Phase.phase(8)));
        settle();
        assertThat(completion.await().isSuccess()).isTrue();
        assertThat(engine.retirementSafeVoters().unwrap()).isEqualTo(AFTER);
        assertThat(persistence.load().unwrap().authority().unwrap().retirementSafe()).isTrue();
        assertThat(engine.voterReconfigurationStatus().stage()).isEqualTo("COMPLETE");
        assertThat(engine.voterReconfigurationStatus().certifiedInstallationWitnesses()).isGreaterThanOrEqualTo(2);
    }

    @Test void oldBarrierPersistenceFailureNeverAdvertisesOrReopensVoting() {
        var failing = new FailingPersistence();
        persistence = failing;
        engine = create(A);
        engine.clusterState(ClusterStateNotification.active());
        engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle();
        assertThat(engine.isActive()).isTrue();
        failing.failEpoch = 0;
        network.clearMessages();
        engine.processDecision(new Decision<>(B, 0, Phase.ZERO, StateValue.V1, Batch.emptyBatch(), Option.some(AFTER.roster())));
        settle();
        assertThat(engine.isActive()).isFalse();
        assertThat(network.getMessages()).noneMatch(ConfigurationTransfer.class::isInstance);
        assertThat(engine.voterReconfigurationStatus().failure()).isNotEmpty();
        engine.processPropose(new Propose<>(B, 0, Phase.phase(1), Batch.emptyBatch()));
        settle();
        assertThat(network.getMessages()).noneMatch(message -> message instanceof VoteRound1 || message instanceof VoteRound2);
        failing.failEpoch = -1;
        engine.handleSyncRequest(new SyncRequest(B));
        settle();
        assertThat(network.getMessages()).anyMatch(ConfigurationTransfer.class::isInstance);
        assertThat(persistence.load().unwrap().authority().unwrap().handoff().isPresent()).isTrue();
        assertThat(engine.voterReconfigurationStatus().failure()).isEmpty();
    }

    @Test void invalidElectorateIsRejectedBeforeAnyBarrierCanBeProposed() {
        engine = create(A);
        engine.clusterState(ClusterStateNotification.active());
        engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle();
        network.clearMessages();
        assertThat(engine.reconfigure(new ClusterConfig(List.of())).await().isFailure()).isTrue();
        assertThat(engine.reconfigure(new ClusterConfig(List.of(A, A, B))).await().isFailure()).isTrue();
        assertThat(engine.isActive()).isTrue();
        assertThat(network.getMessages()).noneMatch(ReconfigurationRequest.class::isInstance);
    }

    private static final class FailingPersistence implements RabiaPersistence<TestCommand> {
        private final RabiaPersistence<TestCommand> delegate = RabiaPersistence.inMemory();
        private long failEpoch = -1;
        private boolean failLoads;
        private int authoritySaves;
        @Override public org.pragmatica.lang.Result<Option<SavedState<TestCommand>>> loadVerified() {
            return failLoads ? ReconfigurationError.AUTHORITY_PERSISTENCE_UNSUPPORTED.result() : delegate.loadVerified();
        }
        @Override public org.pragmatica.lang.Result<org.pragmatica.lang.Unit> append(RabiaProtocolMessage message) {
            return delegate.append(message);
        }
        @Override public org.pragmatica.lang.Result<java.util.List<RabiaProtocolMessage>> loadJournal() {
            return delegate.loadJournal();
        }
        @Override public org.pragmatica.lang.Result<org.pragmatica.lang.Unit> save(
            org.pragmatica.consensus.StateMachine<TestCommand> machine, Phase phase,
            java.util.Collection<Batch<TestCommand>> pending) { return delegate.save(machine, phase, pending); }
        @Override public org.pragmatica.lang.Result<org.pragmatica.lang.Unit> save(
            org.pragmatica.consensus.StateMachine<TestCommand> machine, Phase phase,
            java.util.Collection<Batch<TestCommand>> pending, VoterAuthority<TestCommand> authority) {
            authoritySaves++;
            return authority.configuration().epoch() == failEpoch
                ? ReconfigurationError.AUTHORITY_PERSISTENCE_UNSUPPORTED.result()
                : delegate.save(machine, phase, pending, authority);
        }
        @Override public Option<SavedState<TestCommand>> load() { return delegate.load(); }
    }

    private RabiaEngine<TestCommand> create(NodeId self) {
        var engine = new RabiaEngine<>(new TestTopologyManager(self, 3), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
            ConsensusMetrics.noop(), false, persistence);
        return engine;
    }
    private void settle() { engine.settleForTesting().await(); engine.settleForTesting().await(); }
}
