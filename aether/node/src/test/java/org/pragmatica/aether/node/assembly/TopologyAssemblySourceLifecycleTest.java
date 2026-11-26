// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mockito.Mockito;
import org.pragmatica.aether.node.AetherNodeConfig;
import org.pragmatica.aether.node.assembly.TopologyAssembly.SourceLifecycleInputs;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderManager;
import org.pragmatica.consensus.rabia.ProtocolConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// t01 of #3754 C1: the fence's elapsed check must use the consensus engine's own apply timeout, so the node assembly hands the lifecycle the
/// value `config.protocol().applyTimeout()` names. Without that call the fence silently keeps the 30 s default. Driven through the real
/// assembly function, with a configured value that is not the default.
class TopologyAssemblySourceLifecycleTest {
    private static final TimeSpan CONFIGURED = timeSpan(7).seconds();

    @Test
    @SuppressWarnings("unchecked")
    void sourceLifecycle_carriesTheEnginesConfiguredApplyTimeout() {
        var config = Mockito.mock(AetherNodeConfig.class, Answers.RETURNS_DEEP_STUBS);
        var protocol = ProtocolConfig.protocolConfig(timeSpan(1).seconds(), timeSpan(1).seconds(), 10, 10, CONFIGURED).unwrap();
        var clusterNode = (RabiaNode<KVCommand<AetherKey>>) Mockito.mock(RabiaNode.class);
        var kvStore = (KVStore<AetherKey, AetherValue>) Mockito.mock(KVStore.class);

        Mockito.doReturn(protocol).when(config).protocol();
        Mockito.doReturn(new NodeId("self")).when(config).self();
        Mockito.doReturn(Option.none()).when(config).environment();
        Mockito.doReturn(Option.none()).when(config).configProvider();
        Mockito.doReturn(Option.none()).when(config).clusterName();
        Mockito.doReturn(Option.none()).when(config.autoHeal()).maxNodes();
        Mockito.doReturn(Mockito.mock(LeaderManager.class)).when(clusterNode).leaderManager();

        var lifecycle = TopologyAssembly.sourceLifecycle(new SourceLifecycleInputs(config, kvStore, clusterNode, new AtomicReference<>()));

        assertThat(CONFIGURED).as("control: the configured value is not the default").isNotEqualTo(ProtocolConfig.DEFAULT_APPLY_TIMEOUT);
        assertThat(lifecycle.applyTimeout()).as("the lifecycle uses the engine's configured apply timeout").isEqualTo(CONFIGURED);
    }
}
