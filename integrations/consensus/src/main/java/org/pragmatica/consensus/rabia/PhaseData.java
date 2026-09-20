/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.pragmatica.consensus.rabia;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import org.pragmatica.consensus.Command;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.Decision;
import org.pragmatica.consensus.rabia.RabiaProtocolMessage.Synchronous.VoteRound1;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import static org.pragmatica.consensus.StateMachine.Batch.emptyBatch;


/// Represents the outcome of Round 2 completion per Rabia specification.
/// Completion outcomes:
/// 1. Decided - f+1 threshold met, commit the value
/// 2. CarryForward - continue the same log slot in another binary round
/// 3. AwaitingProposal - V1 is established but the majority-supported batch is not yet available
sealed interface Round2Outcome<C extends Command> {
    StateValue lockedValue();

    record Decided<C extends Command>(Decision<C> decision) implements Round2Outcome<C> {
        public StateValue lockedValue() {
            return decision.stateValue();
        }
    }

    record AwaitingProposal<C extends Command>() implements Round2Outcome<C> {
        public StateValue lockedValue() {
            return StateValue.V1;
        }
    }

    record CarryForward<C extends Command>(StateValue value) implements Round2Outcome<C> {
        public StateValue lockedValue() {
            return value;
        }
    }
}

/// One log slot: immutable proposals and independently indexed binary-round ballots.
///
/// This class tracks proposals, round 1 votes, round 2 votes, and decision state
/// for one weak-MVC instance. A binary carry-forward does not complete this instance.
///
/// @param <C> Command type
final class PhaseData<C extends Command> {
    private final Phase phase;
    private final long epoch;
    private final Map<NodeId, ClusterConfig> configurations = new ConcurrentHashMap<>();

    private record ProposalIdentity(Batch.Id batch, Option<ClusterConfig> configuration) {}

    private record ProposalValue<C extends Command>(Batch<C> batch, Option<ClusterConfig> configuration) {
        ProposalIdentity identity() {
            return new ProposalIdentity(batch.id(), configuration);
        }

        boolean isNotEmpty() {
            return batch.isNotEmpty() || configuration.isPresent();
        }
    }

    private final Map<NodeId, Batch<C>> proposals = new ConcurrentHashMap<>();

    private record Ballots(Map<NodeId, StateValue> first, Map<NodeId, StateValue> second) {
        static Ballots ballots() {
            return new Ballots(new ConcurrentHashMap<>(), new ConcurrentHashMap<>());
        }
    }

    private final Map<Long, Ballots> ballots = new ConcurrentHashMap<>();
    private long round;

    org.pragmatica.lang.Unit restoreOwnRound(long restoredRound) {
        round = Math.max(round, restoredRound);

        return org.pragmatica.lang.Unit.unit();
    }

    long round() {
        return round;
    }

    private Ballots ballots(long index) {
        return ballots.computeIfAbsent(index, _ -> Ballots.ballots());
    }

    org.pragmatica.lang.Unit advanceRound(NodeId self, StateValue value) {
        round++;
        registerRound1Vote(self, value);

        return org.pragmatica.lang.Unit.unit();
    }

    private final AtomicBoolean decided = new AtomicBoolean(false);
    private Option<Decision<C>> completedDecision = Option.none();

    Option<Decision<C>> completedDecision() {
        return completedDecision;
    }

    org.pragmatica.lang.Unit completedDecision(Decision<C> decision) {
        completedDecision = Option.some(decision);

        return org.pragmatica.lang.Unit.unit();
    }

    PhaseData(Phase phase) {
        this(phase, 0);
    }

    PhaseData(Phase phase, long epoch) {
        this.phase = phase;
        this.epoch = epoch;
    }

    long epoch() {
        return epoch;
    }

    Phase phase() {
        return phase;
    }

    // ==================== Intent-Revealing API ====================
    /// Registers a proposal from a node. Idempotent - first proposal wins.
    @Contract
    org.pragmatica.lang.Unit registerProposal(NodeId node, Batch<C> batch) {
        registerProposal(node, batch, Option.none());

        return org.pragmatica.lang.Unit.unit();
    }

    org.pragmatica.lang.Unit registerProposal(NodeId node, Batch<C> batch, Option<ClusterConfig> configuration) {
        if (!proposals.containsKey(node)) {
            configuration.onPresent(value -> configurations.put(node, value));
            proposals.put(node, batch);
        }

        return org.pragmatica.lang.Unit.unit();
    }

    Option<ClusterConfig> configuration(NodeId node) {
        return Option.option(configurations.get(node));
    }

    private List<ProposalValue<C>> proposalValues() {
        return proposals.entrySet()
                        .stream()
                        .map(entry -> new ProposalValue<>(entry.getValue(),
                                                          configuration(entry.getKey())))
                        .filter(ProposalValue::isNotEmpty)
                        .toList();
    }

    /// Checks if a node has already proposed in this phase.
    boolean hasProposal(NodeId node) {
        return proposals.containsKey(node);
    }

    /// Returns the proposal batch for a specific node, or null if not present.
    Batch<C> getProposal(NodeId node) {
        return proposals.get(node);
    }

    /// Returns a snapshot of all proposals collected in this phase, keyed by the originating
    /// node. Used by the stall detector (#258) to re-broadcast the full proposal SET — not just
    /// this node's own proposal — so a phase whose original proposal contributors have died can
    /// still reach `hasQuorumProposals` on surviving/fresh voters that hold the dead nodes'
    /// proposals. The returned map is an immutable copy; iteration is safe under concurrent
    /// registration.
    Map<NodeId, Batch<C>> proposals() {
        return Map.copyOf(proposals);
    }

    /// Checks if a node has already voted in round 1.
    boolean hasVotedRound1(NodeId node) {
        return ballots(round).first()
                      .containsKey(node);
    }

    /// Registers a round 1 vote from a node.
    @Contract
    org.pragmatica.lang.Unit registerRound1Vote(NodeId node, StateValue value) {
        registerRound1Vote(node, round, value);

        return org.pragmatica.lang.Unit.unit();
    }

    org.pragmatica.lang.Unit registerRound1Vote(NodeId node, long ballotRound, StateValue value) {
        ballots(ballotRound).first().putIfAbsent(node, value);

        return org.pragmatica.lang.Unit.unit();
    }

    org.pragmatica.lang.Unit registerRound2Vote(NodeId node, long ballotRound, StateValue value) {
        ballots(ballotRound).second().putIfAbsent(node, value);

        return org.pragmatica.lang.Unit.unit();
    }

    Option<StateValue> round1Vote(NodeId node, long ballotRound) {
        return Option.option(ballots(ballotRound).first().get(node));
    }

    Option<StateValue> round2Vote(NodeId node, long ballotRound) {
        return Option.option(ballots(ballotRound).second().get(node));
    }

    /// Returns this node's round 1 vote value, or null if not cast.
    StateValue getRound1Vote(NodeId node) {
        return ballots(round).first()
                      .get(node);
    }

    /// Checks if a node has already voted in round 2.
    boolean hasVotedRound2(NodeId node) {
        return ballots(round).second()
                      .containsKey(node);
    }

    /// Returns this node's round 2 vote value, or null if not cast.
    StateValue getRound2Vote(NodeId node) {
        return ballots(round).second()
                      .get(node);
    }

    /// Registers a round 2 vote from a node.
    @Contract
    org.pragmatica.lang.Unit registerRound2Vote(NodeId node, StateValue value) {
        registerRound2Vote(node, round, value);

        return org.pragmatica.lang.Unit.unit();
    }

    /// Checks if a decision has been made for this phase.
    boolean isDecided() {
        return decided.get();
    }

    /// Attempts to mark this phase as decided. Returns true if successful
    /// (was not already decided), false if already decided.
    boolean tryMarkDecided() {
        return decided.compareAndSet(false, true);
    }

    /// Returns the number of proposals collected.
    int proposalCount() {
        return proposals.size();
    }

    /// Checks if we have collected proposals from a majority of nodes.
    boolean hasQuorumProposals(int quorumSize) {
        return proposals.size() >= quorumSize;
    }

    // ==================== Voting Logic ====================
    /// Checks if we have collected votes from a majority of nodes in round 1.
    boolean hasRound1MajorityVotes(int quorumSize) {
        return ballots(round).first()
                      .size() >= quorumSize;
    }

    /// Checks if we have collected votes from a majority of nodes in round 2.
    boolean hasRound2MajorityVotes(int quorumSize) {
        return ballots(round).second()
                      .size() >= quorumSize;
    }

    /// Only an actual proposal majority can supply a V1 command batch.
    private Option<ProposalValue<C>> agreedValue(int quorumSize) {
        return Option.from(proposalValues().stream()
                                         .collect(Collectors.groupingBy(ProposalValue::identity))
                                         .values()
                                         .stream()
                                         .filter(values -> values.size() >= quorumSize)
                                         .map(List::getFirst)
                                         .findFirst());
    }

    Option<ClusterConfig> agreedConfiguration(int quorumSize) {
        return agreedValue(quorumSize).flatMap(ProposalValue::configuration);
    }

    Option<Batch<C>> agreedProposal(int quorumSize) {
        return agreedValue(quorumSize).filter(value -> value.configuration()
                                                            .isEmpty())
                          .map(ProposalValue::batch);
    }

    Batch<C> findAgreedProposal(int quorumSize) {
        return agreedProposal(quorumSize).or(Batch::emptyBatch);
    }

    /// Evaluates the initial round 1 vote based on collected proposals.
    /// Per Rabia spec: vote V1 if a majority of nodes proposed the same batch, else V0.
    ///
    /// This should only be called after hasQuorumProposals() returns true.
    VoteRound1 evaluateInitialVote(NodeId self, int quorumSize) {
        boolean hasQuorumAgreement = agreedValue(quorumSize).isPresent();
        var stateValue = hasQuorumAgreement
                         ? StateValue.V1
                         : StateValue.V0;

        return new VoteRound1(self, epoch, phase, round, stateValue);
    }

    /// Evaluates the round 2 vote based on round 1 voting results.
    /// Per Rabia spec: if majority voted same value, vote that; else vote VQUESTION.
    StateValue evaluateRound2Vote(int quorumSize) {
        for (var value : List.of(StateValue.V0, StateValue.V1)) {
            if (countRound1VotesForValue(value) >= quorumSize) {
                return value;
            }
        }

        return StateValue.VQUESTION;
    }

    /// Counts round 1 votes for a specific state value.
    int countRound1VotesForValue(StateValue value) {
        return (int) ballots(round).first()
                            .values()
                            .stream()
                            .filter(v -> v == value)
                            .count();
    }

    /// Counts round 2 votes for a specific state value.
    int countRound2VotesForValue(StateValue value) {
        return (int) ballots(round).second()
                            .values()
                            .stream()
                            .filter(v -> v == value)
                            .count();
    }

    /// Processes round 2 completion and determines the outcome.
    /// Per Rabia spec (weak_mvc.ivy lines 163-171):
    /// 1. If f+1 nodes voted V1 or V0, decide that value
    /// 2. If any non-question vote seen (but < f+1), carry that value forward WITHOUT decision
    /// 3. If all votes are VQUESTION, carry the common coin into the next binary round
    Round2Outcome<C> processRound2Completion(NodeId self, int fPlusOneSize, int quorumSize) {
        // Case 1: f+1 threshold met - DECIDE
        if (countRound2VotesForValue(StateValue.V1) >= fPlusOneSize) {
            return agreedValue(quorumSize).<Round2Outcome<C>> map(value -> new Round2Outcome.Decided<>(new Decision<>(self,
                                                                                                                      epoch,
                                                                                                                      phase,
                                                                                                                      StateValue.V1,
                                                                                                                      value.batch(),
                                                                                                                      value.configuration())))
                              .or(Round2Outcome.AwaitingProposal::new);
        }

        if (countRound2VotesForValue(StateValue.V0) >= fPlusOneSize) {
            return new Round2Outcome.Decided<>(new Decision<>(self, epoch, phase, StateValue.V0, emptyBatch()));
        }
        // Case 2: Any non-question vote seen (but < f+1) - carry forward WITHOUT decision
        for (var value : List.of(StateValue.V1, StateValue.V0)) {
            if (countRound2VotesForValue(value) > 0) {
                return new Round2Outcome.CarryForward<>(value);
            }
        }
        // The coin chooses the next binary-round state. It is never a decision.
        return new Round2Outcome.CarryForward<>(coinFlip());
    }

    /// Gets a deterministic coin flip value for a phase.
    /// Must be deterministic across all nodes for consensus correctness.
    /// Uses bit-based check to avoid Math.abs(Long.MIN_VALUE) returning negative.
    StateValue coinFlip() {
        long seed = phase.value() * 0x9E3779B97F4A7C15L + round;

        return (seed & 1) == 0
               ? StateValue.V0
               : StateValue.V1;
    }
}
