package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.consensus.Consensus;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.*;
import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.Command;
import org.pragmatica.cluster.state.StateMachine;
import org.pragmatica.lang.utils.SharedScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

public class RabiaEngine<T extends RabiaProtocolMessage, C extends Command> implements Consensus<T, C> {
    private static final Logger log = LoggerFactory.getLogger(RabiaEngine.class);

    private final NodeId self;
    private final AddressBook addressBook;
    private final ClusterNetwork<T> network;
    private final StateMachine<C> stateMachine;
    private final RabiaEngineConfig config;

    private final Queue<Batch<C>> pendingBatches = new ConcurrentLinkedQueue<>();
    private final Map<Long, RoundData<C>> instances = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> nodePhases = new ConcurrentHashMap<>();
    private final Map<Long, StateValue> coinFlips = new ConcurrentHashMap<>();

    private final AtomicLong nextSlot = new AtomicLong(1);
    private final AtomicLong syncAttempts = new AtomicLong(0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile NodeMode mode = NodeMode.FOLLOWER;

    enum NodeMode {FOLLOWER, ACTIVE}

    enum VotePhase {ROUND1, ROUND2}

    enum StateValue {V0, V1, VQUESTION}

    public RabiaEngine(NodeId self,
                       AddressBook addressBook,
                       ClusterNetwork<T> network,
                       StateMachine<C> stateMachine,
                       RabiaEngineConfig config) {
        this.self = self;
        this.addressBook = addressBook;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;

        SharedScheduler.scheduleAtFixedRate(this::cleanupOldInstances, timeSpan(config.cleanupInterval()).seconds());
        SharedScheduler.schedule(this::trySynchronize, timeSpan(config.syncRetryInterval()).millis());
    }

    private void trySynchronize() {
        if (mode == NodeMode.ACTIVE) {
            return;
        }

        log.info("Node {} requests snapshot", self);
        network.broadcast(new SnapshotRequest(self));
        syncAttempts.incrementAndGet();
        SharedScheduler.schedule(this::trySynchronize, timeSpan(config.syncRetryInterval()).millis());
    }

    @Override
    public void submitCommands(List<C> commands) {
        var batch = Batch.create(commands);
        pendingBatches.add(batch);

        log.info("Node {} received batch {}", self, pendingBatches.peek());

        if (mode == NodeMode.ACTIVE) {
            executor.execute(this::startRound);
        }
    }

    private void startRound() {
        var slot = nextSlot.get();

        if (instances.containsKey(slot)) {
            return;
        }

        var batch = pendingBatches.poll();

        if (batch == null) {
            return;
        }

        log.info("Node {} starts round {} with batch {}", self, slot, batch);

        instances.put(slot, RoundData.create(slot, batch));
        network.broadcast(new Propose<>(self, slot, batch));
    }

    @Override
    @SuppressWarnings("unchecked")
    public void processMessage(RabiaProtocolMessage message) {
        executor.execute(() -> {
            switch (message) {
                case Propose<?> propose -> onPropose((Propose<C>) propose);
                case Vote vote -> onVote(vote);
                case Decide<?> decide -> onDecide((Decide<C>) decide);
                case SnapshotRequest snapshotRequest -> onSnapshotRequest(snapshotRequest);
                case SnapshotResponse snapshotResponse -> onSnapshotResponse(snapshotResponse);
            }
        });
    }

    private void onPropose(Propose<C> msg) {
        log.info("Node {} received proposal for slot {} with batch {}", self, msg.slot(), msg.batch());

        var instance = instances.computeIfAbsent(msg.slot(), s -> RoundData.create(s, msg.batch()));

        instance.proposals.put(msg.sender(), msg.batch());

        nodePhases.merge(msg.sender(),
                         msg.slot(),
                         (oldPhase, newPhase) -> oldPhase.equals(newPhase) ? oldPhase : throwPhaseError());

        if (instance.phase.get() == VotePhase.ROUND1 && !instance.voted.get()) {
            var value = evaluateConsensusState(instance);
            network.broadcast(new Vote(self, msg.slot(), mapStateToBatchId(value, instance.batch), true));
            instance.voted.set(true);
        }
    }

    private void onVote(Vote msg) {
        log.info("Node {} received vote from {} for slot {} with batch {} and match {}",
                 self,
                 msg.sender(),
                 msg.slot(),
                 msg.batchId(),
                 msg.match());
        var instance = instances.get(msg.slot());

        if (instance == null || instance.decided.get()) {
            log.info("Node {} ignores vote for slot {} with batch {} and match {}",
                     self,
                     msg.slot(),
                     msg.batchId(),
                     msg.match());
            return;
        }

        instance.addVote(msg.batchId(), msg.match());

        if (instance.phase.get() == VotePhase.ROUND1) {
            handleRound1Vote(instance);
        } else {
            handleRound2Vote(instance);
        }
    }

    private void handleRound1Vote(RoundData<C> instance) {
        if (instance.hasMajorityPositive()) {
            instance.phase.set(VotePhase.ROUND2);
            instance.voted.set(false);
            instance.votes.clear();
        } else if (instance.hasMajorityNegative()) {
            decide(instance, Batch.create(List.of()));
        }
    }

    private void handleRound2Vote(RoundData<C> instance) {
        if (instance.hasMajorityPositive()) {
            decide(instance, instance.batch);
        } else if (instance.hasMajorityNegative()) {
            decide(instance, Batch.create(List.of()));
        } else if (instance.allVotesVQuestion() && !coinFlips.containsKey(instance.slot)) {
            var coin = generateCoin(instance.slot);
            coinFlips.put(instance.slot, coin);
            network.broadcast(new Vote(self, instance.slot, mapStateToBatchId(coin, instance.batch), true));
        }
    }

    private void onDecide(Decide<C> msg) {
        var instance = instances.computeIfAbsent(msg.slot(), s -> RoundData.create(s, msg.batch()));
        decide(instance, msg.batch());
    }

    private void decide(RoundData<C> instance, Batch<C> batch) {
        if (instance.decided.compareAndSet(false, true)) {
            log.info("Node {} decides slot {} with batch {}", self, instance.slot, batch);
            batch.commands().forEach(stateMachine::process);
            network.broadcast(new Decide<>(self, instance.slot, batch));
            nextSlot.incrementAndGet();
            startRound();
        }
    }

    private void onSnapshotRequest(SnapshotRequest request) {
        if (request.sender().equals(self)) {
            return;
        }
        if (mode != NodeMode.ACTIVE && (syncAttempts.get() <= config.maxSyncAttempts() || !network.quorumConnected())) {
            return;
        }

        stateMachine.makeSnapshot()
                    .onSuccess(snapshot -> network.send(request.sender(),
                                                        new SnapshotResponse(self, snapshot, nextSlot.get())));
    }

    private void onSnapshotResponse(SnapshotResponse response) {
        if (mode == NodeMode.ACTIVE || response.sender().equals(self) || response.slot() < nextSlot.get()) {
            return;
        }

        stateMachine.restoreSnapshot(response.snapshot())
                    .onSuccessRun(() -> {
                        mode = NodeMode.ACTIVE;
                        nextSlot.set(response.slot());
                        startRound();
                    });
    }

    private void cleanupOldInstances() {
        var currentSlot = nextSlot.get();
        instances.keySet().removeIf(slot -> slot < currentSlot - config.removeOlderThan());
    }

    private StateValue evaluateConsensusState(RoundData<C> instance) {
        if (instance.proposals.values().stream().distinct().count() == 1) {
            return StateValue.V1;
        }
        if (instance.proposals.isEmpty()) {
            return StateValue.VQUESTION;
        }
        return StateValue.V0;
    }

    private BatchId mapStateToBatchId(StateValue value, Batch<C> fallbackBatch) {
        return switch (value) {
            case V1 -> fallbackBatch.id();
            case V0 -> BatchId.create("V0");
            case VQUESTION -> BatchId.create("VQUESTION");
        };
    }

    private StateValue generateCoin(long slot) {
        var seed = slot ^ self.id().hashCode();
        return (Math.abs(seed) % 2 == 0) ? StateValue.V0 : StateValue.V1;
    }

    private Long throwPhaseError() {
        throw new IllegalStateException("Node appears in multiple phases simultaneously");
    }

    record RoundData<C extends Command>(long slot,
                                        Batch<C> batch,
                                        Map<NodeId, Batch<C>> proposals,
                                        Map<BatchId, Map<Boolean, Integer>> votes,
                                        AtomicBoolean voted,
                                        AtomicBoolean decided,
                                        AtomicReference<VotePhase> phase) {

        static <C extends Command> RoundData<C> create(long slot, Batch<C> batch) {
            return new RoundData<>(slot,
                                   batch,
                                   new ConcurrentHashMap<>(),
                                   new ConcurrentHashMap<>(),
                                   new AtomicBoolean(false),
                                   new AtomicBoolean(false),
                                   new AtomicReference<>(VotePhase.ROUND1));
        }

        void addVote(BatchId batchId, boolean match) {
            votes.computeIfAbsent(batchId, _ -> new ConcurrentHashMap<>()).merge(match, 1, Integer::sum);
        }

        boolean hasMajorityPositive() {
            return votes.values()
                        .stream()
                        .anyMatch(map -> map.getOrDefault(true, 0) >= quorum());
        }

        boolean hasMajorityNegative() {
            return votes.values()
                        .stream()
                        .anyMatch(map -> map.getOrDefault(false, 0) >= quorum());
        }

        boolean allVotesVQuestion() {
            return votes.keySet().stream().allMatch(id -> id.id().equals("VQUESTION"));
        }

        private int quorum() {
            return (batch.commands().size() / 2) + 1;
        }
    }
}
