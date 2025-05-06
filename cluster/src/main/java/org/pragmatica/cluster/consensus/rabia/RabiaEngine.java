package org.pragmatica.cluster.consensus.rabia;

import org.pragmatica.cluster.consensus.Consensus;
import org.pragmatica.cluster.consensus.ConsensusErrors;
import org.pragmatica.cluster.consensus.rabia.RabiaPersistence.SavedState;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.Asynchronous;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.Asynchronous.NewBatch;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.Asynchronous.SyncRequest;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.Synchronous;
import org.pragmatica.cluster.consensus.rabia.RabiaProtocolMessage.Synchronous.*;
import org.pragmatica.cluster.net.AddressBook;
import org.pragmatica.cluster.net.ClusterNetwork;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.cluster.net.QuorumState;
import org.pragmatica.cluster.state.Command;
import org.pragmatica.cluster.state.StateMachine;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.SharedScheduler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/// Implementation of the Rabia consensus protocol.
public class RabiaEngine<T extends RabiaProtocolMessage, C extends Command> implements Consensus<T, C> {
    private static final Logger log = LoggerFactory.getLogger(RabiaEngine.class);

    private final NodeId self;
    private final AddressBook addressBook;
    private final ClusterNetwork<T> network;
    private final StateMachine<C> stateMachine;
    private final ProtocolConfig config;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Random random = new Random();
    private final Map<CorrelationId, Batch<C>> pendingBatches = new ConcurrentHashMap<>();
    private final Map<NodeId, SavedState<C>> syncResponses = new ConcurrentHashMap<>();
    private final RabiaPersistence<C> persistence = RabiaPersistence.inMemory();
    @SuppressWarnings("rawtypes")
    private final Map<CorrelationId, Promise> correlationMap = new ConcurrentHashMap<>();

    //--------------------------------- Node State Start
    private final Map<Phase, PhaseData<C>> phases = new ConcurrentHashMap<>();
    private final AtomicReference<Phase> currentPhase = new AtomicReference<>(Phase.ZERO);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean isInPhase = new AtomicBoolean(false);
    private final AtomicReference<Promise<Unit>> startPromise = new AtomicReference<>(Promise.promise());
    private final AtomicReference<Phase> lastCommittedPhase = new AtomicReference<>(Phase.ZERO);
    //--------------------------------- Node State End

    /// Creates a new Rabia consensus engine.
    ///
    /// @param self         The node ID of this node
    /// @param addressBook  The address book for node communication
    /// @param network      The network implementation
    /// @param stateMachine The state machine to apply commands to
    /// @param config       Configuration for the consensus engine
    public RabiaEngine(NodeId self,
                       AddressBook addressBook,
                       ClusterNetwork<T> network,
                       StateMachine<C> stateMachine,
                       ProtocolConfig config) {
        this.self = self;
        this.addressBook = addressBook;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;

        //TODO: use message bus instead of direct subscriptions
        // Subscribe to network events
        network.observeQuorumState(this::quorumState);
        // Setup periodic tasks
        SharedScheduler.scheduleAtFixedRate(this::cleanupOldPhases, config.cleanupInterval());
        SharedScheduler.schedule(this::synchronize, config.syncRetryInterval());
    }

    public void quorumState(QuorumState quorumState) {
        log.trace("Node {} received quorum state {}", self, quorumState);
        switch (quorumState) {
            case APPEARED -> clusterConnected();
            case DISAPPEARED -> clusterDisconnected();
        }
    }

    private void clusterConnected() {
        // Start synchronization attempts
        log.trace("Node {}: quorum connected. Starting synchronization attempts.", self);
        SharedScheduler.schedule(this::synchronize, randomize(config.syncRetryInterval()));
    }

    private void clusterDisconnected() {
        if (active.compareAndSet(true, false)) {
            persistence.save(stateMachine, lastCommittedPhase.get(), pendingBatches.values())
                       .onSuccessRun(() -> log.info("Node {} disconnected. State persisted.", self))
                       .onFailure(cause -> log.error("Node {} failed to persist state: {}", self, cause));
            phases.clear();
            currentPhase.set(Phase.ZERO);
            isInPhase.set(false);
            stateMachine.reset();
            startPromise.set(Promise.promise());
            pendingBatches.clear();
        }
    }

    private boolean nodeIsDormant() {
        return !active.get();
    }

    private TimeSpan randomize(TimeSpan timeSpan) {
        var nanos = timeSpan.nanos() + timeSpan.nanos() * (random.nextDouble() - 0.5d);

        return TimeSpan.timeSpan((long) nanos).nanos();
    }

    @Override
    public <R> Promise<List<R>> apply(List<C> commands) {
        if (commands.isEmpty()) {
            return Promise.failure(ConsensusErrors.commandBatchIsEmpty());
        }

        if (nodeIsDormant()) {
            return Promise.failure(ConsensusErrors.nodeInactive(self));
        }

        log.trace("Node {} received commands batch with {} commands", self, commands.size());

        var batch = Batch.create(commands);
        var pendingAnswer = Promise.<List<R>>promise();

        correlationMap.put(batch.correlationId(), pendingAnswer);
        pendingBatches.put(batch.correlationId(), batch);
        network.broadcast(new NewBatch<>(self, batch));

        if (!isInPhase.get()) {
            executor.execute(this::startPhase);
        }

        return pendingAnswer;
    }

    @Override
    public Promise<Unit> startPromise() {
        return startPromise.get();
    }

    @Override
    public void processMessage(RabiaProtocolMessage message) {
        switch (message) {
            case Synchronous sync -> processMessageSync(sync);
            case Asynchronous async -> processMessageAsync(async);
        }
    }

    @SuppressWarnings("unchecked")
    private void processMessageSync(Synchronous message) {
        executor.execute(() -> {
            try {
                switch (message) {
                    case Propose<?> propose -> handlePropose((Propose<C>) propose);
                    case VoteRound1 voteRnd1 -> handleVoteRound1(voteRnd1);
                    case VoteRound2 voteRnd2 -> handleVoteRound2(voteRnd2);
                    case Decision<?> decision -> handleDecision((Decision<C>) decision);
                    case SyncResponse<?> syncResponse -> handleSyncResponse((SyncResponse<C>) syncResponse);
                }
            } catch (Exception e) {
                // Unlikely anything like that will happen, but better safe than sorry
                log.error("Error processing message: {}", message, e);
            }
        });
    }

    @SuppressWarnings("unchecked")
    private void processMessageAsync(Asynchronous message) {
        switch (message) {
            case SyncRequest syncRequest -> handleSyncRequest(syncRequest);
            case NewBatch<?> newBatch ->
                    pendingBatches.put(newBatch.batch().correlationId(), (Batch<C>) newBatch.batch());
        }
    }

    /// Starts a new phase with pending commands.
    private void startPhase() {
        if (isInPhase.get() || pendingBatches.isEmpty()) {
            return;
        }

        var phase = currentPhase.get();
        var batch = pendingBatches.values()
                                  .stream()
                                  .sorted()
                                  .findFirst()
                                  .orElse(Batch.empty());

        log.trace("Node {} starting phase {} with batch {}", self, phase, batch.id());

        // Initialize phase data
        var phaseData = getOrCreatePhaseData(phase);
        phaseData.proposals.put(self, batch);

        // Mark that we're in a phase now
        isInPhase.set(true);

        // Send initial batch
        network.broadcast(new Propose<>(self, phase, batch));

        // Vote in round 1
        var vote = phaseData.evaluateInitialVote(self);
        network.broadcast(new VoteRound1(self, phase, vote));
        phaseData.round1Votes.put(self, vote);
    }

    /// Synchronizes with other nodes to catch up if needed.
    private void synchronize() {
        if (active.get()) {
            return;
        }

        var request = new SyncRequest(self);
        log.trace("Node {}: requesting phase synchronization {}", self, request);
        network.broadcast(request);

        // Schedule next synchronization attempt
        SharedScheduler.schedule(this::synchronize, randomize(config.syncRetryInterval()));
    }

    /// Handles a synchronization response from another node.
    private void handleSyncResponse(SyncResponse<C> response) {
        if (active.get()) {
            log.trace("Node {} ignoring synchronization response {}. Node is active.", self, response);
            return;
        }

        syncResponses.put(response.sender(), response.state());

        if (syncResponses.size() < addressBook.quorumSize()) {
            log.trace("Node {} received {} responses {}, not enough to proceed (quorum size = {})",
                      self, syncResponses.size(), syncResponses.keySet(), addressBook.quorumSize());
            return;
        }

        log.trace("Node {} received {} responses, collected: {}", self, syncResponses.size(), syncResponses);

        // Use the latest known state among received responses
        var candidate = syncResponses.values()
                                     .stream()
                                     .sorted(Comparator.comparing(SavedState::lastCommittedPhase))
                                     .toList()
                                     .getLast();

        log.trace("Node {} uses {} as synchronization candidate", self, candidate);
        restoreState(candidate);
    }

    private void restoreState(SavedState<C> state) {
        syncResponses.clear();

        if (state.snapshot().length == 0) {
            activate();
            return;
        }
        stateMachine.restoreSnapshot(state.snapshot())
                    .onSuccess(_ -> {
                        currentPhase.set(state.lastCommittedPhase());
                        lastCommittedPhase.set(state.lastCommittedPhase());
                        state.pendingBatches()
                             .forEach(batch -> pendingBatches.put(batch.correlationId(), batch));
                        persistence.save(stateMachine, currentPhase.get(), pendingBatches.values());

                        log.info("Node {} restored state from persistence. Current phase {}",
                                 self,
                                 currentPhase.get());
                    })
                    .onSuccessRun(this::activate)
                    .onFailure(cause -> log.error("Node {} failed to restore state: {}", self, cause))
                    .onFailureRun(syncResponses::clear); // synchronize() will restart synchronization automatically
    }

    /// Activate node and adjust phase, if necessary.
    private void activate() {
        active.set(true);
        startPromise.get()
                    .succeed(Unit.unit());
        syncResponses.clear();

        log.trace("Node {} activated in phase {}", self, currentPhase.get());
        executor.execute(this::startPhase);
    }

    /// Handles a synchronization request from another node.
    private void handleSyncRequest(SyncRequest request) {
        if (active.get()) {
            stateMachine.makeSnapshot()
                        .map(snapshot -> new SyncResponse<>(self,
                                                            SavedState.create(snapshot,
                                                                              lastCommittedPhase.get(),
                                                                              pendingBatches.values())))
                        .onSuccess(response -> network.send(request.sender(), response))
                        .onFailure(cause -> log.error("Node {} failed to create snapshot: {}", self, cause));
        } else {
            log.trace("Node {} is inactive, trying to share saved (or empty) state for request: {}", self, request);

            var response = new SyncResponse<>(self, persistence.load()
                                                               .or(SavedState.empty()));
            network.send(request.sender(), response);
        }

    }

    /// Cleans up old phase data to prevent memory leaks.
    private void cleanupOldPhases() {
        if (nodeIsDormant()) {
            return;
        }

        var current = currentPhase.get();

        phases.keySet()
              .removeIf(phase -> isExpiredPhase(phase, current));
    }

    private boolean isExpiredPhase(Phase phase, Phase current) {
        return phase.compareTo(current) < 0
                && current.value() - phase.value() > config.removeOlderThanPhases();
    }

    /// Handles a Propose message from another node.
    private void handlePropose(Propose<C> propose) {
        if (nodeIsDormant()) {
            log.warn("Node {} ignores proposal {}. Node is dormant.", self, propose);
            return;
        }

        log.trace("Node {} received proposal from {} for phase {}", self, propose.sender(), propose.phase());

        var currentPhaseValue = currentPhase.get();
        // Ignore proposals for past phases
        if (propose.phase().compareTo(currentPhaseValue) < 0) {
            log.trace("Node {} ignoring proposal for past phase {}", self, propose.phase());
            return;
        }

        // Potentially add check for proposals too far in the future
        var phaseData = getOrCreatePhaseData(propose.phase());

        // Ensure the phase state is correct. If we receive a proposal for the current
        // phase value but aren't "in" it, enter it now.
        // This assumes the currentPhaseValue is correctly managed by sync/moveToNextPhase
        if (propose.phase().equals(currentPhaseValue) && !isInPhase.get() && active.get()) {
            log.trace("Node {} entering phase {} triggered by proposal from {}",
                      self,
                      propose.phase(),
                      propose.sender());
            isInPhase.set(true);
            // If this node has pending commands, it might need to propose too,
            // but let's handle that separately if needed.
        }

        // Store the proposal *after* potential phase transition
        // Use computeIfAbsent to avoid overwriting if multiple proposals are allowed per sender (though unlikely needed)
        phaseData.proposals.putIfAbsent(propose.sender(), propose.value());

        // If we are active, now in this phase, and haven't voted R1 yet... Vote!
        if (active.get() &&
                isInPhase.get() && // Should be true now if phase matches currentPhaseValue
                currentPhase.get().equals(propose.phase()) &&
                !phaseData.round1Votes.containsKey(self)) {

            // Call the (modified) evaluateInitialVote and broadcast R1 vote
            var vote = phaseData.evaluateInitialVote(self);
            log.trace("Node {} broadcasting R1 vote {} for phase {} based on received proposal from {}",
                      self,
                      vote,
                      propose.phase(),
                      propose.sender());
            network.broadcast(new VoteRound1(self, propose.phase(), vote));
            phaseData.round1Votes.put(self, vote); // Record our own vote
        } else {
            log.trace(
                    "Node {} conditions not met to vote R1 on proposal from {} for phase {}. Active: {}, InPhase: {}, CurrentPhase: {}, HasVotedR1: {}",
                    self,
                    propose.sender(),
                    propose.phase(),
                    active.get(),
                    isInPhase.get(),
                    currentPhase.get(),
                    phaseData.round1Votes.containsKey(self));
        }
    }

    /// Handles a round 1 vote from another node.
    private void handleVoteRound1(VoteRound1 vote) {
        if (nodeIsDormant()) {
            log.warn("Node {} ignores vote1 {}. Node is dormant.", self, vote);
            return;
        }

        log.trace("Node {} received round 1 vote from {} for phase {} with value {}",
                  self, vote.sender(), vote.phase(), vote.stateValue());

        var phaseData = getOrCreatePhaseData(vote.phase());
        phaseData.round1Votes.put(vote.sender(), vote.stateValue());

        // If we're active and in this phase, check if we can proceed to round 2
        if (active.get()
                && isInPhase.get()
                && currentPhase.get().equals(vote.phase())
                && !phaseData.round2Votes.containsKey(self)) {

            if (phaseData.hasRound1MajorityVotes(addressBook.quorumSize())) {
                var round2Vote = phaseData.evaluateRound2Vote(addressBook.quorumSize());
                network.broadcast(new VoteRound2(self, vote.phase(), round2Vote));
                phaseData.round2Votes.put(self, round2Vote);
            }
        }
    }

    /// Handles a round 2 vote from another node.
    private void handleVoteRound2(VoteRound2 vote) {
        if (nodeIsDormant()) {
            log.warn("Node {} ignores vote2 {}. Node is dormant.", self, vote);
            return;
        }

        log.trace("Node {} received round 2 vote from {} for phase {} with value {}",
                  self, vote.sender(), vote.phase(), vote.stateValue());

        var phaseData = getOrCreatePhaseData(vote.phase());
        phaseData.round2Votes.put(vote.sender(), vote.stateValue());

        // If we're active and in this phase, check if we can make a decision
        if (active.get()
                && isInPhase.get()
                && currentPhase.get().equals(vote.phase())
                && !phaseData.hasDecided.get()) {

            if (phaseData.hasRound2MajorityVotes(addressBook.quorumSize())) {
                phaseData.processRound2Completion(self, addressBook.quorumSize(), addressBook.fPlusOne())
                         .onPresent(decision -> processDecision(phaseData, decision));
            }
        }
    }

    private void processDecision(PhaseData<C> phaseData, Decision<C> decision) {
        // Broadcast the decision
        // Note: Send the batch only if the decision is V1?
        network.broadcast(decision);

        // Apply commands to state machine ONLY if it was a V1 decision with a non-empty batch
        if (decision.stateValue() == StateValue.V1 && !decision.value().commands().isEmpty()) {
            log.trace("Node {} applying batch {} commands for phase {}", self, decision.value().id(), phaseData.phase);
            commitChanges(phaseData, decision);
        } else {
            log.trace("Node {} decided {} for phase {}, no commands to apply.",
                      self,
                      decision.value(),
                      phaseData.phase);
        }

        // ALWAYS move to the next phase now that a decision value is determined
        moveToNextPhase(phaseData.phase);
    }

    @SuppressWarnings("unchecked")
    private void commitChanges(PhaseData<C> phaseData, Decision<C> decision) {
        var results = stateMachine.process(decision.value().commands());
        lastCommittedPhase.set(phaseData.phase);
        pendingBatches.remove(decision.value().correlationId());
        correlationMap.computeIfPresent(decision.value().correlationId(), (_, promise) -> {
            promise.succeed(results);
            return null;    // Remove mapping
        });
    }

    /// Handles a decision message from another node.
    private void handleDecision(Decision<C> decision) {
        if (nodeIsDormant()) {
            log.warn("Node {} ignores decision {}. Node is dormant.", self, decision);
            return;
        }

        log.trace("Node {} received decision {}", self, decision);

        var phaseData = getOrCreatePhaseData(decision.phase());

        if (phaseData.hasDecided.compareAndSet(false, true)) {
            // Apply commands to the state machine if the decision is positive
            if (decision.stateValue() == StateValue.V1 && decision.value().isNotEmpty()) {
                commitChanges(phaseData, decision);
            }

            // Move to the next phase
            moveToNextPhase(decision.phase());
        }
    }

    /// Moves to the next phase after a decision.
    private void moveToNextPhase(Phase currentPhase) {
        var nextPhase = currentPhase.successor();
        this.currentPhase.set(nextPhase);
        isInPhase.set(false);

        log.trace("Node {} moving to phase {}", self, nextPhase);

        // If we have more commands to process, start a new phase
        if (!pendingBatches.isEmpty()) {
            executor.execute(this::startPhase);
        }
    }

    /// Gets or creates phase data for a specific phase.
    private PhaseData<C> getOrCreatePhaseData(Phase phase) {
        return phases.computeIfAbsent(phase, PhaseData::new);
    }

    /// Data structure to hold all state related to a specific phase.
    private static class PhaseData<C extends Command> {
        final Phase phase;
        final Map<NodeId, Batch<C>> proposals = new ConcurrentHashMap<>();
        final Map<NodeId, StateValue> round1Votes = new ConcurrentHashMap<>();
        final Map<NodeId, StateValue> round2Votes = new ConcurrentHashMap<>();
        final AtomicBoolean hasDecided = new AtomicBoolean(false);

        PhaseData(Phase phase) {
            this.phase = phase;
        }

        /// Checks if we have a majority of votes in round 1.
        boolean hasRound1MajorityVotes(int quorumSize) {
            return round1Votes.size() >= quorumSize;
        }

        /// Checks if we have a majority of votes in round 2.
        boolean hasRound2MajorityVotes(int quorumSize) {
            return round2Votes.size() >= quorumSize;
        }

        /// Finds the agreed proposal when a V1 decision is made.
        public Batch<C> findAgreedProposal(NodeId self) {
            // If all proposals are the same, return that one
            if (!proposals.isEmpty()
                    && proposals.values().stream().distinct().count() == 1) {
                return proposals.values().iterator().next();
            }

            // Otherwise, just use our own proposal or an empty one
            return proposals.getOrDefault(self, Batch.empty());
        }

        public StateValue evaluateInitialVote(NodeId self) {
            // Vote V1 if *any* known proposal for this phase contains actual commands.
            // Check our own proposal first if available, otherwise check any received proposals.
            var ownProposal = proposals.get(self);

            if (ownProposal != null && !ownProposal.commands().isEmpty()) {
                return StateValue.V1;
            }

            // If our own proposal is empty or doesn't exist, check received proposals
            boolean anyNonEmptyProposal = proposals.values()
                                                   .stream()
                                                   .anyMatch(batch -> batch != null
                                                           && !batch.commands().isEmpty());

            // Vote V0 only if all known proposals (own included, if any) are empty.
            return anyNonEmptyProposal ? StateValue.V1 : StateValue.V0;
        }

        public StateValue evaluateRound2Vote(int quorumSize) {
            // If a majority voted for the same value in round 1, vote that value
            for (var value : List.of(StateValue.V0, StateValue.V1)) {
                if (countRound1VotesForValue(value) >= quorumSize) {
                    return value;
                }
            }

            // Otherwise, vote VQUESTION
            return StateValue.VQUESTION;
        }

        public int countRound1VotesForValue(StateValue value) {
            return (int) round1Votes.values()
                                    .stream()
                                    .filter(v -> v == value)
                                    .count();
        }

        public int countRound2VotesForValue(StateValue value) {
            return (int) round2Votes.values()
                                    .stream()
                                    .filter(v -> v == value)
                                    .count();
        }

        public Option<Decision<C>> processRound2Completion(NodeId self, int quorumSize, int fPlusOneSize) {
            // Check if already decided to prevent redundant processing
            if (!hasDecided.compareAndSet(false, true)) {
                log.trace("Phase {} already decided, skipping.", phase);
                return Option.empty(); // Already decided in a concurrent execution or previous invocation
            }

            StateValue decisionValue;
            Batch<C> batch = null; // Batch is only relevant for V1 decisions

            // 1. Check for a quorum majority for V1
            if (countRound2VotesForValue(StateValue.V1) >= quorumSize) {
                decisionValue = StateValue.V1;
                batch = findAgreedProposal(self); // Need the batch for V1 decision
                // 2. Check for the quorum majority for V0
            } else if (countRound2VotesForValue(StateValue.V0) >= quorumSize) {
                decisionValue = StateValue.V0;
                // 3. Check for f+1 votes for V1 (Paxos-like optimization/condition)
            } else if (countRound2VotesForValue(StateValue.V1) >= fPlusOneSize) {
                decisionValue = StateValue.V1;
                batch = findAgreedProposal(self); // Need the batch for V1 decision
                // 4. Check for f+1 votes for V0 (Paxos-like optimization/condition)
            } else if (countRound2VotesForValue(StateValue.V0) >= fPlusOneSize) {
                decisionValue = StateValue.V0;
                // 5. Fallback: If none of the above conditions are met, use the coin flip.
                //    This covers the ambiguous cases (mixed votes, including VQUESTION,
                //    that don't meet thresholds) and the "all VQUESTION" case implicitly.
            } else {
                decisionValue = coinFlip(self);
                // If coin flip is V1, we still need to try and find *an* agreed proposal.
                // The definition of findAgreedProposal might need review for this case,
                // but typically it would look for *any* proposal associated with V1 votes
                // or a default empty batch if none is found.
                if (decisionValue == StateValue.V1) {
                    batch = findAgreedProposal(self);
                }
            }

            // Decision has been made (either by majority, f+1, or coin flip)
            log.trace("Node {} decided phase {} with value {} and batch ID {}",
                      self, phase, decisionValue, (batch != null ? batch.id() : "N/A"));

            return Option.some(new Decision<>(self, phase, decisionValue,
                                              decisionValue == StateValue.V1 ? batch : Batch.empty()));
        }

        /// Gets a coin flip value for a phase, creating one if needed.
        private StateValue coinFlip(NodeId self) {
            long seed = phase.value() ^ self.id().hashCode();
            return (Math.abs(seed) % 2 == 0) ? StateValue.V0 : StateValue.V1;
        }
    }
}
