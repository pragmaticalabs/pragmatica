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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimMember.MemberState;
import org.pragmatica.swim.SwimMessage.Ack;
import org.pragmatica.swim.SwimMessage.MembershipUpdate;
import org.pragmatica.swim.SwimMessage.Ping;
import org.pragmatica.swim.SwimTransport.SwimMessageHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;

/// #2724 race 2: a LIVE peer whose QUIC link to this node was closed must not be declared dead
/// on the strength of the `LINK_LOST` hint alone.
///
/// Production chain (Ember artifact, cycle 4): the leader removes a flapping voter and evicts its
/// QUIC link; the voter's QUIC `onPeerLeft` sends a `LINK_LOST` hint for the LEADER, which arms a
/// suspicion floored to 3 s and corroborates the #336 kill-gate. The voter probes round-robin, one
/// peer per 1 s tick over four peers, so the leader's next probe is 4 s after its last one, and the
/// tick expires suspicions before it probes. The floored expiry won by 1 ms (11.132 vs the 11.133
/// probe that would have refuted it); the voter terminalised the live leader, both sides held the
/// other DEAD, neither re-dialled, and the leader's CTM replaced the live voter.
///
/// Deterministic by construction, not by timing: the hint is delivered inside the send of the
/// leader's probe (the worst phase, as in production), every peer answers every Ping, and with four
/// peers on a 1 s period the leader is next probed 4 periods later, while the 3 s floor expires at
/// the tick 3 or 4 periods later. The tick runs `expireSuspectMembers` before `probeTarget`, so on
/// the unfixed protocol the expiry precedes the refuting probe in every interleaving.
class SwimLinkLostLivePeerTest {
    private static final NodeId SELF_ID = new NodeId("node-self");
    /// The evicting leader. Its id sorts first so it is the first probe target.
    private static final NodeId LEADER = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");
    private static final NodeId NODE_C = new NodeId("node-c");
    private static final NodeId NODE_D = new NodeId("node-d");
    private static final InetSocketAddress SELF_ADDR = new InetSocketAddress("127.0.0.1", 9000);
    private static final InetSocketAddress ADDR_A = new InetSocketAddress("127.0.0.1", 9001);
    private static final InetSocketAddress ADDR_B = new InetSocketAddress("127.0.0.1", 9002);
    private static final InetSocketAddress ADDR_C = new InetSocketAddress("127.0.0.1", 9003);
    private static final InetSocketAddress ADDR_D = new InetSocketAddress("127.0.0.1", 9004);
    private static final long PERIOD_MS = 1_000L;
    private static final long FLOOR_MS = 3_000L;

    /// Production period (1 s) and peer count (4); suspectTimeout well above the floor so a
    /// floored expiry is distinguishable; probeTimeout short so a silent peer's cycle ends inside
    /// one period; joinGrace 0.
    private final SwimConfig config = swimConfig(timeSpan(PERIOD_MS).millis(),
                                                 timeSpan(200).millis(),
                                                 3,
                                                 timeSpan(8).seconds(),
                                                 8,
                                                 timeSpan(40).millis()).withJoinGrace(timeSpan(0).millis());
    /// Live QUIC links of this node. The leader is absent: it evicted the link.
    private final Set<NodeId> liveTransport = new CopyOnWriteArraySet<>(Set.of(NODE_B, NODE_C, NODE_D));
    private final CopyOnWriteArrayList<SwimObservation> observations = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<NodeId> firstHandFaulty = new CopyOnWriteArrayList<>();
    private EvictingLeaderTransport transport;
    private SwimProtocol protocol;

    @BeforeEach
    void setUp() {
        transport = new EvictingLeaderTransport();
    }

    @AfterEach
    void tearDown() {
        Option.option(protocol).onPresent(SwimProtocol::stop);
    }

    /// RED at rc5: the leader answers every Ping, yet the floored LINK_LOST suspicion expires
    /// before the leader is probed again, and the lone first-hand verdict is emitted as the death
    /// verdict (`DepartedObserved`, the kill-gate vetoed by the same hint).
    @Test
    void linkLostHint_peerAnswersEveryProbe_noDeathVerdict() {
        startWith(Map.of(LEADER, true, NODE_B, true, NODE_C, true, NODE_D, true));

        awaitPastTheFloor();

        assertThat(forLeader(SwimObservation.DepartedObserved.class))
            .as("a peer that answers every probe must not get a death verdict from a LINK_LOST hint (%s since the hint)",
                System.currentTimeMillis() - transport.hintedAt.get())
            .isEmpty();
        assertThat(forLeader(SwimObservation.FaultyObserved.class))
            .as("no FAULTY evidence for a peer that answers every probe")
            .isEmpty();
        assertThat(firstHandFaulty).as("no first-hand onMemberFaulty for the live leader").doesNotContain(LEADER);
        assertThat(protocol.members().get(LEADER).state()).isEqualTo(MemberState.ALIVE);
    }

    /// The #1061 faster-death guard and this harness's positive control: the same leader answers
    /// its first probe and is then dead. The LINK_LOST floor still drives the death verdict, no
    /// later than the first tick after the floor. Green at rc5; must stay green with the fix.
    @Test
    void linkLostHint_peerSilentAfterEviction_stillDiesAtTheFloor() {
        startWith(Map.of(LEADER, false, NODE_B, true, NODE_C, true, NODE_D, true));

        await().atMost(Duration.ofSeconds(8))
               .pollInterval(Duration.ofMillis(10))
               .until(() -> !forLeader(SwimObservation.DepartedObserved.class).isEmpty());
        var diedAfter = System.currentTimeMillis() - transport.hintedAt.get();

        assertThat(diedAfter)
            .as("a dead peer under a current LINK_LOST hint dies at the floor, within one period after it")
            .isBetween(FLOOR_MS - 50, FLOOR_MS + PERIOD_MS + 500);
        assertThat(forLeader(SwimObservation.FaultyObserved.class)).hasSize(1);
    }

    /// `answersAfterHint`: whether the leader keeps answering Pings after the eviction. Every peer
    /// answers before it, so the leader is HEALTHY when the hint lands.
    private void startWith(Map<NodeId, Boolean> answersAfterHint) {
        transport.answersAfterHint.putAll(answersAfterHint);
        protocol = SwimProtocol.swimProtocol(config,
                                             transport,
                                             new RecordingListener(),
                                             SELF_ID,
                                             SELF_ADDR,
                                             () -> false,
                                             liveTransport::contains)
                               .unwrap();
        protocol.addObservationListener(observations::add);
        transport.protocol.set(protocol);
        seed(LEADER, ADDR_A);
        seed(NODE_B, ADDR_B);
        seed(NODE_C, ADDR_C);
        seed(NODE_D, ADDR_D);
        protocol.start();
    }

    /// Past the floor AND past the tick that would probe the leader again: node-b's second probe
    /// follows the leader's slot in the rotation (b, c, d, then a or b).
    private void awaitPastTheFloor() {
        await().atMost(Duration.ofSeconds(10))
               .pollInterval(Duration.ofMillis(10))
               .until(() -> transport.hintedAt.get() > 0 && transport.pingsTo(ADDR_B) >= 2);
        assertThat(System.currentTimeMillis() - transport.hintedAt.get())
            .as("instrument: the observation must cover the whole floored window")
            .isGreaterThanOrEqualTo(FLOOR_MS);
    }

    private void seed(NodeId nodeId, InetSocketAddress addr) {
        protocol.onMessage(ADDR_B,
                           new Ping(NODE_B, 1L, List.of(MembershipUpdate.membershipUpdate(nodeId, MemberState.ALIVE, 0, addr))));
    }

    private <T extends SwimObservation> List<T> forLeader(Class<T> type) {
        return observations.stream()
                           .filter(type::isInstance)
                           .map(type::cast)
                           .filter(observation -> observation.peer().equals(LEADER))
                           .toList();
    }

    /// Answers each Ping on the sending thread (the pending probe is registered before the send),
    /// and on the FIRST probe of the leader records the LINK_LOST hint right after the answer —
    /// the eviction lands just after the leader's probe, the worst phase.
    private static final class EvictingLeaderTransport implements SwimTransport {
        private static final Map<InetSocketAddress, NodeId> PEERS = Map.of(ADDR_A, LEADER, ADDR_B, NODE_B, ADDR_C, NODE_C, ADDR_D, NODE_D);
        final Map<NodeId, Boolean> answersAfterHint = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicReference<SwimProtocol> protocol = new AtomicReference<>();
        final AtomicBoolean hinted = new AtomicBoolean();
        final AtomicLong hintedAt = new AtomicLong();
        private final CopyOnWriteArrayList<InetSocketAddress> pings = new CopyOnWriteArrayList<>();

        long pingsTo(InetSocketAddress target) {
            return pings.stream().filter(target::equals).count();
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            if (message instanceof Ping ping && PEERS.containsKey(target)) {
                pings.add(target);
                var peer = PEERS.get(target);

                if (!hinted.get() || answersAfterHint.getOrDefault(peer, true)) {
                    protocol.get().onMessage(target, new Ack(peer, ping.sequence(), List.of()));
                }
                if (peer.equals(LEADER) && hinted.compareAndSet(false, true)) {
                    hintedAt.set(System.currentTimeMillis());
                    protocol.get()
                            .recordTransportHint(LEADER,
                                                 new TransportObservation.PeerUnreachable(LEADER,
                                                                                          Causes.cause("QUIC link evicted by the leader after NodeRemoved"),
                                                                                          TransportObservation.HintOrigin.LINK_LOST));
                }
            }

            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> start(int port, SwimMessageHandler handler) {
            return Promise.success(Unit.unit());
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.success(Unit.unit());
        }
    }

    private final class RecordingListener implements SwimMembershipListener {
        @Override public void onMemberJoined(SwimMember member) {}
        @Override public void onMemberSuspect(SwimMember member) {}
        @Override public void onMemberFaulty(SwimMember member, boolean firstHand) {
            if (firstHand) {
                firstHandFaulty.add(member.nodeId());
            }
        }
        @Override public void onMemberLeft(NodeId nodeId) {}
    }
}
