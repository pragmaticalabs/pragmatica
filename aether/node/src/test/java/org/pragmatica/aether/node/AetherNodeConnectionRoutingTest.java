// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage.ConnectionEstablished;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.NodeAddress;

import static org.assertj.core.api.Assertions.assertThat;

/// `AetherNode.routeConnectionEstablished`: what a QUIC `ConnectionEstablished` feeds to the membership FSM and to
/// the SWIM health detector. Two obligations, one per sink: the Hello's role reaches the FSM descriptor
/// whenever the transport attached it, and the SWIM feed keeps its established source order (the topology's own
/// `NodeInfo` first, the Hello's only for a peer topology does not know, the bare id otherwise).
class AetherNodeConnectionRoutingTest {
    private static final NodeId PEER = new NodeId("peer");
    private static final NodeInfo HELLO = info("hello-host", Map.of(NodeInfo.LABEL_ROLE, "core"));
    private static final NodeInfo TOPOLOGY = info("topology-host", Map.of());

    private final List<NodeInfo> descriptor = new ArrayList<>();
    private final List<NodeInfo> swimInfo = new ArrayList<>();
    private final List<NodeId> swimId = new ArrayList<>();

    @Test
    void helloNodeInfo_reachesTheMembershipDescriptor() {
        route(ConnectionEstablished.connectionEstablished(PEER, HELLO), Option.none());

        assertThat(descriptor).containsExactly(HELLO);
    }

    @Test
    void knownPeerWithHelloLabels_swimFeedKeepsTheTopologyNodeInfo() {
        route(ConnectionEstablished.connectionEstablished(PEER, HELLO), Option.some(TOPOLOGY));

        assertThat(swimInfo).as("topology's NodeInfo wins for a known peer").containsExactly(TOPOLOGY);
        assertThat(swimId).isEmpty();
    }

    @Test
    void unknownPeer_swimFeedUsesTheHelloNodeInfo() {
        route(ConnectionEstablished.connectionEstablished(PEER, HELLO), Option.none());

        assertThat(swimInfo).containsExactly(HELLO);
        assertThat(swimId).isEmpty();
    }

    @Test
    void knownPeerWithoutHello_swimFeedUsesTheTopologyNodeInfo_andNothingReachesTheDescriptor() {
        route(ConnectionEstablished.connectionEstablished(PEER), Option.some(TOPOLOGY));

        assertThat(swimInfo).containsExactly(TOPOLOGY);
        assertThat(descriptor).isEmpty();
    }

    @Test
    void peerKnownNowhere_swimFeedGetsTheBareId() {
        route(ConnectionEstablished.connectionEstablished(PEER), Option.none());

        assertThat(swimId).containsExactly(PEER);
        assertThat(swimInfo).isEmpty();
    }

    private void route(ConnectionEstablished connection, Option<NodeInfo> topologyView) {
        AetherNode.routeConnectionEstablished(connection, _ -> topologyView, descriptor::add, swimInfo::add, swimId::add);
    }

    private static NodeInfo info(String host, Map<String, String> labels) {
        return NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress(host, 9000).unwrap(), labels);
    }
}
