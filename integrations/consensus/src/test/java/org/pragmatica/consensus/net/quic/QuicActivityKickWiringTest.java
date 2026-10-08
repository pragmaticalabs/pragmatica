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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
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

import io.netty.handler.codec.quic.QuicheStall;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1727 (M2) — the PRODUCTION wiring of the activity kick, on two real [QuicClusterNetwork]s: a data
/// write through `send` must open the kick window (`writeIfWritable` -> `noteLaneWrite`), the connection
/// must have its kick installed at attach (`installActivityKick`), and the transport's own keepalive must
/// not keep the kick alive.
///
/// The stranded-loss test uses the deterministic recipe of [QuicActivityKickStrandedLossTest] (delay relay,
/// drop A, deliver B, induce the late netty timer) on the dialer's real FORWARD lane, with NO manual kick
/// call. Removing either hook leaves A stranded, which is the tripwire half of that class. With the kick
/// running, its own packets are in flight during the induction, so the quiche timer is NOT asserted -1 here
/// (that positive control belongs to the no-kick recipe); what is asserted is production recovery.
@Timeout(180)
class QuicActivityKickWiringTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(30).seconds();
    private static final long ONE_WAY_DELAY_MS = 300;
    private static final String PAD = "x".repeat(400);
    /// Far below the 30 s transport keepalive, which would otherwise rescue the data and hide a missing kick.
    private static final TimeSpan RECOVERY_BOUND = TimeSpan.timeSpan(4).seconds();
    private static final long LOSS_TIMER_MAX_NANOS = TimeUnit.MILLISECONDS.toNanos(150);

    private final List<QuicClusterNetwork> networks = new java.util.ArrayList<>();
    private QuicActivityKickStrandedLossTest.DelayRelay relay;

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
        networks.clear();
        if (relay != null) {
            relay.close();
        }
    }

    @Test
    void strandedLossOnARealLane_isRecoveredByTheProductionKick() throws Exception {
        var low = new NodeId("kw-a");
        var high = new NodeId("kw-b");
        var received = new CopyOnWriteArrayList<LaneProbe>();
        var lowNet = liveNetwork(low, new CopyOnWriteArrayList<>(), TimeSpan.timeSpan(30).seconds());
        var highNet = liveNetwork(high, received, TimeSpan.timeSpan(30).seconds());

        relay = new QuicActivityKickStrandedLossTest.DelayRelay(boundPort(highNet), ONE_WAY_DELAY_MS);
        lowNet.dialForTests(infoOf(high, relay.port()), false);
        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), "both ends CONNECTED");
        LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(4 * ONE_WAY_DELAY_MS));   // nothing in flight

        relay.dropNextDialerPacket();
        lowNet.send(high, LaneProbe.laneProbe(low, StreamType.FORWARD, "A" + PAD));
        awaitTrue(() -> relay.sentByDialerSinceArm.get() >= 1, "data packet A reached the relay");
        lowNet.send(high, LaneProbe.laneProbe(low, StreamType.FORWARD, "B" + PAD));
        awaitTrue(() -> relay.sentByDialerSinceArm.get() >= 2, "data packet B reached the relay");
        assertThat(relay.droppedByDialer.get()).as("exactly A was dropped").isEqualTo(1);

        var connection = lowNet.activeConnectionForTests(high).unwrap();
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        var timer = -1L;

        while (System.nanoTime() < deadline) {
            timer = QuicheStall.quicheTimerNanos(connection.connection());
            if (timer > 0 && timer < LOSS_TIMER_MAX_NANOS) {
                break;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        assertThat(timer).as("quiche armed the loss timer for A once B was acked").isBetween(1L, LOSS_TIMER_MAX_NANOS);
        assertThat(received.stream().anyMatch(probe -> probe.marker().equals("A" + PAD))).as("A not delivered before the induction").isFalse();

        var reading = QuicheStall.induceLateTimer(connection.connection());

                assertThat(reading.quicheTimerBefore()).as("quiche's timer was due when connectionSend ran").isLessThanOrEqualTo(0);

        awaitTrue(() -> received.stream().anyMatch(probe -> probe.marker().equals("A" + PAD)),
                  RECOVERY_BOUND,
                  "the production kick recovered the stranded data A within " + RECOVERY_BOUND.millis() + " ms");
    }

    /// With the real 1 s transport keepalive running, an idle link sends no kicks: the keepalive rides the
    /// CONTROL lane and must not hold the window open. v-2023 measured 9.79 kicks/s here before that fix.
    @Test
    void idleLink_withTheRealOneSecondKeepAlive_sendsZeroKicks() {
        var low = new NodeId("ki-a");
        var high = new NodeId("ki-b");
        var lowNet = liveNetwork(low, new CopyOnWriteArrayList<>(), TimeSpan.timeSpan(1).seconds());
        var highNet = liveNetwork(high, new CopyOnWriteArrayList<>(), TimeSpan.timeSpan(1).seconds());

        lowNet.dialForTests(infoOf(high, boundPort(highNet)), false);
        awaitTrue(() -> connected(lowNet, high) && connected(highNet, low), "both ends CONNECTED");
        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(7));   // past any window opened while connecting

        var connection = lowNet.activeConnectionForTests(high).unwrap();
        var before = connection.activityKicksSent();

        LockSupport.parkNanos(TimeUnit.SECONDS.toNanos(4));

        assertThat(connection.activityKicksSent() - before).as("kick frames over 4 s of idle link").isZero();
    }

    private static int boundPort(QuicClusterNetwork network) {
        return network.boundPort().fold(() -> fail("not bound"), bound -> bound);
    }

    private static NodeInfo infoOf(NodeId id, int port) {
        return NodeInfo.nodeInfo(id, NodeAddress.nodeAddress("127.0.0.1", port).fold(_ -> fail("bad address"), a -> a));
    }

    private static boolean connected(QuicClusterNetwork network, NodeId peer) {
        return network.peerPhaseForTests(peer).filter(phase -> phase == PeerState.Phase.CONNECTED).isPresent();
    }

    private QuicClusterNetwork liveNetwork(NodeId id, List<LaneProbe> sink, TimeSpan pingInterval) {
        var codec = LaneProbe.codec();
        var self = infoOf(id, 19994);
        var router = MessageRouter.mutable();
        var serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server")).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client")).fold(_ -> fail("client ssl"), ssl -> ssl);

        router.addRoute(LaneProbe.class, sink::add);
        var network = new QuicClusterNetwork(stubTopology(self, pingInterval), codec, codec, router, serverSsl, clientSsl);

        networks.add(network);
        network.startOnPort(0).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));

        return network;
    }

    private static void awaitTrue(BooleanSupplier condition, String what) {
        awaitTrue(condition, AWAIT, what);
    }

    private static void awaitTrue(BooleanSupplier condition, TimeSpan bound, String what) {
        var deadline = System.nanoTime() + bound.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(20));
        }
        fail("Timed out waiting for: " + what);
    }

    private static TopologyObserver stubTopology(NodeInfo self, TimeSpan pingInterval) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 2;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return pingInterval;}
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
