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
 *
 */

package org.pragmatica.swim;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTombstoneTest.RecordingTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #3028 PR C, design §3.4 (S1): a node latched self-isolated holds every peer FAULTY and so probes nobody. Each probe
/// round it sends one Ping to one FAULTY peer, least-recently-probed first; a lost isolation ping raises no LHM score
/// and sends no PingReq (D4); a peer holding the node dead answers with its death notice, which the node refutes.
/// Design behaviour (CTO-accepted): a verified isolation Ack does not unlatch by itself, because this node's own death
/// record for the peer refuses the peer's ack; the latch clears through the notice exchange, so probing continues while
/// latched. With every peer swept, the node asks one seed for the view (D4b).
class SwimIsolationProbeTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final NodeInfo INFO_SELF = NodeInfo.nodeInfo(SELF_ID, new NodeAddress("127.0.0.1", 9000));
    private static final long SELF_TOKEN = 0x5E1FL;
    private static final long TOKEN_A = 0x3028AL;

    private AddressedTransport transport;
    private SwimProtocol protocol;
    private SwimProtocol holder;

    record Sent(InetSocketAddress target, SwimMessage message) {}

    /// Records the target of every datagram, so a test can tell which peer a Ping went to.
    static class AddressedTransport implements SwimTransport {
        final CopyOnWriteArrayList<Sent> sent = new CopyOnWriteArrayList<>();

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sent.add(new Sent(target, message));
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }

        List<InetSocketAddress> pingTargets() {
            return sent.stream()
                       .filter(entry -> entry.message() instanceof Ping)
                       .map(Sent::target)
                       .toList();
        }

        boolean anyPingNamingARequester() {
            return sent.stream()
                       .map(Sent::message)
                       .filter(Ping.class::isInstance)
                       .map(Ping.class::cast)
                       .anyMatch(ping -> ping.onBehalfOf().isPresent());
        }

        boolean anyPingReq() {
            return sent.stream().anyMatch(entry -> entry.message() instanceof PingReq);
        }

        Ping firstPingTo(InetSocketAddress target) {
            return sent.stream()
                       .filter(entry -> entry.target().equals(target) && entry.message() instanceof Ping)
                       .map(entry -> (Ping) entry.message())
                       .findFirst()
                       .orElseThrow();
        }
    }

    private static SwimConfig config() {
        return swimConfig(timeSpan(20).millis(),
                          timeSpan(20).millis(),
                          3,
                          timeSpan(100).millis(),
                          8,
                          timeSpan(20).millis()).withJoinGrace(timeSpan(0).millis());
    }

    @BeforeEach
    void setUp() {
        transport = new AddressedTransport();
        protocol = SwimProtocol.swimProtocol(config(), transport, new RecordingListener(), SELF_ID, SELF_ADDR, () -> false)
                               .unwrap();
        protocol.setBootTokens(BootTokens.bootTokens(SELF_TOKEN));
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.FAULTY);
        protocol.putMemberForTest(NODE_B, ADDR_B, MemberState.FAULTY);
    }

    @AfterEach
    void tearDown() {
        protocol.stop();
        if (holder != null) {
            holder.stop();
        }
    }

    @Test
    void isolationProbe_latched_onePingPerRoundToTheFaultyPeers_roundRobin() {
        assertThat(protocol.latchSelfIsolation(() -> true)).as("precondition: latched").isTrue();

        protocol.probeRoundForTest();
        protocol.probeRoundForTest();
        protocol.probeRoundForTest();

        assertThat(transport.pingTargets()).as("one Ping per round, least-recently-probed FAULTY peer first")
                                           .containsExactly(ADDR_A, ADDR_B, ADDR_A);
    }

    @Test
    void isolationProbe_directPing_namesNoRequester() {
        protocol.latchSelfIsolation(() -> true);

        protocol.probeRoundForTest();

        assertThat(transport.pingTargets()).as("precondition: the isolation ping went out").containsExactly(ADDR_A);
        assertThat(transport.anyPingNamingARequester()).as("a direct ping is nobody's relay").isFalse();
    }

    @Test
    void isolationProbe_notLatched_noPingToAFaultyPeer() {
        protocol.probeRoundForTest();

        assertThat(transport.pingTargets()).as("an unlatched node never pings a FAULTY peer").isEmpty();
    }

    @Test
    void isolationProbe_lostPing_raisesNoLhmAndSendsNoPingReq() {
        protocol.latchSelfIsolation(() -> true);

        protocol.probeRoundForTest();
        await().pollDelay(300, TimeUnit.MILLISECONDS).atMost(Duration.ofSeconds(1)).until(() -> true);

        assertThat(transport.pingTargets()).as("precondition: the isolation ping went out").containsExactly(ADDR_A);
        assertThat(protocol.lhmScoreForTest()).as("a lost isolation ping is not local slowness (D4)").isZero();
        assertThat(transport.anyPingReq()).as("no indirect probe for an isolation ping").isFalse();
        assertThat(protocol.members().get(NODE_A).state()).as("the peer is not re-escalated").isEqualTo(MemberState.FAULTY);
    }

    /// The latched node's isolation ping carries its self-Alive; NODE_A holds it dead at incarnation 1 with the same
    /// token, answers with the death notice, and the node refutes to incarnation 2.
    @Test
    void isolationProbe_peerHoldingTheNodeDead_answersWithNotice_nodeRefutes() {
        protocol.announceJoin(INFO_SELF, "", 1L, SELF_TOKEN, List.of());
        protocol.latchSelfIsolation(() -> true);
        var holderTransport = new RecordingTransport();
        holder = SwimProtocol.swimProtocol(config(), holderTransport, new RecordingListener(), NODE_A, ADDR_A, () -> false)
                             .unwrap();
        holder.setBootTokens(BootTokens.bootTokens(TOKEN_A));
        holder.putDeathRecordForTest(SELF_ID, 1L, SELF_TOKEN, System.currentTimeMillis());

        protocol.probeRoundForTest();
        var isolationPing = transport.firstPingTo(ADDR_A);
        holder.onMessage(SELF_ADDR, isolationPing);
        var ack = holderTransport.sentMessages.stream()
                                              .filter(Ack.class::isInstance)
                                              .map(Ack.class::cast)
                                              .filter(sent -> sent.sequence() == isolationPing.sequence())
                                              .findFirst()
                                              .orElseThrow();
        protocol.onMessage(ADDR_A, ack);

        assertThat(protocol.selfIncarnation()).as("the notice in the isolation ping's Ack is refuted").isEqualTo(2L);
    }

    @Test
    void isolationProbe_stillLatchedAfterAPeerAck_probingContinues() {
        protocol.latchSelfIsolation(() -> true);

        protocol.probeRoundForTest();
        protocol.onMessage(ADDR_A, Ack.ack(NODE_A, transport.firstPingTo(ADDR_A).sequence(), List.of()));
        protocol.probeRoundForTest();

        assertThat(protocol.selfIsolatedForTest()).as("an isolation Ack alone does not unlatch (design behaviour)").isTrue();
        assertThat(transport.pingTargets()).as("probing continues while latched, round-robin on").containsExactly(ADDR_A, ADDR_B);
    }

    @Test
    void isolationProbe_allPeersSwept_announcesToASeed() {
        var seed = new InetSocketAddress("127.0.0.1", 9100);

        protocol.announceJoin(INFO_SELF, "c", 1L, SELF_TOKEN, List.of(seed));
        protocol.joinAcknowledgedForTest();
        protocol.latchSelfIsolation(() -> true);
        protocol.removeMemberForTest(NODE_A);
        protocol.removeMemberForTest(NODE_B);
        transport.sent.clear();

        protocol.probeRoundForTest();

        await().atMost(Duration.ofSeconds(2))
               .untilAsserted(() -> assertThat(transport.sent).as("one ANNOUNCE to the seed, after a jitter below the period")
                                                              .anyMatch(entry -> entry.target().equals(seed)
                                                                                 && entry.message() instanceof SwimMessage.Announce));
    }

    @Test
    void isolationProbe_allPeersSwept_seedsRegisteredOnlyViaAddSeedMember_announcesToASeed() {
        var seed = new InetSocketAddress("127.0.0.1", 9101);
        var seedNode = new NodeId("seed-only");

        protocol.announceJoin(INFO_SELF, "c", 1L, SELF_TOKEN, List.of());
        protocol.addSeedMember(seedNode, seed);
        protocol.joinAcknowledgedForTest();
        protocol.latchSelfIsolation(() -> true);
        protocol.removeMemberForTest(NODE_A);
        protocol.removeMemberForTest(NODE_B);
        protocol.removeMemberForTest(seedNode);
        transport.sent.clear();

        protocol.probeRoundForTest();

        await().atMost(Duration.ofSeconds(2))
               .untilAsserted(() -> assertThat(transport.sent).as("production registers seeds via addSeedMember only; the swept seed is still asked")
                                                              .anyMatch(entry -> entry.target().equals(seed)
                                                                                 && entry.message() instanceof SwimMessage.Announce));
    }

    @Test
    void isolationProbe_peersRemain_noAnnounceToASeed() {
        var seed = new InetSocketAddress("127.0.0.1", 9100);

        protocol.announceJoin(INFO_SELF, "c", 1L, SELF_TOKEN, List.of(seed));
        protocol.joinAcknowledgedForTest();
        protocol.latchSelfIsolation(() -> true);
        transport.sent.clear();

        protocol.probeRoundForTest();
        await().pollDelay(300, TimeUnit.MILLISECONDS).atMost(Duration.ofSeconds(1)).until(() -> true);

        assertThat(transport.sent).as("a node that still holds peers pings them and does not announce")
                                  .noneMatch(entry -> entry.message() instanceof SwimMessage.Announce && entry.target().equals(seed));
    }

    @Test
    void isolationProbe_onlyAnUnprobableNonFaultyPeerHeld_noAnnounceToASeed() {
        var seed = new InetSocketAddress("127.0.0.1", 9100);

        protocol.announceJoin(INFO_SELF, "c", 1L, SELF_TOKEN, List.of(seed));
        protocol.joinAcknowledgedForTest();
        protocol.latchSelfIsolation(() -> true);
        protocol.removeMemberForTest(NODE_A);
        protocol.removeMemberForTest(NODE_B);
        protocol.putMemberForTest(NODE_A, ADDR_A, MemberState.UNKNOWN);
        transport.sent.clear();

        protocol.probeRoundForTest();
        await().pollDelay(300, TimeUnit.MILLISECONDS).atMost(Duration.ofSeconds(1)).until(() -> true);

        assertThat(transport.sent).as("a node that still holds a peer is not alone, so it does not announce")
                                  .noneMatch(entry -> entry.message() instanceof SwimMessage.Announce && entry.target().equals(seed));
    }
}
