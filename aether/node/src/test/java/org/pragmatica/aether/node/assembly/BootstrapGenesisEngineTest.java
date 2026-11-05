// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ClusterIncarnationKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterIncarnationValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngine;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentEngineValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.utility.warning.OperatorWarningSink;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// F1 #2643 kill test for mutant u06 (`ClusterBootstrapAssembly.bootstrapRegistrars` minting the genesis marker with a hard-coded LEGACY instead of
/// `config.deploymentEngine()`): boots the real registrar wiring, makes this node the leader, and reads the engine out of the genesis transaction
/// that reaches the cluster command applier. Both engines are asserted so the engine cannot be a constant.
class BootstrapGenesisEngineTest {
    private static final NodeId SELF = NodeId.nodeId("genesis-1").unwrap();

    @Test
    void genesisMarker_underAnFsm2Config_isMintedFsm2() {
        assertThat(mintedEngine(DeploymentEngine.FSM2)).isEqualTo(DeploymentEngine.FSM2);
    }

    @Test
    void genesisMarker_underALegacyConfig_isMintedLegacy() {
        assertThat(mintedEngine(DeploymentEngine.LEGACY)).isEqualTo(DeploymentEngine.LEGACY);
    }

    @SuppressWarnings("unchecked")
    private static DeploymentEngine mintedEngine(DeploymentEngine configured) {
        var config = mock(AetherNodeConfig.class);

        when(config.deploymentEngine()).thenReturn(configured);
        when(config.self()).thenReturn(SELF);
        when(config.clusterSecret()).thenReturn(Option.none());
        when(config.backupConfig()).thenReturn(Option.none());

        var committed = new AtomicBoolean(false);
        var commands = new AtomicReference<List<KVCommand<AetherKey>>>();
        var kvStore = (KVStore<AetherKey, AetherValue>) mock(KVStore.class);

        when(kvStore.getTyped(any(ClusterIncarnationKey.class), any(Class.class)))
            .thenAnswer(_ -> committed.get()
                             ? Option.some(ClusterIncarnationValue.genesis("lineage", "incarnation"))
                             : Option.none());
        when(kvStore.getTyped(any(LeaderKey.class), any(Class.class))).thenReturn(Option.some(new LeaderValue(SELF, 1L)));

        var routes = new ArrayList<MessageRouter.Entry<?>>();
        var inputs = new ClusterBootstrapAssembly.BootstrapRegistrarsInputs(config,
                                                                            kvStore,
                                                                            mock(SliceCodec.class),
                                                                            () -> true,
                                                                            batch -> {
                                                                                commands.set(batch);
                                                                                committed.set(true);
                                                                                return Promise.success(List.of());
                                                                            },
                                                                            OperatorWarningSink.handingOffTo(_ -> {}),
                                                                            routes);

        ClusterBootstrapAssembly.bootstrapRegistrars(inputs);
        // Registration order in bootstrapRegistrars: [0] bootstrap admin key, [1] cluster incarnation genesis, then the backup coordinator.
        var incarnationRegistrar = leaderChangeConsumers(routes).get(1);

        incarnationRegistrar.accept(new LeaderChange(Option.some(SELF), true));
        await().atMost(5, SECONDS).until(() -> commands.get() != null);

        return engineOf(commands.get());
    }

    @SuppressWarnings("unchecked")
    private static List<Consumer<LeaderChange>> leaderChangeConsumers(List<MessageRouter.Entry<?>> routes) {
        return routes.stream()
                     .flatMap(entry -> entry.entries())
                     .filter(tuple -> tuple.first() == LeaderChange.class)
                     .map(tuple -> (Consumer<LeaderChange>) tuple.last())
                     .toList();
    }

    private static DeploymentEngine engineOf(List<KVCommand<AetherKey>> batch) {
        assertThat(batch).hasSize(1);
        var transaction = (KVCommand.LeaderTransaction<AetherKey, AetherValue>) batch.getFirst();

        return transaction.mutations()
                          .stream()
                          .flatMap(mutation -> mutation.replacement().stream())
                          .filter(DeploymentEngineValue.class::isInstance)
                          .map(value -> ((DeploymentEngineValue) value).engine())
                          .findFirst()
                          .orElseThrow();
    }
}
