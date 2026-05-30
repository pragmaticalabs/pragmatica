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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

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
import org.pragmatica.swim.SwimObservation.JoinAnnounced;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.consensus.net.NodeInfo.nodeInfo;
import static org.pragmatica.swim.SwimConfig.swimConfig;

class SwimNewVariantsTest {

    private static final NodeId NODE_ID = new NodeId("node-announce");
    private static final NodeAddress NODE_ADDR = NodeAddress.nodeAddress("127.0.0.1", 7100).unwrap();
    private static final NodeInfo NODE_INFO = nodeInfo(NODE_ID, NODE_ADDR, NodeRole.ACTIVE, Map.of("zone", "us-east-1"));
    private static final String CLUSTER = "test-cluster";
    private static final long INCARNATION = 42L;

    @Nested
    class JoinAnnouncedObservationTests {

        @Test
        void constructor_allFields_roundTripPreservesAllFields() {
            var obs = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);

            assertThat(obs.nodeInfo()).isEqualTo(NODE_INFO);
            assertThat(obs.clusterName()).isEqualTo(CLUSTER);
            assertThat(obs.incarnation()).isEqualTo(INCARNATION);
            assertThat(obs.version()).isEqualTo(SwimObservation.CURRENT_VERSION);
        }

        @Test
        void peer_delegatesToNodeInfoId() {
            var obs = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);

            assertThat(obs.peer()).isEqualTo(NODE_ID);
        }

        @Test
        void canonicalConstructor_explicitVersion_preserved() {
            var obs = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION, (byte) 99);

            assertThat(obs.version()).isEqualTo((byte) 99);
        }

        @Test
        void recordEquality_sameFields_equal() {
            var a = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);
            var b = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void recordEquality_differentIncarnation_notEqual() {
            var a = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);
            var b = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION + 1);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void recordEquality_differentCluster_notEqual() {
            var a = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);
            var b = new JoinAnnounced(NODE_INFO, "other-cluster", INCARNATION);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void exhaustiveSwitch_joinAnnounced_handledWithoutDefault() {
            SwimObservation obs = new JoinAnnounced(NODE_INFO, CLUSTER, INCARNATION);
            var handled = new ArrayList<String>();

            switch (obs) {
                case SwimObservation.HealthyObserved _ -> handled.add("healthy");
                case SwimObservation.SuspectObserved _ -> handled.add("suspect");
                case SwimObservation.FaultyObserved _ -> handled.add("faulty");
                case SwimObservation.DepartedObserved _ -> handled.add("departed");
                case SwimObservation.UnknownObserved _ -> handled.add("unknown");
                case SwimObservation.MemberDiscovered _ -> handled.add("member-discovered");
                case JoinAnnounced ja -> handled.add("join-announced:" + ja.clusterName());
            }

            assertThat(handled).containsExactly("join-announced:" + CLUSTER);
        }
    }

    @Nested
    class AnnounceMessageTests {

        @Test
        void factoryMethod_allFields_roundTripPreservesAllFields() {
            var msg = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);

            assertThat(msg.nodeInfo()).isEqualTo(NODE_INFO);
            assertThat(msg.clusterName()).isEqualTo(CLUSTER);
            assertThat(msg.incarnation()).isEqualTo(INCARNATION);
        }

        @Test
        void constructor_allFields_roundTripPreservesAllFields() {
            var msg = new Announce(NODE_INFO, CLUSTER, INCARNATION);

            assertThat(msg.nodeInfo()).isEqualTo(NODE_INFO);
            assertThat(msg.clusterName()).isEqualTo(CLUSTER);
            assertThat(msg.incarnation()).isEqualTo(INCARNATION);
        }

        @Test
        void recordEquality_sameFields_equal() {
            var a = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);
            var b = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);

            assertThat(a).isEqualTo(b);
            assertThat(a.hashCode()).isEqualTo(b.hashCode());
        }

        @Test
        void recordEquality_differentIncarnation_notEqual() {
            var a = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);
            var b = Announce.announce(NODE_INFO, CLUSTER, INCARNATION + 1);

            assertThat(a).isNotEqualTo(b);
        }

        @Test
        void exhaustiveSwitch_announce_handledWithoutDefault() {
            SwimMessage msg = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);
            var handled = new ArrayList<String>();

            switch (msg) {
                case SwimMessage.Ping _ -> handled.add("ping");
                case SwimMessage.Ack _ -> handled.add("ack");
                case SwimMessage.PingReq _ -> handled.add("pingreq");
                case Announce ann -> handled.add("announce:" + ann.clusterName());
            }

            assertThat(handled).containsExactly("announce:" + CLUSTER);
        }
    }

    @Nested
    class AnnounceToObservationBridgeTests {

        private static final NodeId SELF_ID = new NodeId("node-self");
        private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
        private static final InetSocketAddress SENDER_ADDR = new InetSocketAddress("127.0.0.1", 9100);

        private RecordingObservationSink observations;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            var transport = new StubTransport();
            var listener = new NoOpMembershipListener();
            observations = new RecordingObservationSink();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(_ -> null, v -> v);
            protocol.addObservationListener(observations);
        }

        @Test
        void onMessage_announce_emitsJoinAnnounced() {
            var announce = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);
            protocol.onMessage(SENDER_ADDR, announce);

            var emitted = observations.byType(JoinAnnounced.class);
            assertThat(emitted).hasSize(1);

            var obs = emitted.getFirst();
            assertThat(obs.nodeInfo()).isEqualTo(NODE_INFO);
            assertThat(obs.clusterName()).isEqualTo(CLUSTER);
            assertThat(obs.incarnation()).isEqualTo(INCARNATION);
            assertThat(obs.peer()).isEqualTo(NODE_ID);
        }

        @Test
        void onMessage_announce_multipleAnnounces_emitsOnePerCall() {
            var announce = Announce.announce(NODE_INFO, CLUSTER, INCARNATION);
            protocol.onMessage(SENDER_ADDR, announce);
            protocol.onMessage(SENDER_ADDR, announce);

            assertThat(observations.byType(JoinAnnounced.class)).hasSize(2);
        }
    }

    static class StubTransport implements SwimTransport {
        @Override public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.success(Unit.unit());
        }

        @Override public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    static class NoOpMembershipListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member) {}
        @Override public void onMemberLeft(NodeId nodeId) {}
    }

    static class RecordingObservationSink implements Consumer<SwimObservation> {
        final CopyOnWriteArrayList<SwimObservation> all = new CopyOnWriteArrayList<>();

        @Override public void accept(SwimObservation observation) {
            all.add(observation);
        }

        <T extends SwimObservation> List<T> byType(Class<T> type) {
            return all.stream()
                      .filter(type::isInstance)
                      .map(type::cast)
                      .toList();
        }
    }
}
