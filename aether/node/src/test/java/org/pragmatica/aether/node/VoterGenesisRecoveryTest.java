package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class VoterGenesisRecoveryTest {
    private static final List<NodeId> GENESIS_IDS = List.of(new NodeId("a"), new NodeId("b"), new NodeId("c"));
    private static final VoterConfiguration GENESIS = new VoterConfiguration(0, new ClusterConfig(GENESIS_IDS));

    @Test void recoveryUsesPersistedGenesisDespiteIncompleteSeedsAndChangedDesiredCount() {
        var result = AetherNode.resolveGenesis(Option.none(), Option.some(GENESIS), List.of(new NodeId("e")), 5);
        assertThat(result.unwrap()).isEqualTo(GENESIS);
    }
    @Test void explicitDifferentGenesisCannotAttachExistingHistoryToAnotherCluster() {
        var result = AetherNode.resolveGenesis(Option.some("a,b,d"), Option.some(GENESIS), GENESIS_IDS, 3);
        assertThat(result.isFailure()).isTrue();
        result.onFailure(cause -> assertThat(cause).isEqualTo(AetherNode.VoterBootstrapError.GENESIS_MISMATCH));
    }
    @Test void explicitSameGenesisAcceptsDifferentOrderingAndDiscoverySubset() {
        var result = AetherNode.resolveGenesis(Option.some("c,b,a"), Option.some(GENESIS), List.of(new NodeId("a")), 5);
        assertThat(result.unwrap()).isEqualTo(GENESIS);
    }
    @Test void freshNodeRequiresCompleteFallbackRosterButAcceptsExplicitGenesis() {
        assertThat(AetherNode.resolveGenesis(Option.none(), Option.none(), List.of(new NodeId("a")), 3).isFailure()).isTrue();
        assertThat(AetherNode.resolveGenesis(Option.some("a,b,c"), Option.none(), List.of(new NodeId("a")), 3).unwrap())
            .isEqualTo(GENESIS);
    }
}
