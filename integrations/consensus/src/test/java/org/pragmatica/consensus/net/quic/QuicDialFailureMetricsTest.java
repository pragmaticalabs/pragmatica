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

import java.net.DatagramSocket;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
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
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1489 — `quic_handshake_failures_total` was incremented by EVERY failed dial (`onConnectFailed` →
/// `onHandshakeFailure`), so it measured churn, not TLS; the 12-network suite marks its TLS contract C3 as
/// uncheckable for exactly that reason. A TLS handshake failure is now its own cause (`HandshakeFailed`), counted
/// by `quic_handshake_failures_total`; every failed dial, TLS or not, is counted by `quic_dial_failures_total`.
@Timeout(60)
class QuicDialFailureMetricsTest {
    private static final TimeSpan AWAIT = TimeSpan.timeSpan(20).seconds();
    private static final String FOREIGN_SECRET = "a-different-clusters-secret";

    private final List<QuicClusterNetwork> networks = new ArrayList<>();
    /// `ConnectionFailed` messages routed by any network under test. `onConnectFailed` routes it AFTER both
    /// metric writes, so seeing it means every counter that dial failure bumps has been bumped (#1855).
    private final AtomicInteger connectionFailedRouted = new AtomicInteger();

    @AfterEach
    void tearDown() {
        networks.forEach(network -> network.stop().await(AWAIT));
    }

    /// A peer from another cluster: its certificate fails our verification in the TLS handshake. Mutation that
    /// reddens it: classify no failure as `HandshakeFailed` (`isTlsFailure` → false).
    @Test
    void tlsRefusal_countsAHandshakeFailure_andADialFailure() {
        var dialer = network("dfm-dialer", ClusterTestTls.clusterTls("dfm-dialer"));
        var foreignPort = freeUdpPort();
        var foreign = network("dfm-foreign", ClusterTestTls.clusterTls("dfm-foreign", FOREIGN_SECRET));

        start(dialer, 0);
        start(foreign, foreignPort);
        dialer.dialForTests(nodeInfo("dfm-foreign", foreignPort), true);

        // onConnectFailed writes the dial counter, then the handshake counter, on the network thread: await both.
        await(() -> dialer.quicMetrics().dialFailureCount() >= 1, "the dial to the foreign cluster fails");
        await(() -> dialer.quicMetrics().handshakeFailureCount() >= 1, "the TLS refusal is counted as a handshake failure");
        assertThat(dialer.quicMetrics().handshakeFailureCount()).as("#1489: a TLS refusal is a handshake failure").isEqualTo(1);
        assertThat(dialer.quicMetrics().dialFailureCount()).isEqualTo(1);
    }

    /// The TLS handshake succeeds (same cluster) but the Hello names a different node than the one dialed: a failed
    /// dial that is NOT a handshake failure. Mutation that reddens it: count every dial failure as a handshake
    /// failure (the pre-#1489 behaviour).
    @Test
    void identityMismatch_countsADialFailure_butNoHandshakeFailure() {
        var dialer = network("dfm-dialer", ClusterTestTls.clusterTls("dfm-dialer"));
        var actualPort = freeUdpPort();
        var actual = network("dfm-actual", ClusterTestTls.clusterTls("dfm-actual"));

        start(dialer, 0);
        start(actual, actualPort);
        dialer.dialForTests(nodeInfo("dfm-expected", actualPort), true);

        // The zero asserted below only means something once the failure path has run past both metric writes.
        await(() -> connectionFailedRouted.get() >= 1, "the dial fails on the identity check and its failure is routed");
        assertThat(dialer.quicMetrics().handshakeFailureCount()).as("#1489: a completed TLS handshake is not a handshake failure")
                                                                .isZero();
        assertThat(dialer.quicMetrics().snapshot()).containsEntry("quic_dial_failures_total", 1L)
                                                   .containsEntry("quic_handshake_failures_total", 0L);
    }

    private QuicClusterNetwork network(String id, TlsConfig tls) {
        var codec = LaneProbe.codec();
        var self = NodeInfo.nodeInfo(new NodeId(id), NodeAddress.nodeAddress("127.0.0.1", 19997).fold(_ -> fail("bad address"), a -> a));
        var serverSsl = QuicTlsProvider.serverContext(tls).fold(_ -> fail("server ssl"), ssl -> ssl);
        var clientSsl = QuicTlsProvider.clientContext(tls).fold(_ -> fail("client ssl"), ssl -> ssl);
        var router = MessageRouter.mutable();

        router.addRoute(NetworkServiceMessage.ConnectionFailed.class, _ -> connectionFailedRouted.incrementAndGet());

        var network = new QuicClusterNetwork(stubTopology(self), codec, codec, router, serverSsl, clientSsl);

        networks.add(network);

        return network;
    }

    private static void start(QuicClusterNetwork network, int port) {
        network.startOnPort(port).await(AWAIT).onFailure(cause -> fail("start failed: " + cause.message()));
    }

    private static NodeInfo nodeInfo(String id, int port) {
        return NodeInfo.nodeInfo(new NodeId(id), NodeAddress.nodeAddress("127.0.0.1", port).fold(_ -> fail("bad address"), a -> a));
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (SocketException e) {
            return fail("no free UDP port: " + e.getMessage());
        }
    }

    private static void await(BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT.nanos();

        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.onSpinWait();
        }
        assertThat(condition.getAsBoolean()).as(what).isTrue();
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return new TopologyObserver() {
            @Override public Unit setConsensusMembership(Predicate<NodeId> membership) {return Unit.unit();}
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 3;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return TimeSpan.timeSpan(30).seconds();}
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
