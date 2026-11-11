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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.swim.SwimMember.MemberState;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2456 (CommunityObservability 10-28): a LIVE node that every peer's SWIM declared FAULTY, because
/// its datagrams were dropped for longer than the suspicion window, must be re-admitted once the
/// datagrams flow again. At rc5 the observers keep the FAULTY verdict at incarnation k, the live node
/// still answers at k (nothing told it of the verdict, and the acks it gets count as reachability, so
/// the at-risk bump never fires), and `Alive(k)` does not out-rank the tombstone: no HealthyObserved
/// follows the death verdict, so MembershipFsm (DEAD->OBSERVED needs a strictly higher incarnation) never reopens it.
class SwimFalseDeadRejoinTest {
    private static final NodeId OBSERVER = new NodeId("obs");
    private static final NodeId HELPER = new NodeId("helper");
    private static final NodeId LIVE = new NodeId("live");
    private static final int OBSERVER_PORT = 29201;
    private static final int HELPER_PORT = 29202;
    private static final int LIVE_PORT = 29203;

    private final Map<InetSocketAddress, SwimTransport.SwimMessageHandler> net = new ConcurrentHashMap<>();
    private final ExecutorService wire = Executors.newSingleThreadExecutor();
    private final List<SwimProtocol> started = new ArrayList<>();
    private final AtomicBoolean liveIsolated = new AtomicBoolean(false);
    /// true: drop only datagrams addressed TO the live node (it can still send and gets acks to its own probes).
    private final AtomicBoolean inboundOnly = new AtomicBoolean(false);
    /// true: drop datagrams between the live node and the observer only (the helper still reaches both).
    private final AtomicBoolean pairCut = new AtomicBoolean(false);
    /// true (with `pairCut`): the observer's gossip about the live node never reaches the helper, and the observer sends
    /// no PingReq, so the observer alone declares the live node dead and nobody else hears of it.
    private final AtomicBoolean observerVerdictPrivate = new AtomicBoolean(false);
    /// Sequences of the Pings the helper relayed to the observer on behalf of a requester (D3): the observer's Acks to
    /// them carry the death notice and are exempt from the gossip filter.
    private final Set<Long> relayedSequences = ConcurrentHashMap.newKeySet();
    /// Datagrams the live node sent while it was inbound-isolated (`inboundOnly`).
    private final AtomicInteger liveSpoke = new AtomicInteger();
    /// Every datagram delivered to the live node that carried a SUSPECT or FAULTY entry about the live node itself,
    /// as "sender-port:message-type:state": the ways the live node can learn of its death.
    private final CopyOnWriteArrayList<String> newsOfDeathToLive = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<SwimObservation> observed = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(SwimProtocol::stop);
        net.clear();
        wire.shutdownNow();
    }

    @Test
    void liveNodeDeclaredFaultyByDroppedDatagrams_isReadmittedAfterTheDropsStop() {
        runScenario(false);
    }

    /// #3028 arm 1e (the ruled scenario): X<->T is cut, X<->I<->T is up. T alone declares X dead at k: T sends no PingReq
    /// and its gossip about X never reaches I, so I keeps X ALIVE at k and X (hearing from I) never bumps its incarnation.
    /// The co-confirmation kill-gate holds a lone accuser's verdict (no FaultyObserved, no death record), so T's
    /// transport reports the link to X lost: the transport-veto corroboration lets T's verdict through.
    /// T's Faulty(X,k) stops circulating before the helper's first relay (the relay is held until T's buffer holds no FAULTY entry), and I's Alive(X,k) is refused by T's death-record gate, so the ONLY path by
    /// which X can learn of its death is the notice T answers to the Ping I relays for X's PingReq (`onBehalfOf` = X):
    /// X refutes with k+1 and T re-admits it. RED without the notice (relay marker ignored): X stays dead at T.
    @Test
    void liveNodeReachableOnlyViaTheHelper_isReadmittedByTheObserverThroughTheRelay() {
        var observer = node(OBSERVER, OBSERVER_PORT, 1L);
        var helper = node(HELPER, HELPER_PORT, 1L);
        var live = node(LIVE, LIVE_PORT, 1L);

        observer.addObservationListener(this::captureAtTheVerdict);
        seed(observer, HELPER, HELPER_PORT, LIVE, LIVE_PORT);
        seed(helper, OBSERVER, OBSERVER_PORT, LIVE, LIVE_PORT);
        seed(live, OBSERVER, OBSERVER_PORT, HELPER, HELPER_PORT);
        awaitHealthyOnObserver();
        observerVerdictPrivate.set(true);
        pairCut.set(true);
        observer.recordTransportHint(LIVE,
                                     new TransportObservation.PeerUnreachable(LIVE,
                                                                              Causes.cause("link to the live node lost"),
                                                                              TransportObservation.HintOrigin.LINK_LOST));

        await().atMost(30, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(verdictSnapshot.get()).as("instrument: the observer alone declared the live node dead")
                                                                     .isNotNull());
        var verdict = verdictSnapshot.get();

        assertThat(verdict.observerView()).as("instrument: the observer holds the live node FAULTY at incarnation 1")
                                          .isEqualTo(MemberState.FAULTY + "@1");
        assertThat(verdict.helperView()).as("instrument: the helper still holds the live node ALIVE at the same incarnation")
                                        .isEqualTo(MemberState.ALIVE + "@1");
        assertThat(verdict.liveIncarnation()).as("instrument: the live node has not advanced its incarnation (nothing told it of the verdict)")
                                             .isEqualTo(1L);
        await().atMost(30, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(forLive(SwimObservation.HealthyObserved.class).size()).as("T must re-admit X through the relayed notice alone (selfIncarnation=%s, observer view=%s)",
                                                                                                         live.selfIncarnation(),
                                                                                                         observer.members().get(LIVE))
                                                                                                     .isGreaterThan(verdict.healthyAtVerdict()));
        assertThat(newsOfDeathToLive).as("instrument: the live node learned of its death ONLY through a FAULTY entry relayed by the helper (never a SUSPECT, never from the observer)")
                                     .isNotEmpty()
                                     .allSatisfy(news -> assertThat(news).startsWith(HELPER_PORT + ":")
                                                                         .endsWith(":" + MemberState.FAULTY));
    }

    /// The Ember shape: every peer's writes TO the live node are refused (backpressure), while its own
    /// datagrams and the acks to its own probes still flow, so it never sees a Suspect about itself and
    /// keeps receiving reachability evidence.
    @Test
    void liveNodeDeclaredFaultyByDroppedInboundOnly_isReadmittedAfterTheDropsStop() {
        runScenario(true);
    }

    private record VerdictSnapshot(String observerView, String helperView, long liveIncarnation, int healthyAtVerdict) {}

    private final AtomicReference<VerdictSnapshot> verdictSnapshot = new AtomicReference<>();
    private SwimProtocol observerNode;
    private SwimProtocol helperNode;
    private SwimProtocol liveNode;

    private void runScenario(boolean inboundOnlyAfterTheVerdict) {
        var observer = node(OBSERVER, OBSERVER_PORT, 1L);
        var helper = node(HELPER, HELPER_PORT, 1L);
        var live = node(LIVE, LIVE_PORT, 1L);

        observer.addObservationListener(observed::add);
        seed(observer, HELPER, HELPER_PORT, LIVE, LIVE_PORT);
        seed(helper, OBSERVER, OBSERVER_PORT, LIVE, LIVE_PORT);
        seed(live, OBSERVER, OBSERVER_PORT, HELPER, HELPER_PORT);
        await().atMost(15, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(forLive(SwimObservation.HealthyObserved.class)).as("instrument: the observer saw the live node HEALTHY before the fault")
                                                                                            .isNotEmpty());

        liveIsolated.set(true);

        await().atMost(30, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(forLive(SwimObservation.DepartedObserved.class)).as("instrument: the dropped datagrams drove the live node to the death verdict")
                                                                                             .isNotEmpty());
        var healthyBeforeHeal = forLive(SwimObservation.HealthyObserved.class).size();

        if (inboundOnlyAfterTheVerdict) {
            holdInboundOnlyUntilTheLiveNodeSpeaks();
        }

        liveIsolated.set(false);

        await().atMost(30, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(forLive(SwimObservation.HealthyObserved.class)).as("a live node whose drops stopped must be HEALTHY again on the observer (selfIncarnation=%s, observer view=%s)",
                                                                                                live.selfIncarnation(),
                                                                                                observer.members().get(LIVE))
                                                                                             .hasSizeGreaterThan(healthyBeforeHeal));
    }

    /// The Ember shape after the verdict: the live node's own datagrams flow again while everything addressed to it is
    /// still refused. The barrier is the live node's own traffic (>= 3 datagrams), never a sleep.
    private void holdInboundOnlyUntilTheLiveNodeSpeaks() {
        inboundOnly.set(true);
        await().atMost(15, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(liveSpoke.get()).as("instrument: the live node spoke while inbound-isolated")
                                                               .isGreaterThanOrEqualTo(3));
    }

    private void awaitHealthyOnObserver() {
        await().atMost(15, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(forLive(SwimObservation.HealthyObserved.class)).as("instrument: the observer saw the live node HEALTHY before the fault")
                                                                                            .isNotEmpty());
    }

    /// Runs on the observer's observation thread at the FAULTY edge, before any relay round trip can undo it.
    private void captureAtTheVerdict(SwimObservation observation) {
        observed.add(observation);
        if (observation instanceof SwimObservation.FaultyObserved faulty && faulty.peer().equals(LIVE)) {
            verdictSnapshot.compareAndSet(null,
                                          new VerdictSnapshot(observerNode.members().get(LIVE).state() + "@" + observerNode.members().get(LIVE).incarnation(),
                                                              helperNode.members().get(LIVE).state() + "@" + helperNode.members().get(LIVE).incarnation(),
                                                              liveNode.selfIncarnation(),
                                                              forLive(SwimObservation.HealthyObserved.class).size()));
        }
    }

    private void rememberNode(NodeId id, SwimProtocol protocol) {
        if (id.equals(OBSERVER)) {
            observerNode = protocol;
        } else if (id.equals(HELPER)) {
            helperNode = protocol;
        } else {
            liveNode = protocol;
        }
    }

    private <T extends SwimObservation> List<T> forLive(Class<T> type) {
        return observed.stream()
                       .filter(type::isInstance)
                       .map(type::cast)
                       .filter(o -> peerOf(o).equals(LIVE))
                       .toList();
    }

    private static NodeId peerOf(SwimObservation o) {
        return switch (o) {
            case SwimObservation.HealthyObserved h -> h.peer();
            case SwimObservation.FaultyObserved f -> f.peer();
            case SwimObservation.DepartedObserved d -> d.peer();
            default -> new NodeId("-");
        };
    }

    private static void seed(SwimProtocol protocol, NodeId a, int aPort, NodeId b, int bPort) {
        protocol.addSeedMember(a, new InetSocketAddress("127.0.0.1", aPort));
        protocol.addSeedMember(b, new InetSocketAddress("127.0.0.1", bPort));
    }

    private SwimProtocol node(NodeId id, int port, long incarnation) {
        var config = SwimConfig.swimConfig(timeSpan(100).millis(),
                                           timeSpan(80).millis(),
                                           2,
                                           timeSpan(800).millis(),
                                           8,
                                           timeSpan(200).millis(),
                                           "c",
                                           0,
                                           timeSpan(0).millis());
        var address = new InetSocketAddress("127.0.0.1", port);
        var transport = new MemoryTransport(address, port);
        var protocol = SwimProtocol.swimProtocol(config, transport, new SwimProtocolTest.RecordingListener(), id, address, () -> false)
                                   .unwrap();

        transport.start(port, protocol::onMessage);
        protocol.announceJoin(NodeInfo.nodeInfo(id,
                                                NodeAddress.nodeAddress("127.0.0.1", port).unwrap(),
                                                Map.of(NodeInfo.LABEL_ROLE, "core")),
                              "c",
                              incarnation,
                              1000L + port,
                              List.of());
        protocol.start();
        started.add(protocol);
        rememberNode(id, protocol);

        return protocol;
    }

    /// One wire thread; a datagram to or from the LIVE node is dropped while `liveIsolated`.
    private final class MemoryTransport implements SwimTransport {
        private final InetSocketAddress self;
        private final int selfPort;

        MemoryTransport(InetSocketAddress self, int selfPort) {
            this.self = self;
            this.selfPort = selfPort;
        }

        @Override
        public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
            wire.submit(() -> deliver(target, message));

            return Promise.success(Unit.unit());
        }

        private static boolean isLiveObserverPair(int from, int to) {
            return (from == LIVE_PORT && to == OBSERVER_PORT) || (from == OBSERVER_PORT && to == LIVE_PORT);
        }

        private void deliver(InetSocketAddress target, SwimMessage message) {
            if (pairCut.get() && isLiveObserverPair(selfPort, target.getPort())) {
                return;
            }

            if (observerVerdictPrivate.get() && selfPort == OBSERVER_PORT && message instanceof SwimMessage.PingReq) {
                return;
            }

            if (liveIsolated.get() && (target.getPort() == LIVE_PORT || (!inboundOnly.get() && selfPort == LIVE_PORT))) {
                return;
            }

            if (relayHeld(target, message)) {
                return;
            }

            noteRelayedPing(target, message);
            noteNewsOfDeath(target, message);
            countLiveSpeech(message);
            var handler = net.get(new InetSocketAddress("127.0.0.1", target.getPort()));

            if (handler != null) {
                handler.onMessage(self, filtered(target, message));
            }
        }

        private void countLiveSpeech(SwimMessage message) {
            if (liveIsolated.get() && inboundOnly.get() && selfPort == LIVE_PORT) {
                liveSpoke.incrementAndGet();
            }
        }

        /// The helper's relay to the observer waits until the observer's own Faulty(live) gossip has stopped circulating (its
        /// dissemination buffer holds no FAULTY entry), so the only FAULTY entry a relayed Ack can carry is the death notice.
        private boolean relayHeld(InetSocketAddress target, SwimMessage message) {
            return observerVerdictPrivate.get() && selfPort == HELPER_PORT && target.getPort() == OBSERVER_PORT
                   && message instanceof SwimMessage.Ping ping && ping.onBehalfOf().isPresent()
                   && (verdictSnapshot.get() == null || observerNode.piggybackFaultyCountForTest() > 0);
        }

        private void noteNewsOfDeath(InetSocketAddress target, SwimMessage message) {
            if (target.getPort() != LIVE_PORT) {
                return;
            }

            switch (message) {
                case SwimMessage.Ping ping -> noteNews("Ping", ping.piggyback());
                case SwimMessage.Ack ack -> noteNews("Ack", ack.piggyback());
                default -> {}
            }
        }

        private void noteNews(String type, List<SwimMessage.MembershipUpdate> piggyback) {
            piggyback.stream()
                     .filter(update -> update.nodeId().equals(LIVE) && update.state() != MemberState.ALIVE)
                     .forEach(update -> newsOfDeathToLive.add(selfPort + ":" + type + ":" + update.state()));
        }

        private void noteRelayedPing(InetSocketAddress target, SwimMessage message) {
            if (selfPort == HELPER_PORT && target.getPort() == OBSERVER_PORT && message instanceof SwimMessage.Ping ping
                && ping.onBehalfOf().isPresent()) {
                relayedSequences.add(ping.sequence());
            }
        }

        /// While the observer's verdict is private, what the observer sends the helper carries nothing about the live
        /// node, except the FAULTY entry of its Ack to a relayed Ping (the death notice).
        private SwimMessage filtered(InetSocketAddress target, SwimMessage message) {
            return observerVerdictPrivate.get() && selfPort == OBSERVER_PORT && target.getPort() == HELPER_PORT
                   ? withoutLiveEntries(message)
                   : message;
        }

        private SwimMessage withoutLiveEntries(SwimMessage message) {
            return switch (message) {
                case SwimMessage.Ping ping -> new SwimMessage.Ping(ping.from(),
                                                                   ping.sequence(),
                                                                   aboutOthers(ping.piggyback()),
                                                                   ping.onBehalfOf());
                case SwimMessage.Ack ack -> SwimMessage.Ack.ack(ack.from(),
                                                                ack.sequence(),
                                                                relayedSequences.contains(ack.sequence())
                                                                ? withoutLiveSuspicion(ack.piggyback())
                                                                : aboutOthers(ack.piggyback()));
                default -> message;
            };
        }

        /// The relayed Ack keeps the Faulty entry (the notice) and loses the live node's SUSPECT: a suspicion that reached
        /// the helper would reach the live node and make it refute without any notice.
        private static List<SwimMessage.MembershipUpdate> withoutLiveSuspicion(List<SwimMessage.MembershipUpdate> piggyback) {
            return piggyback.stream()
                            .filter(update -> !(update.nodeId().equals(LIVE) && update.state() == MemberState.SUSPECT))
                            .toList();
        }

        private static List<SwimMessage.MembershipUpdate> aboutOthers(List<SwimMessage.MembershipUpdate> piggyback) {
            return piggyback.stream()
                            .filter(update -> !update.nodeId().equals(LIVE))
                            .toList();
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
