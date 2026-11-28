// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.cluster.node.rabia;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.ConsensusError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.JoinGenerationGuard;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.consensus.NodeId.nodeId;

/// #2981 step 6 wiring: what `RabiaNode` hands the engine as its pre-sync check decides over the core voters other than
/// this node, reads the network's Hello labels, compares against this binary's generation, and honours the configured wait.
class RabiaNodeJoinGuardTest {
    private static final NodeId SELF = nodeId("node-a").unwrap();
    private static final NodeId PEER = nodeId("node-b").unwrap();
    private static final TimeSpan SHORT = TimeSpan.timeSpan(200).millis();
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(5).seconds();
    private static final NodeId THIRD = nodeId("node-c").unwrap();
    private static final String LABEL = "backup";
    private static final Map<String, String> AGREEING = Map.of(NodeInfo.LABEL_WIRE_GENERATION,
                                                                Integer.toString(JoinGenerationGuard.WIRE_GENERATION),
                                                                LABEL,
                                                                "on");

    private static TopologyObserver topology() {
        return topology(Set.of(SELF, PEER), Map.of());
    }

    private static TopologyObserver topology(Set<NodeId> core, Map<String, String> labels) {
        var info = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9000).unwrap(), labels);
        InvocationHandler handler = (_, method, _) -> switch (method.getName()) {
            case "self" -> info;
            case "coreNodes" -> core;
            default -> throw new AssertionError("unexpected topology call " + method.getName());
        };

        return (TopologyObserver) Proxy.newProxyInstance(TopologyObserver.class.getClassLoader(),
                                                         new Class<?>[]{TopologyObserver.class},
                                                         handler);
    }

    private static Fn1<Unit, Set<NodeId>> telling(List<Set<NodeId>> told) {
        return missing -> {
            told.add(missing);

            return Unit.unit();
        };
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

    /// F4 (#3748 round 1): `quorumPeers` is node-level wiring the engine tests hand-build around. In a core of three this node
    /// plus ONE agreeing peer is a quorum: past the bound the wait ends without the third. Counting one peer too many
    /// (`size / 2 + 1`) would leave a three-node cluster with one node down unable to start.
    @Test
    void joinGuard_threeCore_onePeerOfTwoHandshaken_isAQuorum_andAdmitted() {
        var handshaken = Map.of(PEER, AGREEING);

        var result = RabiaNode.joinGuard(topology(Set.of(SELF, PEER, THIRD), AGREEING),
                                         network(handshaken),
                                         SHORT,
                                         Set.of(LABEL),
                                         _ -> Unit.unit(),
                                         Unit::unit,
                                         () -> false)
                              .get()
                              .await(AWAIT);

        assertThat(result.isSuccess()).as("this node and one agreeing peer are a quorum of three").isTrue();
    }

    /// The other side of the pair: with NO peer handshaken this node alone is not a quorum of three, so the wait outlives the
    /// bound and tells the notice sink both missing peers (counting one peer too few would admit it at the bound).
    @Test
    void joinGuard_threeCore_noPeerHandshaken_staysPendingAndNamesBothPeers() {
        var told = new CopyOnWriteArrayList<Set<NodeId>>();
        var ended = new AtomicBoolean();
        var pending = RabiaNode.joinGuard(topology(Set.of(SELF, PEER, THIRD), AGREEING),
                                          network(Map.of()),
                                          SHORT,
                                          Set.of(LABEL),
                                          telling(told),
                                          Unit::unit,
                                          ended::get)
                               .get();

        try {
            await().atMost(Duration.ofSeconds(5)).until(() -> !told.isEmpty());

            assertThat(told).containsExactly(Set.of(PEER, THIRD));
            assertThat(pending.isResolved()).as("one node of three casts no ballot").isFalse();
        } finally {
            ended.set(true);
        }
    }

    /// F-A (#3748 round 1): the peers a quorum needs besides this node, per core size: a majority of the core, less this node.
    /// `size / 2 + 1` and `size` (the mutants that survived) differ from it in every row.
    @Test
    void quorumPeers_isAMajorityOfTheCoreLessThisNode() {
        var fourth = nodeId("node-d").unwrap();
        var fifth = nodeId("node-e").unwrap();

        assertThat(RabiaNode.quorumPeers(topology(Set.of(SELF, PEER), Map.of()))).as("quorum 2 of 2").isEqualTo(1);
        assertThat(RabiaNode.quorumPeers(topology(Set.of(SELF, PEER, THIRD), Map.of()))).as("quorum 2 of 3").isEqualTo(1);
        assertThat(RabiaNode.quorumPeers(topology(Set.of(SELF, PEER, THIRD, fourth), Map.of()))).as("quorum 3 of 4").isEqualTo(2);
        assertThat(RabiaNode.quorumPeers(topology(Set.of(SELF, PEER, THIRD, fourth, fifth), Map.of()))).as("quorum 3 of 5").isEqualTo(2);
    }

    @Test
    void nodeConfig_joinGenerationWait_defaultsToTheGuardsBound() {
        InvocationHandler defaultsOnly = (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args);
        var config = (NodeConfig) Proxy.newProxyInstance(NodeConfig.class.getClassLoader(),
                                                         new Class<?>[]{NodeConfig.class},
                                                         defaultsOnly);

        assertThat(config.joinGenerationWait()).isEqualTo(JoinGenerationGuard.JOIN_GENERATION_WAIT);
    }

    /// #3525 round 1 (F-B): an assembly that names no agreeing label requires nothing of its cluster, so the default is empty.
    @Test
    void nodeConfig_agreeingLabels_defaultsToNone() {
        InvocationHandler defaultsOnly = (proxy, method, args) -> InvocationHandler.invokeDefault(proxy, method, args);
        var config = (NodeConfig) Proxy.newProxyInstance(NodeConfig.class.getClassLoader(),
                                                         new Class<?>[]{NodeConfig.class},
                                                         defaultsOnly);

        assertThat(config.agreeingLabels()).isEmpty();
    }
}
