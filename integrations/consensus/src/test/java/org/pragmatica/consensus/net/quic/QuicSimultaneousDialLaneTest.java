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
package org.pragmatica.consensus.net.quic;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.RepetitionInfo;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.junit.jupiter.api.Assertions.fail;

/// #1578 — the shape the ticket asked for: two real [QuicClusterNetwork]s dial EACH OTHER at the same
/// moment (the lower id as designated initiator, the higher one forced), with traffic on every lane in
/// both directions while the duplicate connection is resolved. Once both ends are CONNECTED, a fresh
/// probe on EVERY lane (FORWARD included) in EACH direction must be delivered. Repeated, because the
/// lane-loss ordering is a race: in the failing CI run a supersede left FORWARD dead with nothing but
/// forward timeouts to show for it.
@Timeout(60)
class QuicSimultaneousDialLaneTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(15).seconds();
    /// Stretched so the keepalive scheduler and the liveness sweep stay out of the window under test.
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(30).seconds();
    private static final long SETTLE_MILLIS = 300;

    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
    }

    @RepeatedTest(20)
    void simultaneousDial_everyLaneCarriesTrafficBothWays_afterTheDuplicateResolves(RepetitionInfo repetition) {
        var round = repetition.getCurrentRepetition();
        var low = new NodeId("sd-a-" + round);
        var high = new NodeId("sd-b-" + round);
        var toLow = new CopyOnWriteArrayList<LaneProbe>();
        var toHigh = new CopyOnWriteArrayList<LaneProbe>();
        var lowNet = liveNetwork(low, toLow);
        var highNet = liveNetwork(high, toHigh);
        var spraying = new AtomicBoolean(true);
        var spray = CompletableFuture.runAsync(() -> sprayUntilStopped(lowNet, low, highNet, high, spraying));

        CompletableFuture.allOf(CompletableFuture.runAsync(() -> lowNet.dialForTests(infoOf(high, highNet), false)),
                                CompletableFuture.runAsync(() -> highNet.dialForTests(infoOf(low, lowNet), true)))
                         .orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS)
                         .join();
        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), "both ends CONNECTED");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(SETTLE_MILLIS));
        spraying.set(false);
        spray.orTimeout(AWAIT.millis(), TimeUnit.MILLISECONDS).join();

        Arrays.stream(StreamType.values())
              .forEach(lane -> sendFinalProbes(round, lane, lowNet, low, highNet, high));

        awaitTrue(() -> everyLaneDelivered(toHigh, "final-" + round + "-low-to-high-"),
                  "low -> high on every lane: missing " + missing(toHigh, "final-" + round + "-low-to-high-"));
        awaitTrue(() -> everyLaneDelivered(toLow, "final-" + round + "-high-to-low-"),
                  "high -> low on every lane: missing " + missing(toLow, "final-" + round + "-high-to-low-"));
    }

    private static void sendFinalProbes(int round,
                                        StreamType lane,
                                        QuicClusterNetwork lowNet,
                                        NodeId low,
                                        QuicClusterNetwork highNet,
                                        NodeId high) {
        lowNet.send(high, LaneProbe.laneProbe(low, lane, "final-" + round + "-low-to-high-" + lane));
        highNet.send(low, LaneProbe.laneProbe(high, lane, "final-" + round + "-high-to-low-" + lane));
    }

    private static void sprayUntilStopped(QuicClusterNetwork lowNet,
                                          NodeId low,
                                          QuicClusterNetwork highNet,
                                          NodeId high,
                                          AtomicBoolean spraying) {
        var sequence = new AtomicInteger();

        while (spraying.get()) {
            var n = sequence.incrementAndGet();

            Arrays.stream(StreamType.values())
                  .forEach(lane -> sprayOnce(lowNet, low, highNet, high, lane, n));
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(2));
        }
    }

    private static void sprayOnce(QuicClusterNetwork lowNet,
                                  NodeId low,
                                  QuicClusterNetwork highNet,
                                  NodeId high,
                                  StreamType lane,
                                  int n) {
        lowNet.send(high, LaneProbe.laneProbe(low, lane, "spray-" + n));
        highNet.send(low, LaneProbe.laneProbe(high, lane, "spray-" + n));
    }

    private static boolean everyLaneDelivered(List<LaneProbe> received, String prefix) {
        return missing(received, prefix).isEmpty();
    }

    private static List<StreamType> missing(List<LaneProbe> received, String prefix) {
        return Arrays.stream(StreamType.values())
                     .filter(Predicate.not(lane -> received.stream()
                                                           .anyMatch(probe -> probe.marker().equals(prefix + lane))))
                     .toList();
    }

    private static boolean connected(QuicClusterNetwork network, NodeId peer) {
        return network.peerPhaseForTests(peer)
                      .filter(phase -> phase == PeerState.Phase.CONNECTED)
                      .isPresent();
    }

    private static NodeInfo infoOf(NodeId id, QuicClusterNetwork network) {
        var port = network.boundPort().fold(() -> fail("not bound"), bound -> bound);

        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", port).fold(_ -> fail("bad address"), a -> a));
    }

    private QuicClusterNetwork liveNetwork(NodeId id, List<LaneProbe> sink) {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", 19994).fold(_ -> fail("bad address"), a -> a));
        var router = MessageRouter.mutable();
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                       .fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                       .fold(_ -> fail("client ssl"), ssl -> ssl);

        router.addRoute(LaneProbe.class, sink::add);
        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, router, serverSsl, clientSsl);

        networks.add(network);
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        return network;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what);
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 2;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
            @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
