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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.Announce;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimProtocolTest.RecordingTransport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #1783: membership must converge for a joiner independent of piggyback retention. The seed answers an
/// ANNOUNCE with its membership view (unsolicited `Ack`s, sequence 0); the joiner merges it through the
/// same precedence as gossip.
class SwimJoinSyncTest {
    private static final NodeId SEED = new NodeId("seed");
    private static final NodeId X = new NodeId("member-x");
    private static final InetSocketAddress SEED_ADDR = new InetSocketAddress("127.0.0.1", 29101);
    private static final InetSocketAddress JOINER_ADDR = new InetSocketAddress("127.0.0.1", 29102);
    private static final InetSocketAddress X_ADDR = new InetSocketAddress("127.0.0.1", 29103);

    private final Map<InetSocketAddress, SwimTransport.SwimMessageHandler> net = new ConcurrentHashMap<>();
    private final ExecutorService wire = Executors.newSingleThreadExecutor();
    private final List<SwimProtocol> started = new ArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(SwimProtocol::stop);
        net.clear();
        wire.shutdownNow();
    }

    /// The seed knows member A (put straight into its membership: no gossip about A is buffered, i.e. A's join
    /// update is long evicted) and never probes (period 1h). Joiner B announces to the seed ONLY. B must learn
    /// A from the join ack, and A must learn B from B's own pings — no other path exists in this topology.
    @Test
    void joinerAfterEviction_andEarlierMember_learnEachOtherWithoutGossip() {
        var seed = node("seed", 29101, false);
        var memberA = node("member-a", 29103, false);

        seed.putMemberForTest(new NodeId("member-a"), new InetSocketAddress("127.0.0.1", 29103), MemberState.ALIVE);

        var joiner = node("joiner-b", 29102, true);

        joiner.addSeedMember(SEED, new InetSocketAddress("127.0.0.1", 29101));
        joiner.announceJoin(selfInfo("joiner-b", 29102), "c", 1L, 1002L, List.of(new InetSocketAddress("127.0.0.1", 29101)));

        await().atMost(10, TimeUnit.SECONDS).untilAsserted(() -> {
            assertThat(joiner.members()).as("B's view").containsKey(new NodeId("member-a"));
            assertThat(memberA.members()).as("A's view").containsKey(new NodeId("joiner-b"));
        });
    }

    @Test
    void announceReply_carriesLiveMembers_notAnnouncerNorFaulty() {
        var transport = new RecordingTransport();
        var seed = SwimProtocol.swimProtocol(manualConfig(), transport, new SwimProtocolTest.RecordingListener(), SEED, SEED_ADDR)
                               .unwrap();

        seed.putMemberForTest(X, X_ADDR, MemberState.ALIVE);
        seed.putMemberForTest(new NodeId("dead"), new InetSocketAddress("127.0.0.1", 29104), MemberState.FAULTY);
        seed.putMemberForTest(new NodeId("joiner-b"), JOINER_ADDR, MemberState.ALIVE);
        seed.onMessage(JOINER_ADDR, Announce.announce(selfInfo("joiner-b", 29102), "c", 1L));

        var replied = transport.sentMessages.stream()
                               .filter(sent -> sent.message() instanceof Ack ack && ack.sequence() == 0L)
                               .flatMap(sent -> ((Ack) sent.message()).piggyback().stream())
                               .map(MembershipUpdate::nodeId)
                               .toList();

        assertThat(replied).containsExactly(X);
    }

    /// A stale entry in the reply (the seed's view of X: ALIVE at incarnation 0) must not resurrect a member
    /// the joiner already holds FAULTY at incarnation 1.
    @Test
    void staleReplyEntry_doesNotResurrectFaultyMember() {
        var joiner = joinerHoldingFaulty(X);

        joiner.onMessage(SEED_ADDR, Ack.ack(SEED, 0L, List.of(MembershipUpdate.membershipUpdate(X, MemberState.ALIVE, 0, X_ADDR))));

        assertThat(joiner.members().get(X).state()).isEqualTo(MemberState.FAULTY);
        joiner.stop();
    }

    /// And must not regress a live member the joiner already knows at a higher incarnation.
    @Test
    void staleReplyEntry_doesNotRegressHigherIncarnation() {
        var transport = new RecordingTransport();
        var joiner = SwimProtocol.swimProtocol(manualConfig(), transport, new SwimProtocolTest.RecordingListener(), new NodeId("joiner-b"), JOINER_ADDR)
                                 .unwrap();

        joiner.onMessage(SEED_ADDR, Ack.ack(SEED, 0L, List.of(MembershipUpdate.membershipUpdate(X, MemberState.ALIVE, 5, X_ADDR))));
        joiner.onMessage(SEED_ADDR, Ack.ack(SEED, 0L, List.of(MembershipUpdate.membershipUpdate(X, MemberState.SUSPECT, 3, X_ADDR))));

        assertThat(joiner.members().get(X).state()).isEqualTo(MemberState.ALIVE);
        assertThat(joiner.members().get(X).incarnation()).isEqualTo(5L);
    }

    @Test
    void pack_boundsPagesByCountBytesAndDatagrams() {
        var updates = new ArrayList<MembershipUpdate>();

        for (int i = 0; i < 30; i++) {
            updates.add(MembershipUpdate.membershipUpdate(new NodeId("n" + i), MemberState.ALIVE, 1, X_ADDR));
        }

        var pages = PiggybackBuffer.pack(updates, 8, 1000, 2);

        assertThat(pages).hasSize(2);
        assertThat(pages).allSatisfy(page -> {
            assertThat(page.size()).isLessThanOrEqualTo(8);
            assertThat(page.stream().mapToInt(PiggybackBuffer::estimatedBytes).sum()).isLessThanOrEqualTo(1000);
        });
    }

    private SwimProtocol joinerHoldingFaulty(NodeId subject) {
        var transport = new RecordingTransport();
        var config = SwimConfig.swimConfig(timeSpan(1).hours(), timeSpan(1).hours(), 3, timeSpan(1).hours(), 8, timeSpan(1).hours());
        var joiner = SwimProtocol.swimProtocol(config, transport, new SwimProtocolTest.RecordingListener(), new NodeId("joiner-b"), JOINER_ADDR, () -> false)
                                 .unwrap();

        joiner.onMessage(SEED_ADDR, new Ping(SEED, 1L, List.of(MembershipUpdate.membershipUpdate(subject, MemberState.ALIVE, 0, X_ADDR))));
        joiner.recordTransportHint(subject,
                                   new TransportObservation.PeerUnreachable(subject,
                                                                            Causes.cause("test peer down"),
                                                                            TransportObservation.HintOrigin.LINK_LOST));
        joiner.onMessage(SEED_ADDR, new Ping(SEED, 2L, List.of(MembershipUpdate.membershipUpdate(subject, MemberState.FAULTY, 1, X_ADDR))));
        assertThat(joiner.members().get(subject).state()).as("precondition: subject FAULTY").isEqualTo(MemberState.FAULTY);

        return joiner;
    }

    private static SwimConfig manualConfig() {
        return SwimConfig.swimConfig(timeSpan(1).hours(), timeSpan(1).hours(), 3, timeSpan(1).hours(), 8, timeSpan(1).hours(), "c", 0);
    }

    private SwimProtocol node(String id, int port, boolean ticking) {
        var config = ticking
                     ? SwimConfig.swimConfig(timeSpan(100).millis(), timeSpan(80).millis(), 3, timeSpan(3).seconds(), 8, timeSpan(200).millis(), "c", 0)
                     : manualConfig();
        var address = new InetSocketAddress("127.0.0.1", port);
        var transport = new MemoryTransport(address);
        var protocol = SwimProtocol.swimProtocol(config, transport, new SwimProtocolTest.RecordingListener(), new NodeId(id), address)
                                   .unwrap();

        transport.start(port, protocol::onMessage);
        if (ticking) {
            protocol.start();
        }
        started.add(protocol);

        return protocol;
    }

    private static NodeInfo selfInfo(String id, int port) {
        return NodeInfo.nodeInfo(new NodeId(id),
                                 NodeAddress.nodeAddress("127.0.0.1", port).unwrap(),
                                 Map.of(NodeInfo.LABEL_ROLE, "core"));
    }

    /// Delivers on one wire thread, so protocols never re-enter each other's handlers.
    private final class MemoryTransport implements SwimTransport {
        private final InetSocketAddress self;

        MemoryTransport(InetSocketAddress self) {
            this.self = self;
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            wire.submit(() -> deliver(target, message));

            return Promise.success(Unit.unit());
        }

        private void deliver(InetSocketAddress target, SwimMessage message) {
            var handler = net.get(new InetSocketAddress("127.0.0.1", target.getPort()));

            if (handler != null) {
                handler.onMessage(self, message);
            }
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            net.put(self, handler);

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            net.remove(self);

            return Promise.success(Unit.unit());
        }
    }
}
