// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.node.health;

import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import org.pragmatica.swim.SwimObservation;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

class CoreSwimHealthDetectorTest {

    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER_A = new NodeId("node-2");
    private static final NodeId PEER_B = new NodeId("node-3");

    private final List<NetworkServiceMessage.DisconnectNode> disconnectNotifications = new ArrayList<>();
    private CoreSwimHealthDetector detector;

    @BeforeEach
    void setUp() {
        disconnectNotifications.clear();
        var router = MessageRouter.mutable();
        router.addRoute(NetworkServiceMessage.DisconnectNode.class, disconnectNotifications::add);

        var nodeA = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
        var nodeB = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
        var nodeC = NodeInfo.nodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap());
        var topologyConfig = new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                List.of(nodeA, nodeB, nodeC));
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
    class FaultyMember {
        @Test
        void onMemberFaulty_doesNotRouteDisconnectNode() throws InterruptedException {
            // RC1-9 audit Step 3: SWIM FAULTY no longer routes a local DisconnectNode.
            // QUIC eviction flows post-consensus via TopologyChangeNotification.NodeRemoved
            // after the leader's HealthReconciler writes DECOMMISSIONED and TopologyObserver
            // fires the membership delta. The FAULTY observation still drives the
            // leader-side aggregation path (emitLeaderHint + bufferHealthObservation).
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberFaulty(faultyMember);
            Thread.sleep(100); // Allow async dispatch to complete

            assertThat(disconnectNotifications).isEmpty();
        }
    }

    @Nested
    class MemberLeft {
        @Test
        void onMemberLeft_doesNotRouteDisconnectNode() throws InterruptedException {
            // RC1-9 audit Step 3: SWIM LEFT no longer routes a local DisconnectNode.
            // Same rationale as FaultyMember above — eviction is membership-delta-driven.
            detector.onMemberLeft(PEER_B);
            Thread.sleep(100); // Allow async dispatch to complete

            assertThat(disconnectNotifications).isEmpty();
        }
    }

    @Nested
    class JoinedAndSuspect {
        @Test
        void onMemberJoined_doesNotRoute() {
            var member = SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberJoined(member);

            assertThat(disconnectNotifications).isEmpty();
        }

        @Test
        void onMemberSuspect_doesNotRoute() {
            var member = SwimMember.swimMember(PEER_A, MemberState.SUSPECT, 0,
                                               new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberSuspect(member);

            assertThat(disconnectNotifications).isEmpty();
        }
    }

    /// Regression: observation listeners registered BEFORE SWIM reaches Running
    /// must still be attached to the live SwimProtocol once it is created.
    /// The previous body of `addObservationListener` did
    ///     `protocol().onPresent(p -> p.addObservationListener(consumer))`
    /// which silently dropped the listener when `protocol()` was empty (the
    /// case at AetherNode init time, before `QuorumStateNotification` arrives).
    /// Both `healthReconciler::onSwimObservation` and
    /// `eventAggregator::onSwimObservation` were registered through this hole,
    /// so neither one received any observations and the cluster events ring
    /// buffer never recorded `NODE_FAILED`/`NODE_LEFT` on cloud.
    @Nested
    class ObservationListenerPreRegistration {
        @Test
        void listenerAttachedBeforeStart_isInvokedByLiveProtocol() throws InterruptedException {
            var router = MessageRouter.mutable();
            var nodeA = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap());
            var nodeB = NodeInfo.nodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap());
            var topologyConfig = new TopologyConfig(SELF, 2, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                    List.of(nodeA, nodeB));
            Serializer serializer = Mockito.mock(Serializer.class);
            Deserializer deserializer = Mockito.mock(Deserializer.class);
            var freshDetector = CoreSwimHealthDetector.coreSwimHealthDetector(router, topologyConfig,
                                                                              serializer, deserializer);

            var received = new CopyOnWriteArrayList<SwimObservation>();
            Consumer<SwimObservation> listener = received::add;

            // Register BEFORE start() — the production-equivalent of an AetherNode
            // init-time `swimHealthDetector.addObservationListener(...)` call before
            // QuorumStateNotification arrives.
            freshDetector.addObservationListener(listener);

            // Drive through the production start() path, which constructs a real
            // SwimProtocol via createAndStartProtocol → seedAndWrap. The fix in
            // seedAndWrap re-attaches every pending listener to the freshly-created
            // protocol.
            freshDetector.start(org.pragmatica.lang.Option.none(), GossipEncryptor.none()).await();

            // Wait briefly for the async dispatch chain to land Running.
            for (int i = 0; i < 50; i++) {
                if (freshDetector.lifecycleState() instanceof org.pragmatica.aether.node.health.fsm.SwimHealthState.Running) {
                    break;
                }
                Thread.sleep(20);
            }

            // Inject a membership-update gossip simulating peer-A reporting itself ALIVE.
            // This drives applyNewAliveMember → recordHealthyAndEmit → HealthyObserved.
            var protocol = ((org.pragmatica.aether.node.health.fsm.SwimHealthState.Running) freshDetector.lifecycleState()).swim();
            var membershipUpdate = SwimMessage.MembershipUpdate.membershipUpdate(
                    PEER_A, MemberState.ALIVE, 1L,
                    new InetSocketAddress("127.0.0.2", 9101));
            // Wrap in piggybacked Ack so the protocol consumes it.
            var ack = SwimMessage.Ack.ack(PEER_A, 1L, java.util.List.of(membershipUpdate));
            protocol.onMessage(new InetSocketAddress("127.0.0.2", 9101), ack);

            assertThat(received).as("HealthyObserved must reach a listener registered BEFORE start()")
                                .anyMatch(o -> o instanceof SwimObservation.HealthyObserved h
                                               && h.peer().equals(PEER_A));
        }
    }
}
