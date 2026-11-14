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

/// #3213 — a laggard genesis voter must not adopt a phase past a roster change from a COLD sync answer alone.
///
/// The production path (cores use in-memory persistence, so no restart reloads an older disk): A and C are genesis
/// voters that missed the slot-0 change {A,B,C} → {A,B,D} and stay at epoch 0. B decided it and the slot-1 batch, so it
/// sits at epoch 1, phase 2; it paused once (persisting phase 2 under epoch 1) and is now Syncing, so it answers COLD
/// from that persisted state. If a COLD answer drops the configuration, A and C read B's phase 2 as epoch-0 evidence,
/// adopt it, and decide slot 2 under the genesis roster while B and D decide slot 2 under the epoch-1 roster: two
/// disjoint majorities ({A,C} of {A,B,C}, {B,D} of {A,B,D}) for one slot.
///
/// The decisions at slots 0 and 1 are injected on B ([#decideSetup]), as the single-engine reconfiguration tests do;
/// each move into Syncing is started by a far-future Propose ([#triggerResync]), standing for the production triggers
/// (a far-future ballot or a decision-gap timeout) whose payload the engine discards. Everything else is real engine
/// traffic, delivered by an explicit schedule (lost and delayed messages only).
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

    @Test void laggardGenesisVoters_neverDecideASlotTheNewerEpochGoverns_fromAColdAnswer() {
        persistedEpochOneOnB();

        // B, A and C each go Syncing; B now answers COLD from its persisted phase 2.
        triggerResync(B, D, 1);
        triggerResync(A, C, 0);
        triggerResync(C, A, 0);

        // A and C hear only B (one answer is the no-live adoption bound at n=3); B hears only D.
        deliverOnly(d -> (d.source().equals(A) || d.source().equals(C)) && d.target().equals(B) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(B) && (d.target().equals(A) || d.target().equals(C)) && d.message() instanceof SyncResponse<?>);
        deliverOnly(d -> d.source().equals(B) && d.target().equals(D) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(D) && d.target().equals(B) && d.message() instanceof SyncResponse<?>);
        queue.removeIf(d -> d.message() instanceof SyncRequest || d.message() instanceof SyncResponse<?>);
        assertThat(engine(B).isActive()).as("B rejoins the live newer epoch").isTrue();
        assertThat(engine(B).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));

        // Slot 2: A and C carry "x", B and D carry "y"; the two pairs' traffic interleaves freely but never crosses.
        submit(A, "x");
        submit(B, "y");
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
        assertThat(epochZeroBallotsForSlotTwo).as("a laggard must not vote at epoch 0 for slot 2, which epoch 1 governs")
                                              .isEmpty();
        var logs = ALL.stream().map(id -> List.copyOf(machines.get(id).getProcessedCommands())).toList();
        var longest = logs.stream().max(java.util.Comparator.comparingInt(List::size)).orElse(List.of());
        logs.forEach(log -> assertThat(log).as("slot 2 is decided at most once: every applied log is a prefix of the longest")
                                           .containsExactlyElementsOf(longest.subList(0, log.size())));
        // Positive control: the epoch-1 pair is live on slot 2 (both ballot at epoch 1), so the check above is not vacuous.
        // D already holds its own join-time proposal for slot 2, so the two batches differ and D votes V0: the pair
        // ballots without deciding, which is all the control needs.
        assertThat(sent.stream().filter(d -> d.source().equals(B)).anyMatch(d -> ballotAt(d.message(), 1, Phase.phase(2))))
            .as("positive control: B ballots at epoch 1 for slot 2").isTrue();
        assertThat(sent.stream().filter(d -> d.source().equals(D)).anyMatch(d -> d.message() instanceof VoteRound1 vote
                                                                                 && vote.epoch() == 1 && vote.phase().equals(Phase.phase(2))))
            .as("positive control: D votes at epoch 1 for slot 2").isTrue();
    }

    /// A and C lag at epoch 0; B decided the roster change and a batch, D joined epoch 1 from B's LIVE answer, and B
    /// persisted phase 2 under epoch 1 by pausing and resuming.
    private void persistedEpochOneOnB() {
        ALL.forEach(id -> disks.put(id, RabiaPersistence.inMemory()));
        start(A); start(B); start(C);
        deliverAllWhile(() -> !(engine(A).isActive() && engine(B).isActive() && engine(C).isActive()));
        queue.clear();

        // B decides the change to {A,B,D} at slot 0 and a batch at slot 1; A and C miss both decisions.
        decideSetup(B);
        assertThat(engine(B).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        assertThat(engine(A).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(0, GENESIS_ROSTER));
        assertThat(engine(C).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(0, GENESIS_ROSTER));
        queue.clear();

        // D joins the newer epoch from B's LIVE answer.
        start(D);
        deliverOnly(d -> d.source().equals(D) && d.target().equals(B) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(B) && d.target().equals(D) && d.message() instanceof SyncResponse<?>);
        assertThat(engine(D).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        assertThat(engine(D).currentPhaseForTesting()).isEqualTo(Phase.phase(2));
        queue.clear();

        // B loses and regains quorum: the pause persists phase 2 under epoch 1.
        engine(B).clusterState(ClusterStateNotification.passive());
        settle();
        engine(B).clusterState(ClusterStateNotification.active());
        settle();
        var persistedB = disks.get(B).load().unwrap();
        assertThat(persistedB.lastCommittedPhase()).isEqualTo(Phase.phase(2));
        assertThat(persistedB.configuration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        queue.clear();
    }

    /// Side effect of the fix: a COLD answer from an engine that persisted now carries its configuration, so a receiver
    /// already at epoch 1 counts a same-epoch COLD answer (it used to refuse the config-less one). The cold rule (#660: with
    /// no LIVE responder, `clusterSize / 2` answers plus the receiver's own state, a majority of the electorate) must be
    /// unchanged: no answer adopts nothing, and an answer that does not carry the receiver's epoch, however many, is not counted.
    @Test void sameEpochColdAnswer_isCountedOnlyAsTheColdRuleCountsIt() {
        persistedEpochOneOnB();
        triggerResync(B, D, 1);
        triggerResync(D, B, 1);
        triggerResync(C, A, 0);
        queue.removeIf(d -> !d.source().equals(D));

        assertThat(engine(D).isActive()).as("D, Syncing at epoch 1, has no answer yet").isFalse();

        // C never persisted: its COLD answer is config-less epoch-0 evidence and an epoch-1 receiver refuses it.
        deliverOnly(d -> d.source().equals(D) && d.target().equals(C) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(C) && d.target().equals(D) && d.message() instanceof SyncResponse<?>);
        assertThat(engine(D).isActive()).as("a config-less epoch-0 COLD answer is not counted at epoch 1").isFalse();

        // B persisted epoch 1 itself: its COLD answer carries the configuration and is the one answer clusterSize/2 = 1
        // asks for at n=3; with D's own state that is 2 of the 3 voters, the #660 majority.
        deliverOnly(d -> d.source().equals(D) && d.target().equals(B) && d.message() instanceof SyncRequest);
        deliverOnly(d -> d.source().equals(B) && d.target().equals(D) && d.message() instanceof SyncResponse<?>);
        var answerFromB = sent.stream()
                              .filter(d -> d.source().equals(B) && d.target().equals(D) && d.message() instanceof SyncResponse<?>)
                              .map(d -> (SyncResponse<?>) d.message())
                              .reduce((first, second) -> second)
                              .orElseThrow();
        assertThat(answerFromB.responder()).as("B answered COLD").isEqualTo(ResponderState.COLD);
        assertThat(answerFromB.state().configuration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
        assertThat(engine(D).isActive()).as("one same-epoch COLD answer plus D itself is the #660 majority").isTrue();
        assertThat(engine(D).voterConfiguration().unwrap()).isEqualTo(new VoterConfiguration(1, REPLACED));
    }

    /// Moves `target` into Syncing the way a far-future ballot does; the Propose itself is discarded by the engine.
    private void triggerResync(NodeId target, NodeId sender, long epoch) {
        engine(target).processPropose(new Propose<>(sender, epoch, Phase.phase(500), batch("trigger")));
        settle();
    }

    private static boolean ballotAt(ProtocolMessage message, long epoch, Phase phase) {
        return switch (message) {
            case Propose<?> propose -> propose.epoch() == epoch && propose.phase().equals(phase);
            case VoteRound1 vote -> vote.epoch() == epoch && vote.phase().equals(phase);
            case VoteRound2 vote -> vote.epoch() == epoch && vote.phase().equals(phase);
            default -> false;
        };
    }

    /// The decided history B holds (simulated: injected as Decisions, not voted here).
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
