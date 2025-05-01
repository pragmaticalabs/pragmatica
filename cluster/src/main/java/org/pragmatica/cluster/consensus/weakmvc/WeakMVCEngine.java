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
    /// @param self         The node ID of this node
    /// @param addressBook  The address book for node communication
    /// @param network      The network implementation
    /// @param stateMachine The state machine to apply commands to
    /// @param config       Configuration for the consensus engine
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

    private boolean nodeIsDormant() {
        return !network.quorumConnected() || !active.get();
    }

    @Override
    public boolean submitCommands(List<C> commands) {
        if (commands.isEmpty() || nodeIsDormant()) {
            return false;
        }

        log.info("Node {} received commands batch with {} commands", self, commands.size());
        pendingCommands.add(commands);

        if (!isInPhase.get()) {
            executor.execute(this::startPhase);
        }
        return true;
    }

    @Override
    @SuppressWarnings("unchecked")
    public void processMessage(WeakMVCProtocolMessage message) {
        executor.execute(() -> {
            try {
                switch (message) {
                    case Propose<?> propose -> handlePropose((Propose<C>) propose);
                    case VoteRound1 voteRnd1 -> handleVoteRound1(voteRnd1);
                    case VoteRound2 voteRnd2 -> handleVoteRound2(voteRnd2);
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
        network.broadcast(new VoteRound1(self, phase, vote));
        phaseData.round1Votes.put(self, vote);
    }

    /**
     * Synchronizes with other nodes to catch up if needed.
     */
    private void synchronize() {
        if (active.get()) {
            return;
        }

        SyncRequest request = new SyncRequest(self, syncAttempts.incrementAndGet());
        log.info("Node {} requesting phase synchronization {}", self, request);
        network.broadcast(request);

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
        if (nodeIsDormant()) {
            log.info("Node {} ignores proposal {}. Node is dormant.", self, propose);
            return;
        }

        log.debug("Node {} received proposal from {} for phase {}", self, propose.sender(), propose.phase());

        Phase currentPhaseValue = currentPhase.get();
        // Ignore proposals for past phases
        if (propose.phase().compareTo(currentPhaseValue) < 0) {
            log.trace("Node {} ignoring proposal for past phase {}", self, propose.phase());
            return;
        }
        // Potentially add check for proposals too far in the future

        PhaseData<C> phaseData = getOrCreatePhaseData(propose.phase());

        // Ensure phase state is correct. If we receive a proposal for the current
        // phase value but aren't "in" it, enter it now.
        // This assumes currentPhaseValue is correctly managed by sync/moveToNextPhase
        if (propose.phase().equals(currentPhaseValue) && !isInPhase.get() && active.get()) {
            log.debug("Node {} entering phase {} triggered by proposal from {}",
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
            var vote = evaluateInitialVote(phaseData);
            log.debug("Node {} broadcasting R1 vote {} for phase {} based on received proposal from {}",
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

    /**
     * Handles a round 1 vote from another node.
     */
    private void handleVoteRound1(VoteRound1 vote) {
        if (nodeIsDormant()) {
            log.info("Node {} ignores vote1 {}. Node is dormant.", self, vote);
            return;
        }

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
                network.broadcast(new VoteRound2(self, vote.phase(), round2Vote));
                phaseData.round2Votes.put(self, round2Vote);
            }
        }
    }

    /**
     * Handles a round 2 vote from another node.
     */
    private void handleVoteRound2(VoteRound2 vote) {
        if (nodeIsDormant()) {
            log.info("Node {} ignores vote2 {}. Node is dormant.", self, vote);
            return;
        }

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
        if (nodeIsDormant()) {
            log.info("Node {} ignores decision {}. Node is dormant.", self, decision);
            return;
        }

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
            log.debug("Node {} ignoring self-initiated synchronization request", self);
            return;
        }

        log.debug("Node {} is {}active, request {}, max attempts {}, network {} quorum",
                  self,
                  active.get() ? "" : "not ",
                  request,
                  config.maxSyncAttempts(),
                  network.quorumConnected() ? "connected" : "not connected");

        // Only respond if:
        // - node is active
        // - there are enough nodes and we have reached the maximum number of sync attempts
        if (active.get() || (request.attempt() >= config.maxSyncAttempts() && network.quorumConnected())) {
            stateMachine.makeSnapshot()
                        .onSuccess(snapshot -> {
                            SyncResponse response = new SyncResponse(self, currentPhase.get(), isInPhase.get(), snapshot);
                            log.debug("Node {} responds with {}", self, response);
                            network.send(request.sender(), response);
                        })
                        .onFailure(cause -> log.error("Node {} failed to create snapshot: {}", self, cause));
        }
    }

    /**
     * Handles a synchronization response from another node.
     */
    private void handleSyncResponse(SyncResponse response) {
        if (!active.get() && !response.sender().equals(self)) {
            log.debug("Node {} received synchronization response {}", self, response);
            // Update our current phase if needed
            var responsePhase = response.phase();
            var currentPhase = this.currentPhase.get();

            if (responsePhase.compareTo(currentPhase) > 0) {
                log.debug("Node {} updates current phase to {}", self, responsePhase);
                this.currentPhase.set(responsePhase);
            }

            stateMachine.restoreSnapshot(response.snapshot())
                        .onSuccessRun(() -> {
                            // Activate the node if we've received a response
                            active.set(true);
                            log.info("Node {} activated in phase {}", self, this.currentPhase.get());

//                            if (response.inPhase()) {
//                                moveToNextPhase(this.currentPhase.get());
//                            } else {
                                executor.execute(this::startPhase);
//                            }
                        })
                        .onFailureRun(() -> log.error("Node {} failed to restore snapshot", self));

        }
    }

    /**
     * Processes the completion of round 2 voting.
     */
    private void processRound2Completion(PhaseData<C> phaseData) {
        // Check if already decided to prevent redundant processing
        if (!phaseData.hasDecided.compareAndSet(false, true)) {
            log.debug("Phase {} already decided, skipping.", phaseData.phase);
            return; // Already decided in a concurrent execution or previous invocation
        }

        StateValue decisionValue;
        Batch<C> batch = null; // Batch is only relevant for V1 decisions

        // 1. Check for quorum majority for V1
        if (countVotesForValue(phaseData.round2Votes, StateValue.V1) >= quorumSize()) {
            decisionValue = StateValue.V1;
            batch = findAgreedProposal(phaseData); // Need the batch for V1 decision
            // 2. Check for quorum majority for V0
        } else if (countVotesForValue(phaseData.round2Votes, StateValue.V0) >= quorumSize()) {
            decisionValue = StateValue.V0;
            // 3. Check for f+1 votes for V1 (Paxos-like optimization/condition)
        } else if (countVotesForValue(phaseData.round2Votes, StateValue.V1) >= fPlusOneSize()) {
            decisionValue = StateValue.V1;
            batch = findAgreedProposal(phaseData); // Need the batch for V1 decision
            // 4. Check for f+1 votes for V0 (Paxos-like optimization/condition)
        } else if (countVotesForValue(phaseData.round2Votes, StateValue.V0) >= fPlusOneSize()) {
            decisionValue = StateValue.V0;
            // 5. Fallback: If none of the above conditions are met, use the coin flip.
            //    This covers the ambiguous cases (mixed votes, including VQUESTION,
            //    that don't meet thresholds) and the "all VQUESTION" case implicitly.
        } else {
            decisionValue = getCoinFlip(phaseData.phase);
            // If coin flip is V1, we still need to try and find *an* agreed proposal.
            // The definition of findAgreedProposal might need review for this case,
            // but typically it would look for *any* proposal associated with V1 votes
            // or a default empty batch if none found.
            if (decisionValue == StateValue.V1) {
                batch = findAgreedProposal(phaseData);
                // If findAgreedProposal can return null when no clear proposal exists
                // even with a V1 decision (e.g., via coin flip), handle it.
                // Often, a V1 decision without an agreed proposal defaults to an empty batch.
                if (batch == null) {
                    log.warn("Phase {}: V1 decision by coin flip, but no agreed proposal found. Using empty batch.",
                             phaseData.phase);
                    batch = Batch.empty(); // Assuming Batch has an empty static factory
                }
            }
        }

        // Decision has been made (either by majority, f+1, or coin flip)
        log.info("Node {} decided phase {} with value {} and batch ID {}",
                 self, phaseData.phase, decisionValue, (batch != null ? batch.id() : "N/A"));

        // Broadcast the decision
        // Note: Send the batch only if the decision is V1
        network.broadcast(new Decision<>(self, phaseData.phase, decisionValue,
                                         decisionValue == StateValue.V1 ? batch : null));

        // Apply commands to state machine ONLY if it was a V1 decision with a non-empty batch
        if (decisionValue == StateValue.V1 && batch != null && !batch.commands().isEmpty()) {
            log.debug("Node {} applying batch {} commands for phase {}", self, batch.id(), phaseData.phase);
            batch.commands().forEach(stateMachine::process);
        } else {
            log.debug("Node {} decided {} for phase {}, no commands to apply.", self, decisionValue, phaseData.phase);
        }

        // ALWAYS move to the next phase now that a decision value is determined
        moveToNextPhase(phaseData.phase);
    }

    /**
     * Moves to the next phase after a decision.
     */
    private void moveToNextPhase(Phase currentPhase) {
        var nextPhase = currentPhase.successor();
        this.currentPhase.set(nextPhase);
        isInPhase.set(false);

        log.info("Node {} moving to phase {}", self, nextPhase);

        // If we have more commands to process, start a new phase
        if (!pendingCommands.isEmpty()) {
            executor.execute(this::startPhase);
        }
    }

    private StateValue evaluateInitialVote(PhaseData<C> phaseData) {
        // Vote V1 if *any* known proposal for this phase contains actual commands.
        // Check own proposal first if available, otherwise check any received proposals.
        Batch<C> ownProposal = phaseData.proposals.get(self);
        if (ownProposal != null && !ownProposal.commands().isEmpty()) {
            return StateValue.V1;
        }

        // If own proposal is empty or doesn't exist, check received proposals
        boolean anyNonEmptyProposal = phaseData.proposals.values().stream()
                                                         .anyMatch(batch -> batch != null && !batch.commands()
                                                                                                   .isEmpty());

        if (anyNonEmptyProposal) {
            return StateValue.V1;
        } else {
            // Vote V0 only if all known proposals (own included, if any) are empty.
            return StateValue.V0;
        }
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