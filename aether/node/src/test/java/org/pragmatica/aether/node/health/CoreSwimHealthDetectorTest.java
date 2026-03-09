package org.pragmatica.aether.node.health;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
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

    private final List<TopologyChangeNotification.NodeRemoved> removedNotifications = new ArrayList<>();
    private CoreSwimHealthDetector detector;

    @BeforeEach
    void setUp() {
        removedNotifications.clear();
        var router = MessageRouter.mutable();
        router.addRoute(TopologyChangeNotification.NodeRemoved.class, removedNotifications::add);

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
        void onMemberFaulty_routesNodeRemoved() {
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberFaulty(faultyMember);

            assertThat(removedNotifications).hasSize(1);
            assertThat(removedNotifications.getFirst().nodeId()).isEqualTo(PEER_A);
        }
    }

    @Nested
    class MemberLeft {
        @Test
        void onMemberLeft_routesNodeRemoved() {
            detector.onMemberLeft(PEER_B);

            assertThat(removedNotifications).hasSize(1);
            assertThat(removedNotifications.getFirst().nodeId()).isEqualTo(PEER_B);
        }
    }

    @Nested
    class JoinedAndSuspect {
        @Test
        void onMemberJoined_doesNotRoute() {
            var member = SwimMember.swimMember(PEER_A, new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberJoined(member);

            assertThat(removedNotifications).isEmpty();
        }

        @Test
        void onMemberSuspect_doesNotRoute() {
            var member = SwimMember.swimMember(PEER_A, MemberState.SUSPECT, 0,
                                               new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberSuspect(member);

            assertThat(removedNotifications).isEmpty();
        }
    }

    @Nested
    class TopologyTracking {
        @Test
        void onMemberFaulty_removesFromTopology() {
            var faultyMember = SwimMember.swimMember(PEER_A, MemberState.FAULTY, 0,
                                                      new InetSocketAddress("127.0.0.2", 9002));

            detector.onMemberFaulty(faultyMember);

            var notification = removedNotifications.getFirst();
            assertThat(notification.topology()).doesNotContain(PEER_A);
            assertThat(notification.topology()).contains(SELF, PEER_B);
        }
    }
}
