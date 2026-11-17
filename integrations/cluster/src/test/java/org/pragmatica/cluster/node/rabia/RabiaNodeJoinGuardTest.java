// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.node.rabia;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.JoinGenerationGuard;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2981 step 6 wiring: what `RabiaNode` hands the engine as its pre-sync check decides over the core voters other than
/// this node, reads the network's Hello labels, compares against this binary's generation, and honours the configured wait.
class RabiaNodeJoinGuardTest {
    private static final NodeId SELF = nodeId("node-a").unwrap();
    private static final NodeId PEER = nodeId("node-b").unwrap();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(200).millis();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(5).seconds();

    private static TopologyObserver topology() {
        var info = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9000).unwrap());
        InvocationHandler handler = (_, method, _) -> switch (method.getName()) {
            case "self" -> info;
            case "coreNodes" -> Set.of(SELF, PEER);
            default -> throw new AssertionError("unexpected topology call " + method.getName());
        };

        return (TopologyObserver) Proxy.newProxyInstance(TopologyObserver.class.getClassLoader(),
                                                         new Class<?>[]{TopologyObserver.class},
                                                         handler);
    }

    private static ClusterNetwork network(Map<NodeId, Map<String, String>> handshaken) {
        InvocationHandler handler = (proxy, method, args) -> method.getName().equals("peerHelloLabels")
                                                             ? Option.option(handshaken.get((NodeId) args[0]))
                                                             : InvocationHandler.invokeDefault(proxy, method, args);

        return (ClusterNetwork) Proxy.newProxyInstance(ClusterNetwork.class.getClassLoader(),
                                                       new Class<?>[]{ClusterNetwork.class},
                                                       handler);
    }

    @Test
    void joinGuard_handshakenPeerBelowThisBinariesGeneration_refusedNamingThePeer() {
        var handshaken = Map.of(PEER, Map.of(NodeInfo.LABEL_WIRE_GENERATION, "1"));

        RabiaNode.joinGuard(topology(), network(handshaken), TimeSpan.timeSpan(30).seconds())
                 .get()
                 .await(AWAIT)
                 .onSuccess(_ -> org.junit.jupiter.api.Assertions.fail("must be refused"))
                 .onFailure(cause -> assertThat(cause)
                     .isEqualTo(new ConsensusError.WireGenerationRefused(PEER, 1, JoinGenerationGuard.WIRE_GENERATION)));
    }

    @Test
    void joinGuard_thisNodeIsNotInTheDecisionSet_itsOwnMissingLabelIsNeverHeldAgainstIt() {
        var handshaken = Map.of(SELF, Map.<String, String>of(),
                                PEER, Map.of(NodeInfo.LABEL_WIRE_GENERATION, Integer.toString(JoinGenerationGuard.WIRE_GENERATION)));

        assertThat(RabiaNode.joinGuard(topology(), network(handshaken), TimeSpan.timeSpan(30).seconds())
                            .get()
                            .await(AWAIT)
                            .isSuccess()).isTrue();
    }

    @Test
    void joinGuard_usesTheConfiguredWait_notTheDefaultTenSeconds() {
        var start = System.nanoTime();
        var result = RabiaNode.joinGuard(topology(), network(Map.of()), SHORT).get().await(AWAIT);

        assertThat(result.isSuccess()).as("no peer handshaken: proceeds when the wait ends").isTrue();
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(5_000);
    }

    @Test
    void nodeConfig_joinGenerationWait_defaultsToTheGuardsBound() {
        InvocationHandler defaultsOnly = (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args);
        var config = (NodeConfig) Proxy.newProxyInstance(NodeConfig.class.getClassLoader(),
                                                         new Class<?>[]{NodeConfig.class},
                                                         defaultsOnly);

        assertThat(config.joinGenerationWait()).isEqualTo(JoinGenerationGuard.JOIN_GENERATION_WAIT);
    }
}
