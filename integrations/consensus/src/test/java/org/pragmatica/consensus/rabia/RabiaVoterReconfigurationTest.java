package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Rabia §4 voter reconfiguration at the single-engine level (#1526): the agreed slot R, the R+1
/// boundary, the base-epoch no-op, and the genesis wait. Cluster-level schedules live in
/// `RabiaReorderedDeliveryTest`.
class RabiaVoterReconfigurationTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final NodeId D = new NodeId("node-4");
    private static final NodeId E = new NodeId("node-5");
    private static final ClusterConfig GENESIS_ROSTER = new ClusterConfig(List.of(A, B, C));
    private static final ClusterConfig REPLACED = new ClusterConfig(List.of(A, B, D));
    private final TestClusterNetwork network = new TestClusterNetwork();
    private final TestStateMachine machine = new TestStateMachine();
    private RabiaPersistence<TestCommand> persistence = RabiaPersistence.inMemory();
    private RabiaEngine<TestCommand> engine;

    @AfterEach void stop() { if (engine != null) { engine.stop().await(); } }

    @Test void staleCommand_decidedWithOutdatedBaseEpoch_appliesAsNoOp() {
        engine = activeVoter(A);
        engine.processDecision(reconfigurationDecision(B, 0, 0, command(0, REPLACED)));
        settle();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));

        // Base epoch 0 no longer matches: the decided command consumes slot 1 and changes nothing.
        engine.processDecision(reconfigurationDecision(B, 1, 1, command(0, new ClusterConfig(List.of(A, B, C)))));
        settle();

        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        assertThat(engine.currentPhaseForTesting()).isEqualTo(Phase.phase(2));
        assertThat(engine.voterReconfigurationStatus().effectiveSlot().unwrap()).isEqualTo(1L);
    }

    @Test void appliedChange_governsFromNextSlot_andRefusesOldEpochBallots() {
        engine = activeVoter(A);
        engine.processDecision(reconfigurationDecision(B, 0, 0, command(0, REPLACED)));
        settle();
        assertThat(engine.voterReconfigurationStatus().effectiveSlot().unwrap()).isEqualTo(1L);
        network.clearMessages();

        // B is still a voter, but its epoch-0 ballot is for a slot the epoch-1 roster governs.
        engine.processPropose(new Propose<>(B, 0, Phase.phase(1), batch("old-epoch")));
        settle();
        assertThat(ownProposalsFor(Phase.phase(1))).as("an old-epoch proposal must not open slot R+1").isEmpty();

        // Positive control: the same proposal at the governing epoch is accepted.
        engine.processPropose(new Propose<>(B, 1, Phase.phase(1), batch("new-epoch")));
        settle();
        assertThat(ownProposalsFor(Phase.phase(1))).isNotEmpty();
    }

    @Test void removedVoter_ballotAtCurrentEpoch_isNotCounted() {
        engine = activeVoter(A);
        engine.processDecision(reconfigurationDecision(B, 0, 0, command(0, REPLACED)));
        settle();
        network.clearMessages();

        // C left at slot 0. Even carrying the current epoch, its proposal is not a voter's.
        engine.processPropose(new Propose<>(C, 1, Phase.phase(1), batch("removed")));
        settle();
        assertThat(ownProposalsFor(Phase.phase(1))).as("a removed voter's proposal must not count").isEmpty();

        // Positive control: the member that replaced it is counted.
        engine.processPropose(new Propose<>(D, 1, Phase.phase(1), batch("added")));
        settle();
        assertThat(ownProposalsFor(Phase.phase(1))).isNotEmpty();
    }

    @Test void newerEpochBallot_isHeldUntilTheChangeIsApplied_andRepairIsRequested() {
        engine = activeVoter(A);
        network.clearMessages();

        // D votes at epoch 1 in slot 1 before this replica has applied slot 0.
        engine.processPropose(new Propose<>(D, 1, Phase.phase(1), batch("early")));
        settle();
        assertThat(ownProposalsFor(Phase.phase(1))).isEmpty();
        assertThat(network.getMessages()).anyMatch(message -> message instanceof RoundRequest request
                                                              && request.phase().equals(Phase.ZERO));

        engine.processDecision(reconfigurationDecision(B, 0, 0, command(0, REPLACED)));
        settle();

        assertThat(engine.voterConfiguration().unwrap().epoch()).isEqualTo(1);
        assertThat(ownProposalsFor(Phase.phase(1))).as("the held proposal is re-delivered after R").isNotEmpty();
    }

    @Test void reconfigure_completesWhenApplied_andRetirementWaitsForAddedMemberCatchUp() {
        engine = activeVoter(A);
        var promise = engine.reconfigure(REPLACED);
        settle();
        assertThat(network.getMessages()).anyMatch(ReconfigurationRequest.class::isInstance);
        assertThat(network.getMessages()).anyMatch(message -> message instanceof Propose<?> propose
                                                              && propose.sender().equals(A)
                                                              && propose.reconfiguration().isPresent());
        assertThat(engine.voterReconfigurationStatus().stage()).isEqualTo("REQUESTED");
        assertThat(engine.retirementSafeVoters().isEmpty()).isTrue();

        engine.processDecision(reconfigurationDecision(B, 0, 0, command(0, REPLACED)));
        settle();

        assertThat(promise.await(timeSpan(3).seconds()).isSuccess()).isTrue();
        assertThat(engine.voterReconfigurationStatus().stage()).isEqualTo("CATCHING_UP");
        assertThat(engine.voterReconfigurationStatus().awaitingCatchUp()).containsExactly("node-4");
        assertThat(engine.retirementSafeVoters().isEmpty()).as("D has not voted past R yet").isTrue();

        engine.processPropose(new Propose<>(D, 1, Phase.phase(1), batch("caught-up")));
        settle();

        assertThat(engine.retirementSafeVoters().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        assertThat(engine.voterReconfigurationStatus().stage()).isEqualTo("STABLE");
    }

    @Test void reconfigure_failsSuperseded_whenADifferentChangeIsApplied() {
        engine = activeVoter(A);
        var promise = engine.reconfigure(REPLACED);
        settle();

        engine.processDecision(reconfigurationDecision(B, 0, 0, command(0, new ClusterConfig(List.of(A, B, E)))));
        settle();

        var outcome = promise.await(timeSpan(3).seconds());
        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(ReconfigurationError.SUPERSEDED));
    }

    @Test void invalidElectorateIsRejectedBeforeAnyCommandIsProposed() {
        engine = activeVoter(A);
        network.clearMessages();

        assertThat(engine.reconfigure(new ClusterConfig(List.of())).await().isFailure()).isTrue();
        assertThat(engine.reconfigure(new ClusterConfig(List.of(A, A, B))).await().isFailure()).isTrue();
        assertRefused(engine.reconfigure(new ClusterConfig(List.of(A, D, E))), ReconfigurationError.INSUFFICIENT_RETAINED_VOTERS);
        assertRefused(engine.reconfigure(new ClusterConfig(List.of(A, B, new NodeId("node-9")))), ReconfigurationError.UNKNOWN_VOTER);
        assertThat(engine.isActive()).isTrue();
        assertThat(network.getMessages()).noneMatch(ReconfigurationRequest.class::isInstance);
    }

    @Test void persistedConfigurationOverridesTheDiscoveryRoster() {
        var installed = new VoterConfiguration(1, REPLACED);
        assertThat(persistence.save(machine, Phase.phase(12), List.of(), installed).isSuccess()).isTrue();
        engine = create(B);

        assertThat(engine.initializeVoters(new VoterConfiguration(0, GENESIS_ROSTER)).isSuccess()).isTrue();

        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(installed);
        assertThat(engine.verifiedVoterHistoryIds()).containsExactlyInAnyOrder(A, B, D);
    }

    @Test void deferredGenesis_neitherVotesNorAdopts_untilTheCompleteRosterIsInstalled() {
        engine = create(A);
        assertThat(engine.deferGenesis().isSuccess()).isTrue();
        assertThat(engine.voterReconfigurationStatus().stage()).isEqualTo("GENESIS_PENDING");
        engine.clusterState(ClusterStateNotification.active());
        engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processPropose(new Propose<>(B, 0, Phase.ZERO, batch("early")));
        settle();
        assertThat(engine.isActive()).isFalse();
        assertThat(network.getMessages()).noneMatch(message -> message instanceof VoteRound1 || message instanceof Propose<?>);

        assertThat(engine.initializeVoters(new VoterConfiguration(0, GENESIS_ROSTER)).isSuccess()).isTrue();
        assertThat(engine.initializeVoters(new VoterConfiguration(0, GENESIS_ROSTER)).isFailure())
            .as("genesis resolves once").isTrue();
        engine.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        settle();

        assertThat(engine.isActive()).isTrue();
        assertThat(engine.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(0, GENESIS_ROSTER));
    }

    @Test void syncResponse_withADifferentRosterAtTheSameEpoch_isRefused() {
        engine = create(A);
        engine.clusterState(ClusterStateNotification.active());
        var otherRoster = Option.some(new VoterConfiguration(0, new ClusterConfig(List.of(A, B, D))));
        engine.processSyncResponse(new SyncResponse<>(B, new RabiaPersistence.SavedState<>(new byte[0], Phase.ZERO, List.of(), otherRoster),
                                                      ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(D, new RabiaPersistence.SavedState<>(new byte[0], Phase.ZERO, List.of(), otherRoster),
                                                      ResponderState.COLD));
        settle();
        assertThat(engine.isActive()).as("a disagreeing genesis roster must not satisfy adoption").isFalse();

        // Positive control: the agreeing roster adopts.
        var ownRoster = Option.some(new VoterConfiguration(0, GENESIS_ROSTER));
        engine.processSyncResponse(new SyncResponse<>(B, new RabiaPersistence.SavedState<>(new byte[0], Phase.ZERO, List.of(), ownRoster),
                                                      ResponderState.COLD));
        settle();
        assertThat(engine.isActive()).isTrue();
    }

    private void assertRefused(org.pragmatica.lang.Promise<org.pragmatica.lang.Unit> result, ReconfigurationError expected) {
        var outcome = result.await(timeSpan(3).seconds());
        assertThat(outcome.isFailure()).isTrue();
        outcome.onFailure(cause -> assertThat(cause).isEqualTo(expected));
    }

    private List<ProtocolMessage> ownProposalsFor(Phase phase) {
        return network.getMessages()
                      .stream()
                      .filter(message -> message instanceof Propose<?> propose
                                         && propose.sender().equals(A)
                                         && propose.phase().equals(phase))
                      .toList();
    }

    private static ReconfigurationCommand command(long baseEpoch, ClusterConfig target) {
        return ReconfigurationCommand.reconfigurationCommand(baseEpoch, target);
    }

    private static Decision<TestCommand> reconfigurationDecision(NodeId sender, long epoch, long slot, ReconfigurationCommand command) {
        return new Decision<>(sender, epoch, Phase.phase(slot), StateValue.V1, Batch.emptyBatch(), Option.some(command));
    }

    private Batch<TestCommand> batch(String value) {
        return Batch.create(machine.serializer(), List.of(new TestCommand(value)));
    }

    private RabiaEngine<TestCommand> activeVoter(NodeId self) {
        var created = create(self);
        created.clusterState(ClusterStateNotification.active());
        created.processSyncResponse(new SyncResponse<>(B, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        created.processSyncResponse(new SyncResponse<>(C, RabiaPersistence.SavedState.empty(), ResponderState.COLD));
        created.settleForTesting().await();
        created.settleForTesting().await();
        assertThat(created.isActive()).isTrue();
        assertThat(created.voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(0, GENESIS_ROSTER));
        return created;
    }

    private RabiaEngine<TestCommand> create(NodeId self) {
        var created = new RabiaEngine<>(new TestTopologyManager(self, 5), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
            ConsensusMetrics.noop(), false, persistence);
        assertThat(created.initializeVoters(new VoterConfiguration(0, GENESIS_ROSTER)).isSuccess()).isTrue();
        return created;
    }

    private void settle() { engine.settleForTesting().await(); engine.settleForTesting().await(); }
}
