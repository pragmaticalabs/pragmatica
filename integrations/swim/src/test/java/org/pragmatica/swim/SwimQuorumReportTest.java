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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
import org.pragmatica.swim.SwimProtocolTest.RecordingObservationSink;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;
import org.pragmatica.swim.SwimProtocolTest.SentMessage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #2936: a node's own quorum-loss report rides its SWIM self-ALIVE as the gossiped `quorum=lost` label. It is STATE,
/// not identity: the subject bumps its incarnation on every change, a receiver takes the report only from a strictly
/// newer update, a report change on an ALIVE member re-emits `HealthyObserved` so the membership layer re-reads it,
/// and the dial `NodeInfo` never carries it. The literal key and value pin the wire format. Per the PR #2940 ruling,
/// `quorum=observer` (passive BY ROLE) is a distinct value that never reads as quorum loss.
class SwimQuorumReportTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId GOSSIPER = new NodeId("node-gossiper");
    private static final NodeId PEER = new NodeId("node-peer");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9200);
    private static final InetSocketAddress GOSSIPER_ADDR = new InetSocketAddress("127.0.0.1", 9201);
    private static final InetSocketAddress PEER_ADDR = new InetSocketAddress("127.0.0.1", 9202);
    private static final long TOKEN = 77L;
    private static final Map<String, String> CORE_LABELS = Map.of(NodeInfo.LABEL_ROLE, "core");
    private static final Map<String, String> CORE_LOST = Map.of(NodeInfo.LABEL_ROLE, "core", "quorum", "lost");
    private static final Map<String, String> CORE_OBSERVER = Map.of(NodeInfo.LABEL_ROLE, "core", "quorum", "observer");

    private RecordingTransport transport;
    private RecordingObservationSink observations;
    private SwimProtocol protocol;
    private long sequence;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        observations = new RecordingObservationSink();
        protocol = SwimProtocol.swimProtocol(swimConfig(), transport, new RecordingListener(), SELF_ID, SELF_ADDR)
                               .unwrap();
        protocol.addObservationListener(observations);
    }

    @Test
    void newerIncarnation_adoptsTheReport_andALaterNewerUpdateClearsIt() {
        gossip(update(MemberState.ALIVE, 0L, CORE_LABELS));
        assertThat(protocol.reportsQuorumLoss(PEER)).isFalse();

        gossip(update(MemberState.ALIVE, 1L, CORE_LOST));
        assertThat(protocol.reportsQuorumLoss(PEER)).as("a strictly newer update carries the report").isTrue();
        assertThat(protocol.members().get(PEER).labels()).containsEntry(NodeInfo.LABEL_ROLE, "core");

        gossip(update(MemberState.ALIVE, 2L, CORE_LABELS));
        assertThat(protocol.reportsQuorumLoss(PEER)).as("a strictly newer update without the label clears it").isFalse();
        assertThat(protocol.members().get(PEER).labels()).as("identity labels survive the clear").isEqualTo(CORE_LABELS);
    }

    @Test
    void sameIncarnation_neverChangesTheReport() {
        gossip(update(MemberState.ALIVE, 1L, CORE_LOST));
        gossip(update(MemberState.SUSPECT, 1L, CORE_LABELS));

        assertThat(protocol.members().get(PEER).state()).isEqualTo(MemberState.SUSPECT);
        assertThat(protocol.reportsQuorumLoss(PEER)).as("a same-incarnation relay is not ordered after the report")
                                                    .isTrue();
    }

    @Test
    void refutationFromSuspect_carriesTheReportBeforeHealthyIsEmitted() {
        gossip(update(MemberState.ALIVE, 0L, CORE_LABELS));
        gossip(update(MemberState.SUSPECT, 0L, CORE_LABELS));
        var reportAtHealthy = new HashMap<Long, Boolean>();

        protocol.addObservationListener(observation -> recordReportAtHealthy(observation, reportAtHealthy));
        gossip(update(MemberState.ALIVE, 1L, CORE_LOST));

        assertThat(reportAtHealthy).as("the refutation's HealthyObserved sees the report it carried")
                                   .containsEntry(1L, true);
    }

    @Test
    void aliveToAlive_reportChange_reemitsHealthyAtTheNewIncarnation() {
        gossip(update(MemberState.ALIVE, 0L, CORE_LABELS));
        var before = healthyIncarnations().size();

        gossip(update(MemberState.ALIVE, 1L, CORE_LOST));
        gossip(update(MemberState.ALIVE, 2L, CORE_LABELS));
        gossip(update(MemberState.ALIVE, 3L, CORE_LABELS));

        assertThat(healthyIncarnations().subList(before, healthyIncarnations().size()))
            .as("one re-emit per report change; an unchanged report at a newer incarnation emits nothing")
            .containsExactly(1L, 2L);
    }

    @Test
    void discoveryNodeInfo_neverCarriesTheReport() {
        gossip(update(MemberState.ALIVE, 0L, CORE_LOST));

        assertThat(protocol.reportsQuorumLoss(PEER)).isTrue();
        assertThat(observations.byType(SwimObservation.MemberDiscovered.class)
                               .stream()
                               .filter(discovered -> discovered.nodeInfo().id().equals(PEER))
                               .map(discovered -> discovered.nodeInfo().labels())
                               .toList())
            .as("NodeInfo equality includes labels: a report change must not split the peer's identity")
            .isNotEmpty()
            .allSatisfy(labels -> assertThat(labels).isEqualTo(CORE_LABELS));
    }

    @Test
    void relayedUpdate_carriesThePeersReport() {
        gossip(update(MemberState.ALIVE, 2L, CORE_LOST));

        assertThat(relayedPeerUpdates())
            .as("a node that missed the subject's own update learns the report from any relay")
            .isNotEmpty()
            .allSatisfy(relayed -> assertThat(relayed.labels()).containsEntry("quorum", "lost"));
    }

    @Test
    void selfReport_bumpsIncarnationOncePerChange_andRidesTheSelfAlive() {
        announceSelf();

        protocol.reportSelfQuorum(QuorumReport.LOST);
        protocol.reportSelfQuorum(QuorumReport.LOST);
        assertThat(protocol.selfIncarnation()).as("one bump for one change; a repeat is a no-op").isEqualTo(2L);
        assertThat(lastSelfAdvertised()).extracting(MembershipUpdate::incarnation).isEqualTo(2L);
        assertThat(lastSelfAdvertised().labels()).containsEntry("quorum", "lost");

        protocol.reportSelfQuorum(QuorumReport.HELD);
        assertThat(protocol.selfIncarnation()).isEqualTo(3L);
        assertThat(lastSelfAdvertised().labels()).doesNotContainKey("quorum");
    }

    @Test
    void selfReport_beforeAnnounce_isKeptAndCarriedOnceAnnounced() {
        protocol.reportSelfQuorum(QuorumReport.LOST);
        assertThat(protocol.selfIncarnation()).as("no incarnation to bump before announce").isZero();

        announceSelf();

        assertThat(lastSelfAdvertised().labels()).as("announce keeps the stored report")
                                                 .containsEntry("quorum", "lost");
    }

    @Test
    void observerReport_isNotQuorumLoss_andALaterLostReportIs() {
        gossip(update(MemberState.ALIVE, 1L, CORE_OBSERVER));
        assertThat(protocol.reportsQuorumLoss(PEER)).as("passive by role is not quorum loss").isFalse();
        assertThat(protocol.members().get(PEER).labels()).containsEntry("quorum", "observer");

        gossip(update(MemberState.ALIVE, 2L, CORE_LOST));
        assertThat(protocol.reportsQuorumLoss(PEER)).as("a re-promoted voter that cannot write reports loss").isTrue();
    }

    @Test
    void aliveToAlive_lostToObserver_reemitsHealthy() {
        gossip(update(MemberState.ALIVE, 1L, CORE_LOST));
        var before = healthyIncarnations().size();

        gossip(update(MemberState.ALIVE, 2L, CORE_OBSERVER));

        assertThat(protocol.reportsQuorumLoss(PEER)).isFalse();
        assertThat(healthyIncarnations().subList(before, healthyIncarnations().size()))
            .as("leaving the loss report for the observer report re-admits through HealthyObserved")
            .containsExactly(2L);
    }

    @Test
    void selfReport_observer_isAdvertisedAsItsOwnValue_withABumpPerChange() {
        announceSelf();

        protocol.reportSelfQuorum(QuorumReport.OBSERVER);
        assertThat(protocol.selfIncarnation()).isEqualTo(2L);
        assertThat(lastSelfAdvertised().labels()).containsEntry("quorum", "observer");

        protocol.reportSelfQuorum(QuorumReport.LOST);
        assertThat(protocol.selfIncarnation()).as("observer -> lost is a change: one bump").isEqualTo(3L);
        assertThat(lastSelfAdvertised().labels()).containsEntry("quorum", "lost");
    }

    private void announceSelf() {
        protocol.announceJoin(NodeInfo.nodeInfo(SELF_ID, new NodeAddress("127.0.0.1", 9200), CORE_LABELS),
                              "",
                              1L,
                              TOKEN,
                              List.of());
    }

    private void recordReportAtHealthy(SwimObservation observation, Map<Long, Boolean> sink) {
        if (observation instanceof SwimObservation.HealthyObserved healthy && healthy.peer().equals(PEER)) {
            sink.put(healthy.incarnation(), protocol.reportsQuorumLoss(PEER));
        }
    }

    private List<Long> healthyIncarnations() {
        return observations.byType(SwimObservation.HealthyObserved.class)
                           .stream()
                           .filter(healthy -> healthy.peer().equals(PEER))
                           .map(SwimObservation.HealthyObserved::incarnation)
                           .toList();
    }

    private List<MembershipUpdate> relayedPeerUpdates() {
        protocol.onMessage(GOSSIPER_ADDR, new Ping(GOSSIPER, ++sequence, List.of()));

        return transport.sentMessages.stream()
                                     .map(SentMessage::message)
                                     .filter(Ack.class::isInstance)
                                     .map(Ack.class::cast)
                                     .flatMap(ack -> ack.piggyback().stream())
                                     .filter(update -> update.nodeId().equals(PEER))
                                     .toList();
    }

    private MembershipUpdate lastSelfAdvertised() {
        protocol.onMessage(GOSSIPER_ADDR, new Ping(GOSSIPER, ++sequence, List.of()));

        return transport.sentMessages.stream()
                                     .map(SentMessage::message)
                                     .filter(Ack.class::isInstance)
                                     .map(Ack.class::cast)
                                     .flatMap(ack -> ack.piggyback().stream())
                                     .filter(update -> update.nodeId().equals(SELF_ID))
                                     .reduce((_, second) -> second)
                                     .orElseThrow();
    }

    private void gossip(MembershipUpdate update) {
        protocol.onMessage(GOSSIPER_ADDR, new Ping(GOSSIPER, ++sequence, List.of(update)));
    }

    private static MembershipUpdate update(MemberState state, long incarnation, Map<String, String> labels) {
        return MembershipUpdate.membershipUpdate(PEER, state, incarnation, PEER_ADDR, 0L, labels);
    }
}
