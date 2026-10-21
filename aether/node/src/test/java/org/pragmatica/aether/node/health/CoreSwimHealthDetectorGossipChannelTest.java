// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node.health;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.pragmatica.aether.metrics.observation.PeerObservationStore;
import org.pragmatica.aether.node.health.fsm.SwimHealthEvents;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.GossipChannel;
import org.pragmatica.swim.GossipEncryptor;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.AppGossip;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// A gossip channel registered on a detector whose protocol is already running reaches SWIM at once. (A channel
/// registered BEFORE the protocol exists is buffered and applied where the detector builds the protocol; that path is
/// covered end to end by the Ember prefetch test, since a protocol handed in from outside never goes through it.)
class CoreSwimHealthDetectorGossipChannelTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final NodeId PEER = new NodeId("node-2");
    private static final InetSocketAddress PEER_ADDRESS = new InetSocketAddress("127.0.0.2", 9002);
    private static final int CHANNEL = 5;

    private final List<byte[]> delivered = new CopyOnWriteArrayList<>();
    private CoreSwimHealthDetector detector;
    private SwimProtocol swim;

    private final GossipChannel channel = new GossipChannel() {
        @Override
        public Option<byte[]> outgoing(int budgetBytes) {
            return Option.none();
        }

        @Override
        public void incoming(NodeId from, byte[] payload) {
            delivered.add(payload);
        }
    };

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();
        var topology = new TopologyConfig(SELF,
                                          3,
                                          timeSpan(1).seconds(),
                                          timeSpan(10).seconds(),
                                          List.of(NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap()),
                                                  NodeInfo.nodeInfo(PEER, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap())));

        detector = CoreSwimHealthDetector.coreSwimHealthDetector(router,
                                                                 topology,
                                                                 Mockito.mock(Serializer.class),
                                                                 Mockito.mock(Deserializer.class),
                                                                 () -> Epoch.epoch(0L, 7L, 3L),
                                                                 () -> true,
                                                                 PeerObservationStore.peerObservationStore());
        swim = SwimProtocol.swimProtocol(SwimConfig.DEFAULT,
                                         new StubTransport(),
                                         noopListener(),
                                         SELF,
                                         new InetSocketAddress("127.0.0.1", 9101))
                           .unwrap();
        swim.addSeedMember(PEER, PEER_ADDRESS);
    }

    private void start() {
        var context = detector.contextForTest();

        context.dispatch(new SwimHealthEvents.StartRequested());
        context.dispatch(new SwimHealthEvents.ProtocolReady(swim, new StubTransport(), GossipEncryptor.none()));
    }

    private void peerSays(byte value) {
        swim.onMessage(PEER_ADDRESS, AppGossip.appGossip(PEER, CHANNEL, new byte[]{value}));
    }

    @Test
    void registerGossipChannel_afterTheProtocolIsRunning_reachesIt() {
        start();
        detector.registerGossipChannel(CHANNEL, channel);
        peerSays((byte) 2);

        assertThat(delivered).hasSize(1);
    }

    @Test
    void withNoChannelRegistered_aGossipDatagramIsDropped() {
        start();
        peerSays((byte) 3);

        assertThat(delivered).isEmpty();
    }

    private static SwimMembershipListener noopListener() {
        return new SwimMembershipListener() {
            @Override
            public void onMemberJoined(SwimMember member) {}

            @Override
            public void onMemberSuspect(SwimMember member) {}

            @Override
            public void onMemberFaulty(SwimMember member, boolean firstHand) {}

            @Override
            public void onMemberLeft(NodeId nodeId) {}
        };
    }

    private static final class StubTransport implements SwimTransport {
        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }
    }
}
