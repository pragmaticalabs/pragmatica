package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.StateMachine.Batch;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestStateMachine;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

class PhaseDataRoundTest {
    @Test
    void carryForwardRetainsProposalAndIsolatesBallotsByRound() {
        var self = nodeId("self").unwrap();
        var peer = nodeId("peer").unwrap();
        var slot = new PhaseData<TestCommand>(Phase.phase(7));
        var batch = Batch.create(new TestStateMachine().serializer(), List.of(new TestCommand("original")));
        slot.registerProposal(self, batch);
        slot.registerProposal(peer, batch);
        slot.registerRound1Vote(peer, 0, StateValue.V0);
        slot.registerRound2Vote(peer, 0, StateValue.VQUESTION);
        slot.registerRound1Vote(peer, 1, StateValue.V1);
        slot.advanceRound(self, StateValue.V1);
        assertThat(slot.phase()).isEqualTo(Phase.phase(7));
        assertThat(slot.round()).isEqualTo(1);
        assertThat(slot.isDecided()).isFalse();
        assertThat(slot.agreedProposal(2).isPresent()).isTrue();
        assertThat(slot.countRound1VotesForValue(StateValue.V1)).isEqualTo(2);
        assertThat(slot.countRound1VotesForValue(StateValue.V0)).isZero();
        assertThat(slot.hasRound2MajorityVotes(1)).isFalse();
        slot.registerRound2Vote(peer, 0, StateValue.V1);
        assertThat(slot.hasRound2MajorityVotes(1)).isFalse();
    }

    @Test
    void conflictingDuplicateCannotChangeEitherBallot() {
        var peer = nodeId("peer").unwrap();
        var slot = new PhaseData<TestCommand>(Phase.ZERO);
        slot.registerRound1Vote(peer, StateValue.V1);
        slot.registerRound1Vote(peer, StateValue.V0);
        slot.registerRound2Vote(peer, StateValue.VQUESTION);
        slot.registerRound2Vote(peer, StateValue.V1);
        assertThat(slot.getRound1Vote(peer)).isEqualTo(StateValue.V1);
        assertThat(slot.getRound2Vote(peer)).isEqualTo(StateValue.VQUESTION);
    }

    @Test
    void binaryV1CannotManufactureMissingProposal() {
        var self = nodeId("self").unwrap();
        var slot = new PhaseData<TestCommand>(Phase.ZERO);
        slot.registerRound2Vote(self, StateValue.V1);
        assertThat(slot.processRound2Completion(self, 1, 1)).isInstanceOf(Round2Outcome.AwaitingProposal.class);
        assertThat(slot.completedDecision().isEmpty()).isTrue();
    }

    @Test
    void coinOnlySelectsNextRoundState() {
        var self = nodeId("self").unwrap();
        var slot = new PhaseData<TestCommand>(Phase.ZERO);
        slot.registerRound2Vote(self, StateValue.VQUESTION);
        var outcome = slot.processRound2Completion(self, 1, 1);
        assertThat(outcome).isInstanceOf(Round2Outcome.CarryForward.class);
        assertThat(slot.isDecided()).isFalse();
        assertThat(slot.completedDecision().isEmpty()).isTrue();
    }
}
