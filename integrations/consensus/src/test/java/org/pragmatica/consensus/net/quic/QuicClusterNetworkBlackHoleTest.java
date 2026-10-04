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

import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.NetCodecs;
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
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;
import java.util.function.BooleanSupplier;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

/// #1727 — a QUIC link whose inbound path goes silently dead (every datagram the peer sends is lost before the
/// receiver's QUIC stack) keeps both channels `isActive()`: the transport's idle timeout is disabled, so nothing in
/// QUIC itself ever closes it, and writes the peer hands to quiche keep "succeeding" with nothing delivered. The
/// bound comes from the network's receipt-evidence liveness TTL (`pingInterval × 8`): the deaf side must evict the
/// link and close it, and the close must reach the other side, so the loss surfaces as a disconnect instead of
/// staying silent. The loss is injected deterministically (a handler drops every datagram ahead of the QUIC codec),
/// and the waits are ceilings far above TTL + reconcile tick (8 s + 5 s), so a loaded box only delays the pass.
@Timeout(120)
class QuicClusterNetworkBlackHoleTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan CONNECT_BOUND = TimeSpan.timeSpan(HELLO_TIMEOUT.nanos() * 3 + TimeSpan.timeSpan(5).seconds().nanos()).nanos();
    /// Liveness TTL (8 × 1 s) plus one reconcile tick (5 s) is 13 s; the ceiling is several times that.
    private static final TimeSpan DETECTION_BOUND = TimeSpan.timeSpan(60).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
        serverSsl = QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                                    .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                                    .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    @AfterEach
    void tearDown() {
        for (var network : networks) {
            network.stop().await(AWAIT_TIMEOUT);
        }
        networks.clear();
    }

    @Test
    void acceptorInboundBlackHoled_deafSideEvictsTheLink_andTheDialerSeesItClose() {
        var dialerId = new NodeId("aaa-blackhole");
        var acceptorId = new NodeId("zzz-blackhole");
        var dialer = createNetwork(dialerId);
        var acceptor = createNetwork(acceptorId);
        var acceptorPort = acceptor.boundPort().fold(() -> fail("acceptor not bound"), port -> port);
        var acceptorAddress = NodeAddress.nodeAddress("127.0.0.1", acceptorPort).fold(_ -> fail("bad address"), a -> a);

        dialer.connect(NodeInfo.nodeInfo(acceptorId, acceptorAddress));
        awaitTrue(() -> dialer.connectedPeers().contains(acceptorId)
                        && acceptor.activeConnectionForTests(dialerId).isPresent(),
                  CONNECT_BOUND,
                  "both ends hold the link");
        var dialerLink = dialer.activeConnectionForTests(acceptorId).fold(() -> fail("dialer has no link"), c -> c);
        var acceptorLink = acceptor.activeConnectionForTests(dialerId).fold(() -> fail("acceptor has no link"), c -> c);
        var dropped = new AtomicInteger();
        var blackHole = new AtomicBoolean(true);

        acceptorLink.connection().parent().pipeline().addFirst("black-hole", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                if (blackHole.get()) {
                    ReferenceCountUtil.release(msg);
                    dropped.incrementAndGet();
                    return;
                }
                ctx.fireChannelRead(msg);
            }
        });

        awaitTrue(() -> !acceptorLink.isActive() || acceptor.activeConnectionForTests(dialerId).map(c -> c != acceptorLink).or(true),
                  DETECTION_BOUND,
                  "the deaf acceptor evicts the black-holed link (dropped=" + dropped.get() + ")");
        awaitTrue(() -> !dialerLink.isActive(),
                  DETECTION_BOUND,
                  "the dialer's end of the black-holed link closes, so the loss surfaces as a disconnect");
        assertThat(dropped.get()).as("arming: the dialer's traffic was actually black-holed").isPositive();
    }

    private static void awaitTrue(BooleanSupplier condition, TimeSpan timeout, String what) {
        var deadline = System.nanoTime() + timeout.nanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            LockSupport.parkNanos(TimeSpan.timeSpan(50).millis().nanos());
        }
        fail("Timed out waiting for: " + what);
    }

    private QuicClusterNetwork createNetwork(NodeId nodeId) {
        var address = NodeAddress.nodeAddress("127.0.0.1", 19999).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        var topology = stubTopologyManager(selfInfo, HELLO_TIMEOUT);
        var network = new QuicClusterNetwork(topology, codec, codec, MessageRouter.mutable(), serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults());
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private TopologyObserver stubTopologyManager(NodeInfo self, TimeSpan helloTimeout) {
        return new TopologyObserver() {
            @Override
            public org.pragmatica.lang.Unit setConsensusMembership(java.util.function.Predicate<NodeId> membership) { return org.pragmatica.lang.Unit.unit(); }

            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {
                return id.equals(self.id()) ? Option.some(self) : Option.empty();
            }
            @Override public int clusterSize() {return 1;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
            @Override public TimeSpan helloTimeout() {return helloTimeout;}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {return List.of(self.id());}
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
