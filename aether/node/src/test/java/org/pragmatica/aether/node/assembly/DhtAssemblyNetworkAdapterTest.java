// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.assembly;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.cluster.node.rabia.RabiaNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.dht.DHTMessage;
import org.pragmatica.lang.Promise;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/// #2583 P1: the DHT network adapter's `send` returns the cluster network's own outcome, so a refusal reaches the DHT
/// quorum fail-fast instead of a constant `Sent`.
class DhtAssemblyNetworkAdapterTest {
    private static final NodeId TARGET = new NodeId("dht-target");
    private static final ProtocolMessage MESSAGE = new DHTMessage.GetRequest("request-1",
                                                                             new NodeId("dht-self"),
                                                                             new byte[]{1});

    @Test
    void send_passesTheNetworksRefusalThrough() {
        var network = mock(ClusterNetwork.class);
        var refused = Promise.<WriteOutcome>success(new WriteOutcome.BackpressureRefused(TARGET));

        when(network.send(TARGET, MESSAGE)).thenReturn(refused);

        assertThat(adapterOver(network).send(TARGET, MESSAGE)).isSameAs(refused);
    }

    @Test
    void send_passesTheNetworksSentThrough() {
        var network = mock(ClusterNetwork.class);
        var sent = Promise.<WriteOutcome>success(new WriteOutcome.Sent(TARGET));

        when(network.send(TARGET, MESSAGE)).thenReturn(sent);

        assertThat(adapterOver(network).send(TARGET, MESSAGE)).isSameAs(sent);
    }

    /// `send` reads only the cluster node's network; the config and DHT node feed `livePeers`, which these cases never
    /// call, so they are absent here.
    @SuppressWarnings("unchecked")
    private static org.pragmatica.dht.DHTNetwork adapterOver(ClusterNetwork network) {
        RabiaNode<KVCommand<AetherKey>> clusterNode = mock(RabiaNode.class);

        when(clusterNode.network()).thenReturn(network);

        return DhtAssembly.dhtNetwork(new DhtAssembly.DhtNetworkInputs(null, clusterNode, null, new AtomicReference<>()));
    }
}
