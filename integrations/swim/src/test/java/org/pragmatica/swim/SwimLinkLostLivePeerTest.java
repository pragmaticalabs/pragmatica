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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.swim.SwimConfig.swimConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;


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
        assertThat(forLeader(SwimObservation.DepartedObserved.class)).as("a peer that answers every probe must not get a death verdict from a LINK_LOST hint (%s since the hint)",
                                                                         System.currentTimeMillis() - transport.hintedAt.get())
                  .isEmpty();
        assertThat(forLeader(SwimObservation.FaultyObserved.class)).as("no FAULTY evidence for a peer that answers every probe")
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

        assertThat(diedAfter).as("a dead peer under a current LINK_LOST hint dies at the floor, within one period after it")
                  .isBetween(FLOOR_MS - 50, FLOOR_MS + PERIOD_MS + 500);
        assertThat(forLeader(SwimObservation.FaultyObserved.class)).hasSize(1);
        assertThat(transport.otherPeerPingsBeforeDeath.get()).as("the verdict lands in the same tick as at rc5: the 3rd or 4th tick after the hint, counted by the "
                                                                + "one round-robin probe of another peer each earlier tick sends (the hint-time probe neither "
                                                                + "delays nor advances it)")
                  .isBetween(2L, 3L);
    }

    /// #2724 side effect: the hint-time probe of a silent peer times out (direct, then indirect)
    /// and adds exactly ONE Lifeguard LHM increment, even when a second hint arrives while that
    /// probe is pending (the pending probe is not stacked). The next verified Ack (node-b's
    /// round-robin probe one tick later) removes it.
    @Test
    void linkLostHint_silentPeer_hintProbeTimeoutAddsOneLhmIncrementClearedByNextAck() {
        transport.secondHint.set(true);
        startWith(Map.of(LEADER, false, NODE_B, true, NODE_C, true, NODE_D, true));
        await().atMost(Duration.ofSeconds(5))
             .pollInterval(Duration.ofMillis(10))
             .until(() -> transport.hintedAt.get() > 0 && System.currentTimeMillis() - transport.hintedAt.get() >= 700);
        var lhmAfterHintProbe = protocol.lhmScoreForTest();
        var pingsToB = transport.pingsTo(ADDR_B);

        assertThat(pingsToB).as("instrument: read after the hint-time probe cycle (2 x 200 ms) and before the next tick's verified ack")
                  .isZero();
        assertThat(lhmAfterHintProbe).as("one failed hint-time probe cycle = exactly one LHM increment (two hints, one probe)")
                  .isEqualTo(1);
        await().atMost(Duration.ofSeconds(3))
             .pollInterval(Duration.ofMillis(10))
             .until(() -> transport.pingsTo(ADDR_B) >= 1);
        await().atMost(Duration.ofSeconds(1))
             .pollInterval(Duration.ofMillis(10))
             .untilAsserted(() -> assertThat(protocol.lhmScoreForTest()).as("the next verified probe ack clears the increment")
                                            .isZero());
    }

    /// #2724 probe filter 1 (SUSPECT): a hint that does not leave the peer SUSPECT sends no probe.
    /// The leader dies at the floor (FAULTY, not escalated again by [#SwimProtocol]'s suspicion
    /// path); a later current hint for it (the ClusterSync pong report arriving after the verdict)
    /// must not probe a FAULTY member: that would revive a condemned peer's traffic and, for an
    /// untombstoned one, its ALIVE path. Read and hint are consecutive, past the probe timeout of
    /// any earlier probe, so a Ping to the leader in between can only come from the hint.
    @Test
    void peerUnresponsiveHint_peerAlreadyFaulty_notProbed() {
        startWith(Map.of(LEADER, false, NODE_B, true, NODE_C, true, NODE_D, true));
        await().atMost(Duration.ofSeconds(8))
             .pollInterval(Duration.ofMillis(10))
             .until(() -> !forLeader(SwimObservation.DepartedObserved.class).isEmpty());
        pause(3 * 200L);
        var pingsBefore = transport.pingsTo(ADDR_A);
        var stateAtHint = protocol.members().get(LEADER).state();

        protocol.recordTransportHint(LEADER, peerUnresponsiveHint());

        assertThat(stateAtHint).as("instrument: the leader is FAULTY when the second hint lands")
                  .isEqualTo(MemberState.FAULTY);
        assertThat(transport.pingsTo(ADDR_A)).as("a hint that finds the peer non-SUSPECT sends no probe")
                  .isEqualTo(pingsBefore);
    }

    /// #2724 probe filter 2 (current hint): a LINK_LOST hint recorded while the link is connected
    /// (a late hint after a reconnect) is not current death evidence. The peer still goes SUSPECT,
    /// on the unfloored window, and the hint-time probe is not sent: the connected link already
    /// contradicts the hint, so the floor that the probe protects does not apply.
    @Test
    void linkLostHint_linkConnected_suspectedOnUnflooredWindowAndNotProbed() {
        transport.autoHint.set(false);
        liveTransport.add(LEADER);
        startWith(Map.of(LEADER, true, NODE_B, true, NODE_C, true, NODE_D, true));
        await().atMost(Duration.ofSeconds(3))
             .pollInterval(Duration.ofMillis(5))
             .until(() -> transport.pingsTo(ADDR_A) >= 1);
        var pingsBefore = transport.pingsTo(ADDR_A);

        protocol.recordTransportHint(LEADER, linkLostHint());

        assertThat(transport.pingsTo(ADDR_A)).as("a hint that is not current death evidence sends no hint-time probe (the next round-robin probe of the leader is 4 periods away)")
                  .isEqualTo(pingsBefore);
        assertThat(protocol.members().get(LEADER).state()).as("instrument: the late hint still suspects a healthy peer")
                  .isEqualTo(MemberState.SUSPECT);
    }

    private static TransportObservation.PeerUnreachable peerUnresponsiveHint() {
        return new TransportObservation.PeerUnreachable(LEADER,
                                                        Causes.cause("ClusterSync missed pongs"),
                                                        TransportObservation.HintOrigin.PEER_UNRESPONSIVE);
    }

    private static void pause(long millis) {
        await().pollDelay(Duration.ofMillis(millis))
             .atMost(Duration.ofMillis(millis + 1_000))
             .until(() -> true);
    }

    private static TransportObservation.PeerUnreachable linkLostHint() {
        return new TransportObservation.PeerUnreachable(LEADER,
                                                        Causes.cause("QUIC link evicted by the leader after NodeRemoved"),
                                                        TransportObservation.HintOrigin.LINK_LOST);
    }

    /// `answersAfterHint`: whether the leader keeps answering Pings after the eviction. Every peer
    /// answers before it, so the leader is HEALTHY when the hint lands.
    private void startWith(Map<NodeId, Boolean> answersAfterHint) {
        build(answersAfterHint);
        protocol.start();
    }

    private void build(Map<NodeId, Boolean> answersAfterHint) {
        transport.answersAfterHint.putAll(answersAfterHint);
        protocol = SwimProtocol.swimProtocol(config,
                                             transport,
                                             new RecordingListener(),
                                             SELF_ID,
                                             SELF_ADDR,
                                             () -> false,
                                             liveTransport::contains).unwrap();
        // Listeners run in registration order on the protocol thread: recordDeath must precede observations::add,
        // or the awaiting test thread sees the death in `observations` while the ping count still holds its -1 sentinel.
        protocol.addObservationListener(transport::recordDeath);
        protocol.addObservationListener(observations::add);
        transport.protocol.set(protocol);
        seed(LEADER, ADDR_A);
        seed(NODE_B, ADDR_B);
        seed(NODE_C, ADDR_C);
        seed(NODE_D, ADDR_D);
    }

    /// Past the floor AND past the tick that would probe the leader again: node-b's second probe
    /// follows the leader's slot in the rotation (b, c, d, then a or b).
    private void awaitPastTheFloor() {
        await().atMost(Duration.ofSeconds(10))
             .pollInterval(Duration.ofMillis(10))
             .until(() -> transport.hintedAt.get() > 0 && transport.pingsTo(ADDR_B) >= 2);
        assertThat(System.currentTimeMillis() - transport.hintedAt.get()).as("instrument: the observation must cover the whole floored window")
                  .isGreaterThanOrEqualTo(FLOOR_MS);
    }

    private void seed(NodeId nodeId, InetSocketAddress addr) {
        protocol.onMessage(ADDR_B,
                           new Ping(NODE_B,
                                    1L,
                                    List.of(MembershipUpdate.membershipUpdate(nodeId, MemberState.ALIVE, 0, addr))));
    }

    private <T extends SwimObservation> List<T> forLeader(Class<T> type) {
        return observations.stream()
                           .filter(type::isInstance)
                           .map(type::cast)
                           .filter(observation -> observation.peer()
                                                             .equals(LEADER))
                           .toList();
    }

    /// Answers each Ping on the sending thread (the pending probe is registered before the send),
    /// and on the FIRST probe of the leader records the LINK_LOST hint right after the answer —
    /// the eviction lands just after the leader's probe, the worst phase.
    private static final class EvictingLeaderTransport implements SwimTransport {
        private static final Map<InetSocketAddress, NodeId> PEERS = Map.of(ADDR_A,
                                                                           LEADER,
                                                                           ADDR_B,
                                                                           NODE_B,
                                                                           ADDR_C,
                                                                           NODE_C,
                                                                           ADDR_D,
                                                                           NODE_D);

        final Map<NodeId, Boolean> answersAfterHint = new java.util.concurrent.ConcurrentHashMap<>();
        final AtomicReference<SwimProtocol> protocol = new AtomicReference<>();
        final AtomicBoolean hinted = new AtomicBoolean();
        final AtomicLong hintedAt = new AtomicLong();
        final AtomicBoolean secondHint = new AtomicBoolean();
        final AtomicBoolean autoHint = new AtomicBoolean(true);
        final AtomicLong pingIndexAtHint = new AtomicLong();
        final AtomicLong otherPeerPingsBeforeDeath = new AtomicLong(-1);
        private final CopyOnWriteArrayList<InetSocketAddress> pings = new CopyOnWriteArrayList<>();

        /// On the leader's death verdict: how many probes of OTHER peers were sent since the hint,
        /// i.e. how many ticks passed before the verdict (one round-robin probe per tick).
        void recordDeath(SwimObservation observation) {
            if (observation instanceof SwimObservation.DepartedObserved departed && departed.peer().equals(LEADER)) {
                otherPeerPingsBeforeDeath.compareAndSet(-1, otherPeerPingsSinceHint());
            }
        }

        private long otherPeerPingsSinceHint() {
            return pings.subList((int) pingIndexAtHint.get(),
                                 pings.size())
                        .stream()
                        .filter(target -> !ADDR_A.equals(target))
                        .count();
        }

        long pingsTo(InetSocketAddress target) {
            return pings.stream()
                        .filter(target::equals)
                        .count();
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            if (message instanceof Ping ping && PEERS.containsKey(target)) {
                var peer = PEERS.get(target);

                if (!hinted.get() || answersAfterHint.getOrDefault(peer, true)) {
                    protocol.get().onMessage(target,
                                             new Ack(peer, ping.sequence(), List.of()));
                }

                if (autoHint.get() && peer.equals(LEADER) && hinted.compareAndSet(false, true)) {
                    hintedAt.set(System.currentTimeMillis());
                    pingIndexAtHint.set(pings.size());
                    protocol.get()
                            .recordTransportHint(LEADER,
                                                 new TransportObservation.PeerUnreachable(LEADER,
                                                                                          Causes.cause("QUIC link evicted by the leader after NodeRemoved"),
                                                                                          TransportObservation.HintOrigin.LINK_LOST));
                    if (secondHint.get()) {
                        protocol.get()
                                .recordTransportHint(LEADER,
                                                     new TransportObservation.PeerUnreachable(LEADER,
                                                                                              Causes.cause("ClusterSync missed pongs"),
                                                                                              TransportObservation.HintOrigin.PEER_UNRESPONSIVE));
                    }
                }

                // Counted LAST: a test thread that sees this Ping in `pings` must also see its Ack (and the hint)
                // already processed, or it races the protocol thread (everSeenHealthy, member state).
                pings.add(target);
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
        @Override
        public void onMemberJoined(SwimMember member) {}

        @Override
        public void onMemberSuspect(SwimMember member) {}

        @Override
        public void onMemberFaulty(SwimMember member, boolean firstHand) {
            if (firstHand) {
                firstHandFaulty.add(member.nodeId());
            }
        }

        @Override
        public void onMemberLeft(NodeId nodeId) {}
    }
}
