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
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;
import org.pragmatica.swim.SwimProtocolTest.SentMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// A SWIM datagram must stay under [PiggybackBuffer#MAX_DATAGRAM_BYTES]: the receiver's buffer is 2048 B and
/// anything longer is truncated and silently lost (a 12-update Ping with realistic labels measured 2476 B).
/// Piggyback packing is therefore size-aware: a round carries as many updates as fit the budget, and the
/// rest go in later rounds — delayed, never dropped.
class SwimDatagramBudgetTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId ASKER = new NodeId("node-asker");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9700);
    private static final InetSocketAddress ASKER_ADDR = new InetSocketAddress("127.0.0.1", 9701);
    private static final int SUBJECTS = 40;
    /// The label set a real node carries (role, source, hostname, zone, instance type, pool).
    private static final Map<String, String> REALISTIC_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                        NodeInfo.LABEL_SOURCE, "replacement",
                                                                        NodeInfo.LABEL_HOSTNAME, "aether-prod-eu-core-0123456789",
                                                                        NodeInfo.LABEL_ZONE, "fsn1-dc14",
                                                                        NodeInfo.LABEL_INSTANCE_TYPE, "ccx33",
                                                                        NodeInfo.LABEL_POOL, "core-pool-primary");

    private RecordingTransport transport;
    private SwimProtocol protocol;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        protocol = SwimProtocol.swimProtocol(swimConfig(timeSpan(1).seconds(), timeSpan(500).millis(), 3, timeSpan(5).seconds(), 64, timeSpan(1).seconds()),
                                             transport,
                                             new RecordingListener(),
                                             SELF_ID,
                                             SELF_ADDR)
                               .unwrap();
        IntStream.range(0, SUBJECTS).forEach(this::learnBySeedGossip);
    }

    @Test
    void ack_withManyLabelledUpdatesBuffered_neverExceedsBudget_andEveryUpdateIsEventuallyDelivered() {
        var delivered = new HashSet<NodeId>();

        for (int round = 1; round <= SUBJECTS * 4; round++) {
            var piggyback = ackToPing(round).piggyback();

            assertThat(estimatedBytes(piggyback)).as("round %d piggyback", round).isLessThanOrEqualTo(PiggybackBuffer.piggybackBudgetFor(SELF_ID));
            piggyback.forEach(update -> delivered.add(update.nodeId()));
        }

        assertThat(delivered).hasSize(SUBJECTS);
    }

    @Test
    void selfAliveGossip_carriesTheNodesOwnLabels() {
        var fresh = SwimProtocol.swimProtocol(swimConfig(timeSpan(100).millis(), timeSpan(80).millis(), 3, timeSpan(5).seconds(), 8, timeSpan(40).millis())
                                                  .withJoinGrace(timeSpan(0).millis()),
                                              transport,
                                              new RecordingListener(),
                                              SELF_ID,
                                              SELF_ADDR)
                                .unwrap();
        var info = NodeInfo.nodeInfo(SELF_ID,
                                     NodeAddress.nodeAddress("127.0.0.1", 9700).unwrap(),
                                     Map.of(NodeInfo.LABEL_ROLE, "core", NodeInfo.LABEL_SOURCE, "replacement"));

        fresh.start();
        fresh.announceJoin(info, "c", 1L, 77L, List.of());
        try {
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> assertThat(selfUpdates(fresh))
                .isNotEmpty()
                .allSatisfy(update -> assertThat(update.labels()).containsEntry(NodeInfo.LABEL_ROLE, "core")));
        } finally {
            fresh.stop();
        }
    }

    private List<MembershipUpdate> selfUpdates(SwimProtocol subject) {
        transport.sentMessages.clear();
        subject.onMessage(ASKER_ADDR, new Ping(ASKER, 5000L, List.of()));

        return transport.sentMessages.stream()
                                     .map(SentMessage::message)
                                     .filter(Ack.class::isInstance)
                                     .map(Ack.class::cast)
                                     .flatMap(ack -> ack.piggyback().stream())
                                     .filter(update -> update.nodeId().equals(SELF_ID))
                                     .toList();
    }

    /// The default configuration sends eight updates per message. Even with long, realistic full label maps on the
    /// members, gossip carries only the labels core counting reads (role, source), so the default-8 Ack stays under
    /// the budget with room to spare instead of tipping every probe past the receive buffer.
    @Test
    void defaultEightUpdateAck_withLongRealisticLabels_carriesOnlyRoleAndSource_underBudget() {
        var defaults = SwimProtocol.swimProtocol(SwimConfig.DEFAULT, transport, new RecordingListener(), SELF_ID, SELF_ADDR)
                                   .unwrap();
        var longLabels = Map.of(NodeInfo.LABEL_ROLE, "core",
                                NodeInfo.LABEL_SOURCE, "replacement",
                                NodeInfo.LABEL_HOSTNAME, "aether-production-eu-central-core-node-0123456789abcdef.internal.example.com",
                                NodeInfo.LABEL_ZONE, "fsn1-dc14-availability-zone-primary",
                                NodeInfo.LABEL_INSTANCE_TYPE, "ccx33-dedicated-vcpu-general-purpose",
                                NodeInfo.LABEL_POOL, "core-pool-primary-replacement-generation-7");

        IntStream.range(0, 20).forEach(i -> defaults.onMessage(ASKER_ADDR,
                                                               new Ping(ASKER,
                                                                        1L,
                                                                        List.of(MembershipUpdate.membershipUpdate(new NodeId("aether-test-cluster-node-01m3rc2gnr6bnvmnmc7rs0r" + i),
                                                                                                                  MemberState.ALIVE,
                                                                                                                  0L,
                                                                                                                  new InetSocketAddress("127.0.0.1", 9900 + i),
                                                                                                                  0L,
                                                                                                                  longLabels)))));
        transport.sentMessages.clear();
        defaults.onMessage(ASKER_ADDR, new Ping(ASKER, 2L, List.of()));
        var piggyback = transport.sentMessages.stream()
                                              .map(SentMessage::message)
                                              .filter(Ack.class::isInstance)
                                              .map(Ack.class::cast)
                                              .findFirst()
                                              .orElseThrow()
                                              .piggyback();

        assertThat(SwimConfig.DEFAULT.maxPiggyback()).isEqualTo(8);
        assertThat(piggyback).hasSize(8);
        assertThat(estimatedBytes(piggyback)).isLessThanOrEqualTo(PiggybackBuffer.piggybackBudgetFor(SELF_ID));
        assertThat(piggyback).allSatisfy(update -> assertThat(update.labels().keySet()).isSubsetOf(NodeInfo.LABEL_ROLE, NodeInfo.LABEL_SOURCE));
    }

    private Ack ackToPing(int sequence) {
        transport.sentMessages.clear();
        protocol.onMessage(ASKER_ADDR, new Ping(ASKER, sequence, List.of()));

        return transport.sentMessages.stream()
                                     .map(SentMessage::message)
                                     .filter(Ack.class::isInstance)
                                     .map(Ack.class::cast)
                                     .findFirst()
                                     .orElseThrow();
    }

    private void learnBySeedGossip(int index) {
        var subject = new NodeId("subject-" + index);
        var address = new InetSocketAddress("127.0.0.1", 9800 + index);
        var update = MembershipUpdate.membershipUpdate(subject, MemberState.ALIVE, 0L, address, 0L, REALISTIC_LABELS);

        protocol.onMessage(ASKER_ADDR, new Ping(ASKER, 1L, List.of(update)));
    }

    private static int estimatedBytes(List<MembershipUpdate> updates) {
        return updates.stream().mapToInt(PiggybackBuffer::estimatedBytes).sum();
    }
}
