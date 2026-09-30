// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.PiggybackBuffer;
import org.pragmatica.swim.SwimConfig;
import org.pragmatica.swim.SwimMember;
import org.pragmatica.swim.SwimMembershipListener;
import org.pragmatica.swim.SwimProtocol;
import org.pragmatica.swim.SwimTransport;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/// The labels a gossiped `MembershipUpdate` carries survive the production codec registry — the same
/// registry that encodes SWIM datagrams — for every carrier (`Ping`, `Ack`) and for both an empty and
/// a populated map, at every position of a multi-update piggyback.
class SwimMessageLabelsCodecTest {
    private static final NodeId FROM = new NodeId("node-from");
    private static final Map<String, String> CORE_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                   NodeInfo.LABEL_SOURCE, "replacement");
    /// Decoded addresses are unresolved (`host/<unresolved>:port`), so the expected value is built the same way.
    private static final InetSocketAddress ADDRESS = InetSocketAddress.createUnresolved("127.0.0.1", 9500);

    private final SliceCodec codec = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());

    @Test
    void membershipUpdate_withLabels_roundTripsLabelsThroughProductionRegistry() {
        var update = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.ALIVE, 7L, ADDRESS, 42L, CORE_LABELS);

        MembershipUpdate decoded = codec.decode(codec.encode(update));

        assertThat(decoded).isEqualTo(update);
        assertThat(decoded.labels()).isEqualTo(CORE_LABELS);
    }

    @Test
    void membershipUpdate_withoutLabels_roundTripsEmptyLabels() {
        var update = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.SUSPECT, 3L, ADDRESS);

        MembershipUpdate decoded = codec.decode(codec.encode(update));

        assertThat(decoded).isEqualTo(update);
        assertThat(decoded.labels()).isEmpty();
    }

    @Test
    void ping_piggybackMixingLabelledAndUnlabelledUpdates_keepsEachUpdatesLabels() {
        var labelled = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.ALIVE, 1L, ADDRESS, 5L, CORE_LABELS);
        var blank = MembershipUpdate.membershipUpdate(new NodeId("core-s"), MemberState.ALIVE, 2L, ADDRESS);
        var worker = MembershipUpdate.membershipUpdate(new NodeId("worker-w"),
                                                       MemberState.ALIVE,
                                                       3L,
                                                       ADDRESS,
                                                       6L,
                                                       Map.of(NodeInfo.LABEL_ROLE, "worker"));
        var ping = Ping.ping(FROM, 11L, List.of(labelled, blank, worker));

        Ping decoded = codec.decode(codec.encode((SwimMessage) ping));

        assertThat(decoded).isEqualTo(ping);
        assertThat(decoded.piggyback()).extracting(MembershipUpdate::labels)
                                       .containsExactly(CORE_LABELS, Map.of(), Map.of(NodeInfo.LABEL_ROLE, "worker"));
    }

    @Test
    void ack_piggybackWithLabels_roundTripsLabels() {
        var labelled = MembershipUpdate.membershipUpdate(new NodeId("core-r"), MemberState.ALIVE, 1L, ADDRESS, 5L, CORE_LABELS);
        var ack = Ack.ack(FROM, 12L, List.of(labelled));

        Ack decoded = codec.decode(codec.encode((SwimMessage) ack));

        assertThat(decoded).isEqualTo(ack);
        assertThat(decoded.piggyback().getFirst().labels()).isEqualTo(CORE_LABELS);
    }

    /// The size estimate that packs piggybacks must never UNDER-state what the production codec writes, or the
    /// budget is fiction. Checked for update shapes from bare to heavily labelled.
    @Test
    void estimatedBytes_neverUnderstatesTheEncodedUpdate() {
        var shapes = List.of(Map.<String, String>of(),
                             CORE_LABELS,
                             REALISTIC_LABELS,
                             Map.of(NodeInfo.LABEL_ROLE, "\u00e9\u00e9\u00e9\u00e9", "k", "v"));

        for (var labels : shapes) {
            var update = MembershipUpdate.membershipUpdate(new NodeId("aether-test-cluster-node-01m3rc2gnr6bnvmnmc7rs0rcmb"),
                                                           MemberState.ALIVE,
                                                           123456789L,
                                                           ADDRESS,
                                                           987654321L,
                                                           labels);
            byte[] encoded = codec.encode(update);

            assertThat(PiggybackBuffer.estimatedBytes(update)).as("labels %s", labels).isGreaterThanOrEqualTo(encoded.length);
        }
    }

    /// A Ping packed from a large labelled backlog fits one datagram once AES-GCM framing (32 B) is added, and
    /// the same 12 updates packed WITHOUT the budget do not (the 2476 B case v1757 measured).
    @Test
    void packedPing_fromLargeLabelledBacklog_fitsTheDatagramBudget() {
        var buffer = PiggybackBuffer.piggybackBuffer(64);

        for (int i = 0; i < 40; i++) {
            buffer.addUpdate(MembershipUpdate.membershipUpdate(new NodeId("aether-test-cluster-node-01m3rc2gnr6bnvmnmc7rs0r" + i),
                                                               MemberState.ALIVE,
                                                               1L,
                                                               ADDRESS,
                                                               5L,
                                                               REALISTIC_LABELS));
        }

        byte[] unbudgeted = codec.encode((SwimMessage) Ping.ping(FROM, 1L, buffer.peekUpdates(12)));
        byte[] budgeted = codec.encode((SwimMessage) Ping.ping(FROM, 2L, buffer.peekUpdates(12, PiggybackBuffer.piggybackBudgetFor(FROM))));

        assertThat(unbudgeted.length + 32).as("12 realistic labelled updates without a budget").isGreaterThan(PiggybackBuffer.MAX_DATAGRAM_BYTES);
        assertThat(budgeted.length + 32).as("budgeted Ping incl. AES-GCM framing").isLessThanOrEqualTo(PiggybackBuffer.MAX_DATAGRAM_BYTES);
    }

    /// The whole chain on the wire: a real protocol whose members carry huge label maps (a hostname of 1856-3000
    /// characters is the band where ANNOUNCE got through but every self-update Ping was dropped), with sender ids of
    /// 60 and 600 characters. Whatever it gossips, the ENCODED Ack plus AES-GCM framing stays under the datagram
    /// ceiling, and the role still propagates, because gossip carries only role and source and the packer budgets
    /// from the sender's actual id length.
    @Test
    void ack_withHugeLabelMapsAndLongSenderIds_neverExceedsTheDatagramCeiling_andRoleStillPropagates() {
        for (var idLength : List.of(60, 200, 600)) {
            for (var hostnameLength : List.of(1856, 1870, 1879, 3072)) {
                var transport = new CapturingTransport();
                var selfId = new NodeId("s".repeat(idLength));
                var protocol = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, transport, new NullListener(), selfId, ADDRESS)
                                           .unwrap();
                var hugeLabels = Map.of(NodeInfo.LABEL_ROLE, "core",
                                        NodeInfo.LABEL_SOURCE, "replacement",
                                        NodeInfo.LABEL_HOSTNAME, "h".repeat(hostnameLength));

                for (int i = 0; i < 30; i++) {
                    var subject = MembershipUpdate.membershipUpdate(new NodeId("aether-test-cluster-node-01m3rc2gnr6bnvmnmc7rs0r" + i),
                                                                    MemberState.ALIVE,
                                                                    0L,
                                                                    ADDRESS,
                                                                    0L,
                                                                    hugeLabels);
                    protocol.onMessage(ADDRESS, new Ping(FROM, 1L, List.of(subject)));
                }

                for (int round = 0; round < 40; round++) {
                    transport.sent.clear();
                    protocol.onMessage(ADDRESS, new Ping(FROM, 100L + round, List.of()));
                    var ack = transport.sent.stream().filter(Ack.class::isInstance).map(Ack.class::cast).findFirst().orElseThrow();
                    byte[] encoded = codec.encode((SwimMessage) ack);

                    assertThat(encoded.length + 32).as("id %d chars, hostname %d chars, round %d", idLength, hostnameLength, round)
                                                   .isLessThanOrEqualTo(PiggybackBuffer.MAX_DATAGRAM_BYTES);
                    assertThat(ack.piggyback()).allSatisfy(update -> assertThat(update.labels()).containsEntry(NodeInfo.LABEL_ROLE, "core")
                                                                                                .doesNotContainKey(NodeInfo.LABEL_HOSTNAME));
                }
            }
        }
    }

    /// The same, for the node's OWN steady-state gossip: its self-ALIVE carries role/source of a node whose full
    /// label map has a 1870-character hostname, from a 600-character id.
    @Test
    void selfAlive_withHugeHostnameAndLongId_fitsTheDatagramCeiling() {
        var transport = new CapturingTransport();
        var selfId = new NodeId("s".repeat(600));
        var protocol = SwimProtocol.swimProtocol(SwimConfig.swimConfig(TimeSpan.timeSpan(100).millis(), TimeSpan.timeSpan(80).millis(), 3, TimeSpan.timeSpan(5).seconds(), 8, TimeSpan.timeSpan(40).millis())
                                                                      .withJoinGrace(TimeSpan.timeSpan(0).millis()),
                                                 transport,
                                                 new NullListener(),
                                                 selfId,
                                                 ADDRESS)
                                   .unwrap();
        var info = NodeInfo.nodeInfo(selfId,
                                     NodeAddress.nodeAddress("127.0.0.1", 9500).unwrap(),
                                     Map.of(NodeInfo.LABEL_ROLE, "core", NodeInfo.LABEL_HOSTNAME, "h".repeat(1870)));

        protocol.start();
        protocol.announceJoin(info, "c", 1L, 5L, List.of());
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                transport.sent.clear();
                protocol.onMessage(ADDRESS, new Ping(FROM, 7L, List.of()));
                var ack = transport.sent.stream().filter(Ack.class::isInstance).map(Ack.class::cast).findFirst().orElseThrow();
                byte[] encoded = codec.encode((SwimMessage) ack);

                assertThat(ack.piggyback()).anySatisfy(update -> assertThat(update.labels()).containsEntry(NodeInfo.LABEL_ROLE, "core"));
                assertThat(encoded.length + 32).isLessThanOrEqualTo(PiggybackBuffer.MAX_DATAGRAM_BYTES);
            });
        } finally {
            protocol.stop();
        }
    }

    private static final class CapturingTransport implements SwimTransport {
        final List<SwimMessage> sent = new CopyOnWriteArrayList<>();

        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {sent.add(message); return Promise.success(Unit.unit());}
        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}
    }

    private static final class NullListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }

    private static final Map<String, String> REALISTIC_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                        NodeInfo.LABEL_SOURCE, "replacement",
                                                                        NodeInfo.LABEL_HOSTNAME, "aether-prod-eu-core-0123456789",
                                                                        NodeInfo.LABEL_ZONE, "fsn1-dc14",
                                                                        NodeInfo.LABEL_INSTANCE_TYPE, "ccx33",
                                                                        NodeInfo.LABEL_POOL, "core-pool-primary");
}
