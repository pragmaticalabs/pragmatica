package org.pragmatica.aether.node.health;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.TopologyConfig;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMember.MemberState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

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

        var nodeA = new NodeInfo(SELF, NodeAddress.nodeAddress("127.0.0.1", 9001).unwrap(), NodeRole.ACTIVE);
        var nodeB = new NodeInfo(PEER_A, NodeAddress.nodeAddress("127.0.0.2", 9001).unwrap(), NodeRole.ACTIVE);
        var nodeC = new NodeInfo(PEER_B, NodeAddress.nodeAddress("127.0.0.3", 9001).unwrap(), NodeRole.ACTIVE);
        var topologyConfig = new TopologyConfig(SELF, 3, timeSpan(1).seconds(), timeSpan(10).seconds(),
                                                List.of(nodeA, nodeB, nodeC));
        Serializer serializer = Mockito.mock(Serializer.class);
        Deserializer deserializer = Mockito.mock(Deserializer.class);
        detector = CoreSwimHealthDetector.coreSwimHealthDetector(router, topologyConfig, serializer, deserializer);
    }

    @Nested
    class FaultyMember {
        @Test
        void onMemberFaulty_routesDisconnectNode() throws InterruptedException {
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberFaulty(faultyMember);
            Thread.sleep(100); // Allow async dispatch to complete

            assertThat(disconnectNotifications).hasSize(1);
            assertThat(disconnectNotifications.getFirst().nodeId()).isEqualTo(PEER_A);
        }
    }

    @Nested
    class MemberLeft {
        @Test
        void onMemberLeft_routesDisconnectNode() throws InterruptedException {
            detector.onMemberLeft(PEER_B);
            Thread.sleep(100); // Allow async dispatch to complete

            assertThat(disconnectNotifications).hasSize(1);
            assertThat(disconnectNotifications.getFirst().nodeId()).isEqualTo(PEER_B);
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
}
