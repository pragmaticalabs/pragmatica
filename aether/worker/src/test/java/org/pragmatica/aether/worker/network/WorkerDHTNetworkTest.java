package org.pragmatica.aether.worker.network;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.pragmatica.aether.worker.governor.GovernorMesh;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.aether.worker.network.WorkerDHTNetwork.workerDHTNetwork;

@ExtendWith(MockitoExtension.class)
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
class WorkerDHTNetworkTest {
    private static final byte[] STUB_PAYLOAD = new byte[]{1, 2, 3};
    private static final NodeId LOCAL_PEER = id("local-peer");
    private static final NodeId REMOTE_PEER = id("remote-peer");
    private static final NodeId UNKNOWN_PEER = id("unknown-peer");
    private static final NodeId GOVERNOR = id("governor-1");
    private static final String REMOTE_COMMUNITY = "community-remote";

    @Mock
    private WorkerNetwork workerNetwork;

    @Mock
    private Serializer serializer;

    private ProtocolMessage testMessage;

    record TestMessage(NodeId sender) implements ProtocolMessage {}

    private static NodeId id(String name) {
        return NodeId.nodeId(name).unwrap();
    }

    @BeforeEach
    void setUp() {
        testMessage = new TestMessage(id("sender"));
        when(workerNetwork.connectedPeers()).thenReturn(List.of(LOCAL_PEER));
    }

    @Nested
    class LocalPeerRouting {

        @Test
        void send_localPeer_routesDirectly() {
            var dhtNetwork = workerDHTNetwork(workerNetwork);

            dhtNetwork.send(LOCAL_PEER, testMessage);

            verify(workerNetwork).send(LOCAL_PEER, testMessage);
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
            when(serializer.encode(any())).thenReturn(STUB_PAYLOAD);
            mesh.registerGovernor(REMOTE_COMMUNITY, GOVERNOR);
            var communityMembers = Map.of(REMOTE_COMMUNITY, List.of(REMOTE_PEER));
            var dhtNetwork = workerDHTNetwork(workerNetwork, mesh, communityMembers,
                                              serializer, () -> "community-local");

            dhtNetwork.send(REMOTE_PEER, testMessage);

            var messageCaptor = ArgumentCaptor.forClass(Object.class);
            verify(workerNetwork).send(eq(GOVERNOR), messageCaptor.capture());

            assertThat(messageCaptor.getValue()).isInstanceOf(DHTRelayMessage.class);
            var relay = (DHTRelayMessage) messageCaptor.getValue();
            assertThat(relay.actualTarget()).isEqualTo(REMOTE_PEER);
            assertThat(relay.serializedPayload()).isEqualTo(STUB_PAYLOAD);
        }

        @Test
        void send_remotePeerNoKnownCommunity_fallsBackToDirectSend() {
            var communityMembers = Map.<String, List<NodeId>>of();
            var dhtNetwork = workerDHTNetwork(workerNetwork, mesh, communityMembers,
                                              serializer, () -> "community-local");

            dhtNetwork.send(UNKNOWN_PEER, testMessage);

            verify(workerNetwork).send(UNKNOWN_PEER, testMessage);
        }

        @Test
        void send_remotePeerNoGovernor_fallsBackToDirectSend() {
            var communityMembers = Map.of(REMOTE_COMMUNITY, List.of(REMOTE_PEER));
            var dhtNetwork = workerDHTNetwork(workerNetwork, mesh, communityMembers,
                                              serializer, () -> "community-local");

            dhtNetwork.send(REMOTE_PEER, testMessage);

            verify(workerNetwork).send(REMOTE_PEER, testMessage);
        }
    }

    @Nested
    class NoMeshConfigured {

        @Test
        void send_noMeshConfigured_routesDirectly() {
            var dhtNetwork = workerDHTNetwork(workerNetwork);

            dhtNetwork.send(REMOTE_PEER, testMessage);

            verify(workerNetwork).send(REMOTE_PEER, testMessage);
        }
    }
}
