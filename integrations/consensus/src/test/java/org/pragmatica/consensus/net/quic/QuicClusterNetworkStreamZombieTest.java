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

import org.pragmatica.net.tcp.TlsConfig;
import java.net.SocketAddress;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetCodecs;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.WriteOutcome;
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
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/// Regression coverage for the QUIC reconnect stream-zombie.
///
/// Root cause: the ACCEPTOR side publishes a peer CONNECTED (via `onPeerConnected` -> `attach`)
/// with ONLY the CONTROL lane registered — the 7 data lanes (CONSENSUS, KV, METRICS, INVOKE,
/// FORWARD, DHT, SYNC) register asynchronously LATER as the dialer's per-lane preamble frames
/// arrive. The DIAL path, by contrast, publishes CONNECTED only after ALL 8 lanes are present. A
/// write racing the acceptor's data-lane window — immediate on RECONNECT, where SWIM probes /
/// consensus are already flowing — hit `writeToStream` with a missing lane and failed
/// "No stream available for peer" on a perfectly healthy link, starving SWIM and livelocking the
/// peer at SUSPECT (never reaching lifecycle READY).
///
/// Fix:
///   - PRIMARY: `writeToStream` lazily (re)opens the missing lane on the live connection (via the
///     installed [QuicPeerConnection.LaneOpener]) and delivers the message once the lane is up,
///     instead of failing — restoring the dial-path "all lanes usable" invariant at the write site.
///   - BACKSTOP: when even the lazy open cannot heal the lane, the connection is evicted (existing
///     event-driven eviction) so the reconciler re-dials cleanly — bounded to ONE eviction per
///     reconnect-backoff grace window per peer, logged at WARN with a counter.
@Timeout(30)
class QuicClusterNetworkStreamZombieTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(10).seconds();

    @Nested
    class PrimaryLazyOpen {

        /// A write to a CONNECTED peer whose requested lane is NOT yet open must lazily (re)open the
        /// lane and DELIVER the message to the freshly-opened stream — never fail "No stream
        /// available". This is the acceptor reconnect-handshake window: peer published CONNECTED with
        /// only CONTROL present, a data-lane write arrives before the lanes register.
        @Test
        void writeToStream_connectedPeerMissingLane_lazilyOpensAndDelivers() {
            var network = network();
            var peerId = new NodeId("reconnect-acceptor-peer");

            // The freshly-opened lane stream the test opener will register + the network will write to.
            var openedStream = writableStream();
            var connection = connectionWithOpener(peerId, QuicPeerConnection.LaneOpener.noop());
            connection.laneOpener(registeringOpener(connection, openedStream));
            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

            var outcome = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

            assertThat(outcome)
                .as("a CONNECTED peer with a lazily-healable lane reports Sent (optimistic), not dead")
                .isInstanceOf(WriteOutcome.Sent.class);
            verify(openedStream, times(1)).writeAndFlush(any());
            assertThat(network.quicMetrics().streamZombieLazyOpenCount())
                .as("the lazy-open PRIMARY heal path was taken exactly once")
                .isEqualTo(1L);
            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("a healable lane must NOT trigger the BACKSTOP eviction")
                .isZero();
            assertThat(network.connectedPeers())
                .as("the peer stays CONNECTED — the zombie healed without a re-dial")
                .contains(peerId);
        }

        /// Once the lazy open registers the lane on the connection, a SUBSEQUENT write to the same
        /// lane takes the normal (no lazy-open) path — proving the heal is durable, not per-write.
        @Test
        void writeToStream_afterLazyOpenRegistersLane_subsequentWriteUsesNormalPath() {
            var network = network();
            var peerId = new NodeId("durable-heal-peer");

            var openedStream = writableStream();
            var connection = connectionWithOpener(peerId, QuicPeerConnection.LaneOpener.noop());
            connection.laneOpener(registeringOpener(connection, openedStream));
            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

            // First write triggers the lazy open (registers CONTROL on the connection).
            network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);
            // Second write finds the now-registered lane and writes directly.
            var second = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

            assertThat(second).isInstanceOf(WriteOutcome.Sent.class);
            assertThat(network.quicMetrics().streamZombieLazyOpenCount())
                .as("only the FIRST write took the lazy-open path; the second used the registered lane")
                .isEqualTo(1L);
            verify(openedStream, times(2)).writeAndFlush(any());
        }
    }

    @Nested
    class BackstopEviction {

        /// A write to a CONNECTED peer with no usable stream that the lazy open CANNOT heal (no
        /// installed opener -> reports empty) is a transport-integrity violation: the connection is
        /// evicted once so the reconciler re-dials cleanly, and the peer drops out of connectedPeers.
        @Test
        void writeToStream_unhealableLane_evictsConnectionForRedial() {
            var network = network();
            var peerId = new NodeId("unhealable-zombie");

            // No opener installed -> the default no-op opener reports empty -> BACKSTOP fires.
            var connection = connectionWithOpener(peerId, QuicPeerConnection.LaneOpener.noop());
            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

            assertThat(network.connectedPeers())
                .as("precondition: the zombie is initially counted as CONNECTED")
                .contains(peerId);

            // The synchronous return is optimistic Sent (the lazy open + BACKSTOP run on the
            // callback); the observable BACKSTOP effect is the eviction below.
            network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("the BACKSTOP evicted the connection exactly once")
                .isEqualTo(1L);
            assertThat(network.connectedPeers())
                .as("the evicted zombie drops out of connectedPeers so the reconciler re-dials")
                .doesNotContain(peerId);
        }

        /// The BACKSTOP must not flap-loop: a SECOND unhealable write against the same peer within
        /// the reconnect-backoff grace window must NOT evict again (the first eviction already
        /// scheduled a re-dial). Re-uses the existing per-peer backoff machinery as the flap guard.
        @Test
        void writeToStream_repeatedUnhealableWrites_evictsAtMostOncePerGraceWindow() {
            var network = network();
            var peerId = new NodeId("flap-guarded-zombie");

            // Re-CONNECT after each eviction so the phase is CONNECTED for the next write attempt,
            // isolating the backoff grace window as the sole flap guard.
            var firstConnection = connectionWithOpener(peerId, QuicPeerConnection.LaneOpener.noop());
            var state = connectedPeerState(peerId, firstConnection);
            network.seedPeerForTests(peerId, state);

            // First unhealable write -> eviction #1 (allowed: fresh backoff).
            network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), firstConnection);
            assertThat(network.quicMetrics().streamZombieEvictionCount()).isEqualTo(1L);

            // Re-CONNECT a fresh connection (still unhealable) so phase==CONNECTED again, WITHOUT
            // resetting the eviction-driven backoff (only a real attach via onPeerConnected resets it;
            // a direct re-attach here does not call resetReconnectBackoff).
            var secondConnection = connectionWithOpener(peerId, QuicPeerConnection.LaneOpener.noop());
            state.beginConnecting(System.nanoTime());
            state.attach(secondConnection, System.nanoTime());

            // Second unhealable write inside the grace window -> eviction SUPPRESSED by backoff.
            network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), secondConnection);

            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("a second unhealable write within the backoff grace window must NOT evict again")
                .isEqualTo(1L);
        }
    }

    @Nested
    class NoRegression {

        /// A write to a CONNECTED peer whose requested lane IS already open takes the normal write
        /// path: direct write, no lazy-open, no eviction. Proves the fix does not perturb the
        /// healthy steady state.
        @Test
        void writeToStream_laneAlreadyOpen_writesNormallyWithoutLazyOpenOrEviction() {
            var network = network();
            var peerId = new NodeId("healthy-peer");

            var laneStream = writableStream();
            var connection = connectionWithOpener(peerId, failingOpener());
            // KeepAlive rides the CONTROL lane — register a writable CONTROL stream up front.
            connection.registerStream(StreamType.CONTROL, laneStream);
            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

            var outcome = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

            assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
            verify(laneStream, times(1)).writeAndFlush(any());
            assertThat(network.quicMetrics().streamZombieLazyOpenCount())
                .as("an already-open lane takes the normal path — no lazy open")
                .isZero();
            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("an already-open lane takes the normal path — no eviction")
                .isZero();
            assertThat(network.connectedPeers()).contains(peerId);
        }

        /// A write to a peer whose underlying connection is INACTIVE keeps the pre-existing
        /// dead-connection eviction path (ConnectionDead + re-dispatch into the offline buffer) —
        /// the lazy-open/BACKSTOP logic only engages when the connection is still ACTIVE.
        @Test
        void writeToStream_inactiveConnection_keepsExistingDeadConnectionPath() {
            var network = network();
            var peerId = new NodeId("dead-connection-peer");

            var connection = connectionWithOpener(peerId, failingOpener(), false);
            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));

            var outcome = network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);

            assertThat(outcome).isInstanceOf(WriteOutcome.ConnectionDead.class);
            assertThat(network.quicMetrics().streamZombieLazyOpenCount())
                .as("an inactive connection never enters the lazy-open path")
                .isZero();
            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("an inactive connection takes the pre-existing dead-connection path, not the BACKSTOP")
                .isZero();
        }
    }

    @Nested
    class EndToEndReconnectHandshake {

        /// A LIVE acceptor that completes a Hello handshake must end up with a usable lane: a write
        /// to the just-connected peer succeeds (the lane is either already registered or lazily
        /// healed) instead of failing "No stream available". Drives two real QuicClusterNetworks.
        @Test
        void acceptorHandshakeThenWrite_peerStaysConnected_writeSucceeds() {
            var serverSsl = serverSsl();
            var clientSsl = clientSsl();
            var codec = combinedCodec();

            var peerBId = new NodeId("zzz-acceptor");
            var nodeA = liveNetwork(new NodeId("aaa-dialer"), codec, serverSsl, clientSsl);
            var nodeB = liveNetwork(peerBId, codec, serverSsl, clientSsl);
            var bPort = nodeB.boundPort().fold(() -> fail("B not bound"), port -> port);
            var bAddress = NodeAddress.nodeAddress("127.0.0.1", bPort).fold(_ -> fail("bad address"), a -> a);

            try {
                nodeA.connect(NodeInfo.nodeInfo(peerBId, bAddress));

                awaitTrue(() -> nodeA.connectedPeers().contains(peerBId), "A connects to B");
                // The acceptor (B) must converge to CONNECTED with A in its peer table.
                var aId = new NodeId("aaa-dialer");
                awaitTrue(() -> nodeB.connectedPeers().contains(aId), "B accepts A");

                // #726: the Hello handshake itself (CONTROL-lane preamble + Hello, both directions)
                // is hooked, so the payload-byte counters are already positive from the handshake
                // ALONE — strictly before any application-level write (the keepalive beacon below).
                // Proves the claim covers handshake bytes, not just post-handshake app traffic.
                // Awaited rather than asserted synchronously: each increment happens on its owning
                // node's QUIC event-loop thread, not the test thread, same reasoning as the
                // receive-side awaits further down. This is a genuine mutation probe, not just a
                // liveness check: liveNetwork() stretches this test's pingInterval to 30s (see its
                // javadoc), so the automatic keepalive scheduler cannot fire within AWAIT_TIMEOUT
                // (10s) and cannot contaminate these counters — a revert of any of the nine #726
                // hooks makes the corresponding awaitTrue below time out and fail for real.
                awaitTrue(() -> nodeA.quicMetrics().bytesSentCount() > 0,
                          "#726: the dialer's CONTROL preamble + Hello writes count before any app write");
                awaitTrue(() -> nodeA.quicMetrics().bytesReceivedCount() > 0,
                          "#726: the dialer's receipt of the acceptor's Hello response counts before any app write");
                awaitTrue(() -> nodeB.quicMetrics().bytesSentCount() > 0,
                          "#726: the acceptor's Hello-response write counts before any app write");
                awaitTrue(() -> nodeB.quicMetrics().bytesReceivedCount() > 0,
                          "#726: the acceptor's receipt of the dialer's CONTROL preamble + Hello counts before any app write");

                // The transport keepalive scheduler writes a CONTROL-lane beacon to every CONNECTED
                // peer each pingInterval (via writeToStream), exercising the acceptor's lane table on
                // both sides. Give it a couple of cadences to drive real writes, then assert neither
                // side stranded an unhealable lane (no stream-zombie BACKSTOP eviction).
                nodeA.keepaliveTick();
                nodeB.keepaliveTick();

                assertThat(nodeA.connectedPeers())
                    .as("the dialer keeps the link CONNECTED after writing")
                    .contains(peerBId);
                assertThat(nodeB.connectedPeers())
                    .as("the acceptor keeps the link CONNECTED after writing (no stream-zombie)")
                    .contains(aId);
                assertThat(nodeB.quicMetrics().streamZombieEvictionCount())
                    .as("a live handshake never strands the acceptor with an unhealable lane")
                    .isZero();
                // #726: the keepalive beacon is a real CONTROL-lane write/read on a live two-node
                // pipeline — proves the payload-byte counters move at BOTH lane boundaries (send:
                // QuicClusterNetwork#writeIfWritable, receive: QuicLaneDataHandler#channelRead0),
                // for BOTH the dialer and the acceptor (the two directions share one handler class).
                // The send-side counter increments synchronously on the calling thread inside
                // writeIfWritable/rawBackpressuredWrite, so it is already positive once keepaliveTick()
                // returns. The receive-side counter only increments once the OTHER node's real QUIC
                // event loop has scheduled and run channelRead0 for the inbound frame — under
                // concurrent reactor load that can take longer than "immediately", so it is awaited
                // like every other cross-node convergence in this test, not asserted synchronously.
                assertThat(nodeA.quicMetrics().bytesSentCount())
                    .as("#726: the dialer's keepalive write increments its payload-byte send counter")
                    .isPositive();
                awaitTrue(() -> nodeB.quicMetrics().bytesReceivedCount() > 0,
                          "#726: the acceptor's receipt of the dialer's frame increments its payload-byte receive counter");
                assertThat(nodeB.quicMetrics().bytesSentCount())
                    .as("#726: the acceptor's own keepalive write increments its payload-byte send counter")
                    .isPositive();
                awaitTrue(() -> nodeA.quicMetrics().bytesReceivedCount() > 0,
                          "#726: the dialer's receipt of the acceptor's frame increments its payload-byte receive counter");
            } finally {
                nodeA.stop().await(AWAIT_TIMEOUT);
                nodeB.stop().await(AWAIT_TIMEOUT);
            }
        }
    }

    // --- Helpers ---

    /// A mock QUIC lane stream that is active + writable and returns a self-listening future.
    private static QuicStreamChannel writableStream() {
        var stream = mock(QuicStreamChannel.class);
        var future = mock(ChannelFuture.class);
        lenient().when(future.addListener(any())).thenReturn(future);
        lenient().when(stream.writeAndFlush(any())).thenReturn(future);
        lenient().when(stream.isActive()).thenReturn(true);
        lenient().when(stream.isWritable()).thenReturn(true);
        return stream;
    }

    /// A LaneOpener that synchronously registers `openedStream` for the requested lane on
    /// `connection` (mirroring the production opener) and reports it — the deterministic stand-in for
    /// the acceptor's async stream creation. Registering on the connection makes the heal durable: a
    /// subsequent write to the same lane finds the registered stream and skips the lazy-open path.
    private static QuicPeerConnection.LaneOpener registeringOpener(QuicPeerConnection connection,
                                                                  QuicStreamChannel openedStream) {
        return (lane, onResult) -> registerAndReport(connection, openedStream, lane, onResult);
    }

    private static void registerAndReport(QuicPeerConnection connection,
                                          QuicStreamChannel openedStream,
                                          StreamType lane,
                                          java.util.function.Consumer<Option<QuicStreamChannel>> onResult) {
        connection.registerStream(lane, openedStream);
        onResult.accept(Option.some(openedStream));
    }

    /// A LaneOpener that always reports failure (empty) — drives the BACKSTOP. Distinct from
    /// `LaneOpener.noop()` only by intent; behaviour is identical (reports empty).
    private static QuicPeerConnection.LaneOpener failingOpener() {
        return (lane, onResult) -> onResult.accept(Option.empty());
    }

    private static QuicPeerConnection connectionWithOpener(NodeId peerId, QuicPeerConnection.LaneOpener opener) {
        return connectionWithOpener(peerId, opener, true);
    }

    private static QuicPeerConnection connectionWithOpener(NodeId peerId,
                                                           QuicPeerConnection.LaneOpener opener,
                                                           boolean active) {
        var chan = mock(QuicChannel.class);
        lenient().when(chan.isActive()).thenReturn(active);
        var connection = QuicPeerConnection.quicPeerConnection(peerId, chan);
        connection.laneOpener(opener);
        return connection;
    }

    /// CONNECTED PeerState bound to the supplied connection, stamped in the past so it is outside any
    /// protection window, with the inbound clock refreshed so the liveness sweep never trips.
    private static PeerState connectedPeerState(NodeId peerId, QuicPeerConnection connection) {
        var past = System.nanoTime() - Duration.ofMinutes(1).toNanos();
        var state = PeerState.peerState(peerId, past);
        state.beginConnecting(past);
        state.attach(connection, past);
        state.markInbound(System.nanoTime());
        return state;
    }

    private void awaitTrue(java.util.function.BooleanSupplier condition, String what) {
        var deadline = System.nanoTime() + AWAIT_TIMEOUT.nanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            java.util.concurrent.locks.LockSupport.parkNanos(TimeSpan.timeSpan(50).millis().nanos());
        }
        fail("Timed out waiting for: " + what);
    }

    private QuicClusterNetwork network() {
        var codec = combinedCodec();
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19996)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(new NodeId("self-zombie"), nodeAddress);
        return new QuicClusterNetwork(stubTopology(selfInfo), codec, codec,
                                      MessageRouter.mutable(), serverSsl(), clientSsl());
    }

    private QuicClusterNetwork liveNetwork(NodeId nodeId, SliceCodec codec, QuicSslContext serverSsl, QuicSslContext clientSsl) {
        var address = NodeAddress.nodeAddress("127.0.0.1", 19995).fold(_ -> fail("bad address"), a -> a);
        var selfInfo = NodeInfo.nodeInfo(nodeId, address);
        // #726: pingInterval is stretched to 30s (vs. the 1s every other helper in this file uses)
        // solely so the automatic keepalive scheduler that QuicClusterNetwork#startOnPort starts
        // internally cannot fire within AWAIT_TIMEOUT (10s). Without this, the scheduler's own
        // pre-existing byte-counter hooks (QuicClusterNetwork#writeIfWritable, QuicLaneDataHandler)
        // can independently satisfy "counter > 0" before the test's explicit keepaliveTick() calls
        // run, making the handshake-phase assertions below pass even with the nine #726 hooks
        // reverted. `network()` below never calls startOnPort, so its own 1s stub is inert and
        // unaffected by this change.
        var network = new QuicClusterNetwork(stubTopology(selfInfo, TimeSpan.timeSpan(30).seconds()), codec, codec,
                                             MessageRouter.mutable(), serverSsl, clientSsl);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private static SliceCodec combinedCodec() {
        return SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
    }

    private static QuicSslContext serverSsl() {
        return QuicTlsProvider.serverContext(ClusterTestTls.clusterTls("test-server"))
                              .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
    }

    private static QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(ClusterTestTls.clusterTls("test-client"))
                              .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }

    private static TopologyObserver stubTopology(NodeInfo self) {
        return stubTopology(self, TimeSpan.timeSpan(1).seconds());
    }

    private static TopologyObserver stubTopology(NodeInfo self, TimeSpan pingInterval) {
        return new TopologyObserver() {
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 1;}
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
