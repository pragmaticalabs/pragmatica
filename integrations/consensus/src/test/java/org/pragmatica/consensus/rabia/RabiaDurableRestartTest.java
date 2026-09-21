package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.SliceCodec;

import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class RabiaDurableRestartTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final SliceCodec CODEC = SliceCodec.sliceCodec(
        TestSerializers.stringCommandSerializer(TestCommand.class, TestCommand::value, TestCommand::new),
        Stream.concat(ConsensusCodecs.CODECS.stream(), RabiaCodecs.CODECS.stream()).toList());
    @TempDir Path directory;

    @Test void restartAfterEachVotingEmissionRetainsTheOriginalPromise() {
        for (int crashPoint = 0; crashPoint < 3; crashPoint++) {
            var path = directory.resolve("vote-" + crashPoint);
            var disk = new CrashPersistence(open(path));
            var network = new TestClusterNetwork();
            var machine = new TestStateMachine();
            var engine = create(disk, network, machine);
            activate(engine, RabiaPersistence.SavedState.empty());
            var original = Batch.create(CODEC, List.of(new TestCommand("original")));
            engine.handleNewBatch(new NewBatch<>(B, original));
            settle(engine);
            if (crashPoint >= 1) { engine.processPropose(new Propose<>(B, 0, Phase.ZERO, original)); settle(engine); }
            if (crashPoint >= 2) { engine.processVoteRound1(new VoteRound1(B, 0, Phase.ZERO, 0, StateValue.V1)); settle(engine); }
            var before = disk.loadJournal().unwrap();
            assertThat(before).anyMatch(Propose.class::isInstance);
            if (crashPoint >= 1) { assertThat(before).anyMatch(VoteRound1.class::isInstance); }
            if (crashPoint >= 2) { assertThat(before).anyMatch(VoteRound2.class::isInstance); }
            disk.crashed = true;
            assertThat(engine.stop().await().isFailure()).isTrue();
            var restoredDisk = open(path);
            assertThat(restoredDisk.loadJournal().unwrap()).hasSize(before.size());
            var restoredNetwork = new TestClusterNetwork();
            var restarted = create(restoredDisk, restoredNetwork, new TestStateMachine());
            activate(restarted, restoredDisk.load().unwrap());
            var different = Batch.create(CODEC, List.of(new TestCommand("different")));
            restarted.processPropose(new Propose<>(B, 0, Phase.ZERO, different));
            restarted.processVoteRound1(new VoteRound1(B, 0, Phase.ZERO, 0, StateValue.V0));
            settle(restarted);
            for (var promised : before) {
                restoredDisk.loadJournal().unwrap().stream().filter(record -> VotingJournal.sameIdentity(record, promised))
                    .forEach(record -> assertThat(VotingJournal.sameValue(record, promised)).isTrue());
                restoredNetwork.getMessages().stream().filter(RabiaProtocolMessage.class::isInstance)
                    .map(RabiaProtocolMessage.class::cast).filter(record -> VotingJournal.sameIdentity(record, promised))
                    .forEach(record -> assertThat(VotingJournal.sameValue(record, promised)).isTrue());
            }
            assertThat(restarted.stop().await().isSuccess()).isTrue();
        }
    }

    @Test void committedDecisionReplaysOnceBeforeTheRestartCanAnswerSynchronization() {
        var disk = new CrashPersistence(open(directory));
        var machine = new TestStateMachine();
        var engine = create(disk, new TestClusterNetwork(), machine);
        activate(engine, RabiaPersistence.SavedState.empty());
        var value = Batch.create(CODEC, List.of(new TestCommand("committed")));
        engine.processDecision(new Decision<>(B, 0, Phase.ZERO, StateValue.V1, value));
        settle(engine);
        assertThat(machine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("committed");
        disk.crashed = true;
        engine.stop().await();
        var restored = open(directory);
        var recoveredMachine = new TestStateMachine();
        var network = new TestClusterNetwork();
        var restarted = create(restored, network, recoveredMachine);
        restarted.handleSyncRequest(new org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest(B));
        settle(restarted);
        assertThat(recoveredMachine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("committed");
        assertThat(restarted.currentPhaseForTesting()).isEqualTo(Phase.phase(1));
        assertThat(restored.load().unwrap().lastCommittedPhase()).isEqualTo(Phase.phase(1));
        restarted.processDecision(new Decision<>(C, 0, Phase.ZERO, StateValue.V1, value));
        settle(restarted);
        assertThat(recoveredMachine.getProcessedCommands()).hasSize(1);
        assertThat(restarted.stop().await().isSuccess()).isTrue();
    }

    @Test void failedWriteCannotEscapeAsAVoteOrAppliedDecision() {
        for (int stage = 0; stage < 4; stage++) {
            var disk = new CrashPersistence(open(directory.resolve("failure-" + stage)));
            var network = new TestClusterNetwork();
            var machine = new TestStateMachine();
            var engine = create(disk, network, machine);
            activate(engine, RabiaPersistence.SavedState.empty());
            var batch = Batch.create(CODEC, List.of(new TestCommand("never-applied")));
            if (stage > 0) { engine.handleNewBatch(new NewBatch<>(B, batch)); settle(engine); }
            if (stage > 1) { engine.processPropose(new Propose<>(B, 0, Phase.ZERO, batch)); settle(engine); }
            disk.failAppend = true;
            network.clearMessages();
            switch (stage) {
                case 0 -> engine.handleNewBatch(new NewBatch<>(B, batch));
                case 1 -> engine.processPropose(new Propose<>(B, 0, Phase.ZERO, batch));
                case 2 -> engine.processVoteRound1(new VoteRound1(B, 0, Phase.ZERO, 0, StateValue.V1));
                default -> engine.processDecision(new Decision<>(B, 0, Phase.ZERO, StateValue.V1, batch));
            }
            settle(engine);
            assertThat(engine.isActive()).isFalse();
            assertThat(network.getMessages()).noneMatch(message -> message instanceof Propose<?> || message instanceof VoteRound1
                || message instanceof VoteRound2 || message instanceof Decision<?>);
            assertThat(machine.getProcessedCommands()).isEmpty();
            engine.stop().await();
        }
    }

    @Test void replayedBarrierOmitsOversizedHintsWithoutLosingPendingRequests() {
        var disk = open(directory);
        var genesis = VoterConfiguration.voterConfiguration(0, List.of(A, B, C)).unwrap();
        var target = new ClusterConfig(List.of(A, B, new NodeId("node-4")));
        var pending = Batch.create(CODEC, List.of(new TestCommand("pending-retry")));
        var authority = new VoterAuthority<TestCommand>(genesis, Option.none());
        assertThat(disk.save(new TestStateMachine(), Phase.ZERO, List.of(pending), authority).isSuccess()).isTrue();
        // Exact crash boundary: the Decision reached stable storage, but no handoff checkpoint did.
        assertThat(disk.append(new Decision<TestCommand>(B, 0, Phase.ZERO, StateValue.V1,
            Batch.emptyBatch(), Option.some(target))).isSuccess()).isTrue();
        assertThat(disk.close().isSuccess()).isTrue();
        var recovered = open(directory);
        var network = new TestClusterNetwork() {
            @Override public Result<Unit> validateOutboundMessage(org.pragmatica.consensus.ProtocolMessage message) {
                return message instanceof RabiaProtocolMessage.Asynchronous.ConfigurationTransfer<?> transfer
                       && !transfer.handoff().pendingBatches().isEmpty()
                       ? ReconfigurationError.STATE_TRANSFER_TOO_LARGE.result() : Result.success(Unit.unit());
            }
        };
        var restarted = create(recovered, network, new TestStateMachine());
        restarted.handleSyncRequest(new RabiaProtocolMessage.Asynchronous.SyncRequest(B));
        settle(restarted);
        var saved = recovered.loadVerified().unwrap().unwrap();
        assertThat(saved.lastCommittedPhase()).isEqualTo(Phase.phase(1));
        assertThat(saved.authority().unwrap().handoff().unwrap().pendingBatches()).isEmpty();
        assertThat(saved.pendingBatches()).contains(pending);
        assertThat(restarted.stop().await().isSuccess()).isTrue();
    }

    private RabiaPersistence<TestCommand> open(Path path) { return RabiaPersistence.<TestCommand>durable(path, CODEC, CODEC).unwrap(); }
    private RabiaEngine<TestCommand> create(RabiaPersistence<TestCommand> disk, TestClusterNetwork network, TestStateMachine machine) {
        return new RabiaEngine<>(new TestTopologyManager(A, 3), network, machine,
            ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()), ConsensusMetrics.noop(), false, disk);
    }
    private void activate(RabiaEngine<TestCommand> engine, RabiaPersistence.SavedState<TestCommand> saved) {
        engine.clusterState(ClusterStateNotification.active());
        engine.processSyncResponse(new SyncResponse<>(B, saved, ResponderState.COLD));
        engine.processSyncResponse(new SyncResponse<>(C, saved, ResponderState.COLD));
        settle(engine);
        assertThat(engine.isActive()).describedAs("network=%s", engine.voterConfiguration()).isTrue();
    }
    private void settle(RabiaEngine<TestCommand> engine) { engine.settleForTesting().await(); engine.settleForTesting().await(); }

    /// Refuses orderly checkpoint writes at the simulated crash, retaining only previously fsynced bytes.
    private static final class CrashPersistence implements RabiaPersistence<TestCommand> {
        private final RabiaPersistence<TestCommand> delegate;
        private boolean crashed;
        private boolean failAppend;
        private CrashPersistence(RabiaPersistence<TestCommand> delegate) { this.delegate = delegate; }
        @Override public Result<Unit> append(RabiaProtocolMessage message) {
            return failAppend ? VotingJournalError.CLOSED.result() : delegate.append(message);
        }
        @Override public Result<List<RabiaProtocolMessage>> loadJournal() { return delegate.loadJournal(); }
        @Override public Option<SavedState<TestCommand>> load() { return delegate.load(); }
        @Override public Result<Option<SavedState<TestCommand>>> loadVerified() { return delegate.loadVerified(); }
        @Override public Result<Unit> save(StateMachine<TestCommand> machine, Phase phase, Collection<Batch<TestCommand>> pending) {
            return crashed ? VotingJournalError.CLOSED.result() : delegate.save(machine, phase, pending);
        }
        @Override public Result<Unit> save(StateMachine<TestCommand> machine, Phase phase, Collection<Batch<TestCommand>> pending,
                                           VoterAuthority<TestCommand> authority) {
            return crashed ? VotingJournalError.CLOSED.result() : delegate.save(machine, phase, pending, authority);
        }
        @Override public Result<Unit> close() { return delegate.close(); }
    }
}
