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

import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.ConsensusCodecs;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
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
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.TlsConfig;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


/// Verifies the periodic missing-peer reconciler in [`QuicClusterNetwork`]: every tick walks
/// configured peers, and for any peer whose `PeerState.phase()` is not CONNECTED, dispatches
/// a reconnect attempt subject to per-peer jittered exponential backoff.
///
/// Closes the asymmetric-handshake hole — node A handshakes with B/C/D but permanently misses
/// E — that left the smoke-gate sticky on `4 of 5` healthy peers.
@Timeout(10)
class QuicClusterNetworkReconcilerTest {
    private static final TimeSpan AWAIT_TIMEOUT = TimeSpan.timeSpan(5).seconds();
    private static final TimeSpan PING_INTERVAL = TimeSpan.timeSpan(1).seconds();
    private static final TimeSpan HELLO_TIMEOUT = TimeSpan.timeSpan(5).seconds();

    private SliceCodec codec;
    private QuicSslContext serverSsl;
    private QuicSslContext clientSsl;
    private final List<QuicClusterNetwork> networks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        codec = SliceCodec.sliceCodec(FrameworkCodecs.frameworkCodecs(), combinedCodecs());
        serverSsl = QuicTlsProvider.serverContext(TlsConfig.selfSignedServer())
                                    .fold(_ -> fail("Server SSL failed"), ssl -> ssl);
        clientSsl = QuicTlsProvider.clientContext(TlsConfig.insecureClient())
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
    void reconcileMissingPeersTick_oneMissingPeer_dispatchesSingleDial() {
        // Self has lower NodeId than the configured peer so ConnectionDirection lets us
        // initiate. Peer is unreachable (port 1 is reserved/blackhole). The reconciler should
        // count it as missing on the first tick and request its NodeInfo from topology.
        var self = new NodeId("aaa-self");
        var missingPeer = new NodeId("zzz-missing");
        var peerInfo = NodeInfo.nodeInfo(missingPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("first reconciler tick must dispatch one dial for the missing peer")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_allPeersConnected_isNoOp() {
        // No peers configured beyond self → connectedPeers() == configured peers (empty);
        // reconciler must not look anything up.
        var self = new NodeId("solo-self");
        var stub = countingTopology(self, List.of());
        var network = createNetwork(self, List.of(), MessageRouter.mutable(), stub);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups)
            .as("reconciler must be a no-op when no peers are configured")
            .isEmpty();
    }

    @Test
    void reconcileMissingPeersTick_repeatedTickWithinBackoff_doesNotBurstLoop() {
        // 5 ticks fired in tight succession under a frozen clock must not produce 5 dials
        // for the same missing peer. The first tick seeds nextAttemptMs ≥ now + 5000ms*0.8;
        // ticks 2..5 within the same logical instant fall before that deadline and are
        // suppressed by the per-peer reconcile-backoff state on PeerState.
        var self = new NodeId("aaa-self");
        var missingPeer = new NodeId("zzz-missing");
        var peerInfo = NodeInfo.nodeInfo(missingPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        for (int i = 0; i < 5; i++) {
            network.reconcileMissingPeersTick();
            // Advance 1ms — well below the 5s lower jitter floor (5000 * 0.8 = 4000ms).
            clock.addAndGet(1L);
        }

        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("close-spaced reconciler ticks must coalesce; backoff suppresses bursts")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_backoffProgresses_onRepeatedFailures() {
        // Drive the reconciler past its first backoff window, then trigger a second tick.
        // The second window must be at least 2x the first (delay doubled before jitter).
        // The topology returns Option.empty() on get() so connectPeer is never invoked
        // (no async phase change to CONNECTING). The reconciler still counts "dispatched"
        // attempts via the topology lookup — backoff progression is exercised end-to-end.
        var self = new NodeId("aaa-self");
        var missingPeer = new NodeId("zzz-missing");
        var stub = countingTopologyWithoutInfo(self, List.of(missingPeer));
        var clock = new AtomicLong(0L);
        var network = createNetwork(self, List.of(), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        // Tick 1 — first attempt dispatched, currentDelay seeded to 5_000ms,
        // nextAttemptMs ∈ [4_000, 6_000].
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L)).isEqualTo(1L);

        // Step past upper jitter bound of first window (5_000 * 1.2 = 6_000ms).
        clock.set(6_500L);

        // Tick 2 — second attempt dispatched, currentDelay doubled to 10_000ms,
        // nextAttemptMs ∈ [now + 8_000, now + 12_000].
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L)).isEqualTo(2L);

        // Probe inside the second window's lower bound (clock at 6_500 + 7_000 = 13_500;
        // nextAttemptMs ≥ 6_500 + 8_000 = 14_500 → still suppressed).
        clock.addAndGet(7_000L);
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("second backoff window must still suppress dispatch 7s after tick 2")
            .isEqualTo(2L);

        // Step past the second window's upper bound (10_000 * 1.2 = 12_000ms after tick 2;
        // tick 2 was at clock=6_500 → upper bound at 18_500). Set clock to 19_000.
        clock.set(19_000L);
        network.reconcileMissingPeersTick();
        assertThat(stub.lookups.getOrDefault(missingPeer, 0L))
            .as("after second window expires, third dispatch is permitted")
            .isEqualTo(3L);
    }

    @Test
    void reconcileMissingPeersTick_skipsPeerWhereSelfShouldNotInitiate() {
        // ConnectionDirection: only the lower NodeId initiates. Self has higher NodeId than
        // the missing peer here, so the reconciler must skip the dial — the peer accepts
        // inbound, so any reconnect must come from the lower-id side.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo));
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("higher-NodeId side must not initiate reconciler dial")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_swimDepartedConfiguredPeer_isNotRedialed() {
        // Membership v2 §5.4: a configured core peer that the SWIM-fed authoritative
        // membership has dropped (absent from coreNodes()) must NOT be re-dialed by the
        // missing-peer reconciler — it is left for the leader's auto-heal. This is the
        // forever-redial wedge fix. Self is lower-id so it would otherwise initiate.
        var self = new NodeId("aaa-self");
        var departedPeer = new NodeId("zzz-departed");
        var peerInfo = NodeInfo.nodeInfo(departedPeer, addressOf("127.0.0.1", 1));
        // coreNodes() omits the departed peer (SWIM-departed) while topology() still lists it.
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(departedPeer, 0L))
            .as("SWIM-departed configured peer (absent from coreNodes) must not be re-dialed")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_swimPresentButDisconnectedPeer_isRedialed() {
        // A SWIM-present peer (still in coreNodes()) that is merely QUIC-disconnected MUST
        // still be re-dialed — that is legitimate reconnection, distinct from a departure.
        var self = new NodeId("aaa-self");
        var presentPeer = new NodeId("zzz-present");
        var peerInfo = NodeInfo.nodeInfo(presentPeer, addressOf("127.0.0.1", 1));
        // coreNodes() includes the peer (SWIM still considers it present).
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, presentPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(presentPeer, 0L))
            .as("SWIM-present but QUIC-disconnected peer must still be re-dialed (reconnection)")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_coldStartNoSnapshot_freshlyDiscoveredPeerIsDialable() {
        // Cold start: no snapshot yet, so coreNodes() falls back to the locally-seeded
        // configured core set, which INCLUDES the configured peer. The reconciler must dial
        // it so formation can proceed (the gate must NOT block a not-yet-connected seed).
        var self = new NodeId("aaa-self");
        var seedPeer = new NodeId("zzz-seed");
        var peerInfo = NodeInfo.nodeInfo(seedPeer, addressOf("127.0.0.1", 1));
        // Cold-start coreNodes() fallback = all configured core members (self + seed).
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, seedPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(seedPeer, 0L))
            .as("cold-start configured seed (present in coreNodes fallback) must be dialable")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_removedPeerBackInCoreNodes_isReadmittedAndDialed() {
        // Transient-partition survivor: a peer driven to REMOVED (departurePermanent) that SWIM
        // has since RE-ADMITTED to the authoritative membership (incarnation supersede → back in
        // coreNodes()) must be reset REMOVED->INIT and re-dialed. Membership-convergence FSM §9.4:
        // only a strictly-higher incarnation is terminal; a same/refuted-incarnation peer rejoins.
        var self = new NodeId("aaa-self");
        var removedPeer = new NodeId("zzz-removed");
        var peerInfo = NodeInfo.nodeInfo(removedPeer, addressOf("127.0.0.1", 1));
        // coreNodes() INCLUDES the peer — SWIM re-admitted it after the tombstone was superseded.
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, removedPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        // Seed the peer in the terminal REMOVED phase, as departurePermanent would have left it.
        var removedState = PeerState.peerState(removedPeer, 1L);
        removedState.authoritativeRemove(2L);
        network.seedPeerForTests(removedPeer, removedState);
        assertThat(removedState.phase()).isEqualTo(PeerState.Phase.REMOVED);

        network.reconcileMissingPeersTick();

        assertThat(removedState.phase())
            .as("REMOVED peer back in coreNodes() must be readmitted out of the terminal phase")
            .isNotEqualTo(PeerState.Phase.REMOVED);
        assertThat(stub.lookups.getOrDefault(removedPeer, 0L))
            .as("re-admitted peer must be re-dialed by the reconciler")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_removedPeerAbsentFromCoreNodes_staysRemovedAndIsNotDialed() {
        // Genuine departure: a REMOVED peer that is NOT in coreNodes() (SWIM never re-admitted it,
        // no higher incarnation) is a permanent corpse. It must stay REMOVED and not be re-dialed —
        // left for the leader's auto-heal. This is the anti-resurrection guarantee at the dial side.
        var self = new NodeId("aaa-self");
        var departedPeer = new NodeId("zzz-departed");
        var peerInfo = NodeInfo.nodeInfo(departedPeer, addressOf("127.0.0.1", 1));
        // coreNodes() OMITS the departed peer (SWIM-departed, no incarnation supersede).
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        var removedState = PeerState.peerState(departedPeer, 1L);
        removedState.authoritativeRemove(2L);
        network.seedPeerForTests(departedPeer, removedState);

        network.reconcileMissingPeersTick();

        assertThat(removedState.phase())
            .as("REMOVED peer absent from coreNodes() must remain terminal (no resurrection)")
            .isEqualTo(PeerState.Phase.REMOVED);
        assertThat(stub.lookups.getOrDefault(departedPeer, 0L))
            .as("genuinely-departed REMOVED peer must not be re-dialed")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_higherSelfIdBeforeGrace_doesNotForceDial() {
        // Fix B precondition: higher-id self does NOT dial a lower-id peer before the higher-id
        // grace window (RECONCILE_BACKOFF_CAP_MS = 60s) has elapsed. First tick only records the
        // missing-since timestamp; the peer is skipped (single-dialer ordering honoured).
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, lowerPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("higher-id self must NOT dial before the higher-id grace elapses (single-dialer)")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_topologyPath_higherSelfIdAfterGrace_forceDials() {
        // Fix B (the topology-path force-dial regression): a higher-id self whose lower-id peer
        // never initiated MUST force-dial once the higher-id grace window elapses. Before Fix B the
        // topology path called the 1-arg reconcileDialPeer → connectPeer(peer, false), so
        // connectPeer's unconditional shouldInitiate guard silently RETURNED EARLY (logging "will
        // force-dial once grace elapses" on every tick) — the peer NEVER left INIT. With
        // forceInitiate threaded through, connectPeer proceeds past the guard and begins the dial,
        // driving the peer out of INIT (→ CONNECTING in flight, or EVICTED after the unroutable
        // address fails fast). Asserting the PHASE TRANSITION (not just the lookup count) is what
        // proves forceInitiate actually reached connectPeer: a lookup increments either way, but
        // only a force-dial drives the seeded peer out of INIT. No desiredConnections is wired, so
        // this exercises the LEGACY TOPOLOGY PATH specifically.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, lowerPeer));
        var clock = new AtomicLong(0L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);
        // Seed the peer in INIT so the force-dial's INIT→CONNECTING transition is observable.
        var seeded = network.seedPeerForTests(lowerPeer, PeerState.peerState(lowerPeer, 1L));

        // Tick 1 at T=0: higher-id self records missing-since=0 and skips (before grace). The peer
        // must stay INIT — proving the single-dialer guard still holds before grace.
        network.reconcileMissingPeersTick();
        assertThat(seeded.phase())
            .as("before grace, higher-id self skips the dial — peer stays INIT")
            .isEqualTo(PeerState.Phase.INIT);

        // Advance past the higher-id grace window (RECONCILE_BACKOFF_CAP_MS = 60s) AND past the
        // per-peer reconcile backoff so the force-dial is dispatched THIS tick.
        clock.set(70_000L);
        network.reconcileMissingPeersTick();

        // The dial dispatch (connectPeer → resolve → beginConnecting) is async on the Netty
        // resolver continuation; await the peer LEAVING INIT. Pre-Fix-B this would never happen
        // (connectPeer returned early on the shouldInitiate guard).
        awaitTrue(() -> seeded.phase() != PeerState.Phase.INIT,
                  "Fix B: after grace, the topology path force-dials the lower-id peer "
                  + "(forceInitiate threaded through to connectPeer) — peer leaves INIT");
        assertThat(seeded.phase())
            .as("force-dial dispatched: peer lands in CONNECTING (in flight) or EVICTED (failed fast)")
            .isIn(PeerState.Phase.CONNECTING, PeerState.Phase.EVICTED);
        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("the force-dial path performed the topology NodeInfo lookup")
            .isGreaterThanOrEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_higherSelfId_previouslyConnectedEvictedPeer_forceDialsWithinGrace() {
        // #491 F1: the 60s higher-id grace exists ONLY to avoid the cold-boot dual-Hello race for a
        // NEVER-connected peer. A LIVE authoritative member that reached CONNECTED and is now EVICTED
        // (an asymmetrically-dropped link) is a lost connection to a known-live node — there is no
        // dual-Hello race to avoid — so the higher-id side MUST force-dial it on the NEXT tick, WITHIN
        // the grace window. Direct contrast with reconcileMissingPeersTick_higherSelfIdBeforeGrace_
        // doesNotForceDial: an INIT (never-connected) peer under the SAME higher-id ordering stays
        // un-dialed (lookups == 0) until the 60s grace elapses. The clock is frozen at T=0 (deep inside
        // grace) so only the previously-CONNECTED bypass can explain a dial here.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, lowerPeer));
        var clock = new AtomicLong(0L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);
        // Seed the peer as a member that CONNECTED then lost its link (EVICTED, ever-connected).
        var seeded = network.seedPeerForTests(lowerPeer, evictedAfterConnected(lowerPeer));
        assertThat(seeded.hasEverConnected())
            .as("precondition: seeded peer completed a Hello handshake before eviction")
            .isTrue();
        assertThat(seeded.phase())
            .as("precondition: seeded peer is EVICTED (lost link to a known-live member)")
            .isEqualTo(PeerState.Phase.EVICTED);

        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("#491: a previously-CONNECTED evicted live member is force-dialed within grace (grace bypassed)")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_previouslyConnectedEvictedPeer_repeatTickWithinBackoff_deduped() {
        // #491 F1 dedup: the grace bypass fires the force-dial ONCE, then the per-peer reconcile
        // backoff (and the in-flight beginConnecting guard) suppress redundant dials while the first
        // attempt is outstanding. With the clock frozen at T=0, every subsequent tick — this test's
        // extra manual ticks AND any concurrent scheduler tick — shares the same backoff window and is
        // suppressed, so the topology lookup count stays at exactly one. reconcileBackoffAllows is
        // synchronized, so the check-and-set is atomic under the concurrent scheduler.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, lowerPeer));
        var clock = new AtomicLong(0L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);
        network.seedPeerForTests(lowerPeer, evictedAfterConnected(lowerPeer));

        network.reconcileMissingPeersTick();
        network.reconcileMissingPeersTick();
        network.reconcileMissingPeersTick();

        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("#491: repeated ticks within the backoff window force-dial the evicted peer exactly once (deduped)")
            .isEqualTo(1L);
    }

    @Test
    void connect_removedPeer_isSkipped() {
        // #491 Q3: connect(ConnectNode) must NOT dial a peer whose PeerState is REMOVED — a
        // killed/decommissioned node is a terminal departure, and retrying it forever spammed the dial
        // path (the sof-3 case). Any genuine transient-partition survivor is readmitted REMOVED->INIT
        // by the incarnation-fenced missing-peer reconciler and never reaches connect() still REMOVED.
        // The peer is absent from coreNodes() so the concurrent reconciler leaves it terminal on its
        // own — the ONLY thing that could dial it here is connect(), and the Q3 gate must stop it
        // BEFORE the topology lookup (lookups == 0 discriminates the gate from a plain shouldInitiate skip).
        var self = new NodeId("aaa-self");
        var removedPeer = new NodeId("zzz-removed");
        var peerInfo = NodeInfo.nodeInfo(removedPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        var removedState = PeerState.peerState(removedPeer, 1L);
        removedState.authoritativeRemove(2L);
        network.seedPeerForTests(removedPeer, removedState);

        network.connect(new NetworkServiceMessage.ConnectNode(removedPeer));

        assertThat(removedState.phase())
            .as("#491 Q3: connect(ConnectNode) leaves a REMOVED peer terminal")
            .isEqualTo(PeerState.Phase.REMOVED);
        assertThat(stub.lookups.getOrDefault(removedPeer, 0L))
            .as("#491 Q3: connect(ConnectNode) performs no topology lookup / dial for a REMOVED peer")
            .isEqualTo(0L);
    }

    @Test
    void reconcileMissingPeersTick_higherSelfId_memberBeforeGrace_eagerlyCreatesPeerStateWithoutDial() {
        // #491 m3(i): a higher-id self does NOT dial a lower-id member before the grace window, but the
        // authoritative desired/topology set means the member should still receive an eager INIT
        // PeerState — so outbound to it BUFFERS (offline buffer) instead of hard-dropping on a null
        // state. No dial happens (single-dialer ordering honoured): the topology lookup stays 0, yet
        // the PeerState now exists in INIT.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, lowerPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        network.peerPhaseForTests(lowerPeer)
               .onEmpty(() -> fail("#491 m3(i): a member peer we are not yet dialing must get an eager INIT PeerState"))
               .onPresent(phase -> assertThat(phase)
                   .as("eagerly-created PeerState is INIT (never dialed) so outbound buffers")
                   .isEqualTo(PeerState.Phase.INIT));
        assertThat(stub.lookups.getOrDefault(lowerPeer, 0L))
            .as("#491 m3(i): eager PeerState creation performs NO dial (single-dialer ordering preserved)")
            .isEqualTo(0L);
    }

    @Test
    void send_toEagerlyCreatedMemberPeer_buffersInsteadOfDroppingToUnknown() {
        // #491 m3(i): once the reconciler has eagerly created the INIT PeerState, an outbound send to
        // that not-yet-connected member BUFFERS (offline buffer) rather than hard-dropping. The
        // drop-to-unknown metric stays flat; the backpressure-queued metric advances by one.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, lowerPeer));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);
        network.reconcileMissingPeersTick();

        var dropsBefore = network.quicMetrics().dropToUnknownPeerCount();
        var queuedBefore = network.quicMetrics().backpressureQueuedCount();

        network.send(lowerPeer, stubProtocolMessage(self));

        assertThat(network.quicMetrics().dropToUnknownPeerCount() - dropsBefore)
            .as("send to an eagerly-created INIT member buffers — no drop-to-unknown")
            .isEqualTo(0L);
        assertThat(network.quicMetrics().backpressureQueuedCount() - queuedBefore)
            .as("the buffered send advances the offline-buffer (backpressure-queued) metric")
            .isEqualTo(1L);
    }

    @Test
    void reconcileMissingPeersTick_higherSelfId_nonMemberPeer_staysNullAndHardDrops() {
        // #491 m3(i): eager PeerState creation is MEMBERSHIP-gated. A peer ABSENT from coreNodes()
        // (departed / not a member) is NOT eagerly materialised — it stays null and outbound to it
        // still HARD-DROPS with the drop-to-unknown metric. Only genuine members earn the buffer.
        var self = new NodeId("zzz-self");
        var lowerPeer = new NodeId("aaa-lower");
        var peerInfo = NodeInfo.nodeInfo(lowerPeer, addressOf("127.0.0.1", 1));
        // coreNodes() OMITS the peer — not a member of the authoritative membership.
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self));
        var clock = new AtomicLong(1_000_000L);
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);
        network.overrideWallClockForTests(clock::get);

        network.reconcileMissingPeersTick();

        assertThat(network.peerPhaseForTests(lowerPeer).isEmpty())
            .as("#491 m3(i): a non-member peer is NOT eagerly created — stays null")
            .isTrue();

        var dropsBefore = network.quicMetrics().dropToUnknownPeerCount();
        network.send(lowerPeer, stubProtocolMessage(self));
        assertThat(network.quicMetrics().dropToUnknownPeerCount() - dropsBefore)
            .as("outbound to a null (non-member) peer still hard-drops to the unknown-peer metric")
            .isEqualTo(1L);
    }

    @Test
    void send_toNullMemberPeer_createsPeerStateAndBuffersInsteadOfDropping() {
        // #491 unicast buffering: a send to an authoritative MEMBER with NO PeerState (the backfill-unicast
        // race — the catch-up send from the backfill thread can precede the missing-peer reconciler's eager
        // creation) must EAGERLY create an INIT PeerState and BUFFER the message in its offline buffer (the
        // exact Queued path broadcast uses), NOT hard-drop. The drop-to-unknown metric stays flat; the
        // buffered message is delivered on the next attach via the shared drain (PeerStateTest-proven).
        var self = new NodeId("zzz-self");
        var member = new NodeId("aaa-member");
        var peerInfo = NodeInfo.nodeInfo(member, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, member));
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);

        var dropsBefore = network.quicMetrics().dropToUnknownPeerCount();

        network.send(member, stubProtocolMessage(self));

        network.peerPhaseForTests(member)
               .onEmpty(() -> fail("#491: a unicast to a null-state member must eagerly create its PeerState"))
               .onPresent(phase -> assertThat(phase)
                   .as("the eagerly-created PeerState buffers (INIT/CONNECTING/EVICTED — never a null hard-drop)")
                   .isIn(PeerState.Phase.INIT, PeerState.Phase.CONNECTING, PeerState.Phase.EVICTED));
        assertThat(network.offlineBufferSizeForTests(member))
            .as("#491: the message is buffered in the member's offline buffer, not dropped")
            .isEqualTo(1);
        assertThat(network.quicMetrics().dropToUnknownPeerCount() - dropsBefore)
            .as("a buffered unicast to a member does not increment the drop-to-unknown metric")
            .isEqualTo(0L);
    }

    @Test
    void sendOutcome_toNullMemberPeer_buffersAndReportsSent() {
        // #491: the outcome-tracking sendOutcome variant must ALSO buffer (not NoPeerState-drop) a unicast to
        // a null-state member, and report Sent per the offline-buffer convention so the DHT caller treats it
        // as enqueued for eventual delivery.
        var self = new NodeId("zzz-self");
        var member = new NodeId("aaa-member");
        var peerInfo = NodeInfo.nodeInfo(member, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, member));
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);

        var outcome = network.sendOutcome(member, stubProtocolMessage(self)).await(AWAIT_TIMEOUT);

        outcome.onFailure(cause -> fail("sendOutcome should resolve: " + cause.message()))
               .onSuccess(result -> assertThat(result)
                   .as("#491: sendOutcome to a null-state member buffers and reports Sent")
                   .isInstanceOf(WriteOutcome.Sent.class));
        assertThat(network.offlineBufferSizeForTests(member))
            .as("the sendOutcome message is buffered in the member's offline buffer")
            .isEqualTo(1);
    }

    @Test
    void send_toNullMemberPeer_offlineBufferBoundHolds() {
        // #491: buffering a unicast to a null-state member uses the SAME bounded offline buffer as broadcast —
        // OFFLINE_BUFFER_MAX-capped, oldest-evicted on overflow. Spot-check the bound: sending past the cap
        // holds the buffer at OFFLINE_BUFFER_MAX (never unbounded growth).
        var self = new NodeId("zzz-self");
        var member = new NodeId("aaa-member");
        var peerInfo = NodeInfo.nodeInfo(member, addressOf("127.0.0.1", 1));
        var stub = countingTopology(self, List.of(peerInfo), Set.of(self, member));
        var network = createNetwork(self, List.of(peerInfo), MessageRouter.mutable(), stub);

        for (var i = 0; i < PeerState.OFFLINE_BUFFER_MAX + 2; i++) {
            network.send(member, stubProtocolMessage(self));
        }

        assertThat(network.offlineBufferSizeForTests(member))
            .as("#491: the offline buffer is bounded at OFFLINE_BUFFER_MAX on overflow (oldest evicted)")
            .isEqualTo(PeerState.OFFLINE_BUFFER_MAX);
    }

    private static NodeAddress addressOf(String host, int port) {
        return NodeAddress.nodeAddress(host, port).fold(_ -> fail("bad address"), a -> a);
    }

    /// Bounded poll for an async condition (no Awaitility in this module): the QUIC dial dispatch
    /// runs on a Netty resolver continuation, so the INIT→CONNECTING transition arrives
    /// asynchronously. Asserts the condition holds within AWAIT_TIMEOUT.
    private static void awaitTrue(java.util.function.BooleanSupplier condition, String description) {
        var deadline = System.nanoTime() + AWAIT_TIMEOUT.nanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            sleepBriefly();
        }
        assertThat(condition.getAsBoolean()).as(description).isTrue();
    }

    private static void sleepBriefly() {
        try {
            Thread.sleep(25);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /// Builds a `PeerState` for a member that CONNECTED then lost its link: INIT → CONNECTING →
    /// CONNECTED (via a live-channel attach, marking it ever-connected) → EVICTED. This is the exact
    /// shape `previouslyConnectedNowEvicted` keys the #491 grace bypass off — distinct from a hung
    /// cold-boot dial (CONNECTING → EVICTED) which never connected. The mocked channel only needs to
    /// report active so the attach is ACCEPTED; the connection is dropped by `evict`.
    private static PeerState evictedAfterConnected(NodeId peerId) {
        var state = PeerState.peerState(peerId, 1L);
        state.beginConnecting(2L);
        state.attach(liveConnectionFor(peerId), 3L);
        state.evict(4L);
        return state;
    }

    private static QuicPeerConnection liveConnectionFor(NodeId peerId) {
        var channel = mock(QuicChannel.class);
        when(channel.isActive()).thenReturn(true);
        return QuicPeerConnection.quicPeerConnection(peerId, channel);
    }

    private static StubProtocolMessage stubProtocolMessage(NodeId sender) {
        return new StubProtocolMessage(sender);
    }

    private record StubProtocolMessage(NodeId sender) implements ProtocolMessage {
        @Override
        public StreamType streamType() {
            return StreamType.CONSENSUS;
        }
    }

    private QuicClusterNetwork createNetwork(NodeId nodeId,
                                              List<NodeInfo> peers,
                                              MessageRouter router,
                                              TopologyObserver topology) {
        var network = new QuicClusterNetwork(topology, codec, codec, router, serverSsl, clientSsl,
                                              ClusterFormationConfig.defaults(), QuicDisconnectListener.noop());
        networks.add(network);
        network.startOnPort(0).await(AWAIT_TIMEOUT).onFailure(cause -> fail("start failed: " + cause.message()));
        return network;
    }

    private CountingTopology countingTopology(NodeId selfId, List<NodeInfo> peers) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        return new CountingTopology(selfInfo, peers, false, Option.empty());
    }

    /// Variant that pins an explicit `coreNodes()` set — the SWIM-fed authoritative
    /// membership. A configured peer present in `topology()` but absent from this set is
    /// SWIM-departed and must not be re-dialed; a peer present in both is SWIM-present and
    /// must be re-dialed (reconnection). Used by the §5.4 dial-gate tests.
    private CountingTopology countingTopology(NodeId selfId, List<NodeInfo> peers, Set<NodeId> coreNodes) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        return new CountingTopology(selfInfo, peers, false, Option.some(coreNodes));
    }

    /// Variant that lists peer NodeIds in `topology()` but returns `Option.empty()` from
    /// `get(NodeId)`. The reconciler counts the lookup attempt (proof that the backoff
    /// gate let the tick through) without actually invoking `connectPeer` — keeps the
    /// per-peer phase at INIT so backoff progression can be exercised across multiple
    /// ticks without async dial-failure noise.
    private CountingTopology countingTopologyWithoutInfo(NodeId selfId, List<NodeId> peerIds) {
        var selfInfo = NodeInfo.nodeInfo(selfId, addressOf("127.0.0.1", 19998));
        var peers = peerIds.stream().map(id -> NodeInfo.nodeInfo(id, addressOf("127.0.0.1", 1))).toList();
        return new CountingTopology(selfInfo, peers, true, Option.empty());
    }

    /// Stub topology that counts `get(NodeId)` invocations per peer. Used as the proxy for
    /// "reconciler dispatched a dial" — `considerPeerForReconcile` calls `topologyManager.get`
    /// only after the backoff gate allows the attempt.
    private static final class CountingTopology implements TopologyObserver {
        private final NodeInfo selfInfo;
        private final List<NodeInfo> peers;
        private final boolean returnEmptyOnGet;
        private final Option<Set<NodeId>> coreNodesOverride;
        private final Map<NodeId, Long> lookups = new ConcurrentHashMap<>();

        CountingTopology(NodeInfo selfInfo, List<NodeInfo> peers, boolean returnEmptyOnGet, Option<Set<NodeId>> coreNodesOverride) {
            this.selfInfo = selfInfo;
            this.peers = peers;
            this.returnEmptyOnGet = returnEmptyOnGet;
            this.coreNodesOverride = coreNodesOverride;
        }

        @Override public NodeInfo self() {return selfInfo;}
        @Override public Option<NodeInfo> get(NodeId id) {
            if (!id.equals(selfInfo.id())) {
                lookups.merge(id, 1L, Long::sum);
            }
            if (id.equals(selfInfo.id())) {return Option.some(selfInfo);}
            if (returnEmptyOnGet) {return Option.empty();}
            return peers.stream().filter(p -> p.id().equals(id)).findFirst().map(Option::some).orElse(Option.empty());
        }
        @Override public int clusterSize() {return Math.max(peers.size() + 1, 1);}
        @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {return Option.empty();}
        @Override public Promise<Unit> start() {return Promise.unitPromise();}
        @Override public Promise<Unit> stop() {return Promise.unitPromise();}
        @Override public TimeSpan pingInterval() {return PING_INTERVAL;}
        @Override public TimeSpan helloTimeout() {return HELLO_TIMEOUT;}
        @Override public Option<TlsConfig> tls() {return Option.empty();}
        @Override public Option<NodeState> getState(NodeId id) {return Option.empty();}
        @Override public List<NodeId> topology() {
            var result = new ArrayList<NodeId>();
            result.add(selfInfo.id());
            peers.forEach(p -> result.add(p.id()));
            return result;
        }
        @Override public Set<NodeId> coreNodes() {
            return coreNodesOverride.or(() -> Set.copyOf(topology()));
        }
        @Override public void reconcile(NetworkServiceMessage.ConnectedNodesList connectedNodesList) {}
        @Override public void handleDiscoverNodes(NetworkMessage.DiscoverNodes discoverNodes) {}
        @Override public void handleDiscoveredNodes(NetworkMessage.DiscoveredNodes discoveredNodes) {}
        @Override public void handleSetClusterSize(TopologyManagementMessage.SetClusterSize message) {}
    }

    private static List<SliceCodec.TypeCodec<?>> combinedCodecs() {
        var all = new ArrayList<SliceCodec.TypeCodec<?>>();
        all.addAll(ConsensusCodecs.CODECS);
        all.addAll(NetCodecs.CODECS);
        return all;
    }
}
