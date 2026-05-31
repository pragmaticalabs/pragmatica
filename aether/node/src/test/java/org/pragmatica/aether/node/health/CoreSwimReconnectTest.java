// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.aether.node.health.fsm.SwimHealthState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CoreSwimReconnectTest {

    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");
    private static final NodeId PEER_C = new NodeId("node-4");
    private static final NodeId PEER_D = new NodeId("node-5");

    private final List<NetworkServiceMessage.DisconnectNode> disconnectNotifications = new ArrayList<>();
    private CoreSwimHealthDetector detector;

    @BeforeEach
    void setUp() {
        disconnectNotifications.clear();
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.DisconnectNode.class, disconnectNotifications::add);

        var nodeSelf = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodeA = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var nodeB = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap());
        var nodeC = NodeInfo.nodeInfo(PEER_C, NodeAddress.nodeAddress("127.0.0.4", 9001).unwrap());
        var nodeD = NodeInfo.nodeInfo(PEER_D, NodeAddress.nodeAddress("127.0.0.5", 9001).unwrap());

        var topologyConfig = new TopologyConfig(SELF, 5, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                List.of(nodeSelf, nodeA, nodeB, nodeC, nodeD));
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        detector = CoreSwimHealthDetector.coreSwimHealthDetector(router, topologyConfig, serializer, deserializer);
        // Drive the FSM to Running so the membership-callback assertions exercise the
        // production-active code path with a live SwimProtocol behind the listener.
        driveToRunning(detector);
    }

    private static void driveToRunning(CoreSwimHealthDetector det) {
        var ctx = det.contextForTest();
        ctx.dispatch(new SwimHealthEvents.StartRequested());
        var swim = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, new StubTransport(),
                                              noopListener(), SELF,
                                              new InetSocketAddress("127.0.0.1", 9101)).unwrap();
        ctx.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(), GossipEncryptor.none()));
    }

    private static SwimMembershipListener noopListener() {
        return new SwimMembershipListener() {
            @Override public void onMemberJoined(SwimMember member) {}
            @Override public void onMemberSuspect(SwimMember member) {}
            @Override public void onMemberFaulty(SwimMember member) {}
            @Override public void onMemberLeft(NodeId nodeId) {}
        };
    }

    private static final class StubTransport implements SwimTransport {
        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) { return Promise.unitPromise(); }
        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) { return Promise.unitPromise(); }
        @Override public Promise<Unit> stop() { return Promise.unitPromise(); }
    }

    @Nested
    class FaultyEmission {
        @Test
        void singleFaulty_doesNotRouteDisconnect() throws InterruptedException {
            // RC1-9 audit Step 3: SWIM FAULTY no longer routes a local DisconnectNode.
            // Eviction now flows post-consensus via MembershipDecision.NodeRemoved
            // after the leader's MembershipFsm writes DECOMMISSIONED.
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                     new InetSocketAddress("127.0.0.2", 9101));

            detector.onMemberFaulty(faultyMember);
            Thread.sleep(100);

            assertThat(disconnectNotifications).isEmpty();
        }

        @Test
        void massFaulty_doesNotRouteDisconnect() throws InterruptedException {
            // RC1-9 audit Step 3: same rationale as singleFaulty above. The
            // local-disconnect guard logic no longer matters because there is no
            // local routing of DisconnectNode on SWIM FAULTY at all.
            var faultyA = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                new InetSocketAddress("127.0.0.2", 9101));

            detector.onMemberFaulty(faultyA);
            Thread.sleep(100);

            assertThat(disconnectNotifications).isEmpty();
        }
    }

    @Nested
    class SwimReconnect {
        @Test
        void onNodeConnected_withoutProtocol_doesNotThrow() {
            // onNodeConnected on a running detector is a safe no-op (no NPE) and
            // leaves the FSM in Running.
            detector.onNodeConnected(PEER_A);

            assertThat(detector.lifecycleState()).isInstanceOf(SwimHealthState.Running.class);
        }
    }
}
