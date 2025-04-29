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

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// Implementation of the Rabia state machine replication consensus protocol for distributed systems.
///
/// @param <T> Type of protocol messages used for communication
/// @param <C> Type of commands to be processed by the state machine
public class RabiaEngine<T extends RabiaProtocolMessage, C extends Command> implements Consensus<T, C> {
    private static final Logger log = LoggerFactory.getLogger(RabiaEngine.class);

    private final NodeId self;
    private final AddressBook addressBook;
    private final ClusterNetwork<T> network;
    private final StateMachine<C> stateMachine;
    private final RabiaEngineConfig config;
    private final Queue<Batch<C>> pendingBatches = new ConcurrentLinkedQueue<>();
    private final Map<Long, RoundData<C>> instances = new ConcurrentHashMap<>();
    private final AtomicLong nextSlot = new AtomicLong(1);
    private final AtomicLong syncAttempts = new AtomicLong(0);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile NodeMode mode = NodeMode.FOLLOWER;

    public RabiaEngine(NodeId self, AddressBook addressBook, ClusterNetwork<T> network, StateMachine<C> stateMachine, RabiaEngineConfig config) {
        this.self = self;
        this.addressBook = addressBook;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;

        //TODO: make interval configurable
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

    @SuppressWarnings("unchecked")
    @Override
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

        // Only try to vote if we're in round 1 and haven't voted yet
        if (instance.phase.get() == Phase.ROUND1 && !instance.voted.get()) {
            // Find the batch with majority support
            var majorityBatch = findMajorityBatch(instance);

            if (majorityBatch != null && instance.voted.compareAndSet(false, true)) {
                // We have a majority, lock the value and vote positively
                instance.lockValue(majorityBatch);
                network.broadcast(new Vote(self, msg.slot(), majorityBatch.id(), true));
            } else if (instance.voted.compareAndSet(false, true)) {
                // No majority yet, but we should still vote based on what we know
                // If this is the only proposal we've seen, vote positively for it
                boolean shouldVotePositively = instance.proposals.size() == 1;
                network.broadcast(new Vote(self, msg.slot(), msg.batch().id(), shouldVotePositively));
            }
        }
    }

    private Batch<C> findMajorityBatch(RoundData<C> instance) {
        // Group proposals by batch ID and count occurrences
        var batchCounts = instance.proposals.values().stream()
                                            .collect(Collectors.groupingBy(
                                                    Batch::id,
                                                    Collectors.counting()));

        // Find any batch that has majority support
        return batchCounts.entrySet().stream()
                          .filter(entry -> entry.getValue() >= addressBook.quorumSize())
                          .findFirst()
                          .flatMap(entry -> instance.proposals.values()
                                                              .stream()
                                                              .filter(batch -> batch.id().equals(entry.getKey()))
                                                              .findFirst())
                          .orElse(null);
    }

    private void onVote(Vote msg) {
        log.info("Node {} received vote from {} for slot {} with batch {} and match {}", self, msg.sender(), msg.slot(), msg.batchId(), msg.match());
        var instance = instances.get(msg.slot());

        if (instance == null || instance.decided.get()) {
            log.info("Node {} ignores vote for slot {} with batch {} and match {}", self, msg.slot(), msg.batchId(), msg.match());
            return;
        }

        instance.addVote(msg.batchId(), msg.match());

        if (instance.phase.get() == Phase.ROUND1) {
            handleRound1Vote(instance, msg.batchId());
        } else {
            handleRound2Vote(instance, msg.batchId());
        }
    }

    private void handleRound1Vote(RoundData<C> instance, BatchId batchId) {
        int positiveVotes = instance.getVotesForBatch(batchId, true);
        int negativeVotes = instance.getVotesForBatch(batchId, false);

        log.info("Node {} calculates votes: {} positive, {} negative. Quorum size: {}", self, positiveVotes, negativeVotes, addressBook.quorumSize());
        if (positiveVotes >= addressBook.quorumSize()) {
            // Majority voted positively in round 1, move to round 2
            instance.phase.set(Phase.ROUND2);
            // Reset votes for round 2
            instance.votes.clear();
            instance.voted.set(false);

            // Vote in round 2 for the locked value or current batch
            if (instance.voted.compareAndSet(false, true)) {
                var batchToVoteFor = instance.isValueLocked()
                        ? instance.lockedValue.get()
                        : instance.batch;

                network.broadcast(new Vote(self, instance.slot, batchToVoteFor.id(), true));
            }
        } else if (negativeVotes >= addressBook.quorumSize()) {
            // Majority voted negatively in round 1, decide empty
            decide(instance, Batch.create(List.of()));
        }
    }

    private void handleRound2Vote(RoundData<C> instance, BatchId batchId) {
        int positiveVotes = instance.getVotesForBatch(batchId, true);
        int negativeVotes = instance.getVotesForBatch(batchId, false);

        if (positiveVotes >= addressBook.quorumSize()) {
            // If we have a locked value, use it; otherwise use the current batch
            var batch = instance.isValueLocked()
                    ? instance.lockedValue.get()
                    : instance.batch;
            decide(instance, batch);
        } else if (negativeVotes >= addressBook.quorumSize()) {
            decide(instance, Batch.create(List.of()));
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

    private void onSnapshotRequest(SnapshotRequest snapshotRequest) {
        if (snapshotRequest.sender().equals(self)) {
            // Don't send snapshot to ourselves
            return;
        }

        if (mode != NodeMode.ACTIVE) {
            if (syncAttempts.get() <= config.maxSyncAttempts() || !network.quorumConnected()) {
                return;
            }
            // Quorum is connected, but we're not active yet, so we can't send a snapshot
            // Send a snapshot response anyway to kick off the process
        }

        stateMachine.makeSnapshot()
                    .onSuccess(snapshot -> network.send(snapshotRequest.sender(),
                                                        new SnapshotResponse(self, snapshot, nextSlot.get())))
                    .onFailure(cause -> log.warn("Node {} is unable to make snapshot of state machine: {}", self, cause));
    }

    private void onSnapshotResponse(SnapshotResponse snapshotResponse) {
        if (mode == NodeMode.ACTIVE) {
            return;
        }

        if (snapshotResponse.sender().equals(self)) {
            return;
        }

        if (snapshotResponse.slot() < nextSlot.get()) {
            return;
        }

        stateMachine.restoreSnapshot(snapshotResponse.snapshot())
                    .onSuccessRun(() -> {
                        log.info("Node {} successfully restored snapshot from {}", self, snapshotResponse.sender());
                        mode = NodeMode.ACTIVE;
                        nextSlot.set(snapshotResponse.slot());
                        startRound();
                    })
                    .onFailure(cause -> log.error("Node {} failed to restore snapshot from {}, {}",
                                                  self,
                                                  snapshotResponse.sender(),
                                                  cause));
    }

    private void cleanupOldInstances() {
        var currentSlot = nextSlot.get();

        instances.keySet()
                 .removeIf(slot -> slot < currentSlot - config.removeOlderThan());
    }

    enum NodeMode {FOLLOWER, ACTIVE}

    enum Phase {ROUND1, ROUND2}

    record RoundData<C extends Command>(long slot,
                                        Batch<C> batch,
                                        Map<NodeId, Batch<C>> proposals,
                                        Map<BatchId, Map<Boolean, Integer>> votes,
                                        AtomicBoolean voted,
                                        AtomicBoolean decided,
                                        AtomicReference<Phase> phase,
                                        AtomicReference<Batch<C>> lockedValue) {
        static <C extends Command> RoundData<C> create(long slot, Batch<C> batch) {
            return new RoundData<>(slot,
                                   batch,
                                   new ConcurrentHashMap<>(),
                                   new ConcurrentHashMap<>(),
                                   new AtomicBoolean(false),
                                   new AtomicBoolean(false),
                                   new AtomicReference<>(Phase.ROUND1),
                                   new AtomicReference<>());
        }

        boolean isValueLocked() {
            return lockedValue.get() != null;
        }

        void lockValue(Batch<C> value) {
            this.lockedValue.set(value);
        }

        void addVote(BatchId batchId, boolean match) {
            votes.computeIfAbsent(batchId, _ -> new ConcurrentHashMap<>())
                 .merge(match, 1, Integer::sum);
        }

        int getVotesForBatch(BatchId batchId, boolean match) {
            return votes.getOrDefault(batchId, Map.of())
                        .getOrDefault(match, 0);
        }
    }
}
