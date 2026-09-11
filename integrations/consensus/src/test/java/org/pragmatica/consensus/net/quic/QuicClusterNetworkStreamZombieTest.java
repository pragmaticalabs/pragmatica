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

import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Filter;
import org.apache.logging.log4j.core.Layout;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.config.LoggerConfig;
import org.apache.logging.log4j.core.config.Property;
import org.apache.logging.log4j.core.layout.PatternLayout;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

    /// #718 — the lazy open is ONE PER (peer, lane), not one per message.
    ///
    /// The PRIMARY heal above is correct and stays; what was missing is a gate in front of it. Each
    /// firing created a stream, so a burst of writes arriving inside the open window consumed the
    /// connection's `initialMaxStreamsBidirectional(64)` credit. The coupling was measured as EQUAL
    /// counts, not a correlation: failed lazy re-opens == `STREAM_LIMIT_ERROR`s, 857==857 in one
    /// 72-second run and 639==639 in another.
    ///
    /// These tests drive the real `writeToStream` path through a DEFERRING opener — one that records
    /// the callback and completes only when the test says so. That is what reproduces the defect's
    /// window: while the open is outstanding nothing is registered on the connection, so every write
    /// in the burst genuinely finds the lane missing. An opener that registers synchronously (the one
    /// `PrimaryLazyOpen` uses) would let writes 2..N find the healed lane and could not distinguish a
    /// deduplicating implementation from a non-deduplicating one.
    @Nested
    class LazyOpenDedup {
        private static final String HEAL_FRAGMENT = "lazily (re)opening the lane";
        private static final String SENTINEL = "positive control: LazyOpenDedup appender is attached";
        private static final int BURST = 12;

        /// THE pin. Twelve messages onto a lane whose open has not completed create ONE stream, and
        /// all twelve are still delivered when it does — the dedup must not cost a message.
        @Test
        void writeToStream_burstOnAMissingLane_opensOnceAndDeliversEveryMessage() {
            var network = network();
            var peerId = new NodeId("dedup-burst-peer");
            var opener = new DeferringOpener();
            var connection = connectionWithOpener(peerId, opener);

            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));
            writeBurst(network, peerId, connection, BURST);

            assertThat(opener.openCount())
                .as("twelve writes, ONE stream created — before #718 this was twelve, against a "
                    + "64-stream credit")
                .isEqualTo(1);
            assertThat(network.quicMetrics().streamZombieLazyOpenCount())
                .as("exactly one write owned the open")
                .isEqualTo(1L);
            assertThat(network.quicMetrics().streamZombieLazyOpenCoalescedCount())
                .as("the other eleven coalesced onto it")
                .isEqualTo(BURST - 1L);

            var opened = writableStream();

            // Mirror the production opener: register the lane, then report it.
            connection.registerStream(StreamType.CONTROL, opened);
            opener.completeWith(Option.some(opened));

            verify(opened, times(BURST)).writeAndFlush(any());
            assertThat(network.quicMetrics().streamZombieLazyOpenDropCount())
                .as("a burst well inside the pending bound loses nothing")
                .isZero();
        }

        /// The volume half of the same claim, and the reason it is worth a separate assertion: the heal
        /// line is a WARN on a hot send path. One per open cycle is diagnostic; one per message is the
        /// #718 amplification pattern in a second place.
        @Test
        void writeToStream_burstOnAMissingLane_emitsOneHealWarnNotOnePerMessage() {
            var network = network();
            var peerId = new NodeId("dedup-warn-volume-peer");
            var opener = new DeferringOpener();
            var connection = connectionWithOpener(peerId, opener);

            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));
            emitSentinel();
            writeBurst(network, peerId, connection, BURST);

            assertSentinelWasCaptured();
            assertThat(warnsContaining(HEAL_FRAGMENT))
                .as("one heal WARN for twelve messages — the line announces the OPEN, not the write")
                .hasSize(1);
        }

        /// The report's standing risk note, pinned: deduplication must not mask a genuinely dead lane.
        /// A coalesced message is a distinct admission outcome from a FAILED open, so the BACKSTOP
        /// eviction is still reached when the open reports empty.
        @Test
        void writeToStream_unhealableLaneAfterDedup_stillEvictsViaBackstop() {
            var network = network();
            var peerId = new NodeId("dedup-backstop-peer");
            var opener = new DeferringOpener();
            var connection = connectionWithOpener(peerId, opener);

            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));
            writeBurst(network, peerId, connection, BURST);

            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("precondition: nothing is evicted while the open is still outstanding")
                .isZero();

            opener.completeWith(Option.empty());

            assertThat(network.quicMetrics().streamZombieEvictionCount())
                .as("an unhealable lane still evicts for a clean re-dial — dedup did not swallow it")
                .isEqualTo(1L);
            assertThat(network.connectedPeers())
                .as("and the peer drops out so the reconciler re-dials")
                .doesNotContain(peerId);
        }

        /// No marker leak THROUGH THE TRANSPORT. The in-flight marker must be released on the failure
        /// outcome too, or the first unhealable open would make that lane permanently undealable for
        /// the rest of the connection's life — trading a stream-credit leak for a worse one.
        @Test
        void writeToStream_afterAFailedOpen_nextWriteStartsAFreshOpen() {
            var network = network();
            var peerId = new NodeId("dedup-marker-release-peer");
            var opener = new DeferringOpener();
            var connection = connectionWithOpener(peerId, opener);

            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));
            writeBurst(network, peerId, connection, 1);
            opener.completeWith(Option.empty());

            // Re-CONNECT the same (still lane-less) connection: the BACKSTOP evicted it above, and the
            // marker — not the peer phase — is what this test is about.
            var state = connectedPeerState(peerId, connection);

            network.seedPeerForTests(peerId, state);
            writeBurst(network, peerId, connection, 1);

            assertThat(opener.openCount())
                .as("the second write drove a SECOND open — a leaked marker would have coalesced it "
                    + "onto an open that already finished")
                .isEqualTo(2);
            assertThat(network.quicMetrics().streamZombieLazyOpenCount()).isEqualTo(2L);
            assertThat(network.quicMetrics().streamZombieLazyOpenCoalescedCount())
                .as("neither write coalesced — they are separate open cycles")
                .isZero();
        }

        /// Overflow of the pending queue is COUNTED and bounded. Retention caps at the bound, the
        /// oldest are the ones dropped, and the drop is visible in metrics rather than in a WARN —
        /// deliberately, since an unbounded WARN on this path is the defect being fixed.
        @Test
        void writeToStream_burstPastThePendingBound_dropsOldestAndCountsIt() {
            var network = network();
            var peerId = new NodeId("dedup-overflow-peer");
            var opener = new DeferringOpener();
            var connection = connectionWithOpener(peerId, opener);
            var bound = QuicPeerConnection.PENDING_LANE_WRITES_MAX;

            network.seedPeerForTests(peerId, connectedPeerState(peerId, connection));
            // One owner + (bound - 1) coalesced fills the queue exactly; the final two overflow it.
            writeBurst(network, peerId, connection, bound + 2);

            assertThat(network.quicMetrics().streamZombieLazyOpenCount())
                .as("still ONE open, however long the burst")
                .isEqualTo(1L);
            assertThat(network.quicMetrics().streamZombieLazyOpenCoalescedCount())
                .isEqualTo(bound + 1L);
            assertThat(network.quicMetrics().streamZombieLazyOpenDropCount())
                .as("exactly the two messages past the bound are dropped")
                .isEqualTo(2L);

            var opened = writableStream();

            connection.registerStream(StreamType.CONTROL, opened);
            opener.completeWith(Option.some(opened));

            verify(opened, times(bound)).writeAndFlush(any());
        }

        // --- dedup fixtures ---

        private CapturingAppender appender;
        private LoggerConfig loggerConfig;

        @BeforeEach
        void attachAppender() {
            appender = CapturingAppender.create("LazyOpenDedupCapture");
            appender.start();

            var ctx = (LoggerContext) LogManager.getContext(false);

            loggerConfig = getOrCreateLoggerConfig(ctx.getConfiguration());
            loggerConfig.addAppender(appender, Level.WARN, null);
            ctx.updateLoggers();
        }

        @AfterEach
        void detachAppender() {
            var ctx = (LoggerContext) LogManager.getContext(false);

            loggerConfig.removeAppender(appender.getName());
            ctx.updateLoggers();
            appender.stop();
        }

        private static void writeBurst(QuicClusterNetwork network,
                                       NodeId peerId,
                                       QuicPeerConnection connection,
                                       int count) {
            for (var index = 0; index < count; index++) {
                network.writeToStreamForTests(peerId, new NetworkMessage.KeepAlive(peerId), connection);
            }
        }

        private static void emitSentinel() {
            LogManager.getLogger(QuicClusterNetwork.class).warn(SENTINEL);
        }

        /// Load-bearing for the volume assertion: `hasSize(1)` would also be satisfied by a detached
        /// appender that captured one stray line, and an absent capture would make a `hasSize(0)`
        /// variant pass vacuously.
        private void assertSentinelWasCaptured() {
            assertThat(appender.messages())
                .as("the appender must be attached to %s, or the volume assertion examines nothing",
                    QuicClusterNetwork.class.getName())
                .anyMatch(message -> message.contains(SENTINEL));
        }

        private List<String> warnsContaining(String fragment) {
            return appender.messages()
                           .stream()
                           .filter(message -> message.contains(fragment))
                           .toList();
        }

        private static LoggerConfig getOrCreateLoggerConfig(Configuration configuration) {
            var name = QuicClusterNetwork.class.getName();
            var existing = configuration.getLoggerConfig(name);

            if (name.equals(existing.getName())) {
                return existing;
            }

            var fresh = new LoggerConfig(name, Level.WARN, true);

            configuration.addLogger(name, fresh);

            return fresh;
        }
    }

    // --- Helpers ---

    /// A [QuicPeerConnection.LaneOpener] that RECORDS the open and defers its outcome until the test
    /// calls [#completeWith]. Registering nothing meanwhile is the point: it holds the lane-missing
    /// window open, which is the state the #718 burst actually arrives in.
    private static final class DeferringOpener implements QuicPeerConnection.LaneOpener {
        private final List<Consumer<Option<QuicStreamChannel>>> pending = new CopyOnWriteArrayList<>();
        /// Counted separately from [#pending], which [#completeWith] drains — the question these tests
        /// ask is how many opens were EVER driven, across completion cycles.
        private final AtomicInteger opens = new AtomicInteger();

        @Override
        public void open(StreamType lane, Consumer<Option<QuicStreamChannel>> onResult) {
            opens.incrementAndGet();
            pending.add(onResult);
        }

        void completeWith(Option<QuicStreamChannel> outcome) {
            var outstanding = List.copyOf(pending);

            pending.clear();
            outstanding.forEach(callback -> callback.accept(outcome));
        }

        int openCount() {
            return opens.get();
        }
    }

    /// In-memory log4j2 appender capturing WARN-and-above messages for the dedup volume assertion.
    private static final class CapturingAppender extends AbstractAppender {
        private final List<String> captured = new CopyOnWriteArrayList<>();

        private CapturingAppender(String name, Layout<?> layout) {
            super(name, (Filter) null, layout, true, Property.EMPTY_ARRAY);
        }

        static CapturingAppender create(String name) {
            return new CapturingAppender(name, PatternLayout.createDefaultLayout());
        }

        @Override
        public void append(LogEvent event) {
            if (event.getLevel().isMoreSpecificThan(Level.WARN)) {
                captured.add(event.getMessage().getFormattedMessage());
            }
        }

        List<String> messages() {
            return List.copyOf(captured);
        }
    }

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
