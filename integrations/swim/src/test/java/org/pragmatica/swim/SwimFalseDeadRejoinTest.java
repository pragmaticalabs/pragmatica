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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
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
    private final CopyOnWriteArrayList<SwimObservation> observed = new CopyOnWriteArrayList<>();

    @AfterEach
    void tearDown() {
        started.forEach(SwimProtocol::stop);
        net.clear();
        wire.shutdownNow();
    }

    @Test
    void liveNodeDeclaredFaultyByDroppedDatagrams_isReadmittedAfterTheDropsStop() {
        runScenario();
    }

    /// The Ember shape: every peer's writes TO the live node are refused (backpressure), while its own
    /// datagrams and the acks to its own probes still flow, so it never sees a Suspect about itself and
    /// keeps receiving reachability evidence.
    @Test
    void liveNodeDeclaredFaultyByDroppedInboundOnly_isReadmittedAfterTheDropsStop() {
        inboundOnly.set(true);
        runScenario();
    }

    private void runScenario() {
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

        liveIsolated.set(false);

        await().atMost(30, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(forLive(SwimObservation.HealthyObserved.class)).as("a live node whose drops stopped must be HEALTHY again on the observer (selfIncarnation=%s, observer view=%s)",
                                                                                                live.selfIncarnation(),
                                                                                                observer.members().get(LIVE))
                                                                                             .hasSizeGreaterThan(healthyBeforeHeal));
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

        private void deliver(InetSocketAddress target, SwimMessage message) {
            if (liveIsolated.get() && (target.getPort() == LIVE_PORT || (!inboundOnly.get() && selfPort == LIVE_PORT))) {
                return;
            }

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
