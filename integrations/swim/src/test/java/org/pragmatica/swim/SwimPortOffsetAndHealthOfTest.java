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
    private static final NodeInfo NODE_A_INFO = nodeInfo(NODE_A, NODE_A_ADDR, Map.of());

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

    /// FIX B: the SWIM probe address for an announced member is taken from the ANNOUNCE
    /// SOURCE IP (asymmetry-proof, kernel-resolved) combined with the SWIM port, falling
    /// back to the gossiped hostname when no source IP is available.
    @Nested
    class HandleAnnounceProbeAddressFromSourceIp {

        private static final NodeId NODE_B = new NodeId("node-b");
        // Advertised (gossiped) host differs from the datagram's source IP — the
        // asymmetry FIX B resolves. Source IP must win.
        private static final NodeAddress NODE_B_ADV = NodeAddress.nodeAddress("advertised-host.invalid", QUIC_PORT).unwrap();
        private static final NodeInfo NODE_B_INFO = nodeInfo(NODE_B, NODE_B_ADV, Map.of());

        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            var cfg = SwimConfig.DEFAULT.withSwimPortOffset(SWIM_OFFSET);
            protocol = SwimProtocol.swimProtocol(cfg, new StubTransport(), new RecordingMembershipListener(), SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
        }

        @Test
        void handleAnnounce_sourceIpPresent_probeAddressUsesSourceIpNotGossipedHost() {
            // Datagram physically arrives from 10.0.0.9 (resolved), gossiped host is
            // advertised-host.invalid. The probe address must use the source IP.
            var sender = new InetSocketAddress("10.0.0.9", QUIC_PORT + SWIM_OFFSET);
            protocol.onMessage(sender, Announce.announce(NODE_B_INFO, "", 1L));

            var member = protocol.members().get(NODE_B);
            assertThat(member).as("ANNOUNCE registers the peer").isNotNull();
            assertThat(member.address().getAddress().getHostAddress())
                .as("probe address must use the ANNOUNCE source IP, not the gossiped hostname")
                .isEqualTo("10.0.0.9");
            assertThat(member.address().getPort())
                .as("probe address port keeps the swim port offset derivation")
                .isEqualTo(QUIC_PORT + SWIM_OFFSET);
        }

        @Test
        void handleAnnounce_sourceIpAbsent_probeAddressFallsBackToGossipedHost() {
            // An unresolved sender (no kernel-resolved IP) forces the fallback to the
            // gossiped hostname (cold-boot static seeds / NAT) — resolution is never
            // mandatory.
            var unresolved = InetSocketAddress.createUnresolved("ignored-host", QUIC_PORT + SWIM_OFFSET);
            protocol.onMessage(unresolved, Announce.announce(NODE_B_INFO, "", 1L));

            var member = protocol.members().get(NODE_B);
            assertThat(member).as("ANNOUNCE registers the peer").isNotNull();
            assertThat(member.address().getHostString())
                .as("with no source IP the probe address falls back to the gossiped host")
                .isEqualTo("advertised-host.invalid");
            assertThat(member.address().getPort())
                .isEqualTo(QUIC_PORT + SWIM_OFFSET);
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
        void healthOf_observedSeedBeforeAnyEdgeEmit_returnsUnknown() {
            // OBSERVED birth state (#336/#241): addSeedMember introduces the peer as OBSERVED
            // (known + probe-eligible, not yet alive) — a channel re-seed is not reachability
            // proof. With no HealthyObserved edge emitted yet, healthOf falls back to the live
            // members map, where a non-ALIVE member maps to UNKNOWN. The swimHealthGate (QUIC)
            // then awaits a real probe-ack before treating the peer as healthy.
            protocol.addSeedMember(NODE_A, new InetSocketAddress("10.0.0.5", 9100));

            assertThat(protocol.healthOf(NODE_A))
                .as("OBSERVED seed must be UNKNOWN before the first edge emit")
                .isEqualTo(SwimHealth.UNKNOWN);
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
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
