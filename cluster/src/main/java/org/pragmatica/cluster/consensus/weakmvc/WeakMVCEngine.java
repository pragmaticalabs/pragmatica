package org.pragmatica.cluster.consensus.weakmvc;

import org.pragmatica.cluster.consensus.Consensus;
import org.pragmatica.cluster.consensus.weakmvc.WeakMVCProtocolMessage.*;
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

import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/**
 * Implementation of the Weak MVC consensus protocol.
 */
public class WeakMVCEngine<T extends WeakMVCProtocolMessage, C extends Command> implements Consensus<T, C> {
    private static final Logger log = LoggerFactory.getLogger(WeakMVCEngine.class);

    private final NodeId self;
    private final AddressBook addressBook;
    private final ClusterNetwork<T> network;
    private final StateMachine<C> stateMachine;
    private final WeakMVCConfig config;
    private final ExecutorService executor;

    private final Queue<List<C>> pendingCommands = new ConcurrentLinkedQueue<>();
    private final Map<Phase, PhaseData<C>> phases = new ConcurrentHashMap<>();
    private final Map<Phase, StateValue> coinFlips = new ConcurrentHashMap<>();

    private final AtomicReference<Phase> currentPhase = new AtomicReference<>(Phase.ZERO);
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final AtomicBoolean isInPhase = new AtomicBoolean(false);

    private final AtomicLong syncAttempts = new AtomicLong(0);

    /// Creates a new Weak MVC consensus engine.
    ///
    /// @param self The node ID of this node
    /// @param addressBook The address book for node communication
    /// @param network The network implementation
    /// @param stateMachine The state machine to apply commands to
    /// @param config Configuration for the consensus engine
    public WeakMVCEngine(NodeId self,
                         AddressBook addressBook,
                         ClusterNetwork<T> network,
                         StateMachine<C> stateMachine,
                         WeakMVCConfig config) {
        this.self = self;
        this.addressBook = addressBook;
        this.network = network;
        this.stateMachine = stateMachine;
        this.config = config;
        this.executor = Executors.newSingleThreadExecutor();

        // Setup periodic tasks
        SharedScheduler.scheduleAtFixedRate(this::cleanupOldPhases, config.cleanupInterval());
        SharedScheduler.schedule(this::synchronize, config.syncRetryInterval());
    }

    @Override
    public void submitCommands(List<C> commands) {
        if (commands.isEmpty()) {
            return;
        }

        log.info("Node {} received commands batch with {} commands", self, commands.size());
        pendingCommands.add(commands);

        if (active.get() && !isInPhase.get()) {
            executor.execute(this::startPhase);
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public void processMessage(WeakMVCProtocolMessage message) {
        executor.execute(() -> {
            try {
                switch (message) {
                    case Propose<?> propose -> handlePropose((Propose<C>) propose);
                    case VoteRound1<?> voteRnd1 -> handleVoteRound1((VoteRound1<C>) voteRnd1);
                    case VoteRound2<?> voteRnd2 -> handleVoteRound2((VoteRound2<C>) voteRnd2);
                    case Decision<?> decision -> handleDecision((Decision<C>) decision);
                    case SyncRequest syncRequest -> handleSyncRequest(syncRequest);
                    case SyncResponse syncResponse -> handleSyncResponse(syncResponse);
                    default -> log.warn("Unknown message type: {}", message.getClass().getSimpleName());
                }
            } catch (Exception e) {
                log.error("Error processing message: {}", message, e);
            }
        });
    }

    /**
     * Starts a new phase with pending commands.
     */
    private void startPhase() {
        if (isInPhase.get() || pendingCommands.isEmpty()) {
            return;
        }

        var phase = currentPhase.get();
        var batch = Batch.create(pendingCommands.poll());

        log.info("Node {} starting phase {} with batch {}", self, phase, batch.id());

        // Initialize phase data
        var phaseData = getOrCreatePhaseData(phase);
        phaseData.proposals.put(self, batch);

        // Mark that we're in a phase now
        isInPhase.set(true);
        
        // Send initial batch
        network.broadcast(new Propose<>(self, phase, batch));
        
        // Vote in round 1
        var vote = evaluateInitialVote(phaseData);
        network.broadcast(new VoteRound1<>(self, phase, vote));
        phaseData.round1Votes.put(self, vote);
    }

    /**
     * Synchronizes with other nodes to catch up if needed.
     */
    private void synchronize() {
        if (active.get()) {
            return;
        }

        log.info("Node {} requesting phase synchronization", self);
        network.broadcast(new SyncRequest(self));
        syncAttempts.incrementAndGet();
        // Schedule next synchronization attempt
        SharedScheduler.schedule(this::synchronize, config.syncRetryInterval());
    }

    /**
     * Cleans up old phase data to prevent memory leaks.
     */
    private void cleanupOldPhases() {
        var current = currentPhase.get();

        phases.keySet()
              .removeIf(phase -> isExpiredPhase(phase, current));
    }

    private boolean isExpiredPhase(Phase phase, Phase current) {
        return phase.compareTo(current) < 0
                && current.value() - phase.value() > config.removeOlderThanPhases();
    }

    /**
     * Handles a Propose message from another node.
     */
    private void handlePropose(Propose<C> propose) {
        log.debug("Node {} received proposal from {} for phase {}", self, propose.sender(), propose.phase());

        // Store the proposal
        PhaseData<C> phaseData = getOrCreatePhaseData(propose.phase());
        phaseData.proposals.put(propose.sender(), propose.value());

        // If we haven't voted in round 1 yet, and we're in this phase, vote
        if (active.get() && 
            isInPhase.get() && 
            currentPhase.get().equals(propose.phase()) && 
            !phaseData.round1Votes.containsKey(self)) {

            var vote = evaluateInitialVote(phaseData);
            network.broadcast(new VoteRound1<>(self, propose.phase(), vote));
            phaseData.round1Votes.put(self, vote);
        }
    }

    /**
     * Handles a round 1 vote from another node.
     */
    private void handleVoteRound1(VoteRound1<C> vote) {
        log.debug("Node {} received round 1 vote from {} for phase {} with value {}", 
                  self, vote.sender(), vote.phase(), vote.stateValue());

        var phaseData = getOrCreatePhaseData(vote.phase());
        phaseData.round1Votes.put(vote.sender(), vote.stateValue());

        // If we're active and in this phase, check if we can proceed to round 2
        if (active.get() && 
            isInPhase.get() && 
            currentPhase.get().equals(vote.phase()) && 
            !phaseData.round2Votes.containsKey(self)) {
            
            if (hasRound1MajorityVotes(phaseData)) {
                var round2Vote = evaluateRound2Vote(phaseData);
                network.broadcast(new VoteRound2<>(self, vote.phase(), round2Vote));
                phaseData.round2Votes.put(self, round2Vote);
            }
        }
    }

    /**
     * Handles a round 2 vote from another node.
     */
    private void handleVoteRound2(VoteRound2<C> vote) {
        log.debug("Node {} received round 2 vote from {} for phase {} with value {}", 
                  self, vote.sender(), vote.phase(), vote.stateValue());

        var phaseData = getOrCreatePhaseData(vote.phase());
        phaseData.round2Votes.put(vote.sender(), vote.stateValue());

        // If we're active and in this phase, check if we can make a decision
        if (active.get() && 
            isInPhase.get() && 
            currentPhase.get().equals(vote.phase()) && 
            !phaseData.hasDecided.get()) {
            
            if (hasRound2MajorityVotes(phaseData)) {
                processRound2Completion(phaseData);
            }
        }
    }

    /**
     * Handles a decision message from another node.
     */
    private void handleDecision(Decision<C> decision) {
        log.info("Node {} received decision from {} for phase {} with value {} and proposal {}", 
                 self, decision.sender(), decision.phase(), decision.stateValue(), 
                 decision.value() != null ? decision.value().id() : "null");

        var phaseData = getOrCreatePhaseData(decision.phase());
        
        if (phaseData.hasDecided.compareAndSet(false, true)) {
            // Apply commands to the state machine if the decision is positive
            if (decision.stateValue() == StateValue.V1 && decision.value() != null) {
                decision.value().commands().forEach(stateMachine::process);
            }
            
            // Move to the next phase
            moveToNextPhase(decision.phase());
        }
    }

    /**
     * Handles a synchronization request from another node.
     */
    private void handleSyncRequest(SyncRequest request) {
        if (request.sender().equals(self)) {
            return;
        }

        // Only respond if:
        // - node is active
        // - there are enough nodes and we have reached the maximum number of sync attempts
        if (active.get() || (syncAttempts.get() >= config.maxSyncAttempts() && network.quorumConnected())) {
            network.send(request.sender(), new SyncResponse(self, currentPhase.get()));
        }
    }

    /**
     * Handles a synchronization response from another node.
     */
    private void handleSyncResponse(SyncResponse response) {
        if (!active.get() && !response.sender().equals(self)) {
            // Update our current phase if needed
            var responsePhase = response.phase();
            var currentPhase = this.currentPhase.get();
            
            if (responsePhase.compareTo(currentPhase) > 0) {
                this.currentPhase.set(responsePhase);
            }
            
            // Activate the node if we've received a response
            active.set(true);
            
            // Start a new phase if we have pending commands
            if (!pendingCommands.isEmpty() && !isInPhase.get()) {
                executor.execute(this::startPhase);
            }
        }
    }

    /**
     * Processes the completion of round 2 voting.
     */
    private void processRound2Completion(PhaseData<C> phaseData) {
        StateValue decisionValue = null;
        Batch<C> batch = null;
        
        // Check for a majority of non-question votes
        for (var value : List.of(StateValue.V0, StateValue.V1)) {
            if (countVotesForValue(phaseData.round2Votes, value) >= quorumSize()) {
                decisionValue = value;
                break;
            }
        }
        
        // If no clear majority, check for f+1 votes
        if (decisionValue == null) {
            for (var value : List.of(StateValue.V0, StateValue.V1)) {
                if (countVotesForValue(phaseData.round2Votes, value) >= fPlusOneSize()) {
                    decisionValue = value;
                    break;
                }
            }
        }
        
        // If still no decision, but all votes are VQUESTION, use/create a coin flip
        if (decisionValue == null && allVotesAreQuestion(phaseData.round2Votes)) {
            decisionValue = getCoinFlip(phaseData.phase);
        }
        
        // If we have a decision, proceed
        if (decisionValue != null) {
            if (decisionValue == StateValue.V1) {
                // For positive decisions (V1), find the agreed proposal
                batch = findAgreedProposal(phaseData);
            }
            
            // Broadcast decision
            phaseData.hasDecided.set(true);
            network.broadcast(new Decision<>(self, phaseData.phase, decisionValue, batch));
            
            // Apply commands to state machine if positive decision
            if (decisionValue == StateValue.V1 && batch != null) {
                batch.commands().forEach(stateMachine::process);
            }
            
            // Move to the next phase
            moveToNextPhase(phaseData.phase);
        }
    }

    /**
     * Moves to the next phase after a decision.
     */
    private void moveToNextPhase(Phase currentPhase) {
        var nextPhase = currentPhase.successor();
        this.currentPhase.set(nextPhase);
        isInPhase.set(false);
        
        // If we have more commands to process, start a new phase
        if (!pendingCommands.isEmpty()) {
            executor.execute(this::startPhase);
        }
    }

    /**
     * Evaluates the initial vote for a phase based on the proposals received.
     */
    private StateValue evaluateInitialVote(PhaseData<C> phaseData) {
        // If proposals from a majority of nodes are the same, vote V1
        if (phaseData.proposals.size() >= quorumSize() && 
            phaseData.proposals.values().stream().distinct().count() == 1) {
            return StateValue.V1;
        }
        
        // Otherwise, vote V0
        return StateValue.V0;
    }

    /**
     * Evaluates the round 2 vote based on round 1 vote count.
     */
    private StateValue evaluateRound2Vote(PhaseData<C> phaseData) {
        // If a majority voted for the same value in round 1, vote that value
        for (StateValue value : List.of(StateValue.V0, StateValue.V1)) {
            if (countVotesForValue(phaseData.round1Votes, value) >= quorumSize()) {
                return value;
            }
        }
        
        // Otherwise, vote VQUESTION
        return StateValue.VQUESTION;
    }

    /// Gets a coin flip value for a phase, creating one if needed.
    private StateValue getCoinFlip(Phase phase) {
        return coinFlips.computeIfAbsent(phase, this::calculateCoinFlip);
    }

    /// Simple deterministic coin flip based on phase and node id
    private StateValue calculateCoinFlip(Phase phase1) {
        long seed = phase1.value() ^ self.id().hashCode();
        return (Math.abs(seed) % 2 == 0) ? StateValue.V0 : StateValue.V1;
    }

    /// Finds the agreed proposal when a V1 decision is made.
    private Batch<C> findAgreedProposal(PhaseData<C> phaseData) {
        // If all proposals are the same, return that one
        if (!phaseData.proposals.isEmpty() &&
            phaseData.proposals.values().stream().distinct().count() == 1) {
            return phaseData.proposals.values().iterator().next();
        }
        
        // Otherwise, just use our own proposal or an empty one
        return phaseData.proposals.getOrDefault(self, Batch.empty());
    }

    /// Checks if we have a majority of votes in round 1.
    private boolean hasRound1MajorityVotes(PhaseData<C> phaseData) {
        return phaseData.round1Votes.size() >= quorumSize();
    }

    /**
     * Checks if we have a majority of votes in round 2.
     */
    private boolean hasRound2MajorityVotes(PhaseData<C> phaseData) {
        return phaseData.round2Votes.size() >= quorumSize();
    }

    /**
     * Counts votes for a specific value.
     */
    private int countVotesForValue(Map<NodeId, StateValue> votes, StateValue value) {
        return (int) votes.values().stream().filter(v -> v == value).count();
    }

    /**
     * Checks if all votes are VQUESTION.
     */
    private boolean allVotesAreQuestion(Map<NodeId, StateValue> votes) {
        return votes.values().stream().allMatch(StateValue::isQuestion);
    }

    /**
     * Gets the quorum size (majority) for the cluster.
     */
    private int quorumSize() {
        return addressBook.quorumSize();
    }

    /**
     * Gets the f+1 size for the cluster (where f is the maximum number of failures).
     */
    private int fPlusOneSize() {
        return (addressBook.clusterSize() - quorumSize()) + 1;
    }

    /**
     * Gets or creates phase data for a specific phase.
     */
    private PhaseData<C> getOrCreatePhaseData(Phase phase) {
        return phases.computeIfAbsent(phase, PhaseData::new);
    }

    /**
     * Data structure to hold all state related to a specific phase.
     */
    private static class PhaseData<C extends Command> {
        final Phase phase;
        final Map<NodeId, Batch<C>> proposals = new ConcurrentHashMap<>();
        final Map<NodeId, StateValue> round1Votes = new ConcurrentHashMap<>();
        final Map<NodeId, StateValue> round2Votes = new ConcurrentHashMap<>();
        final AtomicBoolean hasDecided = new AtomicBoolean(false);

        PhaseData(Phase phase) {
            this.phase = phase;
        }
    }
}