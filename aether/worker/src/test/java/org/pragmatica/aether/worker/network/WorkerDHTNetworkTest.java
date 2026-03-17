package org.pragmatica.aether.worker.network;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.MessageRouter.DelegateRouter;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.worker.network.WorkerDHTNetwork.workerDHTNetwork;

@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
class WorkerDHTNetworkTest {
    private static final byte[] STUB_PAYLOAD = new byte[]{1, 2, 3};
    private static final NodeId LOCAL_PEER = id("local-peer");
    private static final NodeId REMOTE_PEER = id("remote-peer");
    private static final NodeId UNKNOWN_PEER = id("unknown-peer");
    private static final NodeId GOVERNOR = id("governor-1");
    private static final String REMOTE_COMMUNITY = "community-remote";

    private final Serializer serializer = new StubSerializer();
    private DelegateRouter delegateRouter;
    private List<Message> routedMessages;
    private ProtocolMessage testMessage;

    record TestMessage(NodeId sender) implements ProtocolMessage {}

    private static NodeId id(String name) {
        return NodeId.nodeId(name).unwrap();
    }

    @BeforeEach
    void setUp() {
        testMessage = new TestMessage(id("sender"));
        routedMessages = new ArrayList<>();
        delegateRouter = DelegateRouter.delegate();
        var mutableRouter = MessageRouter.mutable();
        mutableRouter.addRoute(NetworkServiceMessage.Send.class, routedMessages::add);
        delegateRouter.replaceDelegate(mutableRouter);
    }

    @Nested
    class LocalPeerRouting {

        @Test
        void send_localPeer_routesDirectlyViaSend() {
            var dhtNetwork = workerDHTNetwork(delegateRouter, () -> Set.of(LOCAL_PEER));

            dhtNetwork.send(LOCAL_PEER, testMessage);

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Send.class);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(LOCAL_PEER);
        }
    }

    @Nested
    class CrossCommunityRouting {
        private GovernorMesh mesh;

        @BeforeEach
        void setUp() {
            mesh = GovernorMesh.governorMesh();
        }

        @Test
        void send_remotePeerInKnownCommunity_relaysViaGovernor() {
            mesh.registerGovernor(REMOTE_COMMUNITY, GOVERNOR);
            var communityMembers = Map.of(REMOTE_COMMUNITY, List.of(REMOTE_PEER));
            var dhtNetwork = workerDHTNetwork(delegateRouter, Set::of, mesh, communityMembers,
                                              serializer, () -> "community-local");

            dhtNetwork.send(REMOTE_PEER, testMessage);

            assertThat(routedMessages).hasSize(1);
            assertThat(routedMessages.getFirst()).isInstanceOf(NetworkServiceMessage.Send.class);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(GOVERNOR);
            assertThat(send.payload()).isInstanceOf(DHTRelayMessage.class);
            var relay = (DHTRelayMessage) send.payload();
            assertThat(relay.actualTarget()).isEqualTo(REMOTE_PEER);
            assertThat(relay.serializedPayload()).isEqualTo(STUB_PAYLOAD);
        }

        @Test
        void send_remotePeerNoKnownCommunity_fallsBackToDirectSend() {
            var communityMembers = Map.<String, List<NodeId>>of();
            var dhtNetwork = workerDHTNetwork(delegateRouter, Set::of, mesh, communityMembers,
                                              serializer, () -> "community-local");

            dhtNetwork.send(UNKNOWN_PEER, testMessage);

            assertThat(routedMessages).hasSize(1);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(UNKNOWN_PEER);
        }

        @Test
        void send_remotePeerNoGovernor_fallsBackToDirectSend() {
            var communityMembers = Map.of(REMOTE_COMMUNITY, List.of(REMOTE_PEER));
            var dhtNetwork = workerDHTNetwork(delegateRouter, Set::of, mesh, communityMembers,
                                              serializer, () -> "community-local");

            dhtNetwork.send(REMOTE_PEER, testMessage);

            assertThat(routedMessages).hasSize(1);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(REMOTE_PEER);
        }
    }

    @Nested
    class NoMeshConfigured {

        @Test
        void send_noMeshConfigured_routesDirectly() {
            var dhtNetwork = workerDHTNetwork(delegateRouter, Set::of);

            dhtNetwork.send(REMOTE_PEER, testMessage);

            assertThat(routedMessages).hasSize(1);
            var send = (NetworkServiceMessage.Send) routedMessages.getFirst();
            assertThat(send.target()).isEqualTo(REMOTE_PEER);
        }
    }

    @SuppressWarnings("JBCT-STY-05")
    static class StubSerializer implements Serializer {
        @Override
        public <T> byte[] encode(T object) { return STUB_PAYLOAD; }

        @Override
        public <T> void write(ByteBuf byteBuf, T object) {}
    }
}
