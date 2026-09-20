package org.pragmatica.consensus.rabia;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.rabia.RabiaEngineTest.TestCommand;
import org.pragmatica.lang.Option;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

class VoterAuthoritySnapshotCodecTest {
    @Test
    void authorityAndBarrierRoundTripTogether() {
        var old = VoterConfiguration.voterConfiguration(0, List.of(nodeId("a").unwrap(), nodeId("b").unwrap(), nodeId("c").unwrap())).unwrap();
        var next = VoterConfiguration.voterConfiguration(1, List.of(nodeId("a").unwrap(), nodeId("b").unwrap(), nodeId("d").unwrap())).unwrap();
        var handoff = new ConfigurationHandoff<TestCommand>(old, next, Phase.phase(42), new byte[]{0, 1, 2}, List.of());
        var authority = new VoterAuthority<>(next, Option.some(handoff));
        var restored = VoterAuthoritySnapshotCodec.<TestCommand>decode(VoterAuthoritySnapshotCodec.encode(authority)).unwrap().unwrap();
        assertThat(restored.configuration()).isEqualTo(next);
        assertThat(restored.handoff().unwrap().sameCheckpoint(handoff)).isTrue();
    }

    @Test
    void malformedAuthorityCannotBecomeGenesis() {
        assertThat(VoterAuthoritySnapshotCodec.decode("# Voter: not-a-number|YQ==\n").isFailure()).isTrue();
        assertThat(VoterAuthoritySnapshotCodec.decode("# Voter: 1|%%%\n").isFailure()).isTrue();
        assertThat(VoterAuthoritySnapshotCodec.decode("# Voter: 1|YQ==\n# Handoff-Previous: 0|YQ==\n").isFailure()).isTrue();
    }
}
