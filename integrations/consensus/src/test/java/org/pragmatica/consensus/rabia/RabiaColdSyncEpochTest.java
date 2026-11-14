package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestClusterNetwork;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestTopologyManager;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Asynchronous.*;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.consensus.rabia.ScheduledCluster.SnapshotMachine;
import org.pragmatica.consensus.topology.ClusterStateNotification;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #3213 — a restarted voter re-initializes at the genesis epoch, and a COLD sync answer carries no
/// configuration (owner ruling, #1526). A restarted genesis majority must not move past a slot that changed
/// the roster and then decide the next slot under the genesis roster while a member of the newer epoch is
/// still live: that member and a voter that rejoins it decide the same slot again at the newer epoch.
///
/// Four engines are needed, not three: every restart resets the epoch, so the newer-epoch side needs one
/// member that never restarted (D). The reconfiguration at slot 0 and the batch at slot 1 are injected as
/// decided ([#decideSetup]), as the single-engine reconfiguration tests do; everything after them is real
/// engine traffic, delivered by an explicit schedule (lost and delayed messages only).
class RabiaColdSyncEpochTest {
    private static final NodeId A = new NodeId("node-1");
    private static final NodeId B = new NodeId("node-2");
    private static final NodeId C = new NodeId("node-3");
    private static final NodeId D = new NodeId("node-4");
    private static final List<NodeId> ALL = List.of(A, B, C, D);
    private static final ClusterConfig GENESIS_ROSTER = new ClusterConfig(List.of(A, B, C));
    private static final ClusterConfig REPLACED = new ClusterConfig(List.of(A, B, D));

    private final Map<NodeId, RabiaPersistence<TestCommand>> disks = new HashMap<>();
    private final Map<NodeId, RabiaEngine<TestCommand>> engines = new HashMap<>();
    private final Map<NodeId, SnapshotMachine> machines = new HashMap<>();
    private final List<Delivery> queue = new ArrayList<>();
    private final List<Delivery> sent = new ArrayList<>();

    record Delivery(NodeId source, NodeId target, ProtocolMessage message) {}

    @AfterEach void stopAll() { engines.values().forEach(engine -> engine.stop().await()); }

    @Test void restartedGenesisMajority_neverDecidesASlotTheNewerEpochGoverns() {
        // Before the restarts: A and B decided the change to {A,B,D} at slot 0 and a batch at slot 1, so they sit
        // at epoch 1, phase 2. C was down the whole time (it is the voter the change replaced).
        ALL.forEach(id -> disks.put(id, RabiaPersistence.inMemory()));
        start(A); start(B); start(C);
        deliverAllWhile(() -> !(engine(A).isActive() && engine(B).isActive() && engine(C).isActive()));
        stop(C);
        List.of(A, B).forEach(this::decideSetup);
        assertThat(engine(A).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        queue.clear();

        // D joins the newer epoch from A's and B's LIVE answers and stays up through everything below.
        start(D);
        deliverAllWhile(() -> !engine(D).isActive());
        assertThat(engine(D).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        assertThat(engine(D).currentPhaseForTesting()).isEqualTo(Phase.phase(2));
        queue.clear();

        // Precondition 1 of the issue: stopping persists phase 2 together with the epoch-1 configuration.
        stop(A); stop(B);
        var persistedB = disks.get(B).load().unwrap();
        assertThat(persistedB.lastCommittedPhase()).isEqualTo(Phase.phase(2));
        assertThat(persistedB.configuration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));

        // Rolling restart of A, B and C: each re-initializes at the genesis epoch.
        start(B); start(A); start(C);

        // Precondition 2: A's only answer is B's COLD one (phase 2, no configuration), and one answer is the
        // no-live adoption majority at n=3.
        deliverOnly(d -> d.source().equals(A) && d.target().equals(B) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(B) && d.target().equals(A) && d.message() instanceof SyncResponse<?>);
        // C hears A and B only; B hears D only. Every other sync message is lost.
        deliverOnly(d -> d.source().equals(C) && (d.target().equals(A) || d.target().equals(B)) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.target().equals(C) && d.message() instanceof SyncResponse<?>);
        deliverOnly(d -> d.source().equals(B) && d.target().equals(D) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(D) && d.target().equals(B) && d.message() instanceof SyncResponse<?>);
        queue.removeIf(d -> d.message() instanceof SyncRequest || d.message() instanceof SyncResponse<?>);
        assertThat(engine(B).voterConfiguration().unwrap()).as("B rejoins the live newer epoch").isEqualTo(new VoterConfiguration(1, REPLACED));

        // Slot 2: A and C carry "x", B and D carry "y"; the two pairs' traffic interleaves freely but never crosses.
        submit(A, "x");
        submit(D, "y");
        var pairAC = Set.of(A, C);
        var pairBD = Set.of(B, D);
        deliverWhile(d -> (pairAC.contains(d.source()) && pairAC.contains(d.target()))
                          || (pairBD.contains(d.source()) && pairBD.contains(d.target())),
                     () -> true);

        var epochZeroBallotsForSlotTwo = sent.stream()
                                             .filter(d -> d.source().equals(A) || d.source().equals(C))
                                             .filter(d -> ballotAt(d.message(), 0, Phase.phase(2)))
                                             .toList();
        assertThat(epochZeroBallotsForSlotTwo).as("a restarted voter must not vote at epoch 0 for slot 2, which epoch 1 governs")
                                              .isEmpty();
        var logs = ALL.stream().filter(engines::containsKey).map(id -> List.copyOf(machines.get(id).getProcessedCommands())).toList();
        var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
        logs.forEach(log -> assertThat(log).as("slot 2 is decided at most once: every applied log is a prefix of the longest")
                                           .containsExactlyElementsOf(longest.subList(0, log.size())));
    }

    private static boolean ballotAt(ProtocolMessage message, long epoch, Phase phase) {
        return switch (message) {
            case Propose<?> propose -> propose.epoch() == epoch && propose.phase().equals(phase);
            case VoteRound1 vote -> vote.epoch() == epoch && vote.phase().equals(phase);
            case VoteRound2 vote -> vote.epoch() == epoch && vote.phase().equals(phase);
            default -> false;
        };
    }

    /// The decided history before the restarts (simulated: injected as Decisions, not voted here).
    private void decideSetup(NodeId id) {
        var change = ReconfigurationCommand.reconfigurationCommand(0, REPLACED);
        engine(id).processDecision(new Decision<>(B, 0, Phase.phase(0), StateValue.V1, Batch.emptyBatch(), Option.some(change)));
        settle();
        engine(id).processDecision(new Decision<>(B, 1, Phase.phase(1), StateValue.V1, batch("s1"), Option.none()));
        settle();
        assertThat(engine(id).currentPhaseForTesting()).isEqualTo(Phase.phase(2));
    }

    private void submit(NodeId id, String value) {
        engine(id).handleNewBatch(new NewBatch<>(id, batch(value)));
        settle();
    }

    private Batch<TestCommand> batch(String value) {
        return Batch.create(machines.values().iterator().next().serializer(), List.of(new TestCommand(value)));
    }

    private RabiaEngine<TestCommand> engine(NodeId id) { return engines.get(id); }

    private void start(NodeId self) {
        var machine = new SnapshotMachine();
        var topology = new TestTopologyManager(self, ALL.size()) {
            @Override public Set<NodeId> coreNodes() { return Set.copyOf(ALL); }
        };
        var network = new TestClusterNetwork() {
            @Override public Set<NodeId> connectedPeers() { return Set.copyOf(ALL); }
            @Override public <M extends ProtocolMessage> Unit broadcast(M message) {
                ALL.stream().filter(id -> !id.equals(self)).forEach(id -> emit(self, id, message));
                return Unit.unit();
            }
            @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId id, M message) {
                emit(self, id, message);
                return Promise.success(new WriteOutcome.Sent(id));
            }
            @Override public <M extends ProtocolMessage> Promise<WriteOutcome> send(NodeId id, M message, TimeSpan offlineTtl) {
                return send(id, message);
            }
        };
        var engine = new RabiaEngine<>(topology, network, machine,
                                       ProtocolConfig.consensusConfig(timeSpan(60).seconds(), timeSpan(60).seconds()),
                                       ConsensusMetrics.noop(), false, disks.get(self));
        assertThat(engine.initializeVoters(new VoterConfiguration(0, GENESIS_ROSTER)).isSuccess()).isTrue();
        machines.put(self, machine);
        engines.put(self, engine);
        engine.clusterState(ClusterStateNotification.active());
        settle();
    }

    private void stop(NodeId id) {
        engines.remove(id).stop().await();
        queue.removeIf(d -> d.source().equals(id) || d.target().equals(id));
    }

    private synchronized void emit(NodeId source, NodeId target, ProtocolMessage message) {
        var delivery = new Delivery(source, target, message);
        sent.add(delivery);
        queue.add(delivery);
    }

    private void settle() {
        engines.values().forEach(engine -> engine.settleForTesting().await());
        engines.values().forEach(engine -> engine.settleForTesting().await());
    }

    private void deliverAllWhile(BooleanSupplier notDone) {
        deliverWhile(_ -> true, notDone);
        assertThat(notDone.getAsBoolean()).as("setup schedule must complete").isFalse();
    }

    /// Delivers the deliveries `allowed` admits, oldest first, until none is left or `notDone` turns false.
    private void deliverWhile(Predicate<Delivery> allowed, BooleanSupplier notDone) {
        for (int step = 0; step < 20_000 && notDone.getAsBoolean(); step++) {
            settle();
            Option<Delivery> next;
            synchronized (this) {
                next = Option.from(queue.stream().filter(allowed).filter(d -> engines.containsKey(d.target())).findFirst());
                next.onPresent(queue::remove);
            }
            if (next.isEmpty()) {
                return;
            }
            next.onPresent(this::deliver);
        }
        settle();
    }

    private void deliverOnly(Predicate<Delivery> allowed) { deliverWhile(allowed, () -> true); }

    @SuppressWarnings("unchecked")
    private void deliver(Delivery delivery) {
        var engine = engines.get(delivery.target());
        switch (delivery.message()) {
            case Propose<?> message -> engine.processPropose((Propose<TestCommand>) message);
            case VoteRound1 message -> engine.processVoteRound1(message);
            case VoteRound2 message -> engine.processVoteRound2(message);
            case Decision<?> message -> engine.processDecision((Decision<TestCommand>) message);
            case SyncRequest message -> engine.handleSyncRequest(message);
            case RoundRequest message -> engine.handleRoundRequest(message);
            case SyncResponse<?> message -> engine.processSyncResponse((SyncResponse<TestCommand>) message);
            case NewBatch<?> message -> engine.handleNewBatch((NewBatch<TestCommand>) message);
            case BatchCommitted message -> engine.handleBatchCommitted(message);
            default -> {}
        }
        settle();
    }
}
