package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

class VoterConfigurationStateTest {
    private static final NodeId A = nodeId("a").unwrap();
    private static final NodeId B = nodeId("b").unwrap();
    private static final NodeId C = nodeId("c").unwrap();
    private static final NodeId D = nodeId("d").unwrap();
    private static final NodeId E = nodeId("e").unwrap();
    private static final VoterConfiguration OLD = VoterConfiguration.voterConfiguration(0, List.of(A, B, C)).unwrap();
    private static final VoterConfiguration NEXT = VoterConfiguration.voterConfiguration(1, List.of(C, D, E)).unwrap();

    @Test
    void replacementNeedsMatchingOldQuorumAndDoesNotCountNewMembers() {
        var state = fresh();
        var handoff = handoff(new byte[]{1});
        assertThat(state.receive(D, handoff).isFailure()).isTrue();
        assertThat(state.receive(A, handoff).unwrap().isEmpty()).isTrue();
        assertThat(state.receive(A, handoff).unwrap().isEmpty()).isTrue();
        assertThat(state.receive(B, handoff(new byte[]{2})).unwrap().isEmpty()).isTrue();
        var candidate = state.receive(B, handoff).unwrap().unwrap();
        assertThat(state.configuration()).isEqualTo(OLD); // Collection alone cannot activate authority.
        assertThat(candidate.extendsConfiguration(OLD)).isTrue();
        state.install(candidate);
        assertThat(state.configuration()).isEqualTo(NEXT);
    }

    @Test
    void barrierStopsOldAuthorityAndAcknowledgmentsRequireNewQuorum() {
        var state = fresh();
        var barrier = state.barrier(NEXT.roster(), Phase.phase(11), new byte[]{1}, List.of()).unwrap();
        state.install(barrier);
        assertThat(state.isAwaitingHandoff()).isTrue();
        assertThat(state.acknowledge(A, NEXT, Phase.phase(11))).isFalse();
        state.receive(A, barrier.handoff().unwrap());
        state.install(state.receive(B, barrier.handoff().unwrap()).unwrap().unwrap());
        assertThat(state.acknowledge(C, NEXT, Phase.phase(11))).isFalse();
        assertThat(state.acknowledge(C, NEXT, Phase.phase(11))).isFalse();
        assertThat(state.acknowledge(D, NEXT, Phase.phase(11))).isTrue();
    }

    @Test
    void persistedAuthorityRestartsWithoutReopeningOldEpoch() {
        var state = fresh();
        var handoff = handoff(new byte[]{1});
        state.receive(A, handoff);
        var installed = state.receive(B, handoff).unwrap().unwrap();
        var recovered = VoterAuthoritySnapshotCodec.<TestCommand>decode(VoterAuthoritySnapshotCodec.encode(installed)).unwrap().unwrap();
        var restarted = new VoterConfigurationState<>(recovered);
        assertThat(restarted.configuration()).isEqualTo(NEXT);
        assertThat(restarted.accepts(new VoterAuthority<>(OLD, Option.none()))).isFalse();
        assertThat(restarted.isAwaitingHandoff()).isFalse();
    }

    @Test
    void conflictingBarrierCannotReplaceAnAlreadyAgreedBarrier() {
        var state = fresh();
        state.install(state.barrier(NEXT.roster(), Phase.phase(11), new byte[]{1}, List.of()).unwrap());
        var other = VoterConfiguration.voterConfiguration(1, List.of(A, D, E)).unwrap();
        var conflict = new ConfigurationHandoff<TestCommand>(OLD, other, Phase.phase(11), new byte[]{1}, List.of());
        assertThat(state.receive(A, conflict).isFailure()).isTrue();
    }

    private static VoterConfigurationState<TestCommand> fresh() {
        return new VoterConfigurationState<>(new VoterAuthority<>(OLD, Option.none()));
    }

    private static ConfigurationHandoff<TestCommand> handoff(byte[] snapshot) {
        return new ConfigurationHandoff<>(OLD, NEXT, Phase.phase(11), snapshot, List.of());
    }
}
