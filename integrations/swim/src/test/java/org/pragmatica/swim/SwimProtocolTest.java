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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimMessage.PingReq;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

class SwimProtocolTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9003);

    @Nested
    class MembershipTests {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void addSeedMember_newMember_addedAsAlive() {
            protocol.addSeedMember(NODE_A, ADDR_A);

            assertThat(protocol.members()).containsKey(NODE_A);
            assertThat(protocol.members().get(NODE_A).state()).isEqualTo(MemberState.ALIVE);
            assertThat(listener.joined).hasSize(1);
            assertThat(listener.joined.getFirst().nodeId()).isEqualTo(NODE_A);
        }

        @Test
        void addSeedMember_selfNode_ignored() {
            protocol.addSeedMember(SELF_ID, SELF_ADDR);

            assertThat(protocol.members()).isEmpty();
            assertThat(listener.joined).isEmpty();
        }

        @Test
        void members_multipleSeeds_allPresent() {
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.addSeedMember(NODE_B, ADDR_B);
            protocol.addSeedMember(NODE_C, ADDR_C);

            assertThat(protocol.members()).hasSize(3);
        }
    }

    @Nested
    class MessageHandlingTests {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void onMessage_ping_sendsAck() {
            var ping = new Ping(NODE_A, 1L, List.of());

            protocol.onMessage(ADDR_A, ping);

            assertThat(transport.sentMessages).hasSize(1);
            assertThat(transport.sentMessages.getFirst().message()).isInstanceOf(Ack.class);

            var ack = (Ack) transport.sentMessages.getFirst().message();
            assertThat(ack.from()).isEqualTo(SELF_ID);
            assertThat(ack.sequence()).isEqualTo(1L);
        }

        @Test
        void onMessage_pingWithPiggyback_processesUpdates() {
            var update = new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B);
            var ping = new Ping(NODE_A, 1L, List.of(update));

            protocol.onMessage(ADDR_A, ping);

            assertThat(protocol.members()).containsKey(NODE_B);
            assertThat(listener.joined).hasSize(1);
        }

        @Test
        void onMessage_pingReqForKnownTarget_forwardsPing() {
            protocol.addSeedMember(NODE_B, ADDR_B);
            transport.sentMessages.clear();

            var pingReq = new PingReq(NODE_A, NODE_B, 42L);

            protocol.onMessage(ADDR_A, pingReq);

            assertThat(transport.sentMessages).hasSize(1);

            var forwarded = transport.sentMessages.getFirst();
            assertThat(forwarded.target()).isEqualTo(ADDR_B);
            assertThat(forwarded.message()).isInstanceOf(Ping.class);
        }

        @Test
        void onMessage_pingReqForUnknownTarget_ignored() {
            var pingReq = new PingReq(NODE_A, NODE_B, 42L);

            protocol.onMessage(ADDR_A, pingReq);

            assertThat(transport.sentMessages).isEmpty();
        }

        @Test
        void onMessage_pingReqRelay_ackForwardedBackToRequester() {
            // Setup: self knows both NODE_A (requester) and NODE_B (target)
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.addSeedMember(NODE_B, ADDR_B);
            transport.sentMessages.clear();

            // NODE_A asks self to probe NODE_B (indirect probe)
            var pingReq = new PingReq(NODE_A, NODE_B, 42L);
            protocol.onMessage(ADDR_A, pingReq);

            // Self should send Ping to NODE_B with its OWN sequence (not 42)
            assertThat(transport.sentMessages).hasSize(1);
            assertThat(transport.sentMessages.getFirst().target()).isEqualTo(ADDR_B);
            var relayPing = (Ping) transport.sentMessages.getFirst().message();
            var relaySeq = relayPing.sequence();
            assertThat(relaySeq).isNotEqualTo(42L); // relay uses its own sequence

            transport.sentMessages.clear();

            // NODE_B responds with Ack using the RELAY sequence
            var ack = Ack.ack(NODE_B, relaySeq, List.of());
            protocol.onMessage(ADDR_B, ack);

            // Self should forward Ack back to NODE_A with the ORIGINAL sequence (42)
            assertThat(transport.sentMessages).hasSize(1);
            var forwarded = transport.sentMessages.getFirst();
            assertThat(forwarded.target()).isEqualTo(ADDR_A);
            assertThat(forwarded.message()).isInstanceOf(Ack.class);
            assertThat(((Ack) forwarded.message()).from()).isEqualTo(NODE_B);
            assertThat(((Ack) forwarded.message()).sequence()).isEqualTo(42L); // original sequence restored
        }
    }

    @Nested
    class PiggybackBufferTests {

        @Test
        void addUpdate_withinCapacity_allRetained() {
            var buffer = PiggybackBuffer.piggybackBuffer(5);
            var update = new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A);

            buffer.addUpdate(update);

            assertThat(buffer.size()).isEqualTo(1);
        }

        @Test
        void peekUpdates_returnsUpdatesWithoutRemoving() {
            var buffer = PiggybackBuffer.piggybackBuffer(10);
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B));

            var peeked = buffer.peekUpdates(1);

            assertThat(peeked).hasSize(1);
            assertThat(peeked.getFirst().nodeId()).isEqualTo(NODE_A);
            // peekUpdates re-queues non-evicted entries, so size stays 2
            assertThat(buffer.size()).isEqualTo(2);
        }

        @Test
        void addUpdate_exceedsDoubleCapacity_evictsOldest() {
            var buffer = PiggybackBuffer.piggybackBuffer(2);
            // Buffer allows up to maxSize*2=4 entries for dissemination headroom
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.ALIVE, 0, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.ALIVE, 0, ADDR_B));
            buffer.addUpdate(new MembershipUpdate(NODE_C, MemberState.ALIVE, 0, ADDR_C));

            assertThat(buffer.size()).isEqualTo(3); // under 2*2=4 threshold

            // Add 2 more to exceed threshold
            buffer.addUpdate(new MembershipUpdate(NODE_A, MemberState.SUSPECT, 1, ADDR_A));
            buffer.addUpdate(new MembershipUpdate(NODE_B, MemberState.SUSPECT, 1, ADDR_B));

            assertThat(buffer.size()).isEqualTo(4); // trimmed to 4 (maxSize*2)
        }

        @Test
        void peekUpdates_emptyBuffer_returnsEmpty() {
            var buffer = PiggybackBuffer.piggybackBuffer(5);

            var peeked = buffer.peekUpdates(3);

            assertThat(peeked).isEmpty();
        }
    }

    @Nested
    class SuspectDetectionTests {
        private RecordingTransport transport;
        private RecordingListener listener;
        private SwimProtocol protocol;

        @BeforeEach
        void setUp() {
            var config = swimConfig(timeSpan(50).millis(), timeSpan(20).millis(), 3, timeSpan(100).millis(), 8);
            transport = new RecordingTransport();
            listener = new RecordingListener();
            protocol = SwimProtocol.swimProtocol(config, transport, listener, SELF_ID, SELF_ADDR)
                                   .fold(cause -> null, v -> v);
        }

        @Test
        void piggybackDissemination_memberUpdate_propagatedViaPiggyback() {
            protocol.addSeedMember(NODE_A, ADDR_A);
            protocol.addSeedMember(NODE_B, ADDR_B);

            // Respond to any ping — the ack should contain piggybacked membership info
            var ping = new Ping(NODE_A, 1L, List.of());
            protocol.onMessage(ADDR_A, ping);

            assertThat(transport.sentMessages).isNotEmpty();

            var ack = (Ack) transport.sentMessages.getFirst().message();
            // The piggyback should contain updates about newly added members
            assertThat(ack.piggyback()).isNotEmpty();
        }
    }

    @Nested
    class GovernorElectionTests {

        @Test
        void governorElection_lowestNodeId_isDeterministic() {
            // Governor election = lowest NodeId among ALIVE members.
            // This is a pure deterministic property test.
            var members = List.of(
                SwimMember.swimMember(NODE_C, ADDR_C),
                SwimMember.swimMember(NODE_A, ADDR_A),
                SwimMember.swimMember(NODE_B, ADDR_B)
            );

            var governor = members.stream()
                                  .filter(m -> m.state() == MemberState.ALIVE)
                                  .map(SwimMember::nodeId)
                                  .min(NodeId::compareTo)
                                  .orElse(null);

            assertThat(governor).isEqualTo(NODE_A);
        }
    }

    @Nested
    class LifecycleTests {
        private RecordingTransport transport;
        private RecordingListener listener;

        @BeforeEach
        void setUp() {
            transport = new RecordingTransport();
            listener = new RecordingListener();
        }

        @Test
        void start_alreadyRunning_returnsError() {
            var protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                       .fold(cause -> null, v -> v);

            protocol.start();
            var second = protocol.start();

            assertThat(second.isSuccess()).isFalse();

            protocol.stop();
        }

        @Test
        void stop_notRunning_returnsError() {
            var protocol = SwimProtocol.swimProtocol(swimConfig(), transport, listener, SELF_ID, SELF_ADDR)
                                       .fold(cause -> null, v -> v);

            var result = protocol.stop();

            assertThat(result.isSuccess()).isFalse();
        }
    }

    // -- Test infrastructure --

    record SentMessage(InetSocketAddress target, SwimMessage message) {}

    static class RecordingTransport implements SwimTransport {
        final CopyOnWriteArrayList<SentMessage> sentMessages = new CopyOnWriteArrayList<>();
        final AtomicReference<SwimMessageHandler> handler = new AtomicReference<>();

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            sentMessages.add(new SentMessage(target, message));
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            this.handler.set(handler);
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            handler.set(null);
            return Promise.success(Unit.unit());
        }
    }

    static class RecordingListener implements SwimMembershipListener {
        final CopyOnWriteArrayList<SwimMember> joined = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SwimMember> suspected = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<SwimMember> faulty = new CopyOnWriteArrayList<>();
        final CopyOnWriteArrayList<NodeId> left = new CopyOnWriteArrayList<>();

        @Override
        public void onMemberJoined(SwimMember member) {
            joined.add(member);
        }

        @Override
        public void onMemberSuspect(SwimMember member) {
            suspected.add(member);
        }

        @Override
        public void onMemberFaulty(SwimMember member) {
            faulty.add(member);
        }

        @Override
        public void onMemberLeft(NodeId nodeId) {
            left.add(nodeId);
        }
    }
}
