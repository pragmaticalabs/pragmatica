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
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import io.netty.channel.ChannelFuture;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.NodeId;
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
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.Retry.BackoffStrategy;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.pragmatica.lang.Unit.unit;

/// Regression coverage for the consensus-send backpressure fix.
///
/// Root cause: a burst of consensus commands (deploy → ACTIVATE) backpressured the per-peer
/// CONSENSUS QUIC stream; the old code silently dropped the message (and its retransmits),
/// denying quorum until the 30s `cluster.apply` timeout. The fix wraps CONSENSUS sends in an
/// async, non-blocking [Retry] so a transiently-unwritable stream that later becomes writable
/// is eventually DELIVERED, while non-CONSENSUS streams keep the fast-fail BackpressureRefused
/// that the DHT quorum path relies on.
@Timeout(30)
class QuicConsensusBackpressureTest {

    private static final NodeId PEER = new NodeId("peer-1");

    @Nested
    class RawConsensusWriteRetryComposition {

        /// A write that is backpressured a few times then succeeds must eventually be DELIVERED
        /// via the async retry — never silently dropped. Proves the Retry + rawConsensusWrite
        /// composition delivers within the attempt budget.
        @Test
        void consensusRetry_deliversAfterTransientBackpressure_withinAttempts() {
            var attempts = new AtomicInteger(0);
            var delivered = new AtomicInteger(0);

            var retry = fastRetry(8);
            retry.execute(() -> attemptThatFailsThenSucceeds(attempts, delivered, 3))
                 .await(TimeSpan.timeSpan(10).seconds())
                 .onFailure(cause -> fail("Retry should have delivered: " + cause.message()))
                 .onSuccess(_ -> assertThat(delivered.get()).isEqualTo(1));

            assertThat(attempts.get())
                .as("first 3 attempts fail (backpressured), 4th delivers")
                .isEqualTo(4);
        }

        /// A write that is backpressured for the entire attempt budget must EXHAUST and fail —
        /// the optimistic caller then relies on Rabia retransmit. Proves give-up semantics.
        @Test
        void consensusRetry_exhaustsAndFails_whenPermanentlyBackpressured() {
            var attempts = new AtomicInteger(0);

            var retry = fastRetry(4);
            retry.execute(() -> attemptThatAlwaysFails(attempts))
                 .await(TimeSpan.timeSpan(10).seconds())
                 .onSuccess(_ -> fail("Retry should have exhausted, not delivered"));

            assertThat(attempts.get())
                .as("exactly maxAttempts attempts before giving up")
                .isEqualTo(4);
        }

        private Promise<Unit> attemptThatFailsThenSucceeds(AtomicInteger attempts,
                                                           AtomicInteger delivered,
                                                           int failTimes) {
            var n = attempts.incrementAndGet();
            if (n <= failTimes) {
                return Causes.cause("CONSENSUS stream backpressured (attempt " + n + ")").promise();
            }
            delivered.incrementAndGet();
            return Promise.success(unit());
        }

        private Promise<Unit> attemptThatAlwaysFails(AtomicInteger attempts) {
            attempts.incrementAndGet();
            return Causes.cause("permanently backpressured").promise();
        }
    }

    @Nested
    class StreamTypeBranch {

        /// A CONSENSUS write to a channel that is NOT writable initially but BECOMES writable
        /// is eventually DELIVERED (writeAndFlush invoked) — NOT dropped. This is the exact
        /// defect: consensus bursts must survive transient backpressure.
        @Test
        void writeIfWritable_consensusBecomesWritable_eventuallyDelivers() {
            var ch = mock(QuicStreamChannel.class);
            var future = mock(ChannelFuture.class);
            when(future.addListener(any())).thenReturn(future);
            when(ch.writeAndFlush(any())).thenReturn(future);
            when(ch.isActive()).thenReturn(true);
            // Not writable for the first two probes (initial writeIfWritable + first retry
            // attempt), writable thereafter — simulates a backpressure window that clears.
            when(ch.isWritable()).thenReturn(false, false, true);

            var network = network();

            var outcome = network.writeIfWritableForTest(ch, payload(), PEER, StreamType.CONSENSUS);

            // Optimistic synchronous outcome: consensus callers are fire-and-forget.
            assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);

            // Wait for the async retry to land the delivery (writeAndFlush invoked exactly once).
            awaitWriteFlush(ch, 1);
            verify(ch, times(1)).writeAndFlush(any());
        }

        /// A non-CONSENSUS (DHT) backpressured write must return BackpressureRefused
        /// IMMEDIATELY and NEVER call writeAndFlush — the DHT quorum fast-fail path depends on
        /// this. Proves the fix did not weaken non-consensus backpressure handling.
        @Test
        void writeIfWritable_dhtRelayBackpressured_refusesImmediately_noWrite() {
            var ch = mock(QuicStreamChannel.class);
            when(ch.isWritable()).thenReturn(false);

            var network = network();

            var outcome = network.writeIfWritableForTest(ch, payload(), PEER, StreamType.DHT);

            assertThat(outcome)
                .as("DHT_RELAY backpressure must fast-fail")
                .isInstanceOf(WriteOutcome.BackpressureRefused.class);
            verify(ch, never()).writeAndFlush(any());
        }

        /// A writable CONSENSUS channel writes synchronously on the normal path (no retry).
        @Test
        void writeIfWritable_consensusWritable_writesImmediately() {
            var ch = mock(QuicStreamChannel.class);
            var future = mock(ChannelFuture.class);
            when(future.addListener(any())).thenReturn(future);
            when(ch.writeAndFlush(any())).thenReturn(future);
            when(ch.isWritable()).thenReturn(true);

            var network = network();

            var outcome = network.writeIfWritableForTest(ch, payload(), PEER, StreamType.CONSENSUS);

            assertThat(outcome).isInstanceOf(WriteOutcome.Sent.class);
            verify(ch, times(1)).writeAndFlush(any());
        }

        /// A DHT write to a LIVE-but-backpressured channel (isActive==true, isWritable==false)
        /// must be routed through the async retry and report Sent OPTIMISTICALLY — NOT refused.
        /// This is the exact defect: a 64KB GetResponse to a momentarily-backpressured live reader
        /// was fast-failed and dropped, denying read-quorum until the 30s operationTimeout.
        @Test
        void writeIfWritable_dhtLiveButBackpressured_routesToRetry_reportsSent() {
            var ch = mock(QuicStreamChannel.class);
            var future = mock(ChannelFuture.class);
            when(future.addListener(any())).thenReturn(future);
            when(ch.writeAndFlush(any())).thenReturn(future);
            when(ch.isActive()).thenReturn(true);
            // Backpressured at the writeIfWritable probe, clears on the first retry attempt.
            when(ch.isWritable()).thenReturn(false, false, true);

            var network = network();

            var outcome = network.writeIfWritableForTest(ch, payload(), PEER, StreamType.DHT);

            assertThat(outcome)
                .as("live-but-backpressured DHT write must report Sent (routed to retry)")
                .isInstanceOf(WriteOutcome.Sent.class);

            // The async retry lands the delivery once writability returns.
            awaitWriteFlush(ch, 1);
            verify(ch, times(1)).writeAndFlush(any());
        }

        /// A DHT write to a DEAD channel (isActive==false, isWritable==false) must return
        /// BackpressureRefused IMMEDIATELY and NEVER call writeAndFlush — preserving the DHT
        /// QuorumCollector fast-fail against genuinely-dead replicas (no 30s stall).
        @Test
        void writeIfWritable_dhtInactiveChannel_refusesImmediately_noWrite() {
            var ch = mock(QuicStreamChannel.class);
            when(ch.isActive()).thenReturn(false);
            when(ch.isWritable()).thenReturn(false);

            var network = network();

            var outcome = network.writeIfWritableForTest(ch, payload(), PEER, StreamType.DHT);

            assertThat(outcome)
                .as("dead DHT replica must fast-fail so the quorum collector does not stall")
                .isInstanceOf(WriteOutcome.BackpressureRefused.class);
            verify(ch, never()).writeAndFlush(any());
        }

        /// A non-DHT/non-CONSENSUS lane (KV) that is backpressured must STILL fast-fail with
        /// BackpressureRefused regardless of channel liveness — the DHT live-peer relaxation does
        /// not leak into other lanes.
        @Test
        void writeIfWritable_kvBackpressured_refusesImmediately_evenWhenActive() {
            var ch = mock(QuicStreamChannel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.isWritable()).thenReturn(false);

            var network = network();

            var outcome = network.writeIfWritableForTest(ch, payload(), PEER, StreamType.KV);

            assertThat(outcome)
                .as("non-DHT/non-CONSENSUS lanes keep unconditional fast-fail")
                .isInstanceOf(WriteOutcome.BackpressureRefused.class);
            verify(ch, never()).writeAndFlush(any());
        }
    }

    @Nested
    class RawConsensusWriteUnit {

        /// rawConsensusWrite fails cleanly (a Cause, never a throw) when the channel is
        /// inactive — so a peer disconnecting mid-retry lets the Retry give up cleanly.
        @Test
        void rawConsensusWrite_inactiveChannel_failsWithCause() {
            var ch = mock(QuicStreamChannel.class);
            when(ch.isActive()).thenReturn(false);
            when(ch.isWritable()).thenReturn(true);

            var network = network();

            network.rawConsensusWriteForTest(ch, payload(), PEER)
                   .await(TimeSpan.timeSpan(5).seconds())
                   .onSuccess(_ -> fail("inactive channel must fail, not deliver"));
            verify(ch, never()).writeAndFlush(any());
        }

        /// rawConsensusWrite delivers when the channel is active and writable.
        @Test
        void rawConsensusWrite_activeWritable_delivers() {
            var ch = mock(QuicStreamChannel.class);
            var future = mock(ChannelFuture.class);
            when(future.addListener(any())).thenReturn(future);
            when(ch.writeAndFlush(any())).thenReturn(future);
            when(ch.isActive()).thenReturn(true);
            when(ch.isWritable()).thenReturn(true);

            var network = network();

            network.rawConsensusWriteForTest(ch, payload(), PEER)
                   .await(TimeSpan.timeSpan(5).seconds())
                   .onFailure(cause -> fail("active writable channel must deliver: " + cause.message()));
            verify(ch, times(1)).writeAndFlush(any());
        }
    }

    @Nested
    class RemovedPeerShortCircuit {

        private static final NodeId DEAD = new NodeId("dead-peer");

        /// rawConsensusWrite to a REMOVED peer DROPS (fails with a terminal Cause) without ever
        /// touching the channel — even when the channel is active and writable. Closes the
        /// 200x "CONSENSUS stream backpressured or inactive" retry loop against a corpse.
        @Test
        void rawConsensusWrite_removedPeer_dropsWithoutWriting() {
            var ch = mock(QuicStreamChannel.class);
            when(ch.isActive()).thenReturn(true);
            when(ch.isWritable()).thenReturn(true);

            var network = network();
            network.seedPeerForTests(DEAD, removedPeerState(DEAD));

            network.rawConsensusWriteForTest(ch, payload(), DEAD)
                   .await(TimeSpan.timeSpan(5).seconds())
                   .onSuccess(_ -> fail("REMOVED peer must drop, not deliver"))
                   .onFailure(cause -> org.junit.jupiter.api.Assertions.assertTrue(cause.isTerminal(),
                       "the removed-peer cause must be TERMINAL so the enclosing Retry stops on the first"
                       + " verdict — '(terminal)' in message text alone was measured at 4,160 scheduled"
                       + " retries of this exact cause"));
            verify(ch, never()).writeAndFlush(any());
        }

        /// A peer that is NOT removed (no resident state / EVICTED-style) still writes normally —
        /// proving the short-circuit triggers ONLY on REMOVED and never on a live/flapping peer.
        @Test
        void rawConsensusWrite_nonRemovedPeer_writesNormally() {
            var ch = mock(QuicStreamChannel.class);
            var future = mock(ChannelFuture.class);
            when(future.addListener(any())).thenReturn(future);
            when(ch.writeAndFlush(any())).thenReturn(future);
            when(ch.isActive()).thenReturn(true);
            when(ch.isWritable()).thenReturn(true);

            var network = network();

            network.rawConsensusWriteForTest(ch, payload(), PEER)
                   .await(TimeSpan.timeSpan(5).seconds())
                   .onFailure(cause -> fail("non-REMOVED peer must deliver: " + cause.message()));
            verify(ch, times(1)).writeAndFlush(any());
        }

        private static PeerState removedPeerState(NodeId peerId) {
            var s = PeerState.peerState(peerId, System.nanoTime());
            var dropped = s.authoritativeRemove(System.nanoTime());
            assertThat(dropped.isEmpty()).isTrue();
            assertThat(s.phase()).isEqualTo(PeerState.Phase.REMOVED);
            return s;
        }
    }

    @Nested
    class BroadcastMembershipFilter {

        private static final NodeId IN_VIEW = new NodeId("member-in-view");
        private static final NodeId EVICTED = new NodeId("evicted-but-cached");

        /// With an FSM-backed membership view that EXCLUDES a still-cached peer, a broadcast must
        /// NOT target that peer — even though it lingers in the transport `peers` table as a live
        /// connection. This is the #68 dead-ULID storm fix: the evicted peer is never re-targeted,
        /// so no `CONSENSUS stream backpressured` retry can ever start against it.
        @Test
        void broadcastTargets_membershipViewExcludesPeer_peerNotTargeted() {
            var network = network();
            network.seedPeerForTests(IN_VIEW, PeerState.peerState(IN_VIEW, System.nanoTime()));
            network.seedPeerForTests(EVICTED, PeerState.peerState(EVICTED, System.nanoTime()));

            network.setBroadcastMembership(() -> Set.of(IN_VIEW));

            var targets = network.broadcastTargetsForTests();

            assertThat(targets)
                .as("only the membership-view member is a broadcast target")
                .containsExactly(IN_VIEW);
            assertThat(targets)
                .as("an evicted-but-cached peer is never targeted — #68 storm fix")
                .doesNotContain(EVICTED);
        }

        /// Wave 5: the membership filter is MANDATORY — there is no unfiltered default. From
        /// construction the view is `topologyManager.coreNodes()` (the SWIM-fed authoritative
        /// membership), so a cached peer ABSENT from the authoritative membership is never a
        /// broadcast target even before the FSM view is wired.
        @Test
        void broadcastTargets_unwiredDefault_filtersToAuthoritativeMembership() {
            var network = network(IN_VIEW);
            network.seedPeerForTests(IN_VIEW, PeerState.peerState(IN_VIEW, System.nanoTime()));
            network.seedPeerForTests(EVICTED, PeerState.peerState(EVICTED, System.nanoTime()));

            var targets = network.broadcastTargetsForTests();

            assertThat(targets)
                .as("unwired default filters to the authoritative membership (topology coreNodes)")
                .containsExactly(IN_VIEW);
            assertThat(targets)
                .as("a cached peer outside the authoritative membership is never targeted — no unfiltered path")
                .doesNotContain(EVICTED);
        }

        /// A `null` supplier passed to `setBroadcastMembership` resets to the MANDATORY
        /// construction-time default (`topologyManager.coreNodes()`) — never to "no filter".
        @Test
        void broadcastTargets_nullSupplierResetsToMandatoryTopologyDefault() {
            var network = network(IN_VIEW);
            network.seedPeerForTests(IN_VIEW, PeerState.peerState(IN_VIEW, System.nanoTime()));

            network.setBroadcastMembership(() -> Set.of());
            assertThat(network.broadcastTargetsForTests())
                .as("an explicit empty membership view excludes every peer")
                .isEmpty();

            network.setBroadcastMembership(null);

            assertThat(network.broadcastTargetsForTests())
                .as("null supplier restores the topology-backed mandatory default, not an unfiltered path")
                .containsExactly(IN_VIEW);
        }
    }

    // --- Helpers ---

    private static Retry fastRetry(int attempts) {
        return Retry.retry()
                    .attempts(attempts)
                    .strategy(BackoffStrategy.fixed().interval(TimeSpan.timeSpan(5).millis()));
    }

    private static byte[] payload() {
        return new byte[]{1, 2, 3, 4};
    }

    private static void awaitWriteFlush(QuicStreamChannel ch, int expected) {
        var deadline = System.nanoTime() + TimeSpan.timeSpan(5).seconds().nanos();
        while (System.nanoTime() < deadline) {
            try {
                verify(ch, times(expected)).writeAndFlush(any());
                return;
            } catch (AssertionError retry) {
                parkBriefly();
            }
        }
    }

    private static void parkBriefly() {
        java.util.concurrent.locks.LockSupport.parkNanos(TimeSpan.timeSpan(10).millis().nanos());
    }

    private QuicClusterNetwork network(NodeId... extraTopology) {
        var codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), List.of());
        var serverSsl = serverSsl();
        var clientSsl = clientSsl();
        var nodeAddress = NodeAddress.nodeAddress("127.0.0.1", 19997)
                                     .fold(_ -> fail("Invalid address"), addr -> addr);
        var selfInfo = NodeInfo.nodeInfo(new NodeId("self"), nodeAddress);
        return new QuicClusterNetwork(stubTopology(selfInfo, List.of(extraTopology)), codec, codec,
                                      MessageRouter.mutable(), serverSsl, clientSsl);
    }

    private static QuicSslContext serverSsl() {
        return QuicTlsProvider.serverContext(TlsConfig.selfSignedServer())
                              .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
    }

    private static QuicSslContext clientSsl() {
        return QuicTlsProvider.clientContext(TlsConfig.insecureClient())
                              .fold(_ -> fail("Client SSL failed"), ssl -> ssl);
    }

    private static TopologyObserver stubTopology(NodeInfo self, List<NodeId> extraTopology) {
        return new TopologyObserver() {
            @Override public NodeInfo self() {return self;}
            @Override public Option<NodeInfo> get(NodeId id) {return id.equals(self.id()) ? Option.some(self) : Option.empty();}
            @Override public int clusterSize() {return 1;}
            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
            @Override public Promise<Unit> start() {return Promise.unitPromise();}
            @Override public Promise<Unit> stop() {return Promise.unitPromise();}
            @Override public TimeSpan pingInterval() {return TimeSpan.timeSpan(1).seconds();}
            @Override public TimeSpan helloTimeout() {return TimeSpan.timeSpan(5).seconds();}
            @Override public Option<TlsConfig> tls() {return Option.empty();}
            @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
            @Override public List<NodeId> topology() {
                var r = new ArrayList<NodeId>();
                r.add(self.id());
                r.addAll(extraTopology);
                return r;
            }
            @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
            @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
            @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
            @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
        };
    }
}
