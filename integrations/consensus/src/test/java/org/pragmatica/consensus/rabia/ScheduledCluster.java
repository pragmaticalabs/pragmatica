package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Disabled;
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
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.testlogging.LogCapture;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.BooleanSupplier;
import java.util.stream.IntStream;
import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Real `RabiaEngine`s driven by a seeded, reorder-and-duplicate delivery schedule, shared by the delivery tests.
/// Moved out of `RabiaReorderedDeliveryTest` with its behaviour unchanged: only visibility widened to package level
/// and `pumpBounded` added for the partial-partition liveness probe.
final class ScheduledCluster {
    final List<RabiaEngine<TestCommand>> engines = new ArrayList<>();
    final List<SnapshotMachine> machines = new ArrayList<>();
    /// The `ConsensusActive` / `ConsensusPassive` edges each engine has published.
    final List<List<ConsensusEvent>> events = new ArrayList<>();
    final List<NodeId> members;
    final ConcurrentLinkedQueue<Delivery> emitted = new ConcurrentLinkedQueue<>();
    final List<Delivery> pending = new ArrayList<>();
    /// Every Propose, ballot and Decision any engine emitted since the last clear.
    final List<ProtocolMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
    final java.util.Set<Integer> dead = new java.util.HashSet<>();
    final List<Delivery> parked = new ArrayList<>();
    /// Deliveries that are lost outright.
    java.util.function.Predicate<Delivery> blocked = _ -> false;
    /// Deliveries that wait, in emission order, until the predicate releases them.
    java.util.function.Predicate<Delivery> held = _ -> false;
    final Random random;
    volatile boolean splitHealth;
    /// A test that SUBMITS identical commands twice expects them applied twice (#2011).
    volatile boolean allowIdenticalCommands;

    ScheduledCluster(int size, int seed) { this(size, seed, size); }

    ScheduledCluster(int size, int seed, int initialVoters) { this(size, seed, initialVoters, timeSpan(60).seconds()); }

    ScheduledCluster(int size, int seed, org.pragmatica.lang.io.TimeSpan syncRetryInterval, org.pragmatica.lang.io.TimeSpan decisionGapTimeout) {
        this(size, seed, size, syncRetryInterval, decisionGapTimeout);
    }

    ScheduledCluster(int size, int seed, int initialVoters, org.pragmatica.lang.io.TimeSpan syncRetryInterval) {
        this(size, seed, initialVoters, syncRetryInterval, ProtocolConfig.DEFAULT_DECISION_GAP_TIMEOUT);
    }

    ScheduledCluster(int size, int seed, int initialVoters, org.pragmatica.lang.io.TimeSpan syncRetryInterval,
                     org.pragmatica.lang.io.TimeSpan decisionGapTimeout) {
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
                            emit(new Delivery(sender, target, message));
                        }
                    }
                    return Unit.unit();
                }

                @Override
                public <M extends ProtocolMessage> Unit send(NodeId id, M message) {
                    emit(new Delivery(sender, members.indexOf(id), message));
                    return Unit.unit();
                }
            };
            machines.add(machine);
            var consensusEvents = new java.util.concurrent.CopyOnWriteArrayList<ConsensusEvent>();
            events.add(consensusEvents);
            var config = ProtocolConfig.consensusConfig(timeSpan(60).seconds(), syncRetryInterval)
                                       .withDecisionGapTimeout(decisionGapTimeout);
            var engine = new RabiaEngine<>(topology, network, machine, config, ConsensusMetrics.noop(), false,
                                          RabiaPersistence.inMemory(), RabiaEngine.DEFAULT_PHASE_STALL_CHECK, consensusEvents::add);
            assertThat(engine.initializeVoters(new VoterConfiguration(0, new ClusterConfig(members.subList(0, initialVoters)))).isSuccess()).isTrue();
            if (index >= initialVoters) { engine.authorizeObservation(); }
            engines.add(engine);
        }
    }

    void emit(Delivery delivery) {
        if (delivery.message() instanceof Propose<?> || delivery.message() instanceof VoteRound1
            || delivery.message() instanceof VoteRound2 || delivery.message() instanceof Decision<?>) {
            sent.add(delivery.message());
        }
        emitted.add(delivery);
    }

    /// Crash-stops a replica: nothing is delivered to or from it again.
    void kill(int index) {
        dead.add(index);
        pending.removeIf(delivery -> delivery.source() == index || delivery.target() == index);
        engines.get(index).stop().await();
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
            if (pumpStep(completed)) {
                return;
            }
        }
        settle();
        assertThat(completed.getAsBoolean()).as("schedule must make progress; pending=%s", pending.size()).isTrue();
    }

    /// Pumps against a wall-clock budget instead of a step count, for schedules whose progress comes
    /// from an engine TIMER (a quiet cluster emits nothing until one fires), which a step budget can
    /// exhaust in less time than one timer period.
    void pumpWithTimersUntil(BooleanSupplier completed, long budgetMillis) {
        var deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(budgetMillis);
        while (System.nanoTime() < deadline) {
            if (pumpStep(completed)) {
                return;
            }
        }
        settle();
        assertThat(completed.getAsBoolean()).as("schedule must make progress within %sms; pending=%s", budgetMillis, pending.size()).isTrue();
    }

    /// Pumps at most `budget` steps without asserting: the steps used once `completed` held, or -1 when the budget ran out.
    int pumpBounded(BooleanSupplier completed, int budget) {
        for (int step = 0; step < budget; step++) {
            if (pumpStep(completed)) {
                return step;
            }
        }
        settle();
        return completed.getAsBoolean() ? budget : -1;
    }

    /// One settle-collect-deliver step; true once `completed` holds.
    boolean pumpStep(BooleanSupplier completed) {
        settle();
        verifyPrefixes();
        collectEmitted();
        for (var iterator = parked.iterator(); iterator.hasNext(); ) {
            var delivery = iterator.next();
            if (!held.test(delivery)) {
                pending.add(delivery);
                iterator.remove();
            }
        }
        if (completed.getAsBoolean()) {
            return true;
        }
        if (pending.isEmpty()) {
            return false;
        }
        var delivery = pending.remove(random.nextInt(pending.size()));
        deliver(delivery);
        if (random.nextInt(8) == 0) {
            deliver(delivery);
        }
        return false;
    }

    void collectEmitted() {
        for (var delivery = emitted.poll(); delivery != null; delivery = emitted.poll()) {
            if (!dead.contains(delivery.source()) && !dead.contains(delivery.target()) && !blocked.test(delivery)) {
                parked.add(delivery);
            }
        }
    }

    /// Stops holding and delivers everything held so far, once each and in emission order, settling
    /// after every delivery. Traffic the release provokes joins the ordinary randomized schedule.
    void releaseHeldInOrder() {
        var releasing = held;
        held = _ -> false;
        settle();
        collectEmitted();
        var released = new ArrayList<Delivery>();
        for (var iterator = parked.iterator(); iterator.hasNext(); ) {
            var delivery = iterator.next();
            if (releasing.test(delivery)) {
                released.add(delivery);
                iterator.remove();
            }
        }
        for (var delivery : released) {
            deliver(delivery);
            settle();
            verifyPrefixes();
        }
    }

    /// Runs `rounds` genesis rounds on the given engines, delivering all traffic between rounds.
    void genesisRounds(List<Integer> indices, int rounds) {
        for (int round = 0; round < rounds; round++) {
            indices.forEach(index -> engines.get(index).runGenesisRoundForTesting());
            pumpUntil(() -> pending.isEmpty() && emitted.isEmpty());
        }
    }

    void settle() {
        for (int index = 0; index < engines.size(); index++) {
            if (!dead.contains(index)) {
                engines.get(index).settleForTesting().await();
            }
        }
    }

    void verifyPrefixes() {
        var logs = machines.stream().map(machine -> List.copyOf(machine.getProcessedCommands())).toList();
        var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
        for (var log : logs) {
            assertThat(log).containsExactlyElementsOf(longest.subList(0, log.size()));
            if (!allowIdenticalCommands) {
                assertThat(log).doesNotHaveDuplicates();
            }
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
            case GenesisAnnouncement message -> engine.genesisAnnouncement(message);
            case SyncResponse<?> message -> engine.processSyncResponse((SyncResponse<TestCommand>) message);
            default -> {}
        }
    }

    void stop() {
        engines.forEach(engine -> engine.stop().await());
    }

    record Delivery(int source, int target, ProtocolMessage message) {}

    static final class SnapshotMachine extends TestStateMachine {
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
