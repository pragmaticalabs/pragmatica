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

/// #1526 genesis: full roster with wait-and-retry, `cluster.genesis_voters` optional.
class VoterGenesisResolutionTest {
    private static final NodeId A = new NodeId("a");
    private static final NodeId B = new NodeId("b");
    private static final NodeId C = new NodeId("c");
    private static final List<NodeId> GENESIS_IDS = List.of(A, B, C);
    private static final VoterConfiguration GENESIS = new VoterConfiguration(0, new ClusterConfig(GENESIS_IDS));

    @Test void resolveGenesis_incompleteKnownRoster_waitsInsteadOfRefusing() {
        var result = AetherNode.resolveGenesis(Option.none(), Option.none(), List.of(A, B), 3);

        assertThat(result.isSuccess()).as("a late core must never fail assembly").isTrue();
        assertThat(result.unwrap().isEmpty()).isTrue();
    }

    @Test void resolveGenesis_completeKnownRoster_resolvesWithoutGenesisVoters() {
        assertThat(AetherNode.resolveGenesis(Option.none(), Option.none(), GENESIS_IDS, 3).unwrap().unwrap())
            .isEqualTo(GENESIS);
    }

    @Test void resolveGenesis_moreKnownCoresThanConfigured_keepsWaiting() {
        assertThat(AetherNode.resolveGenesis(Option.none(), Option.none(), List.of(A, B, C, new NodeId("d")), 3)
                             .unwrap()
                             .isEmpty()).isTrue();
    }

    @Test void resolveGenesis_explicitGenesisVoters_areAuthoritativeInAnyOrder() {
        assertThat(AetherNode.resolveGenesis(Option.some("c,b,a"), Option.none(), List.of(A), 5).unwrap().unwrap())
            .isEqualTo(GENESIS);
    }

    @Test void resolveGenesis_persistedConfiguration_isUsedWhenGenesisVotersAreAbsent() {
        var persisted = new VoterConfiguration(2, new ClusterConfig(List.of(A, B, new NodeId("d"))));

        assertThat(AetherNode.resolveGenesis(Option.none(), Option.some(persisted), List.of(A), 3).unwrap().unwrap())
            .isEqualTo(persisted);
    }

    @Test void resolveGenesis_blankGenesisVoterId_failsBootLoudly() {
        for (var malformed : List.of("a,,c", "a, ,c", "", "a,b,")) {
            var result = AetherNode.resolveGenesis(Option.some(malformed), Option.none(), GENESIS_IDS, 3);

            assertThat(result.isFailure()).as(malformed).isTrue();
            result.onFailure(cause -> assertThat(cause).isEqualTo(AetherNode.VoterBootstrapError.MALFORMED_GENESIS_VOTERS));
        }
    }

    @Test void retryGenesis_lateCore_completesGenesisOnceItIsDiscovered() {
        var pending = new AtomicBoolean(true);
        var installed = new ArrayList<VoterConfiguration>();
        var discovered = new LinkedHashSet<>(List.of(A, B));
        var install = (java.util.function.Function<VoterConfiguration, Result<Unit>>) configuration -> {
            installed.add(configuration);
            pending.set(false);
            return Result.success(Unit.unit());
        };

        AetherNode.retryGenesis(pending::get, install, Option.none(), () -> Set.copyOf(discovered), 3);
        AetherNode.retryGenesis(pending::get, install, Option.none(), () -> Set.copyOf(discovered), 3);
        assertThat(installed).as("two of three cores discovered: keep waiting").isEmpty();
        assertThat(pending.get()).isTrue();

        discovered.add(C);
        AetherNode.retryGenesis(pending::get, install, Option.none(), () -> Set.copyOf(discovered), 3);
        AetherNode.retryGenesis(pending::get, install, Option.none(), () -> Set.copyOf(discovered), 3);

        assertThat(installed).containsExactly(GENESIS);
        assertThat(pending.get()).isFalse();
    }
}
