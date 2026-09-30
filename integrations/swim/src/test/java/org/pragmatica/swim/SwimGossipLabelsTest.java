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
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolTest.RecordingListener;
import org.pragmatica.swim.SwimProtocolTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;
import org.pragmatica.swim.SwimProtocolTest.SentMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// Descriptor labels ride SWIM gossip. A replacement core minted by another leader is learned by
/// this node ONLY through a gossiped `MembershipUpdate` (never its ANNOUNCE, never a Hello), so the
/// update itself must carry the `role=core` label the membership layer classifies on — otherwise the
/// member is present and healthy but not counted as a core.
///
/// Rule under test (first-non-blank-wins): a resident member with no labels adopts the labels a
/// gossiped update carries; a resident member that has labels keeps them.
class SwimGossipLabelsTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId GOSSIPER = new NodeId("node-gossiper");
    private static final NodeId OTHER = new NodeId("node-other");
    private static final NodeId REPLACEMENT = new NodeId("node-replacement");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9100);
    private static final InetSocketAddress GOSSIPER_ADDR = new InetSocketAddress("127.0.0.1", 9101);
    private static final InetSocketAddress OTHER_ADDR = new InetSocketAddress("127.0.0.1", 9102);
    private static final InetSocketAddress REPLACEMENT_ADDR = new InetSocketAddress("127.0.0.1", 9103);
    private static final Map<String, String> CORE_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core",
                                                                   NodeInfo.LABEL_SOURCE, "replacement");
    private static final Map<String, String> OTHER_LABELS = Map.of(NodeInfo.LABEL_ROLE, "worker");

    private RecordingTransport transport;
    private RecordingObservationSink observations;
    private SwimProtocol protocol;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        observations = new RecordingObservationSink();
        protocol = SwimProtocol.swimProtocol(swimConfig(), transport, new RecordingListener(), SELF_ID, SELF_ADDR)
                               .unwrap();
        protocol.addObservationListener(observations);
    }

    @Test
    void gossipedNewMember_withLabels_storesLabelsAndDiscoveryCarriesRole() {
        gossip(1L, update(MemberState.ALIVE, 0L, CORE_LABELS));

        assertThat(protocol.members().get(REPLACEMENT).labels()).isEqualTo(CORE_LABELS);
        assertThat(discoveredLabels()).last().isEqualTo(CORE_LABELS);
    }

    @Test
    void gossipedNewMember_withoutLabels_hasNoLabels() {
        gossip(1L, update(MemberState.ALIVE, 0L, Map.of()));

        assertThat(protocol.members().get(REPLACEMENT).labels()).isEmpty();
        assertThat(discoveredLabels()).last().isEqualTo(Map.of());
    }

    @Test
    void gossipedResidentMember_blankLabels_adoptsLabelsFromSameStateRebroadcast() {
        gossip(1L, update(MemberState.ALIVE, 0L, Map.of()));
        gossip(2L, update(MemberState.ALIVE, 0L, CORE_LABELS));

        assertThat(protocol.members().get(REPLACEMENT).labels()).isEqualTo(CORE_LABELS);
        assertThat(discoveredLabels())
            .as("the newly learned role is re-emitted so the membership layer sees it now")
            .last()
            .isEqualTo(CORE_LABELS);
    }

    @Test
    void gossipedResidentMember_labelsPresent_firstNonBlankWins() {
        gossip(1L, update(MemberState.ALIVE, 0L, CORE_LABELS));
        gossip(2L, update(MemberState.ALIVE, 1L, OTHER_LABELS));

        assertThat(protocol.members().get(REPLACEMENT).labels()).isEqualTo(CORE_LABELS);
    }

    @Test
    void gossipedStateChange_withoutLabels_keepsAnnouncedLabels() {
        protocol.onMessage(REPLACEMENT_ADDR, Announce.announce(announcedInfo(), "", 0L));
        gossip(1L, update(MemberState.ALIVE, 0L, Map.of()));

        assertThat(protocol.members().get(REPLACEMENT).state()).isEqualTo(MemberState.ALIVE);
        assertThat(protocol.members().get(REPLACEMENT).labels())
            .as("a label-less gossip promotion must not erase the ANNOUNCE-supplied labels")
            .isEqualTo(CORE_LABELS);
        assertThat(discoveredLabels()).last().isEqualTo(CORE_LABELS);
    }

    @Test
    void gossipedLabels_areRedisseminatedToOtherPeers() {
        gossip(1L, update(MemberState.ALIVE, 0L, CORE_LABELS));
        protocol.onMessage(OTHER_ADDR, new Ping(OTHER, 1L, List.of()));

        assertThat(piggybackedFor(REPLACEMENT))
            .as("an update relayed for the replacement carries the labels this node stored")
            .isNotEmpty()
            .allSatisfy(relayed -> assertThat(relayed.labels()).isEqualTo(CORE_LABELS));
    }

    @Test
    void gossipedLabelsForAFaultyMember_areStoredButNotReEmittedAsDiscovery() {
        gossip(1L, update(MemberState.FAULTY, 0L, Map.of()));
        var discoveredBefore = discoveredLabels().size();
        gossip(2L, update(MemberState.FAULTY, 0L, CORE_LABELS));

        assertThat(protocol.members().get(REPLACEMENT).labels()).as("labels adopted").isEqualTo(CORE_LABELS);
        assertThat(discoveredLabels())
            .as("a FAULTY member must not re-enter the QUIC dial set through a discovery re-emit")
            .hasSize(discoveredBefore);
    }

    private void gossip(long sequence, MembershipUpdate update) {
        protocol.onMessage(GOSSIPER_ADDR, new Ping(GOSSIPER, sequence, List.of(update)));
    }

    private static MembershipUpdate update(MemberState state, long incarnation, Map<String, String> labels) {
        return MembershipUpdate.membershipUpdate(REPLACEMENT, state, incarnation, REPLACEMENT_ADDR, 0L, labels);
    }

    private static NodeInfo announcedInfo() {
        return NodeInfo.nodeInfo(REPLACEMENT,
                                 NodeAddress.nodeAddress(REPLACEMENT_ADDR.getHostString(), REPLACEMENT_ADDR.getPort())
                                            .unwrap(),
                                 CORE_LABELS);
    }

    private List<Map<String, String>> discoveredLabels() {
        return observations.byType(SwimObservation.MemberDiscovered.class)
                           .stream()
                           .filter(discovered -> discovered.nodeInfo().id().equals(REPLACEMENT))
                           .map(discovered -> discovered.nodeInfo().labels())
                           .toList();
    }

    private List<MembershipUpdate> piggybackedFor(NodeId nodeId) {
        return transport.sentMessages.stream()
                                     .map(SentMessage::message)
                                     .filter(Ack.class::isInstance)
                                     .map(Ack.class::cast)
                                     .flatMap(ack -> ack.piggyback().stream())
                                     .filter(update -> update.nodeId().equals(nodeId))
                                     .toList();
    }
}
