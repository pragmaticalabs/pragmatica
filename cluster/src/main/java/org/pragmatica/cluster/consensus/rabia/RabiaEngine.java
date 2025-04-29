package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.consensus.Consensus;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.*;
import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.state.StateMachine;
import org.pragmatica.cluster.state.Command;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

    //TODO: make queue size configurable
    private final Queue<Batch<C>> pendingBatches = new ConcurrentLinkedQueue<>();
    private final Map<Integer, RoundData<C>> instances = new ConcurrentHashMap<>();
    private final AtomicInteger nextSlot = new AtomicInteger(1);
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private volatile NodeMode mode = NodeMode.FOLLOWER;

    public RabiaEngine(NodeId self, AddressBook addressBook, ClusterNetwork<T> network, StateMachine<C> stateMachine) {
        this.self = self;
        this.addressBook = addressBook;
        this.network = network;
        this.stateMachine = stateMachine;

        //TODO: make interval configurable
        SharedScheduler.scheduleAtFixedRate(this::cleanupOldInstances, TimeSpan.timeSpan(10).seconds());
    }

    @Override
    public void submitCommands(List<C> commands) {
        pendingBatches.add(Batch.create(commands));

        if (mode == NodeMode.NORMAL) {
            executor.execute(this::startRound);
        }
    }

    private void startRound() {
        int slot = nextSlot.get();

        if (instances.containsKey(slot)) {
            return;
        }

        var batch = pendingBatches.poll();

        if (batch == null) {
            return;
        }

        var instance = RoundData.create(slot, batch);
        instances.put(slot, instance);
        network.broadcast(new Propose<>(self, slot, batch));
        // Remove the immediate vote - we'll vote when we see enough proposals
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
        var instance = instances.computeIfAbsent(msg.slot(), s -> RoundData.create(s, msg.batch()));

        instance.proposals.put(msg.sender(), msg.batch());

        // Only try to vote if we're in round 1 and haven't voted yet
        if (instance.phase.get() == Phase.ROUND1 && !instance.voted.get()) {
            // Find the batch with majority support
            var majorityBatch = findMajorityBatch(instance);
            
            if (majorityBatch != null && instance.voted.compareAndSet(false, true)) {
                // We have a majority, lock the value and vote positively
                instance.lockValue(majorityBatch);
                network.broadcast(new Vote(self, msg.slot(), true));
            } else if (instance.voted.compareAndSet(false, true)) {
                // No majority yet, but we should still vote based on what we know
                // If this is the only proposal we've seen, vote positively for it
                boolean shouldVotePositively = instance.proposals.size() == 1;
                network.broadcast(new Vote(self, msg.slot(), shouldVotePositively));
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
                          .filter(entry -> entry.getValue() >= addressBook.consensusSize())
                          .findFirst()
                          .flatMap(entry -> instance.proposals.values()
                                                              .stream()
                                                              .filter(batch -> batch.id().equals(entry.getKey()))
                                                              .findFirst())
                          .orElse(null);
    }

    private void onVote(Vote msg) {
        var instance = instances.get(msg.slot());

        if (instance == null || instance.decided.get()) {
            return;
        }

        instance.votes.merge(msg.vote(), 1, Integer::sum);

        if (instance.phase.get() == Phase.ROUND1) {
            handleRound1Vote(instance);
        } else {
            handleRound2Vote(instance);
        }
    }

    private void handleRound1Vote(RoundData<C> instance) {
        int positiveVotes = instance.votes.getOrDefault(true, 0);
        int negativeVotes = instance.votes.getOrDefault(false, 0);

        if (positiveVotes >= addressBook.consensusSize()) {
            // Majority voted positively in round 1, move to round 2
            instance.phase.set(Phase.ROUND2);
            // Reset votes for round 2
            instance.votes.clear();
            instance.voted.set(false);
        } else if (negativeVotes >= addressBook.consensusSize()) {
            // Majority voted negatively in round 1, decide empty
            decide(instance, Batch.create(List.of()));
        }
    }

    private void handleRound2Vote(RoundData<C> instance) {
        int positiveVotes = instance.votes.getOrDefault(true, 0);
        int negativeVotes = instance.votes.getOrDefault(false, 0);

        if (positiveVotes >= addressBook.consensusSize()) {
            // If we have a locked value, use it; otherwise use the current batch
            var batch = instance.isValueLocked()
                    ? instance.lockedValue.get()
                    : instance.batch;
            decide(instance, batch);
        } else if (negativeVotes >= addressBook.consensusSize()) {
            decide(instance, Batch.create(List.of()));
        }
    }

    private void onDecide(Decide<C> msg) {
        var instance = instances.computeIfAbsent(msg.slot(), s -> RoundData.create(s, msg.batch()));
        decide(instance, msg.batch());
    }

    private void decide(RoundData<C> instance, Batch<C> batch) {
        if (instance.decided.compareAndSet(false, true)) {
            batch.commands().forEach(stateMachine::process);

            network.broadcast(new Decide<>(self, instance.slot, batch));
            nextSlot.incrementAndGet();
            startRound();
        }
    }

    private void onSnapshotRequest(SnapshotRequest snapshotRequest) {
        stateMachine.makeSnapshot()
                    .onSuccess(snapshot -> network.send(snapshotRequest.sender(),
                                                        new SnapshotResponse(self, snapshot)))
                    .onFailure(cause -> log.warn("Unable to make snapshot of state machine: {}", cause));
    }

    private void onSnapshotResponse(SnapshotResponse snapshotResponse) {
        stateMachine.restoreSnapshot(snapshotResponse.snapshot())
                    .onSuccessRun(() -> {
                        log.info("Successfully restored snapshot from {}", snapshotResponse.sender());
                        mode = NodeMode.NORMAL;
                        startRound();
                    })
                    .onFailure(cause -> log.error("Failed to restore snapshot from {}, {}",
                                                  snapshotResponse.sender(),
                                                  cause));
    }

    //TODO: make configurable
    private void cleanupOldInstances() {
        int currentSlot = nextSlot.get();
        instances.keySet()
                 .removeIf(slot -> slot < currentSlot - 100);
    }

    enum NodeMode {FOLLOWER, NORMAL}

    enum Phase {ROUND1, ROUND2}

    record RoundData<C extends Command>(int slot,
                                        Batch<C> batch,
                                        Map<NodeId, Batch<C>> proposals,
                                        Map<Boolean, Integer> votes,
                                        AtomicBoolean voted,
                                        AtomicBoolean decided,
                                        AtomicReference<Phase> phase,
                                        AtomicReference<Batch<C>> lockedValue) {
        static <C extends Command> RoundData<C> create(int slot, Batch<C> batch) {
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
    }
}
