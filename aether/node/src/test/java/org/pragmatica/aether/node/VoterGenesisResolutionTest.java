package org.pragmatica.aether.node;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.rabia.ClusterConfig;
import org.pragmatica.consensus.rabia.VoterConfiguration;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/// #1526 genesis at the node boundary: loud failure on malformed `cluster.genesis_voters`, and the
/// worker wait-and-retry. Core genesis view agreement is pinned in the consensus module
/// (`GenesisViewAgreementSimulationTest`, `RabiaReorderedDeliveryTest`).
class VoterGenesisResolutionTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final List<NodeId> GENESIS_IDS = List.of(A, B, C);
    private static final VoterConfiguration GENESIS = new VoterConfiguration(0, new ClusterConfig(GENESIS_IDS));

    @Test void parseGenesisVoters_blankId_failsBootLoudly() {
        for (var malformed : List.of("a,,c", "a, ,c", "", "a,b,")) {
            var result = AetherNode.parseGenesisVoters(malformed);

            assertThat(result.isFailure()).as(malformed).isTrue();
            result.onFailure(cause -> assertThat(cause).isEqualTo(AetherNode.VoterBootstrapError.MALFORMED_GENESIS_VOTERS));
        }
        assertThat(AetherNode.parseGenesisVoters("c,b,a").unwrap()).isEqualTo(GENESIS);
    }

    @Test void retryGenesis_workerWithALateCore_completesOnceItIsDiscovered() {
        var pending = new AtomicBoolean(true);
        var installed = new ArrayList<VoterConfiguration>();
        var discovered = new LinkedHashSet<>(List.of(A, B));
        var install = (java.util.function.Function<VoterConfiguration, Result<Unit>>) configuration -> {
            installed.add(configuration);
            pending.set(false);
            return Result.success(Unit.unit());
        };

        AetherNode.retryGenesis(pending::get, install, () -> Set.copyOf(discovered), 3);
        assertThat(installed).as("two of three cores discovered: keep waiting").isEmpty();

        discovered.add(C);
        AetherNode.retryGenesis(pending::get, install, () -> Set.copyOf(discovered), 3);
        AetherNode.retryGenesis(pending::get, install, () -> Set.copyOf(discovered), 3);

        assertThat(installed).containsExactly(GENESIS);
        assertThat(pending.get()).isFalse();
    }
}
