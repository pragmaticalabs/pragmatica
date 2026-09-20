package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.RoundRequest;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Executes the real engines with reproducible message reordering and duplication.
class RabiaReorderedDeliveryTest {
    private final List<ScheduledCluster> clusters = new ArrayList<>();

    @AfterEach
    void stopClusters() {
        clusters.forEach(ScheduledCluster::stop);
    }

    @Test
    void conflictingProposalsConvergeToIdenticalLogsAcrossFairSchedules() {
        for (int size : List.of(3, 5)) {
            for (int seed = 0; seed < 12; seed++) {
                runSchedule(size, seed);
            }
        }
    }

    @Test
    void proposalDeliveryRepairsDisjointPendingQueuesWithoutNewBatchRetransmission() {
        for (int seed = 0; seed < 6; seed++) {
            var cluster = new ScheduledCluster(3, seed);
            clusters.add(cluster);
            cluster.start();
            for (int index = 0; index < cluster.engines.size(); index++) {
                var batch = Batch.create(cluster.machines.get(index).serializer(), List.of(new TestCommand("isolated-" + index)));
                cluster.engines.get(index).handleNewBatch(new NewBatch<>(cluster.members.get(index), batch));
            }
            cluster.settle();
            // Only Propose/ballot/Decision traffic is delivered. No voter ever receives another
            // NewBatch; a protocol advancing empty slots forever must fail this application gate.
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 3));
            cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
            cluster.verifyPrefixes();
            cluster.machines.forEach(machine -> assertThat(machine.getProcessedCommands()).hasSize(3).doesNotHaveDuplicates());
        }
    }

    @Test
    void checkpointHandoffReplacesVotersWithoutReplayingApplicationState() {
        for (int seed = 0; seed < 8; seed++) {
            var cluster = new ScheduledCluster(6, seed, 3);
            clusters.add(cluster);
            cluster.start();
            var first = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("before")));
            cluster.engines.subList(0, 3).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.getFirst(), first)));
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1));
            var target = new ClusterConfig(cluster.members.subList(2, 5));
            var changed = cluster.engines.getFirst().reconfigure(target);
            cluster.settle();
            cluster.splitHealth = true;
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().map(v -> v.epoch() == 1).or(false)));
            cluster.pumpUntil(() -> changed.isResolved());
            assertThat(changed.await().isSuccess()).isTrue();
            var stale = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("stale-old-epoch")));
            cluster.engines.forEach(engine -> engine.processDecision(new Decision<>(cluster.members.get(2), 0,
                engine.currentPhaseForTesting(), StateValue.V1, stale)));
            cluster.settle();
            cluster.verifyPrefixes();
            assertThat(cluster.machines.getFirst().getProcessedCommands()).hasSize(1);
            var next = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after")));
            cluster.engines.subList(2, 5).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.get(2), next)));
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 2));
            for (var machine : cluster.machines) {
                assertThat(machine.getProcessedCommands()).extracting(TestCommand::value).containsExactly("before", "after");
            }
            cluster.stop();
        }
    }

    @Test
    void growThenShrinkPreservesTwoEpochHistoryAndRetirementEvidence() {
        for (int seed = 0; seed < 4; seed++) {
            var cluster = new ScheduledCluster(6, seed, 3);
            clusters.add(cluster);
            cluster.start();
            var grow = cluster.engines.getFirst().reconfigure(new ClusterConfig(cluster.members.subList(0, 5)));
            cluster.pumpUntil(grow::isResolved);
            assertThat(grow.await().isSuccess()).isTrue();
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().unwrap().epoch() == 1));
            cluster.pumpUntil(() -> cluster.engines.get(2).retirementSafeVoters().isPresent());
            var shrink = cluster.engines.get(2).reconfigure(new ClusterConfig(cluster.members.subList(2, 5)));
            cluster.pumpUntil(shrink::isResolved);
            assertThat(shrink.await().isSuccess()).as("shrink result %s; epoch %s; active %s", shrink.await(), cluster.engines.get(2).voterConfiguration(), cluster.engines.get(2).isActive()).isTrue();
            cluster.pumpUntil(() -> cluster.engines.stream().allMatch(engine -> engine.voterConfiguration().unwrap().epoch() == 2));
            assertThat(cluster.engines.get(2).retirementSafeVoters().unwrap().epoch()).isEqualTo(2);
            assertThat(cluster.engines.get(2).genesisVoters().unwrap().members()).containsExactlyElementsOf(cluster.members.subList(0, 3));
            var batch = Batch.create(cluster.machines.getFirst().serializer(), List.of(new TestCommand("after-two-epochs")));
            cluster.engines.subList(2, 5).forEach(engine -> engine.handleNewBatch(new NewBatch<>(cluster.members.get(2), batch)));
            cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() == 1));
            cluster.stop();
        }
    }

    private void runSchedule(int size, int seed) {
        var cluster = new ScheduledCluster(size, seed);
        clusters.add(cluster);
        cluster.start();
        cluster.splitHealth = true;
        cluster.submitConflictingBatches();
        cluster.pumpUntil(() -> cluster.machines.stream().allMatch(machine -> machine.getProcessedCommands().size() >= size));
        cluster.pumpUntil(() -> cluster.pending.isEmpty() && cluster.emitted.isEmpty());
        var expected = cluster.machines.getFirst().getProcessedCommands();
        for (var machine : cluster.machines) {
            assertThat(machine.getProcessedCommands()).as("size=%s seed=%s", size, seed).containsExactlyElementsOf(expected);
            assertThat(machine.getProcessedCommands()).doesNotHaveDuplicates().hasSize(size);
        }
        cluster.stop();
    }

    private record Delivery(int target, ProtocolMessage message) {}

    private static final class ScheduledCluster {
        private final List<RabiaEngine<TestCommand>> engines = new ArrayList<>();
        private final List<SnapshotMachine> machines = new ArrayList<>();
        private final List<NodeId> members;
        private final ConcurrentLinkedQueue<Delivery> emitted = new ConcurrentLinkedQueue<>();
        private final List<Delivery> pending = new ArrayList<>();
        private final Random random;
        private volatile boolean splitHealth;

        ScheduledCluster(int size, int seed) { this(size, seed, size); }

        ScheduledCluster(int size, int seed, int initialVoters) {
            random = new Random(seed);
            members = IntStream.range(0, size).mapToObj(index -> nodeId("voter-" + index).unwrap()).toList();
            for (int index = 0; index < size; index++) {
                var sender = index;
                var machine = new SnapshotMachine();
                var topology = new TestTopologyManager(members.get(index), size) {
                    @Override
                    public java.util.Set<NodeId> coreNodes() { return java.util.Set.copyOf(members); }
                    @Override
                    public boolean isConsensusMember(NodeId id) { return members.contains(id); }
                    @Override
                    public List<NodeId> topology() {
                        return members.stream().filter(id -> !splitHealth || members.indexOf(id) % 2 == sender % 2).toList();
                    }
                    @Override
                    public int clusterSize() { return topology().size(); }
                };
                var network = new TestClusterNetwork() {
                    @Override public java.util.Set<NodeId> connectedPeers() { return java.util.Set.copyOf(members); }
                    @Override
                    public <M extends ProtocolMessage> Unit broadcast(M message) {
                        for (int target = 0; target < members.size(); target++) {
                            if (target != sender) {
                                emitted.add(new Delivery(target, message));
                            }
                        }
                        return Unit.unit();
                    }

                    @Override
                    public <M extends ProtocolMessage> Unit send(NodeId id, M message) {
                        emitted.add(new Delivery(members.indexOf(id), message));
                        return Unit.unit();
                    }
                };
                machines.add(machine);
                var engine = new RabiaEngine<>(topology, network, machine,
                                              ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()));
                assertThat(engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(members.subList(0, initialVoters)))).isSuccess()).isTrue();
                if (index >= initialVoters) { engine.authorizeObservation(); }
                engines.add(engine);
            }
        }

        void start() {
            engines.forEach(engine -> engine.clusterState(ClusterStateNotification.active()));
            pumpUntil(() -> engines.stream().allMatch(engine -> engine.isActive() || engine.isObserving()));
        }

        void submitConflictingBatches() {
            var batches = IntStream.range(0, engines.size())
                                   .mapToObj(index -> Batch.create(machines.getFirst().serializer(), List.of(new TestCommand("command-" + index))))
                                   .toList();
            // Each voter initially selects a different immutable proposal for slot zero.
            for (int index = 0; index < engines.size(); index++) {
                engines.get(index).handleNewBatch(new NewBatch<>(members.get(index), batches.get(index)));
            }
            settle();
            // All requests become available before delivering ballots; proposal order still differs.
            for (var engine : engines) {
                batches.forEach(batch -> engine.handleNewBatch(new NewBatch<>(members.getFirst(), batch)));
            }
            settle();
        }

        void pumpUntil(BooleanSupplier completed) {
            for (int step = 0; step < 30_000; step++) {
                settle();
                verifyPrefixes();
                for (var delivery = emitted.poll(); delivery != null; delivery = emitted.poll()) {
                    pending.add(delivery);
                }
                if (completed.getAsBoolean()) {
                    return;
                }
                if (pending.isEmpty()) {
                    continue;
                }
                var delivery = pending.remove(random.nextInt(pending.size()));
                deliver(delivery);
                if (random.nextInt(8) == 0) {
                    deliver(delivery);
                }
            }
            settle();
            assertThat(completed.getAsBoolean()).as("schedule must make progress; pending=%s", pending.size()).isTrue();
        }

        void settle() {
            engines.forEach(engine -> engine.settleForTesting().await());
        }

        void verifyPrefixes() {
            var logs = machines.stream().map(machine -> List.copyOf(machine.getProcessedCommands())).toList();
            var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
            for (var log : logs) {
                assertThat(log).containsExactlyElementsOf(longest.subList(0, log.size()));
                assertThat(log).doesNotHaveDuplicates();
            }
        }


        @SuppressWarnings("unchecked")
        void deliver(Delivery delivery) {
            var engine = engines.get(delivery.target());
            switch (delivery.message()) {
                case Propose<?> message -> engine.processPropose((Propose<TestCommand>) message);
                case VoteRound1 message -> engine.processVoteRound1(message);
                case VoteRound2 message -> engine.processVoteRound2(message);
                case Decision<?> message -> engine.processDecision((Decision<TestCommand>) message);
                case SyncRequest message -> engine.handleSyncRequest(message);
                case RoundRequest message -> engine.handleRoundRequest(message);
                case ReconfigurationRequest message -> engine.reconfigurationRequest(message);
                case ConfigurationTransfer<?> message -> engine.configurationTransfer((ConfigurationTransfer<TestCommand>) message);
                case ConfigurationInstalled message -> engine.configurationInstalled(message);
                case SyncResponse<?> message -> engine.processSyncResponse((SyncResponse<TestCommand>) message);
                default -> {}
            }
        }

        void stop() {
            engines.forEach(engine -> engine.stop().await());
        }
    }

    private static final class SnapshotMachine extends TestStateMachine {
        @Override
        public Result<byte[]> makeSnapshot() {
            return Result.success(String.join("\n", getProcessedCommands().stream().map(TestCommand::value).toList())
                                        .getBytes(StandardCharsets.UTF_8));
        }

        @Override
        public Result<Unit> restoreSnapshot(byte[] snapshot) {
            reset();
            if (snapshot.length > 0) {
                process(Batch.create(serializer(), new String(snapshot, StandardCharsets.UTF_8).lines().map(TestCommand::new).toList()));
            }
            return Result.success(Unit.unit());
        }
    }
}
