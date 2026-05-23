/*
 *  Copyright (c) 2020-2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;

/// Regression tests for:
/// - P2: `SwimProtocol.handleAnnounce` must store the announcer at its SWIM port
///   (QUIC port + `SwimConfig.swimPortOffset`), not the raw NodeInfo address port.
/// - P3: `SwimProtocol.healthOf` must fall back to the `members` map when no edge
///   has been emitted yet, so an ALIVE seed is HEALTHY before the first probe-ack.
class SwimPortOffsetAndHealthOfTest {

    private static final int QUIC_PORT = 7100;
    private static final int SWIM_OFFSET = 100;

    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 19999);

    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeAddress NODE_A_ADDR = NodeAddress.nodeAddress("10.0.0.5", QUIC_PORT).unwrap();
    private static final NodeInfo NODE_A_INFO = nodeInfo(NODE_A, NODE_A_ADDR, NodeRole.ACTIVE, Map.of());

    @Nested
    class HandleAnnouncePortOffset {

        private StubTransport transport;
        private RecordingMembershipListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new StubTransport();
            listener = new RecordingMembershipListener();
            var cfg = SwimConfig.DEFAULT.withSwimPortOffset(SWIM_OFFSET);
            protocol = SwimProtocol.swimProtocol(cfg, transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
        }

        @Test
        void handleAnnounce_unknownPeer_storesMemberAtSwimPort() {
            var announce = Announce.announce(NODE_A_INFO, "", 1L);
            protocol.onMessage(new InetSocketAddress(NODE_A_ADDR.host(), QUIC_PORT + SWIM_OFFSET), announce);

            var member = protocol.members().get(NODE_A);
            assertThat(member).as("ANNOUNCE registers the peer").isNotNull();
            assertThat(member.address().getHostString()).isEqualTo(NODE_A_ADDR.host());
            assertThat(member.address().getPort())
                .as("member address port must include the configured swim port offset")
                .isEqualTo(QUIC_PORT + SWIM_OFFSET);
        }

        @Test
        void handleAnnounce_zeroOffset_storesAtQuicPort() {
            // Use a fresh protocol with the default (offset == 0) configuration to
            // verify backwards-compatible behavior.
            var cfg = SwimConfig.DEFAULT;
            var freshTransport = new StubTransport();
            var freshListener = new RecordingMembershipListener();
            var fresh = SwimProtocol.swimProtocol(cfg, freshTransport, freshListener, SELF_ID, SELF_ADDR)
                                    .fold(_ -> null, v -> v);

            var announce = Announce.announce(NODE_A_INFO, "", 1L);
            fresh.onMessage(new InetSocketAddress(NODE_A_ADDR.host(), QUIC_PORT), announce);

            var member = fresh.members().get(NODE_A);
            assertThat(member).isNotNull();
            assertThat(member.address().getPort())
                .as("offset==0 preserves legacy behavior")
                .isEqualTo(QUIC_PORT);
        }
    }

    @Nested
    class HealthOfFallback {

        private StubTransport transport;
        private RecordingMembershipListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new StubTransport();
            listener = new RecordingMembershipListener();
            protocol = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
        }

        @Test
        void healthOf_aliveSeedBeforeAnyEdgeEmit_returnsHealthy() {
            // Reproduces P3 startup-window race: addSeedMember marks the peer ALIVE
            // but does not emit a HealthyObserved edge. The old healthOf returned
            // UNKNOWN, which caused swimHealthGate (QUIC) to reject the peer.
            protocol.addSeedMember(NODE_A, new InetSocketAddress("10.0.0.5", 9100));

            assertThat(protocol.healthOf(NODE_A))
                .as("ALIVE seed must be HEALTHY before the first edge emit")
                .isEqualTo(SwimHealth.HEALTHY);
        }

        @Test
        void healthOf_unknownPeer_returnsUnknown() {
            assertThat(protocol.healthOf(new NodeId("never-seen")))
                .isEqualTo(SwimHealth.UNKNOWN);
        }
    }

    // -- helpers --

    static class StubTransport implements SwimTransport {
        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {return Promise.success(Unit.unit());}
    }

    static class RecordingMembershipListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
