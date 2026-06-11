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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.Future;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterFormationConfig;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.ConnectionError;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.net.WriteOutcome;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.consensus.topology.TransportObservation;
import org.pragmatica.consensus.topology.TransportObservation.ObservationSource;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.concurrent.CancellableTask;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.JitterUtil;
import org.pragmatica.lang.utils.Retry;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.Message;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import static org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import static org.pragmatica.consensus.net.quic.QuicClusterNetwork.ViewChangeOperation.*;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// Manages network connections between nodes using QUIC transport.
///
/// Replaces TCP-based [org.pragmatica.consensus.net.netty.NettyClusterNetwork] with QUIC,
/// providing stream multiplexing, built-in TLS 1.3, and independent flow control per
/// message type. Connection direction is deterministic: the lower NodeId always initiates.
///
/// ## Per-peer state
///
/// Peer lifecycle is encapsulated in [PeerState] (one instance per NodeId in [peers]).
/// Phases: `INIT → CONNECTING → CONNECTED ⇄ EVICTED → REMOVED`. Transitions are driven by:
///
///   - `connect(ConnectNode)` / `connectPeer(NodeInfo)` → `beginConnecting`
///   - `onPeerConnected(...)` → `attach`, and drains the peer's offline buffer
///   - `sendToConnection(...)` discovers a dead channel → `evict`
///   - `disconnect(DisconnectNode)` → `authoritativeRemove`
///   - `stop()` / `closePeerConnections()` → `authoritativeRemove` on all
///
/// The [PeerState] offline buffer is peer-level and survives transient evictions so
/// consensus broadcasts delivered during a reconnect storm are not lost.
public class QuicClusterNetwork implements ClusterNetwork {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterNetwork.class);

    private final NodeInfo self;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final TopologyObserver topologyManager;
    private final MessageRouter router;
    private volatile QuicSslContext serverSslContext;
    private volatile QuicSslContext clientSslContext;

    private final Map<NodeId, PeerState> peers = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final QuicTransportMetrics quicMetrics = QuicTransportMetrics.quicTransportMetrics();

    /// Transport-ready callbacks (registered via [#whenReady(Runnable)]) and the latch that
    /// gates them. `transportReady` is distinct from `isRunning`: `isRunning` flips true at the
    /// TOP of `startOnPort` before the server binds, whereas `transportReady` flips true only
    /// after `server.start(port)` resolves — i.e. the node can actually dial and accept.
    private final List<Runnable> readyHooks = new CopyOnWriteArrayList<>();
    private final AtomicBoolean transportReady = new AtomicBoolean(false);

    /// Test-only fault injection: when set, this node silently drops ALL application traffic
    /// (outbound send/broadcast become no-ops; inbound frames are dropped before routing) while
    /// keeping every QUIC channel open and `connectedPeers()` unchanged. This reproduces a hard
    /// `docker kill` with QUIC MAX_IDLE_TIMEOUT disabled: peers still believe the link is
    /// CONNECTED yet the node never acks SWIM probes, ClusterSync pongs, or consensus. Distinct
    /// from `stop()`, which closes channels and triggers an immediate transport-disconnect on
    /// peers. Used by the in-process Ember chaos substrate to reproduce — on the fast loop — the
    /// silent-death failure-detection bug otherwise only observable on Docker.
    private volatile boolean blackholed = false;

    /// Per-peer eviction-driven reconnect backoff. Without this, an eviction storm at the
    /// 1Hz consensus heartbeat tempo (writeToStream → evictStaleConnection → connectPeer →
    /// next heartbeat sees same dead channel) reproducibly leaks one ephemeral UDP socket
    /// per cycle. Initial 100ms, capped at 5s, jittered by 0.5..1.5 to prevent thundering
    /// herd when N peers all evict in lock-step.
    private static final long BACKOFF_INITIAL_MS = 100L;
    private static final long BACKOFF_CAP_MS = 5_000L;
    private static final double BACKOFF_JITTER_MIN = 0.5;
    private static final double BACKOFF_JITTER_MAX = 1.5;
    private final Map<NodeId, ReconnectBackoff> reconnectBackoff = new ConcurrentHashMap<>();
    /// Tracks the wall-clock millis at which each peer was first observed missing from
    /// `connectedPeers()` despite being in topology. Cleared when the peer reconnects or
    /// is authoritatively removed. Used to gate the higher-id side's reconciler attempts:
    /// `ConnectionDirection.shouldInitiate` normally restricts dialing to the lower-id
    /// side (preventing dual-Hello collapse at cold start). If the peer has been observed
    /// unreachable for longer than `RECONCILE_BACKOFF_CAP_MS`, the lower-id side has
    /// already saturated its backoff curve without success — the higher-id side may then
    /// also attempt, since one side is provably stuck and the cold-boot dual-Hello race
    /// window has long since passed.
    private final Map<NodeId, Long> firstObservedMissingMs = new ConcurrentHashMap<>();
    private volatile LongSupplier wallClockMs = System::currentTimeMillis;

    /// Periodic missing-peer reconciler tunables. The reconciler runs every
    /// `RECONCILE_TICK` and, for any configured peer whose `PeerState.phase()` is
    /// not CONNECTED, dispatches a `connectPeer(...)` call gated by a per-peer
    /// jittered exponential backoff (initial 5s, cap 60s — matches Theme I patterns).
    /// Closes the asymmetric-recreation gap: when a node container is recreated
    /// mid-cluster it may handshake with most peers but permanently miss one, leaving
    /// no traffic to clear the sticky SUSPECTED snapshot state.
    private static final TimeSpan RECONCILE_TICK = TimeSpan.timeSpan(5L).seconds();
    private static final long RECONCILE_BACKOFF_INITIAL_MS = 5_000L;
    private static final long RECONCILE_BACKOFF_CAP_MS = 60_000L;
    /// Multiple of the QUIC hello/handshake timeout after which a peer pinned in CONNECTING is
    /// treated as a hung dial and force-evicted by the reconciler so a fresh re-dial can proceed.
    /// A dial that neither completes (`onPeerConnected`) nor fails (`onConnectFailed`) — e.g. a
    /// `client.connect(...)` that hangs with no resolution — otherwise wedges the peer in
    /// CONNECTING forever, and the in-flight dedup guard in `considerPeerForReconcile` /
    /// `considerDesiredPeerForReconcile` silently skips it on every tick. The `* 3` factor matches
    /// the protection-window precedent in `disconnect` (`helloTimeout().nanos() * 3`): wide enough
    /// that a legitimately-slow handshake under load is NOT aborted, tight enough that a hung dial
    /// clears within a few reconciler ticks.
    private static final long CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR = 3L;
    /// Multiple of the SWIM ping interval after which a CONNECTED peer that has received NO inbound
    /// frame on ANY lane is treated as a silent zombie and evicted for re-dial. Sized generously
    /// (`pingInterval * 8`): SWIM guarantees cross-link probe traffic at `pingInterval`, so a
    /// healthy-but-quiet link always refreshes the liveness clock well within the window and is never
    /// falsely evicted; a genuine silent-death zombie (the `isActive()`-lies-true / blackholed
    /// substrate) clears within a couple of sweep ticks. This sweep's correctness DEPENDS on the SWIM
    /// probe cadence: if SWIM probing were disabled, an idle follower<->follower link could exceed the
    /// TTL without being dead.
    private static final long LIVENESS_TTL_PING_INTERVAL_FACTOR = 8L;
    private final CancellableTask reconcilerTask = CancellableTask.cancellableTask();

    private volatile QuicDisconnectListener disconnectListener;

    /// QUIC consensus-stream resilience tunables (retry attempts/backoff + CONSENSUS stream
    /// write-buffer watermarks). Defaults cover the observed deploy-burst backpressure window
    /// while staying well under the 30s `cluster.apply` deadline. See [QuicTransportTuning].
    private final QuicTransportTuning tuning;

    /// Async, non-blocking retry for CONSENSUS sends. On backpressure (`!isWritable()`) the
    /// send is handed to this retry instead of being silently dropped. Retry delays are
    /// scheduled via `SharedScheduler` (NOT blocking the Netty event loop or submit thread).
    /// Stateless and thread-safe — a single instance serves all peers concurrently.
    private final Retry consensusRetry;

    /// Late-bound listener for QUIC peer-state changes (join / reconnect / leave).
    /// Higher layers (e.g. `ClusterTopologyManager`) consume these to keep stability
    /// bookkeeping aligned with the transport's view. Defaults to no-op so consensus-
    /// only fixtures and unit tests do not need to wire this.
    private volatile QuicPeerStateListener peerStateListener = QuicPeerStateListener.noop();
    /// Wave-1 transition journal feed (Enrichment A). Forwarded into every [PeerState] this
    /// network creates (via a volatile-reading indirection, so late wiring reaches peers created
    /// earlier) and fed directly with the dialer expected-vs-actual Hello diagnostic
    /// ([#journalDialerHello]). Diagnostic-only; defaults to a no-op.
    private volatile Consumer<PeerTransitionRecord> peerTransitionListener = ignored -> {};

    /// Leader-gate supplier. When `false`, REMOVE view-changes report a
    /// connectivity observation upstream via `PeerConnectivityReporter`
    /// instead of invoking the local disconnect listener (which feeds the
    /// local `HealthReconciler`).
    /// See `aether/docs/specs/clustersync-refactor-spec.md` commit 2.
    private volatile BooleanSupplier isLeaderSupplier;
    private volatile PeerConnectivityReporter connectivityReporter;
    private volatile ObservedEpochSupplier observedEpochSupplier;

    /// SWIM health gate for the missing-peer reconciler. When present, the reconciler
    /// calls this predicate before dialling an EVICTED peer: `true` means SWIM considers
    /// the peer healthy enough to attempt reconnection (HEALTHY or SUSPECTED); `false`
    /// means FAULTY or UNKNOWN — skip until SWIM signals recovery.
    ///
    /// Defaults to empty (absent) → reconciler allows all reconnects, preserving existing
    /// behaviour until AetherNode wires the gate post-construction.
    private volatile Option<Function<NodeId, Boolean>> swimHealthGate = Option.empty();

    /// Membership-view supplier gating consensus broadcast targets. The transport `peers`
    /// table is a connection CACHE, not the membership AUTHORITY: a peer absent from the
    /// membership view (evicted / dead per the FSM) must never be a broadcast target, even
    /// if a stale CONNECTED QUIC link to it lingers. Filtering `broadcastPayload` by this
    /// view dissolves the dead-ULID CONSENSUS retry-storm (#68) at the source — without it,
    /// consensus keeps broadcasting to an evicted node forever (`Retry N/200 @25ms`).
    ///
    /// Back-compat default: the default supplier yields `Option.none()`, meaning "no filter / all
    /// peers" — existing consensus-only fixtures and unit tests broadcast to every connected
    /// peer exactly as before until AetherNode wires the FSM-backed view post-construction.
    private volatile Supplier<Option<Set<NodeId>>> broadcastMembership = Option::none;

    /// FSM-published desired-connection set for the missing-peer reconciler. When present, the
    /// reconciler reconciles ACTUAL connections against THIS FSM core-member set instead of the
    /// static `topologyManager.topology()`, and SKIPS the legacy SWIM membership/health gates
    /// (`swimMembershipAllows` / `swimHealthAllows`): the FSM projection already encodes
    /// membership AND health — it contains MEMBER+SUSPECT core members and excludes DEAD/worker
    /// peers, so a peer present in this set is by definition both a member and reconnect-eligible.
    ///
    /// `NodeInfo` is the carried type (a consensus type) so the adapter in `AetherNode` maps the
    /// FSM `desiredConnections` + per-member descriptors → `NodeInfo`. Single-dialer ordering,
    /// the higher-id grace window, and per-peer reconcile backoff stay MECHANICAL and are applied
    /// to this path unchanged.
    ///
    /// Back-compat default: the default supplier yields `Option.none()`, i.e. UNWIRED — the
    /// reconciler runs the legacy topology-driven path with both SWIM gates exactly as before,
    /// until `AetherNode` wires the FSM-backed supplier post-construction.
    private volatile Supplier<Option<Collection<NodeInfo>>> desiredConnections = Option::none;

    /// Minimal cross-module shape for the follower's observed epoch — keeps the
    /// consensus module free of `aether/slice` types. Upper layers translate.
    public interface ObservedEpochSupplier {
        /// Rabia term currently observed by this node.
        long term();
        /// Local epoch counter currently observed by this node.
        long counter();

        static ObservedEpochSupplier zero() {
            return new ObservedEpochSupplier() {
                @Override public long term() {return 0L;}
                @Override public long counter() {return 0L;}
            };
        }
    }

    private volatile QuicClusterServer server;
    private volatile QuicClusterClient client;

    enum ViewChangeOperation {
        ADD,
        REMOVE,
        SHUTDOWN,
        /// Reconnect after a transient eviction. The peer is already known to upstream
        /// consumers (TopologyObserver, CTM, Rabia) via a prior `ADD`, so we MUST NOT emit
        /// a duplicate `nodeAdded` notification. Used to refresh QUIC peer-state without
        /// re-triggering downstream membership recomputation.
        RECONNECT
    }

    public QuicClusterNetwork(TopologyObserver topologyManager,
                              Serializer serializer,
                              Deserializer deserializer,
                              MessageRouter router,
                              QuicSslContext serverSslContext,
                              QuicSslContext clientSslContext) {
        this(topologyManager, serializer, deserializer, router, serverSslContext, clientSslContext,
             ClusterFormationConfig.defaults(), QuicDisconnectListener.noop());
    }

    public QuicClusterNetwork(TopologyObserver topologyManager,
                              Serializer serializer,
                              Deserializer deserializer,
                              MessageRouter router,
                              QuicSslContext serverSslContext,
                              QuicSslContext clientSslContext,
                              ClusterFormationConfig formationConfig) {
        this(topologyManager, serializer, deserializer, router, serverSslContext, clientSslContext,
             formationConfig, QuicDisconnectListener.noop());
    }

    public QuicClusterNetwork(TopologyObserver topologyManager,
                              Serializer serializer,
                              Deserializer deserializer,
                              MessageRouter router,
                              QuicSslContext serverSslContext,
                              QuicSslContext clientSslContext,
                              ClusterFormationConfig formationConfig,
                              QuicDisconnectListener disconnectListener) {
        this(topologyManager, serializer, deserializer, router, serverSslContext, clientSslContext,
             formationConfig, disconnectListener, () -> true, PeerConnectivityReporter.noop(), ObservedEpochSupplier.zero());
    }

    public QuicClusterNetwork(TopologyObserver topologyManager,
                              Serializer serializer,
                              Deserializer deserializer,
                              MessageRouter router,
                              QuicSslContext serverSslContext,
                              QuicSslContext clientSslContext,
                              ClusterFormationConfig formationConfig,
                              QuicDisconnectListener disconnectListener,
                              BooleanSupplier isLeaderSupplier,
                              PeerConnectivityReporter connectivityReporter,
                              ObservedEpochSupplier observedEpochSupplier) {
        this(topologyManager, serializer, deserializer, router, serverSslContext, clientSslContext,
             formationConfig, disconnectListener, isLeaderSupplier, connectivityReporter, observedEpochSupplier,
             QuicTransportTuning.defaults());
    }

    public QuicClusterNetwork(TopologyObserver topologyManager,
                              Serializer serializer,
                              Deserializer deserializer,
                              MessageRouter router,
                              QuicSslContext serverSslContext,
                              QuicSslContext clientSslContext,
                              ClusterFormationConfig formationConfig,
                              QuicDisconnectListener disconnectListener,
                              BooleanSupplier isLeaderSupplier,
                              PeerConnectivityReporter connectivityReporter,
                              ObservedEpochSupplier observedEpochSupplier,
                              QuicTransportTuning tuning) {
        this.self = topologyManager.self();
        this.topologyManager = topologyManager;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.router = router;
        this.serverSslContext = serverSslContext;
        this.clientSslContext = clientSslContext;
        this.disconnectListener = disconnectListener;
        this.isLeaderSupplier = isLeaderSupplier == null ? () -> true : isLeaderSupplier;
        this.connectivityReporter = connectivityReporter == null ? PeerConnectivityReporter.noop() : connectivityReporter;
        this.observedEpochSupplier = observedEpochSupplier == null ? ObservedEpochSupplier.zero() : observedEpochSupplier;
        this.tuning = tuning == null ? QuicTransportTuning.defaults() : tuning;
        this.consensusRetry = this.tuning.consensusRetry();
    }

    /// Late-bound leader gate + connectivity reporter. Follower REMOVE view-changes
    /// report connectivity observations via the reporter instead of invoking the
    /// local disconnect listener.
    public void setFollowerObservationWiring(BooleanSupplier isLeaderSupplier,
                                             PeerConnectivityReporter connectivityReporter,
                                             ObservedEpochSupplier observedEpochSupplier) {
        this.isLeaderSupplier = isLeaderSupplier == null ? () -> true : isLeaderSupplier;
        this.connectivityReporter = connectivityReporter == null ? PeerConnectivityReporter.noop() : connectivityReporter;
        this.observedEpochSupplier = observedEpochSupplier == null ? ObservedEpochSupplier.zero() : observedEpochSupplier;
    }

    /// Attach a SWIM health gate for the missing-peer reconciler. The predicate receives
    /// a `NodeId` and returns `true` when SWIM considers the peer healthy enough to
    /// reconnect (HEALTHY or SUSPECTED); `false` when FAULTY or UNKNOWN.
    /// A `null` argument removes the gate (all reconnects allowed — default behaviour).
    @Contract public void setSwimHealthGate(Function<NodeId, Boolean> gate) {
        this.swimHealthGate = Option.option(gate);
    }

    /// Attach the FSM-backed membership-view supplier that gates consensus broadcast targets.
    /// The supplier returns the set of NodeIds the membership authority still considers members
    /// (FSM core members: MEMBER + SUSPECT, worker-excluded). `broadcastPayload` skips any
    /// connected peer absent from this set — a stale CONNECTED link to an evicted node is never
    /// a broadcast target, dissolving the #68 CONSENSUS retry-storm at the source.
    /// A `null` argument (or a supplier returning `null`) restores the default "no filter /
    /// all peers" behaviour.
    @Contract public void setBroadcastMembership(Supplier<Set<NodeId>> membershipView) {
        this.broadcastMembership = membershipView == null
                                  ? Option::none
                                  : () -> Option.option(membershipView.get());
    }

    /// Attach the FSM-published desired-connection supplier. When present, the missing-peer
    /// reconciler reconciles actual connections against THIS FSM core-member set instead of the
    /// static topology, and skips the legacy SWIM membership/health gates (the set already
    /// encodes them). When absent (default), the legacy topology-driven path runs unchanged.
    /// A `null` argument (or a supplier returning `null`) restores the default unwired behaviour.
    @Contract public void setDesiredConnections(Supplier<Collection<NodeInfo>> supplier) {
        this.desiredConnections = supplier == null
                                 ? Option::none
                                 : () -> Option.option(supplier.get());
    }

    /// Attach a QUIC-disconnect listener post-construction. Higher layers (e.g.
    /// `AetherNode`) need to wire the listener after the enclosing `RabiaNode`
    /// — which owns this network — has already been built. A `null` argument
    /// resets the listener to the no-op implementation.
    public void setDisconnectListener(QuicDisconnectListener listener) {
        this.disconnectListener = listener == null
                                 ? QuicDisconnectListener.noop()
                                 : listener;
    }

    /// Attach a QUIC peer-state listener post-construction. Fires on join/reconnect/leave
    /// so higher layers (e.g. CTM) can track peer-state churn even when the upstream
    /// `TransportObservation.PeerJoined` is suppressed for transient reconnects.
    /// A `null` argument resets the listener to the no-op implementation.
    @Contract public void setPeerStateListener(QuicPeerStateListener listener) {
        this.peerStateListener = listener == null
                                ? QuicPeerStateListener.noop()
                                : listener;
    }

    /// Wave-1 transition journal feed (Enrichment A): attach the per-peer transition listener
    /// post-construction (same late-wiring shape as [#setPeerStateListener]). Receives one
    /// [PeerTransitionRecord] per [PeerState] phase mutation and per dialer expected-vs-actual
    /// Hello completion. Diagnostic-only — installing or invoking it changes no control flow.
    /// A `null` argument resets the listener to the no-op.
    @Override
    @Contract public void setPeerTransitionListener(Consumer<PeerTransitionRecord> listener) {
        this.peerTransitionListener = listener == null
                                     ? ignored -> {}
                                     : listener;
    }

    @Override
    public void listNodes(ListConnectedNodes listConnectedNodes) {
        router.route(new NetworkServiceMessage.ConnectedNodesList(connectedPeers().stream().toList()));
    }

    @Override
    public void handleSend(NetworkServiceMessage.Send send) {
        dispatchPayload(send.target(), send.payload());
    }

    @Override
    public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {
        broadcastPayload(broadcast.payload(), false);
    }

    @Override
    public Promise<Unit> start() {
        return startOnPort(self.address().port());
    }

    /// Start the network on a specific UDP port.
    /// Package-private to allow tests to bind to port 0 (OS-assigned).
    @SuppressWarnings("JBCT-PAT-01") // Lifecycle: server start then client creation
    Promise<Unit> startOnPort(int port) {
        if (!isRunning.compareAndSet(false, true)) {
            return Promise.unitPromise();
        }
        server = QuicClusterServer.quicClusterServer(
            self.id(), self.role(), self.address(), self.labels(), serializer, deserializer,
            serverSslContext, Option.empty(), this::onPeerConnected, this::onMessageReceived
        );
        client = QuicClusterClient.quicClusterClient(
            self.id(), self.role(), self.address(), self.labels(), serializer, deserializer,
            clientSslContext, Option.empty(), this::onMessageReceived
        );
        return server.start(port)
                     .onSuccess(_ -> startMissingPeerReconciler())
                     .onSuccess(_ -> fireReadyHooks())
                     .onFailure(this::onStartFailed)
                     .mapToUnit();
    }

    @Override
    public void whenReady(Runnable hook) {
        if (transportReady.get()) {
            hook.run();
        } else {
            readyHooks.add(hook);
        }
    }

    @Contract private void fireReadyHooks() {
        transportReady.set(true);
        readyHooks.forEach(Runnable::run);
        readyHooks.clear();
    }

    @Override
    public Promise<Unit> stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return Promise.unitPromise();
        }
        log.debug("Stopping QuicClusterNetwork: notifying view change");
        reconcilerTask.cancel();
        processViewChange(SHUTDOWN, self.id());
        return closePeerConnections()
            .flatMap(this::stopServerAndClient);
    }

    /// Schedule the periodic missing-peer reconciler. Idempotent — second invocation
    /// (rare; `startOnPort` is gated by `isRunning.compareAndSet`) replaces the prior
    /// task via `CancellableTask.set` which cancels the old future first.
    @Contract private void startMissingPeerReconciler() {
        var future = SharedScheduler.scheduleAtFixedRate(this::reconcileMissingPeersTick, RECONCILE_TICK, RECONCILE_TICK);
        reconcilerTask.set(future);
        log.debug("Missing-peer reconciler scheduled (tick={}ms, backoff initial={}ms, cap={}ms)",
                  RECONCILE_TICK.millis(), RECONCILE_BACKOFF_INITIAL_MS, RECONCILE_BACKOFF_CAP_MS);
    }

    @Override
    public void connect(ConnectNode connectNode) {
        if (!isRunning.get()) {
            log.error("Attempt to connect {} while node is not running", connectNode.node());
            return;
        }
        if (connectNode.node().equals(self.id())) {
            return;
        }
        topologyManager.get(connectNode.node())
                       .onPresent(this::connectPeer)
                       .onEmpty(() -> log.error("Unknown {}", connectNode.node()));
    }

    @Override
    public void connect(NodeInfo nodeInfo) {
        if (!isRunning.get() || nodeInfo.id().equals(self.id())) {
            return;
        }
        connectPeer(nodeInfo);
    }

    @Override
    @SuppressWarnings("JBCT-PAT-01") // Channel protection window check + soft-evict + view change
    public void disconnect(DisconnectNode disconnectNode) {
        var nodeId = disconnectNode.nodeId();
        var peer = peers.get(nodeId);
        // SWIM-driven DisconnectNode is the authoritative "this peer is gone right now"
        // signal. Topology projection is updated regardless of whether the local QUIC
        // link is still up — otherwise a peer pruned by SWIM but with a still-alive
        // (zombie) QUIC connection would stay in coreNodes forever.
        if (peer != null && peer.phase() == PeerState.Phase.CONNECTED) {
            var protectionNanos = topologyManager.helloTimeout().nanos() * CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR;
            if (peer.phaseAgeNanos(System.nanoTime()) < protectionNanos) {
                log.debug("DisconnectNode for {} ignored: connection is fresh (protection window)", nodeId);
                return;
            }
        }
        // Soft-evict (CONNECTED → EVICTED), NOT authoritativeRemove (any → REMOVED).
        // Reasoning: EVICTED is the transient live-peer flap state. A peer whose QUIC link
        // momentarily drops (heartbeat-loop reconnect, brief partition) re-attaches via
        // `attach`, which returns RECONNECTED (EVICTED → CONNECTED), preserves the offline
        // buffer, and routes ConnectionEstablished upstream → SWIM markAlive — without firing
        // a spurious duplicate ADD. Terminal removal of a *confirmed-dead* peer is a separate
        // concern handled by `departurePermanent` (the leader's co-confirmed-death verdict /
        // DECOMMISSIONED / SWIM DepartedObserved), which drives the peer to REMOVED. Because
        // restart is disabled cluster-wide, a same-NodeId never returns; the only paths from
        // EVICTED to REMOVED are `departurePermanent` and shutdown — `disconnect` itself never
        // makes the transition terminal.
        //
        // REMOVE-emission idempotency: five independent producers (SWIM departure, health
        // reconciler, topology pruning, etc.) can call `disconnect` for the same peer in a
        // tight window. We must emit `processViewChange(REMOVE)` only when THIS call actually
        // changed state, otherwise a single departure floods ReachabilityAggregator/SWIM with
        // dozens of spurious PeerDisconnected observations. `evict()` returns Some only on a
        // real CONNECTED → EVICTED transition (Empty = already EVICTED/REMOVED no-op). A null
        // peer is treated as a first-time authoritative departure and still emits — SWIM may
        // confirm a peer gone before we ever held a live QUIC link, and topology projection
        // must still prune it (see `disconnect_unknownPeer_propagatesListenerForTopologyRemoval`).
        var emitRemove = peer == null;
        if (peer != null) {
            var evicted = peer.evict(System.nanoTime());
            evicted.onPresent(this::closeDroppedConnection);
            // Only a real transition fires the connection-closed metric.
            evicted.onPresent(_ -> quicMetrics.onConnectionClosed());
            emitRemove = evicted.isPresent();
        }
        if (!emitRemove) {
            log.debug("DisconnectNode for {} suppressed: peer already EVICTED/REMOVED (no transition)", nodeId);
            return;
        }
        // Drop the per-peer ephemeral UDP socket; client opens a fresh one on reconnect.
        var clientRef = client;
        if (clientRef != null) {
            clientRef.closeDatagramChannel(nodeId);
        }
        resetReconnectBackoff(nodeId);
        processViewChange(REMOVE, nodeId);
    }

    @Override
    @SuppressWarnings("JBCT-PAT-01") // Peer removal + channel close + view change
    public void departurePermanent(NodeId nodeId) {
        var peer = peers.get(nodeId);
        firstObservedMissingMs.remove(nodeId);
        // `authoritativeRemove()` always transitions to REMOVED, so it cannot self-report
        // whether the peer was already terminal. Read the phase before the call: emit REMOVE
        // only when the peer existed AND was not already REMOVED. This makes repeated
        // departurePermanent() calls for the same peer idempotent at the emission source,
        // preventing the duplicate PeerDisconnected flood into ReachabilityAggregator/SWIM.
        var emitRemove = peer != null && peer.phase() != PeerState.Phase.REMOVED;
        if (peer != null) {
            // Keep the REMOVED-phase PeerState RESIDENT in `peers` (no `peers.remove`): a
            // reconciler tick with this id still present in `topologyManager.topology()` would
            // otherwise `getOrCreatePeer` a fresh INIT state and re-dial the corpse. Resident
            // REMOVED makes `considerPeerForReconcile`'s REMOVED-skip and `beginConnecting`→false
            // fire, so no re-dial path can revive a confirmed-dead NodeId (restart disabled).
            peer.authoritativeRemove(System.nanoTime())
                .onPresent(this::closeDroppedConnection);
        }
        if (!emitRemove) {
            log.debug("departurePermanent for {} suppressed: peer absent or already REMOVED (no transition)", nodeId);
            return;
        }
        var clientRef = client;
        if (clientRef != null) {
            clientRef.closeDatagramChannel(nodeId);
        }
        processViewChange(REMOVE, nodeId);
    }

    private void closeDroppedConnection(QuicPeerConnection connection) {
        connection.close()
                  .onFailure(cause -> log.warn("Failed to close dropped connection for peer {}: {}",
                                               connection.peerId(), cause.message()));
    }

    /// Close an incumbent connection superseded by a fresh adopt-newer handshake. Routes through
    /// the same close path evictStaleConnection uses: counts the close in metrics, then closes.
    private void closeSupersededConnection(QuicPeerConnection superseded) {
        quicMetrics.onConnectionClosed();
        closeDroppedConnection(superseded);
    }

    @Override
    public <M extends ProtocolMessage> Unit send(NodeId peerId, M message) {
        if (blackholed) {
            return unit();
        }
        dispatchPayload(peerId, message);
        return unit();
    }

    @Override
    public <M extends ProtocolMessage> Promise<WriteOutcome> sendOutcome(NodeId peerId, M message) {
        if (blackholed) {
            return Promise.success(new WriteOutcome.Sent(peerId));
        }
        return Promise.success(dispatchPayloadWithOutcome(peerId, message));
    }

    @Override
    public <M extends ProtocolMessage> Unit broadcast(M message) {
        if (blackholed) {
            return unit();
        }
        broadcastPayload(message, !message.deliverToPassive());
        return unit();
    }

    /// Test-only: toggle silent-death fault injection. See the `blackholed` field doc.
    @Override
    @Contract
    public void blackhole(boolean enabled) {
        blackholed = enabled;
        log.warn("QUIC transport for {} blackhole={} (channels stay open; app traffic {})",
                 self.id(), enabled, enabled ? "DROPPED" : "FLOWING");
    }

    /// Test-only: whether silent-death fault injection is currently active.
    public boolean isBlackholed() {
        return blackholed;
    }

    @Override
    public int connectedNodeCount() {
        return (int) peers.values()
                          .stream()
                          .filter(p -> p.phase() == PeerState.Phase.CONNECTED)
                          .count();
    }

    @Override
    public Set<NodeId> connectedPeers() {
        return peers.values()
                    .stream()
                    .filter(p -> p.phase() == PeerState.Phase.CONNECTED)
                    .map(PeerState::peerId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Set<NodeId> activePeers() {
        // Mirror `activeConnectedCount` semantics: include EVICTED (transient stale-link
        // awaiting reconcile) so external counts do not flicker on momentary evictions.
        return peers.values()
                    .stream()
                    .filter(p -> !p.isPassive())
                    .filter(p -> p.phase() == PeerState.Phase.CONNECTED
                                 || p.phase() == PeerState.Phase.EVICTED)
                    .map(PeerState::peerId)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    @Override
    public Option<Server> server() {
        // QUIC transport does not use a TCP Server instance.
        return Option.empty();
    }

    @Override
    public Map<String, Number> transportMetrics() {
        return quicMetrics.snapshot();
    }

    /// Get the typed QUIC transport metrics collector.
    public QuicTransportMetrics quicMetrics() {
        return quicMetrics;
    }

    /// Get the actual UDP port the QUIC server is bound to.
    /// Useful when started on port 0 (OS-assigned).
    Option<Integer> boundPort() {
        var srv = server;
        return srv != null ? srv.boundPort() : Option.empty();
    }

    /// Rotate TLS certificates by restarting the QUIC server with new SSL contexts.
    /// Existing connections drain naturally; peers reconnect automatically.
    @SuppressWarnings("JBCT-PAT-01") // Lifecycle: stop old server, update contexts, start new
    public Promise<Unit> rotateCertificate(QuicSslContext newServerSsl, QuicSslContext newClientSsl) {
        return boundPort()
            .async(new QuicTransportError.CertificateRotationFailed("Server not running"))
            .flatMap(port -> stopAndRestartServer(port, newServerSsl, newClientSsl));
    }

    @SuppressWarnings("JBCT-PAT-01") // Lifecycle: update contexts, stop old, start new
    private Promise<Unit> stopAndRestartServer(int port, QuicSslContext newServerSsl, QuicSslContext newClientSsl) {
        // Update client context immediately — new outbound connections use the new cert
        clientSslContext = newClientSsl;
        serverSslContext = newServerSsl;
        // Stop old server, then immediately create and start new one
        var oldServer = server;
        var stopPromise = oldServer != null ? oldServer.stop() : Promise.unitPromise();
        return stopPromise.flatMap(_ -> rebuildAndStart(port, newServerSsl, newClientSsl));
    }

    private Promise<Unit> rebuildAndStart(int port, QuicSslContext newServerSsl, QuicSslContext newClientSsl) {
        server = QuicClusterServer.quicClusterServer(
            self.id(), self.role(), self.address(), self.labels(), serializer, deserializer,
            newServerSsl, Option.empty(), this::onPeerConnected, this::onMessageReceived
        );
        client = QuicClusterClient.quicClusterClient(
            self.id(), self.role(), self.address(), self.labels(), serializer, deserializer,
            newClientSsl, Option.empty(), this::onMessageReceived
        );
        return server.start(port)
                     .onSuccess(_ -> log.info("QUIC server restarted on port {} with renewed certificate", port))
                     .onFailure(cause -> log.error("Failed to restart QUIC server after certificate rotation: {}", cause.message()))
                     .mapToUnit();
    }

    private void onStartFailed(Cause cause) {
        log.error("Failed to start QUIC server: {}", cause.message());
        isRunning.set(false);
    }

    /// Routes incoming messages received from peers after Hello handshake.
    /// Protocol messages (consensus, KV) go through the message router.
    /// Network messages (discovery) are handled as service messages.
    @SuppressWarnings("JBCT-PAT-01") // Message routing dispatch
    private void onMessageReceived(NodeId sender, Object message) {
        if (blackholed) {
            // Silent death: receive the frame off the wire (keeps the QUIC channel alive and
            // CONNECTED on the peer) but never route it, so this node neither acks SWIM probes
            // nor replies to ClusterSync pings nor participates in consensus.
            return;
        }
        // Liveness clock refresh: this is the SINGLE inbound funnel for ALL lanes (SWIM probes,
        // ClusterSync pongs, consensus). Refreshing here (AFTER the blackhole early-return so a
        // silently-dropping link is never refreshed) feeds the CONNECTED-zombie TTL sweep without
        // adding a new stream/lane.
        var peer = peers.get(sender);
        if (peer != null) {
            peer.markInbound(System.nanoTime());
        }
        quicMetrics.onMessageReceived();
        if (message instanceof Message.Wired wired) {
            router.route(wired);
        } else {
            log.trace("Non-routable message from {}: {}", sender, option(message).map(Object::getClass).map(Class::getSimpleName));
        }
    }

    // --- Internal: peer state lookup ---

    private PeerState getOrCreatePeer(NodeId peerId) {
        // The transition listener is read through the volatile field at EACH invocation, so
        // wiring installed after a PeerState was created still observes its transitions.
        return peers.computeIfAbsent(peerId, id -> PeerState.peerState(id,
                                                                       System.nanoTime(),
                                                                       record -> peerTransitionListener.accept(record)));
    }

    // --- Internal: peer connection lifecycle ---

    /// Dial a peer. DNS resolution is deferred to dial time and performed **non-blocking** on the
    /// client's Netty event loop (NOT an inline blocking lookup on the reconciler thread): a stale
    /// hostname that yields an *unresolved* `InetSocketAddress` would otherwise be rejected by
    /// `QuicClusterClient.initiateConnection` on every reconciler tick, wedging the peer in a
    /// never-reconnect loop. `beginConnecting` is deferred into the resolution-SUCCESS continuation
    /// so the CONNECTING phase always means "a real dial is in flight" — a resolution stall is bounded
    /// only by the existing per-peer reconcile backoff (the next tick re-attempts) and never pins the
    /// peer in CONNECTING.
    ///
    /// Gate note: re-resolution applies only to peers already approved by
    /// `considerPeerForReconcile` / `considerDesiredPeerForReconcile` (or the explicit `connect`
    /// paths). A DEAD peer is excluded from the desired set upstream (FSM / SWIM membership) and never
    /// reaches `connectPeer`, so deferred resolution cannot revive a confirmed-dead NodeId.
    @SuppressWarnings("JBCT-PAT-01") // Netty future callback chain
    private void connectPeer(NodeInfo peer) {
        var peerId = peer.id();
        // Strict ConnectionDirection: only the lower NodeId initiates. The higher NodeId
        // accepts the inbound connection. Bypassing this caused both sides to dial
        // concurrently at cold start — both Hellos completed, the second arrival closed
        // its own QuicChannel as duplicate, and that close cascaded a CONNECTION_CLOSE
        // to the OTHER side's peer link, silently killing reachability for cluster pairs.
        if (!ConnectionDirection.shouldInitiate(self.id(), peerId)) {
            log.debug("Skipping connection to {}: higher NodeId does not initiate (waits for inbound)", peerId);
            return;
        }
        // Dial the resolved (SWIM-observed IP : advertised QUIC port) address when available;
        // it defaults to peer.address() for non-SWIM-discovered peers, so behavior is unchanged
        // there. peer.address() remains the identity/reporting address everywhere below.
        var dialAddress = peer.resolvedAddress();
        // Resolve the hostname FRESH at dial time, non-blocking, on the Netty resolver. On success
        // we begin CONNECTING and dial the resolved InetAddress (the (InetAddress, port) constructor
        // never re-resolves). On failure we leave the peer untouched (no phase change) — the next
        // reconciler tick re-attempts under the existing backoff.
        client.resolve(dialAddress.host())
              .onSuccess(inetAddress -> dialResolved(peer, inetAddress, dialAddress.port()))
              .onFailure(cause -> log.debug("Skipping dial to {}: address {} did not resolve: {}",
                                            peerId, dialAddress.asString(), cause.message()));
    }

    /// Resolution-success continuation: now that a real IP is in hand, begin CONNECTING (so the
    /// phase reflects an in-flight dial) and dial the resolved address. Construct the dial target
    /// from the resolved `InetAddress` — `new InetSocketAddress(InetAddress, port)` never re-resolves,
    /// unlike the eager two-arg `(String host, port)` constructor.
    @SuppressWarnings("JBCT-PAT-01") // Netty future callback chain
    private void dialResolved(NodeInfo peer, InetAddress inetAddress, int port) {
        var peerId = peer.id();
        var state = getOrCreatePeer(peerId);
        if (!state.beginConnecting(System.nanoTime())) {
            // Already CONNECTING, CONNECTED, or REMOVED — nothing to do.
            return;
        }
        var address = new InetSocketAddress(inetAddress, port);
        // Per-dial timeout escape hatch (root cause): a `client.connect(...)` that hangs with no
        // success/failure callback would otherwise pin the peer in CONNECTING forever. Arm a
        // timeout sized at `helloTimeout * CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR` so a hung
        // dial deterministically fails and routes to `onConnectFailed` (which flips
        // CONNECTING → EVICTED), instead of relying solely on the reconciler staleness sweep.
        client.connect(peerId, address)
              .timeout(connectTimeout())
              .onSuccess(conn -> onDialCompleted(peer, conn))
              .onFailure(cause -> onConnectFailed(peer, cause));
    }

    /// Dial-success continuation. Journals the Wave-1 §6.1 dialer expected-vs-actual identity
    /// observation, then delegates to the unchanged attach path. Wave-3 enforcement lives in
    /// `QuicClusterClient.completePeerConnection`: a mismatched Hello sender is rejected there
    /// (connection closed, dial failed down [#onConnectFailed]), so this continuation only ever
    /// sees connections whose identity was verified against the dialed NodeId.
    private void onDialCompleted(NodeInfo dialed, QuicPeerConnection connection) {
        journalDialerHello(dialed, connection);
        onPeerConnected(connection, dialed.role(), dialed.address(), dialed.labels());
    }

    /// Feed the PEER transition journal with the completed outbound handshake identity
    /// observation (`cause = dialer-hello …`, `from == to` — no phase mutation here).
    /// Wave-3: `expected == actual` always holds here (the client rejects mismatches before
    /// dial success); the WARN below is a regression tripwire, not a live code path.
    @Contract
    private void journalDialerHello(NodeInfo dialed, QuicPeerConnection connection) {
        var actual = connection.peerId();
        if (!dialed.id().equals(actual)) {
            log.warn("QUIC dialer identity mismatch reached the attach path — Wave-3 client-side enforcement should have rejected it: dialed={} helloSender={} dialAddress={}",
                     dialed.id(), actual, dialed.resolvedAddress().asString());
        }
        var phase = getOrCreatePeer(dialed.id()).phase();
        peerTransitionListener.accept(new PeerTransitionRecord(dialed.id(),
                                                               phase,
                                                               phase,
                                                               "dialer-hello expected=" + dialed.id().id()
                                                               + " actual=" + actual.id()
                                                               + " addr=" + dialed.resolvedAddress().asString(),
                                                               System.currentTimeMillis()));
    }

    /// Feed the PEER transition journal with a REJECTED dialer Hello identity mismatch
    /// (Wave-3 enforcement, `QuicClusterClient.completePeerConnection`): the handshake
    /// completed but the Hello sender's claimed identity did not match the dialed identity,
    /// so the connection was closed un-attached. `from == to` — the rejection itself does not
    /// mutate the phase; the normal connect-failed eviction (CONNECTING → EVICTED) follows in
    /// [#onConnectFailed] and journals its transition separately via [PeerState].
    @Contract
    private void journalDialerHelloRejected(NodeInfo dialed, QuicTransportError.IdentityMismatch mismatch) {
        var phase = getOrCreatePeer(dialed.id()).phase();
        peerTransitionListener.accept(new PeerTransitionRecord(dialed.id(),
                                                               phase,
                                                               phase,
                                                               "dialer-hello-REJECTED expected=" + mismatch.expected().id()
                                                               + " actual=" + mismatch.actual().id()
                                                               + " addr=" + mismatch.address(),
                                                               System.currentTimeMillis()));
    }

    /// Per-dial connect timeout: a safe multiple of the QUIC hello/handshake timeout (same factor
    /// as the reconciler CONNECTING-staleness window), so a legitimately-slow handshake under load
    /// is not aborted while a genuinely hung dial still resolves to failure deterministically.
    private TimeSpan connectTimeout() {
        return TimeSpan.timeSpan(topologyManager.helloTimeout().nanos() * CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR)
                       .nanos();
    }

    private void onConnectFailed(NodeInfo peer, Cause cause) {
        // Wave-3 dialer-side identity verification: a rejected Hello identity mismatch flows
        // down this normal connect-failure path (so backoff/eviction engage as for any failed
        // dial), but additionally feeds the PEER transition journal with the REJECTED record.
        if (cause instanceof QuicTransportError.IdentityMismatch mismatch) {
            journalDialerHelloRejected(peer, mismatch);
        }
        quicMetrics.onHandshakeFailure();
        log.warn("Failed to connect from {} to {}: {}", self, peer, cause.message());
        // Reset phase to EVICTED so a subsequent retry (via topology reconciler) can re-enter
        // CONNECTING. The dial failed from CONNECTING, so use `evictStaleConnecting`
        // (CONNECTING → EVICTED); the plain `evict()` only handles CONNECTED → EVICTED and would
        // be a silent no-op here, re-wedging the peer in CONNECTING.
        var state = peers.get(peer.id());
        if (state != null && state.phase() == PeerState.Phase.CONNECTING) {
            var _ = state.evictStaleConnecting(System.nanoTime());
        }
        router.route(new NetworkServiceMessage.ConnectionFailed(
            peer.id(), ConnectionError.networkError(peer.address().asString(), cause.message())));
    }

    @SuppressWarnings("JBCT-PAT-01") // Multi-step peer registration with attach outcome dispatch
    private void onPeerConnected(QuicPeerConnection connection, NodeRole peerRole, NodeAddress peerAddress, Map<String, String> peerLabels) {
        var peerId = connection.peerId();

        // Never register self as a peer — self-connections cause removal cascades
        // (processViewChange REMOVE for self → leader re-election → CDM rebuild)
        if (peerId.equals(self.id())) {
            log.debug("Ignoring self-connection from {}", peerId);
            connection.close();
            return;
        }

        // Check for unknown node — build NodeInfo from Hello data (NodeId, role, address, labels)
        Option<NodeInfo> unknownNodeInfo = topologyManager.get(peerId).isEmpty()
            ? buildUnknownNodeInfo(peerId, peerRole, peerAddress, peerLabels)
            : Option.empty();

        var state = getOrCreatePeer(peerId);
        if (peerRole == NodeRole.PASSIVE) {
            state.markPassive();
        }

        if (state.phase() == PeerState.Phase.REMOVED && swimMembershipAllows(peerId)) {
            // Inbound connection from a peer SWIM has re-admitted to the authoritative membership
            // (incarnation supersede). Honour the re-admit: REMOVED->INIT so attach below accepts it
            // rather than REJECTED. Absent-from-coreNodes REMOVED peers stay terminal (attach rejects).
            if (state.readmit(System.nanoTime())) {
                log.info("Accepting inbound from re-admitted peer {} — REMOVED->INIT (back in SWIM authoritative membership)", peerId);
            }
        }

        var outcome = state.attach(connection, System.nanoTime());
        // Adopt-newer: a still-active but aged incumbent was displaced by this fresh handshake.
        // Close the displaced OLD link through the same path evictStaleConnection uses.
        outcome.superseded().onPresent(this::closeSupersededConnection);
        boolean isReconnect;
        switch (outcome.result()) {
            case PeerState.AttachResult.REJECTED -> {
                log.debug("Rejecting connection from REMOVED peer {}", peerId);
                connection.close();
                return;
            }
            case PeerState.AttachResult.DUPLICATE -> {
                log.debug("Duplicate connection from {}, closing new (existing is active)", peerId);
                connection.close();
                return;
            }
            case PeerState.AttachResult.ACCEPTED -> {
                quicMetrics.onConnectionEstablished();
                isReconnect = false;
            }
            case PeerState.AttachResult.RECONNECTED -> {
                quicMetrics.onConnectionEstablished();
                isReconnect = true;
            }
            default -> {
                // Exhaustive — unreachable but required for definite assignment.
                connection.close();
                return;
            }
        }

        // Successful attach — clear any reconnect backoff so a future flap starts at the
        // initial delay rather than carrying over the doubled value. Same for the
        // missing-peer reconciler backoff (handles the asymmetric-handshake recovery path).
        resetReconnectBackoff(peerId);
        state.resetReconcileBackoff();
        // Event-driven eviction: a genuine channel close now evicts immediately, independent
        // of any outbound traffic (an idle follower<->follower link never writes, so the
        // write-path evictor would never notice). Registered EXACTLY ONCE per successful
        // attach (ACCEPTED + RECONNECTED — DUPLICATE/REJECTED returned early above) on the
        // just-bound `connection`.
        registerCloseListener(peerId, connection);
        drainOfflineBufferInto(state, connection);

        if (isReconnect) {
            finalizeReconnect(peerId, unknownNodeInfo);
            return;
        }

        // R5 (spec §4.1): transport never mutates the topology projection. The Hello
        // handshake is reported upward via processViewChange → peerStateListener →
        // SWIM `recordTransportHint` (PeerReachable). HealthReconciler (sole writer of
        // NodeLifecycleKey) projects the resulting MembershipView for Layer 3.
        // P1 fix: forward `unknownNodeInfo` so SWIM can learn the peer's full identity
        // (id + address) without falling back to a stale static-topology lookup. This
        // closes the QUIC eviction storm under topology-forgot-peer reconnects.
        router.route(new NetworkServiceMessage.ConnectionEstablished(peerId, unknownNodeInfo));
        processViewChange(ADD, peerId);

        // Initiate topology discovery only for unknown nodes
        unknownNodeInfo.onPresent(_ -> router.route(new NetworkServiceMessage.Send(
            peerId, new NetworkMessage.DiscoverNodes(self.id()))));

        log.debug("Node {} connected via QUIC Hello handshake", peerId);
    }

    private Option<NodeInfo> buildUnknownNodeInfo(NodeId peerId, NodeRole peerRole, NodeAddress peerAddress, Map<String, String> peerLabels) {
        log.info("Unknown node {} connected via QUIC Hello with address {}", peerId, peerAddress.asString());
        return Option.some(NodeInfo.nodeInfo(peerId, peerAddress, peerRole, peerLabels));
    }

    /// Finalize a RECONNECTED attach. Suppresses the duplicate ADD view-change.
    /// R5 (spec §4.1): transport never mutates topology — the resilient peer-state
    /// machinery (`PeerState`) is the only memory of the peer at the QUIC layer; any
    /// recovered view of the peer is projected into Layer 3 via SWIM hints.
    /// Package-private to allow direct exercise from regression tests without driving a
    /// full QUIC handshake.
    @Contract
    void finalizeReconnect(NodeId peerId, Option<NodeInfo> unknownNodeInfo) {
        // unknownNodeInfo is retained for diagnostic logging in the surrounding caller; the
        // R4 pre-cursor version called topologyManager.registerPeer here, that mutation has
        // been removed (transport is read-only against TopologyObserver).
        // P1 fix: propagate `unknownNodeInfo` into the routed `ConnectionEstablished`
        // so downstream SWIM (AetherNode handler) can learn the peer's full identity
        // even after topology forgot the peer (CTM scale-down/replace path).
        router.route(new NetworkServiceMessage.ConnectionEstablished(peerId, unknownNodeInfo));
        processViewChange(RECONNECT, peerId);
        log.debug("Node {} reconnected via QUIC Hello handshake (suppressed duplicate ADD), unknownNodeInfo={}",
                  peerId, unknownNodeInfo);
    }

    /// Drain the per-peer offline buffer into a freshly-attached connection. Called from
    /// [onPeerConnected] right after `attach` returns ACCEPTED. The offline buffer holds
    /// messages that were offered while the peer was in CONNECTING/EVICTED phase (e.g. during
    /// a QUIC handshake storm after a mass restart). Without this drain those messages would
    /// be lost and Rabia consensus would stall until the stall detector re-broadcasts.
    @SuppressWarnings("JBCT-PAT-01") // Best-effort drain loop
    private void drainOfflineBufferInto(PeerState state, QuicPeerConnection connection) {
        var drained = state.drainOfflineBuffer();
        if (drained.isEmpty()) {
            return;
        }
        for (var message : drained) {
            var _ = writeToStream(state.peerId(), message, connection);
        }
        log.debug("Drained {} offline messages to newly-connected peer {}", drained.size(), state.peerId());
    }

    // --- Internal: message send ---

    /// Dispatch a typed message to a single peer — runs through the PeerState machine:
    /// SendNow → write to captured connection; Queued → buffered for reconnect; Dropped → REMOVED.
    /// The lane is resolved once here (from the message's `streamType()`); the message object is
    /// never threaded past this point.
    private void dispatchPayload(NodeId peerId, Message.Wired message) {
        var state = peers.get(peerId);
        if (state == null) {
            log.debug("No peer state for {} — dropping message", peerId);
            return;
        }
        var _ = dispatchToPeer(state, message);
    }

    /// Outcome-tracking variant used by `sendOutcome` callers (DHT quorum path). Same
    /// dispatch flow as `dispatchPayload` but surfaces the synchronous local-transport
    /// verdict so the caller can fail-fast against unreachable replicas.
    private WriteOutcome dispatchPayloadWithOutcome(NodeId peerId, Message.Wired message) {
        var state = peers.get(peerId);
        if (state == null) {
            log.debug("No peer state for {} — refusing tracked send", peerId);
            return new WriteOutcome.NoPeerState(peerId);
        }
        return dispatchToPeer(state, message);
    }

    /// Broadcast a typed message to all known peers. When `skipPassive` is true, peers whose
    /// role is PASSIVE are filtered (used by `broadcast(ProtocolMessage)` when the message
    /// opts out of passive delivery via `deliverToPassive() == false`).
    ///
    /// Serialization happens at the single write site, and only for peers that are actually
    /// CONNECTED (SendNow) — so `broadcast` stays a true no-op when `peers` is empty or all
    /// peers are filtered/offline, preserving test fixtures that broadcast unregistered codec
    /// types in isolation. Each connected peer encodes independently (the message rides a
    /// per-peer stream); the prior serialize-once-across-peers reuse is intentionally dropped
    /// in favour of one serialization site and a message-typed offline buffer.
    /// A peer the FSM has evicted (absent from `broadcastMembership`) is
    /// skipped even while a stale CONNECTED QUIC link to it lingers — this is the #68 storm fix:
    /// consensus never re-targets a dead ULID, so `Retry N/200 @25ms` against it cannot start.
    /// When the supplier yields `Option.none()` (default / unwired), no filtering is applied and
    /// every connected peer is a target, preserving the prior behaviour for consensus-only fixtures.
    @SuppressWarnings("JBCT-PAT-01") // Iterate eligible peers, dispatch
    private void broadcastPayload(Message.Wired message, boolean skipPassive) {
        var membershipView = broadcastMembership.get();
        for (var state : peers.values()) {
            if (!isBroadcastEligible(state, membershipView, skipPassive)) {
                continue;
            }
            var _ = dispatchToPeer(state, message);
        }
    }

    /// Broadcast-eligibility decision for a single peer. A peer is eligible unless it is a
    /// skipped passive peer, or — when a membership view is present — it is absent
    /// from that view. The view is the membership AUTHORITY: an evicted-but-still-CONNECTED peer
    /// (present in the `peers` cache, absent from the view) is NOT eligible, which is the #68
    /// storm fix. An empty view (`Option.none()`, default / unwired supplier) means "no filter /
    /// all peers".
    private static boolean isBroadcastEligible(PeerState state, Option<Set<NodeId>> membershipView, boolean skipPassive) {
        if (skipPassive && state.isPassive()) {
            return false;
        }
        return membershipView.map(view -> view.contains(state.peerId())).or(true);
    }

    @SuppressWarnings("JBCT-PAT-01") // Outcome dispatch with metrics + write
    private WriteOutcome dispatchToPeer(PeerState state, Message.Wired message) {
        var outcome = state.offerOutbound(message);
        return switch (outcome) {
            case PeerState.OfferOutcome.SendNow(QuicPeerConnection connection) ->
                writeToStream(state.peerId(), message, connection);
            case PeerState.OfferOutcome.Queued(boolean oldestEvicted) -> {
                quicMetrics.onBackpressureQueued();
                if (oldestEvicted) {
                    quicMetrics.onBackpressureDrop();
                    log.debug("Offline buffer for peer {} at capacity — dropped oldest", state.peerId());
                }
                // Queued in the offline buffer — the message has been accepted for eventual
                // delivery on reconnect. Report as Sent so callers (DHT) treat it as enqueued
                // success; if the peer never reconnects within the operation deadline the
                // collector will time out via its existing mechanism.
                yield new WriteOutcome.Sent(state.peerId());
            }
            case PeerState.OfferOutcome.Dropped ignored -> {
                log.debug("Message to REMOVED peer {} dropped", state.peerId());
                yield new WriteOutcome.NoPeerState(state.peerId());
            }
        };
    }

    @SuppressWarnings("JBCT-PAT-01") // Stream selection, lazy serialize, write
    private WriteOutcome writeToStream(NodeId peerId, Message.Wired message, QuicPeerConnection connection) {
        if (!connection.isActive()) {
            // Connection went dead between offerOutbound capture and write. Evict and re-dispatch
            // so the message lands in the offline buffer for the next attach.
            evictStaleConnection(peerId, connection);
            var state = peers.get(peerId);
            if (state != null) {
                var _ = dispatchToPeer(state, message);
            }
            return new WriteOutcome.ConnectionDead(peerId);
        }
        var lane = message.streamType();
        var stream = connection.stream(lane)
                               .fold(() -> connection.stream(StreamType.CONSENSUS), Option::some);
        if (stream.isEmpty()) {
            log.warn("No stream available for peer {}", peerId);
            return new WriteOutcome.ConnectionDead(peerId);
        }
        // Sole serialization site: encode only once a writable lane stream is confirmed.
        return writeIfWritable(stream.unwrap(), serializer.encode(message), peerId, lane);
    }

    private WriteOutcome writeIfWritable(QuicStreamChannel ch, byte[] bytes, NodeId peerId, StreamType streamType) {
        if (ch.isWritable()) {
            quicMetrics.onMessageSent();
            ch.writeAndFlush(Unpooled.wrappedBuffer(bytes))
              .addListener(future -> handleWriteResult(future, peerId, streamType));
            return new WriteOutcome.Sent(peerId);
        }
        // Backpressure: channel is at netty's high-watermark. Three divergent policies by stream:
        //
        //  - CONSENSUS: NEVER silently drop. A burst of consensus commands (deploy → ACTIVATE)
        //    backpressures the consensus stream; the old silent-drop relied on "Rabia/SWIM will
        //    retransmit", but retransmits hit the same backpressure and ALSO dropped, denying
        //    quorum until the 30s cluster.apply timeout. We now hand the send to an async,
        //    non-blocking Retry (delays scheduled via SharedScheduler — never blocks the event
        //    loop or submit thread) and report Sent optimistically (mirrors the offline-buffer
        //    Queued→Sent pattern; consensus callers send/broadcast are fire-and-forget).
        //  - DHT: gated by channel liveness. A DHT GetResponse (64KB block) to a LIVE-but-
        //    momentarily-backpressured reader was being fast-failed, dropping a good response so
        //    blocks never reached read-quorum → 30s operationTimeout. When the channel is ACTIVE
        //    the peer is alive and just transiently backpressured: route through the same async
        //    retry so the response is delivered when writability returns (the QuorumCollector
        //    waits on the real response). When the channel is INACTIVE the replica is genuinely
        //    dead: STILL refuse so the DHT QuorumCollector fast-fails (no 30s stall on a dead
        //    replica). See aether/docs/specs/dht-resilience-spec.md.
        //  - Everything else (KV / METRICS / INVOKE / FORWARD / CONTROL / SYNC): UNCHANGED
        //    fast-fail BackpressureRefused.
        // Backpressure entry: name the lane so a stalled stream is identifiable in the logs.
        // One concise line per backpressure entry (per-retry logging stays at debug below).
        log.warn("Backpressure on peer {} lane {} (isWritable={})", peerId, streamType, ch.isWritable());
        return switch (streamType) {
            case CONSENSUS -> retryBackpressuredWrite(ch, bytes, peerId, streamType);
            case DHT -> ch.isActive()
                        ? retryBackpressuredWrite(ch, bytes, peerId, streamType)
                        : refuseBackpressured(peerId, streamType);
            default -> refuseBackpressured(peerId, streamType);
        };
    }

    /// Package-private test seam — drives `writeIfWritable` directly so regression tests can
    /// assert the per-stream backpressure branch (CONSENSUS retry vs. fast-fail refusal)
    /// without standing up a full QUIC handshake.
    WriteOutcome writeIfWritableForTest(QuicStreamChannel ch, byte[] bytes, NodeId peerId, StreamType streamType) {
        return writeIfWritable(ch, bytes, peerId, streamType);
    }

    /// Package-private test seam — exposes the single async-write attempt so tests can assert
    /// it fails cleanly (a `Cause`, never a throw) on a backpressured/inactive channel. Defaults
    /// to the CONSENSUS lane so existing regression tests are unchanged; drives the generalized
    /// `rawBackpressuredWrite`.
    Promise<Unit> rawConsensusWriteForTest(QuicStreamChannel ch, byte[] bytes, NodeId peerId) {
        return rawBackpressuredWrite(ch, bytes, peerId, StreamType.CONSENSUS);
    }

    private WriteOutcome refuseBackpressured(NodeId peerId, StreamType streamType) {
        quicMetrics.onBackpressureDrop();
        log.warn("Channel to peer {} not writable on stream {} — refusing message (backpressure)", peerId, streamType);
        return new WriteOutcome.BackpressureRefused(peerId);
    }

    /// Fire-and-forget async retry of a backpressured send on a LIVE peer. The Retry re-invokes
    /// `rawBackpressuredWrite` after a `SharedScheduler`-scheduled delay, so nothing blocks here.
    /// Reports `Sent` optimistically — CONSENSUS callers ignore the synchronous outcome (the retry
    /// plus Rabia's own retransmit owns eventual delivery); for DHT the QuorumCollector waits on
    /// the real response, which the retry delivers once writability returns.
    private WriteOutcome retryBackpressuredWrite(QuicStreamChannel ch, byte[] bytes, NodeId peerId, StreamType streamType) {
        if (isPeerRemoved(peerId)) {
            log.debug("{} retry to {} dropped: peer is REMOVED (terminal) — not rescheduling", streamType, peerId);
            return new WriteOutcome.Sent(peerId);
        }
        quicMetrics.onBackpressureRetry();
        log.debug("{} channel to {} not writable — retrying via async backoff (not dropping)", streamType, peerId);
        var _ = consensusRetry.execute(() -> rawBackpressuredWrite(ch, bytes, peerId, streamType))
                              .onFailure(cause -> log.debug(
                                  "{} send to {} gave up after retries — relying on retransmit: {}",
                                  streamType, peerId, cause.message()));
        return new WriteOutcome.Sent(peerId);
    }

    /// A peer driven to terminal REMOVED by `departurePermanent` (restart disabled — a dead
    /// NodeId never returns under the same id) must not be written to or retried. Distinct from
    /// EVICTED (transient live-flap), which keeps buffering via `offerOutbound` and drains on
    /// reconnect — only REMOVED short-circuits here.
    private boolean isPeerRemoved(NodeId peerId) {
        var state = peers.get(peerId);
        return state != null && state.phase() == PeerState.Phase.REMOVED;
    }

    /// One write attempt against the captured lane stream. Re-validated every attempt:
    /// if the channel went inactive or is still backpressured this fails (a `Cause`, never a
    /// throw) and the enclosing Retry either reschedules or gives up. A peer disconnecting
    /// mid-retry therefore fails cleanly — the offline buffer handles reconnect delivery.
    /// This is the atomic unit the Retry re-invokes, so it performs NO retry itself.
    private Promise<Unit> rawBackpressuredWrite(QuicStreamChannel ch, byte[] bytes, NodeId peerId, StreamType streamType) {
        if (isPeerRemoved(peerId)) {
            return Causes.cause(streamType + " stream dropped: peer " + peerId + " is REMOVED (terminal)").promise();
        }
        if (ch.isActive() && ch.isWritable()) {
            quicMetrics.onMessageSent();
            ch.writeAndFlush(Unpooled.wrappedBuffer(bytes))
              .addListener(future -> handleWriteResult(future, peerId, streamType));
            return Promise.success(unit());
        }
        return Causes.cause(streamType + " stream backpressured or inactive for " + peerId).promise();
    }

    private void handleWriteResult(Future<? super Void> future, NodeId peerId, StreamType streamType) {
        if (!future.isSuccess()) {
            quicMetrics.onWriteFailure();
            log.error("Failed to write to peer {} on stream {}", peerId, streamType, future.cause());
            handleWriteFailure(peerId);
        }
    }

    private void handleWriteFailure(NodeId peerId) {
        // Write failure is advisory — the QUIC channel-close handler owns authoritative
        // peer removal (processViewChange(REMOVE) emits the TransportObservation hint
        // toward SWIM; topology mutation is HealthReconciler's responsibility, not ours).
        // Clearing peer state
        // here races with handshake completion from the reverse direction and prematurely
        // drops the peer from Rabia's membership view.
        log.debug("Write to {} failed; deferring removal to QUIC channel lifecycle", peerId);
    }

    @SuppressWarnings("JBCT-PAT-01") // Evict transition + channel close
    private void evictStaleConnection(NodeId peerId, QuicPeerConnection connection) {
        var state = peers.get(peerId);
        if (state == null) {
            return;
        }
        var evicted = state.evict(System.nanoTime());
        if (evicted.isEmpty()) {
            log.debug("Node {} stale link already replaced — nothing to evict", peerId);
            return;
        }
        quicMetrics.onConnectionClosed();
        evicted.onPresent(this::closeDroppedConnection);
        // Close the per-peer ephemeral UDP socket. Without this, every reconnect leaks
        // one datagram channel because the client previously held a single volatile field
        // and overwrote it on each `bind(0)`. See QuicClusterClient#closeDatagramChannel.
        var clientRef = client;
        if (clientRef != null) {
            clientRef.closeDatagramChannel(peerId);
        }
        log.warn("Node {} evicted stale (inactive) link — peer remains in topology, offline buffer preserved for reconnect",
                 peerId);
        // Explicit use of the `connection` parameter to satisfy the API contract — the
        // identity check already happened inside `state.evict()` which matches by phase.
        if (connection != null && connection.isActive()) {
            connection.close();
        }
    }

    /// Event-driven eviction: when the QUIC channel genuinely closes, evict the peer so the
    /// reconciler re-dials — instead of waiting for the next outbound write to notice
    /// `!isActive()` (which never comes on an idle follower<->follower link). Identity-guarded
    /// in `onChannelClosed`: only evicts if the closed connection is STILL the bound one, so an
    /// adopt-newer supersede (which binds a different object) does not evict the live replacement.
    @Contract
    private void registerCloseListener(NodeId peerId, QuicPeerConnection connection) {
        connection.connection().closeFuture().addListener(_ -> onChannelClosed(peerId, connection));
    }

    private void onChannelClosed(NodeId peerId, QuicPeerConnection closed) {
        var state = peers.get(peerId);
        if (state == null) {
            return;
        }
        // Reference equality on the wrapper: adopt-newer (`attachOverConnected`) binds a
        // DIFFERENT QuicPeerConnection, so a superseded close yields `current != closed` →
        // no evict. After evict/supersede/departurePermanent the peer is EVICTED/REMOVED or
        // rebound → `activeConnection()` empty or `current != closed` → no-op. No eviction
        // loop, no double-close (`evictStaleConnection` guards its own `.close()` on `isActive()`).
        if (state.activeConnection().map(current -> current == closed).or(false)) {
            log.info("QUIC channel to {} closed — evicting (event-driven) so the reconciler re-dials", peerId);
            evictStaleConnection(peerId, closed);
        }
    }

    /// Per-peer reconnect backoff state. Mutable, guarded by the enclosing
    /// `ConcurrentHashMap.compute` callback.
    private static final class ReconnectBackoff {
        long nextAttemptMs;
        long currentDelayMs;

        ReconnectBackoff(long nextAttemptMs, long currentDelayMs) {
            this.nextAttemptMs = nextAttemptMs;
            this.currentDelayMs = currentDelayMs;
        }
    }

    /// Returns true if the eviction-driven reconnect for `peerId` is allowed at the
    /// current wall-clock instant. Doubles `currentDelayMs` (capped) and applies a
    /// random jitter factor in `[BACKOFF_JITTER_MIN, BACKOFF_JITTER_MAX]` for the next
    /// scheduled attempt. Single-threaded per key via `compute`.
    /// Package-private — tests assert backoff doubling under a deterministic clock.
    @SuppressWarnings("JBCT-PAT-01") // Backoff state mutation under compute()
    boolean backoffAllowsReconnect(NodeId peerId) {
        var nowMs = wallClockMs.getAsLong();
        var allowed = new boolean[]{false};
        reconnectBackoff.compute(peerId, (_, prior) -> {
            if (prior == null) {
                allowed[0] = true;
                return new ReconnectBackoff(nowMs + jittered(BACKOFF_INITIAL_MS), BACKOFF_INITIAL_MS);
            }
            if (nowMs < prior.nextAttemptMs) {
                allowed[0] = false;
                return prior;
            }
            allowed[0] = true;
            var nextDelay = Math.min(prior.currentDelayMs * 2, BACKOFF_CAP_MS);
            prior.currentDelayMs = nextDelay;
            prior.nextAttemptMs = nowMs + jittered(nextDelay);
            return prior;
        });
        if (!allowed[0]) {
            log.debug("Reconnect for peer {} suppressed by backoff", peerId);
        }
        return allowed[0];
    }

    private long jittered(long delayMs) {
        var factor = BACKOFF_JITTER_MIN + (BACKOFF_JITTER_MAX - BACKOFF_JITTER_MIN) * ThreadLocalRandom.current().nextDouble();
        return Math.max(1L, (long) (delayMs * factor));
    }

    /// Periodic missing-peer reconciler tick. For every configured non-passive peer that is
    /// not currently CONNECTED, dispatches `connectPeer(...)` (subject to per-peer jittered
    /// exponential backoff) so the asymmetric-handshake hole — node A handshakes with B/C/D
    /// but permanently misses E — self-heals without operator intervention. When all
    /// configured peers are connected this is a no-op.
    ///
    /// Skips peers in CONNECTING phase (a dial is already in flight; existing dedup at
    /// `connectPeer` would no-op anyway, but we save the topology lookup). Skips peers in
    /// REMOVED phase (authoritatively departed; resurrecting them is the responsibility of
    /// the topology / cluster-formation layer, not the transport reconciler).
    ///
    /// Package-private to allow tests to drive ticks deterministically without scheduling.
    @SuppressWarnings("JBCT-PAT-01") // Periodic tick: walk topology, gate per-peer, dispatch dial
    void reconcileMissingPeersTick() {
        if (!isRunning.get()) {
            return;
        }
        Result.lift(this::reconcileMissingPeersUnsafe)
              .onFailure(cause -> log.warn("Missing-peer reconciler tick failed: {}", cause.message()));
    }

    /// Liveness sweep: a CONNECTED [PeerState] whose underlying connection reports
    /// `!isActive()` is a zombie — nothing writes to a follower<->follower link periodically
    /// (ping is leader-only, pongs go only to the leader, Rabia is round-driven), so the
    /// write-path evictor (`writeToStream`) never fires for it. Demote it to EVICTED here so
    /// the reconciler below re-dials it the SAME tick (the sweep runs before `connectedPeers()`
    /// is computed). Complements the adopt-newer handshake path, which handles the
    /// `isActive()`-lies-true orphan from the acceptor side.
    ///
    /// SECOND eviction trigger (Flavor-2 zombie): a CONNECTED peer whose `isActive()` LIES true but
    /// whose link has received NO inbound frame on ANY lane for longer than the liveness TTL
    /// (`pingInterval * LIVENESS_TTL_PING_INTERVAL_FACTOR`). This catches the partition-orphaned /
    /// blackholed link where the channel never learns its peer died (QUIC MAX_IDLE_TIMEOUT disabled).
    /// The TTL relies on the SWIM probe cadence — SWIM guarantees cross-link probe traffic at
    /// `pingInterval`, so a quiet-but-healthy link refreshes its inbound clock long before the TTL.
    @SuppressWarnings("JBCT-PAT-01") // Liveness sweep: iterate peers, evict inactive CONNECTED links
    private void sweepStaleConnectedLinks() {
        peers.forEach(this::sweepPeer);
    }

    private void sweepPeer(NodeId peerId, PeerState state) {
        state.activeConnection()
             .filter(conn -> isZombieLink(state, conn))
             .onPresent(conn -> evictInactiveLink(peerId, conn));
    }

    /// A CONNECTED link is a zombie when its channel is no longer active OR it has been silent
    /// (no inbound frame on any lane) past the liveness TTL despite `isActive()` reporting true.
    private boolean isZombieLink(PeerState state, QuicPeerConnection conn) {
        return !conn.isActive() || state.inboundAgeNanos(System.nanoTime()) > livenessTtlNanos();
    }

    /// Liveness TTL in nanos: `pingInterval * LIVENESS_TTL_PING_INTERVAL_FACTOR`. See the field doc
    /// for the SWIM-cadence dependency.
    private long livenessTtlNanos() {
        return topologyManager.pingInterval().nanos() * LIVENESS_TTL_PING_INTERVAL_FACTOR;
    }

    private void evictInactiveLink(NodeId peerId, QuicPeerConnection conn) {
        log.warn("Liveness sweep: peer {} CONNECTED but link is a zombie (inactive or silent past TTL) — evicting for re-dial", peerId);
        evictStaleConnection(peerId, conn);
    }

    @SuppressWarnings("JBCT-PAT-01") // Iterate, gate, dispatch
    private Unit reconcileMissingPeersUnsafe() {
        sweepStaleConnectedLinks();
        var connected = connectedPeers();
        var nowMs = wallClockMs.getAsLong();
        // ADD-only Wave 2: when the FSM-published desired-connection set is wired, reconcile
        // against it (gate-free — the set already encodes membership+health). Otherwise run the
        // legacy topology-driven path with both SWIM gates, byte-identical to the prior behaviour.
        return desiredConnections.get()
                                 .fold(() -> reconcileAgainstTopology(connected, nowMs),
                                       desired -> reconcileAgainstDesired(desired, connected, nowMs));
    }

    @SuppressWarnings("JBCT-PAT-01") // Iterate, gate, dispatch
    private Unit reconcileAgainstTopology(Set<NodeId> connected, long nowMs) {
        for (var peerId : topologyManager.topology()) {
            if (peerId.equals(self.id())) {continue;}
            if (connected.contains(peerId)) {
                // Restored: clear the missing-since marker so a future drop restarts the
                // grace window. Without this, a peer that briefly disconnected (e.g. SWIM
                // false positive) would inherit a long stale missing-since on its next
                // drop and the higher-id reconciler would fire immediately.
                firstObservedMissingMs.remove(peerId);
                continue;
            }
            if (topologyManager.isPassive(peerId)) {continue;}
            // Track when this peer was first observed missing. Subsequent ticks (re-runs
            // of this method while the peer is still missing) preserve the original
            // timestamp via putIfAbsent semantics — the grace window is anchored to the
            // first observation, not the most recent.
            firstObservedMissingMs.putIfAbsent(peerId, nowMs);
            // ConnectionDirection: lower-id initiates first; higher-id waits for inbound.
            // RC1 chaos-recovery fix: when the peer has been observed missing for longer
            // than RECONCILE_BACKOFF_CAP_MS (60s), the lower-id side has provably failed
            // to initiate (its backoff is capped at 60s, so by now it has tried many
            // times). Let the higher-id side also attempt. PeerState.beginConnecting
            // dedup keeps per-side concurrency safe; the 60s grace window is long enough
            // that the cold-boot dual-Hello race (the original reason for asymmetric dial)
            // is no longer relevant — both sides discover each other in <1s on cold boot,
            // so reaching 60s missing means a real partition was attempted-and-failed.
            if (!ConnectionDirection.shouldInitiate(self.id(), peerId)
                && !higherIdGracePeriodElapsed(peerId, nowMs)) {
                continue;
            }
            considerPeerForReconcile(peerId, nowMs);
        }
        return unit();
    }

    /// FSM-wired reconciler body (Wave 2). Reconciles actual connections against the FSM's
    /// desired core-member set. The set already encodes membership+health (MEMBER+SUSPECT
    /// core members, DEAD/worker excluded), so this path DROPS the legacy SWIM
    /// membership/health gates. Single-dialer ordering, higher-id grace, and per-peer
    /// reconcile backoff stay mechanical and apply unchanged.
    @SuppressWarnings("JBCT-PAT-01") // Iterate desired set, gate, dispatch
    private Unit reconcileAgainstDesired(Collection<NodeInfo> desired, Set<NodeId> connected, long nowMs) {
        for (var nodeInfo : desired) {
            var peerId = nodeInfo.id();
            if (peerId.equals(self.id())) {continue;}
            if (connected.contains(peerId)) {
                firstObservedMissingMs.remove(peerId);
                continue;
            }
            // Defensive: the FSM desired-set already excludes workers; skip any PASSIVE that slips in.
            if (nodeInfo.role() == NodeRole.PASSIVE) {continue;}
            firstObservedMissingMs.putIfAbsent(peerId, nowMs);
            if (!ConnectionDirection.shouldInitiate(self.id(), peerId)
                && !higherIdGracePeriodElapsed(peerId, nowMs)) {
                continue;
            }
            considerDesiredPeerForReconcile(nodeInfo, nowMs);
        }
        return unit();
    }

    private boolean higherIdGracePeriodElapsed(NodeId peerId, long nowMs) {
        var firstMissed = firstObservedMissingMs.get(peerId);
        return firstMissed != null && (nowMs - firstMissed) >= RECONCILE_BACKOFF_CAP_MS;
    }

    /// Staleness escape hatch for the in-flight-dial dedup guard. A peer is normally skipped while
    /// CONNECTING (a dial is genuinely in flight). But a `client.connect(...)` that hangs with no
    /// success/failure callback wedges the peer in CONNECTING forever, and the dedup short-circuit
    /// then silently skips it on every tick. When the peer has been CONNECTING longer than
    /// `helloTimeout * CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR`, treat the in-flight dial as dead:
    /// force CONNECTING → EVICTED so a fresh re-dial proceeds THIS tick. Returns `true` only on a
    /// real eviction (i.e. the caller should fall through and re-dial); `false` means the dial is
    /// still within the protection window (genuinely in flight) and the dedup guard must hold.
    private boolean evictStaleConnecting(NodeId peerId, PeerState state) {
        var stalenessNanos = topologyManager.helloTimeout().nanos() * CONNECTING_STALENESS_HELLO_TIMEOUT_FACTOR;
        if (state.phaseAgeNanos(System.nanoTime()) < stalenessNanos) {
            return false;
        }
        if (!state.evictStaleConnecting(System.nanoTime())) {
            return false;
        }
        log.warn("Missing-peer reconciler: peer {} pinned in CONNECTING beyond staleness window ({}ms) — "
                 + "force-evicting hung dial for re-dial", peerId, stalenessNanos / 1_000_000L);
        var clientRef = client;
        if (clientRef != null) {
            clientRef.closeDatagramChannel(peerId);
        }
        return true;
    }

    private void considerPeerForReconcile(NodeId peerId, long nowMs) {
        var existing = peers.get(peerId);
        // CONNECTING means a dial is already in flight — the reconciler must NOT fire (see commit
        // 2e7b85dd1 dedup). REMOVED is normally terminal, but a REMOVED peer that SWIM has
        // RE-ADMITTED to the authoritative membership is a transient-partition survivor (below).
        if (existing != null) {
            var phase = existing.phase();
            if (phase == PeerState.Phase.CONNECTING) {
                if (!evictStaleConnecting(peerId, existing)) {
                    return;
                }
                // Hung dial force-evicted (CONNECTING → EVICTED): fall through so a fresh
                // re-dial is dispatched THIS tick instead of waiting another tick.
            }
            if (phase == PeerState.Phase.REMOVED) {
                // Incarnation-gated resurrection: a REMOVED peer that SWIM has RE-ADMITTED to the
                // authoritative membership (back in coreNodes() — only after a strictly-higher
                // incarnation superseded the tombstone, SwimProtocol.supersedeOrRefuse) is a
                // transient-partition survivor, not a permanent departure. Reset REMOVED->INIT so
                // it becomes dial-eligible; a REMOVED peer still ABSENT from coreNodes() is a
                // genuine departure and stays REMOVED (left for auto-heal). The SWIM probe-ack
                // remains the sole ALIVE authority — this is not resurrection-to-ALIVE.
                // Unit note: PeerState phase timestamps are System.nanoTime() (see attach/evict
                // callers); nowMs here is wall-clock millis from wallClockMs — readmit takes nanos.
                if (!swimMembershipAllows(peerId)) {
                    return;
                }
                if (existing.readmit(System.nanoTime())) {
                    resetReconnectBackoff(peerId);
                    log.info("Missing-peer reconciler: re-admitting REMOVED peer {} — back in SWIM authoritative membership (incarnation supersede); REMOVED->INIT", peerId);
                }
            }
        }
        var state = existing == null ? getOrCreatePeer(peerId) : existing;
        if (!state.reconcileBackoffAllows(nowMs, RECONCILE_BACKOFF_INITIAL_MS,
                                          RECONCILE_BACKOFF_CAP_MS, this::reconcileJitter)) {
            log.trace("Missing-peer reconciler suppressed by per-peer backoff for {}", peerId);
            return;
        }
        if (!swimHealthAllows(peerId)) {
            log.debug("Missing-peer reconciler suppressed by SWIM health gate for {} — peer is FAULTY or UNKNOWN", peerId);
            return;
        }
        if (!swimMembershipAllows(peerId)) {
            log.debug("Missing-peer reconciler suppressed for {} — peer is absent from the SWIM-fed authoritative membership (departed); left for auto-heal", peerId);
            return;
        }
        topologyManager.get(peerId)
                       .onPresent(this::reconcileDialPeer)
                       .onEmpty(() -> log.debug("Missing-peer reconciler: no NodeInfo for {} — topology lookup empty", peerId));
    }

    /// Gate-free counterpart of [considerPeerForReconcile] for the FSM-wired path. The peer's
    /// presence in the FSM desired-set IS the membership authority, so this performs NO
    /// `swimMembershipAllows` and NO `swimHealthAllows` check. A REMOVED desired peer is
    /// readmitted unconditionally (presence in the desired-set is the incarnation-fenced
    /// re-admit authority). Per-peer reconcile backoff and the CONNECTING-in-flight skip are
    /// retained. Dials via `reconcileDialPeer` with the FSM-carried `NodeInfo` (no topology lookup).
    private void considerDesiredPeerForReconcile(NodeInfo nodeInfo, long nowMs) {
        var peerId = nodeInfo.id();
        var existing = peers.get(peerId);
        if (existing != null) {
            var phase = existing.phase();
            if (phase == PeerState.Phase.CONNECTING) {
                if (!evictStaleConnecting(peerId, existing)) {
                    return;
                }
                // Hung dial force-evicted (CONNECTING → EVICTED): fall through so a fresh
                // re-dial is dispatched THIS tick instead of waiting another tick.
            }
            if (phase == PeerState.Phase.REMOVED) {
                if (existing.readmit(System.nanoTime())) {
                    resetReconnectBackoff(peerId);
                    log.info("Missing-peer reconciler: re-admitting REMOVED peer {} — present in FSM desired-set; REMOVED->INIT", peerId);
                }
            }
        }
        var state = existing == null ? getOrCreatePeer(peerId) : existing;
        if (!state.reconcileBackoffAllows(nowMs, RECONCILE_BACKOFF_INITIAL_MS,
                                          RECONCILE_BACKOFF_CAP_MS, this::reconcileJitter)) {
            log.trace("Missing-peer reconciler suppressed by per-peer backoff for {}", peerId);
            return;
        }
        reconcileDialPeer(nodeInfo);
    }

    /// Default jitter for the missing-peer reconciler. Light bands (0.8x–1.2x) — peers
    /// don't need wide jitter at the 5-60s tempo; correlated retries are tolerable.
    private long reconcileJitter(long delayMs) {
        return JitterUtil.applyJitter(delayMs, JitterUtil.LIGHT_MIN_FACTOR, JitterUtil.LIGHT_MAX_FACTOR);
    }

    /// Returns `true` when the SWIM health gate is absent (default — allow all) or when
    /// the gate predicate says the peer is reconnect-eligible (HEALTHY or SUSPECTED).
    /// Returns `false` only when the gate is present and explicitly rejects the peer.
    private boolean swimHealthAllows(NodeId peerId) {
        return swimHealthGate.map(gate -> gate.apply(peerId)).or(true);
    }

    /// Membership v2 §5.4 dial gate: re-dial a peer ONLY when it is still part of the
    /// SWIM-fed authoritative membership (`TopologyManager.coreNodes()`, which is backed by
    /// the membership snapshot when present and falls back to the locally-seeded core set
    /// otherwise). A configured core peer that SWIM has observed-then-departed leaves the
    /// snapshot's `coreMemberIds`, so it is absent from `coreNodes()` and this gate returns
    /// `false` — the reconciler stops re-dialing the dead peer (the forever-redial wedge)
    /// and leaves it for the leader's auto-heal to provision a replacement.
    ///
    /// Cold-start safety: before SWIM discovers peers, the snapshot is absent and
    /// `coreNodes()` returns the locally-seeded configured core set, which INCLUDES every
    /// configured seed — so a not-yet-discovered configured peer is allowed and formation
    /// proceeds. The gate only ever blocks a peer that the authoritative membership has
    /// positively dropped; it never gates on "must already be QUIC-connected."
    private boolean swimMembershipAllows(NodeId peerId) {
        return topologyManager.coreNodes().contains(peerId);
    }

    @Contract private void reconcileDialPeer(NodeInfo peer) {
        log.info("Missing-peer reconciler: re-dialing configured peer {} (phase={})",
                 peer.id(),
                 Option.option(peers.get(peer.id())).map(PeerState::phase).map(Object::toString).or("ABSENT"));
        connectPeer(peer);
    }

    /// Reset the per-peer reconnect backoff. Called on successful peer attach so a
    /// peer that flaps once does not pay the doubled delay forever.
    @Contract void resetReconnectBackoff(NodeId peerId) {
        reconnectBackoff.remove(peerId);
        // Connection restored: clear the missing-since marker so a future drop restarts
        // the higher-id-grace window. See firstObservedMissingMs documentation.
        firstObservedMissingMs.remove(peerId);
    }

    /// Test/diagnostic — inject a deterministic clock to validate backoff windows
    /// without sleeping. Defaults to `System::currentTimeMillis`.
    @Contract void overrideWallClockForTests(LongSupplier clock) {
        this.wallClockMs = clock == null ? System::currentTimeMillis : clock;
    }

    /// Package-private test seam — the current `PeerState.Phase` for a peer, or empty when no
    /// PeerState exists yet (ABSENT). Lets deferred-resolution tests assert a peer is neither pinned
    /// in CONNECTING nor marked REMOVED after an unresolvable dial.
    Option<PeerState.Phase> peerPhaseForTests(NodeId peerId) {
        return Option.option(peers.get(peerId)).map(PeerState::phase);
    }

    /// Package-private test seam — drives the inbound funnel `onMessageReceived` directly so tests
    /// can assert the blackhole-suppresses-liveness-refresh path (a blackholed inbound must NOT
    /// refresh the per-peer liveness clock) without standing up a live QUIC handshake.
    @Contract
    void onMessageReceivedForTests(NodeId sender, Object message) {
        onMessageReceived(sender, message);
    }

    /// Seeds a pre-built `PeerState` directly into the connection table, bypassing the QUIC
    /// handshake. Used only by transport-level tests that need a CONNECTED/EVICTED peer to
    /// exercise the REMOVE-emission idempotency paths in `disconnect`/`departurePermanent`
    /// without standing up a live datagram channel. The caller drives the supplied state to
    /// the desired phase (via `attach`/`evict`) before injection.
    @Contract
    PeerState seedPeerForTests(NodeId peerId, PeerState peerState) {
        peers.put(peerId, peerState);
        return peerState;
    }

    /// Package-private test seam — registers the event-driven QUIC close listener on a seeded
    /// connection without driving a live handshake. Lets transport-level tests fire the
    /// channel's `closeFuture` and assert the bound-vs-superseded eviction identity guard.
    @Contract
    void registerCloseListenerForTests(NodeId peerId, QuicPeerConnection connection) {
        registerCloseListener(peerId, connection);
    }

    /// Test hook: the set of peer NodeIds a `broadcast(skipPassive)` would actually target,
    /// after applying the same passive-skip + membership-view filter as `broadcastPayload`.
    /// Lets transport-level tests assert the #68 fix (an evicted-but-cached peer absent from the
    /// membership view is never targeted) without standing up live QUIC datagram channels.
    Set<NodeId> broadcastTargetsForTests(boolean skipPassive) {
        var membershipView = broadcastMembership.get();
        return peers.values()
                    .stream()
                    .filter(state -> isBroadcastEligible(state, membershipView, skipPassive))
                    .map(PeerState::peerId)
                    .collect(Collectors.toSet());
    }

    // --- Internal: view change ---

    /// QUIC is pure transport — peer-link state, peerLinks table, hello handshakes, message
    /// routing. Membership decisions are owned by the aether `MembershipDeltaProjector`
    /// (canonical publisher of `MembershipDecision`, fed by the `MembershipFsm` delta edge). Cluster-state notifications
    /// (`ClusterStateNotification`) are owned by `RabiaEngine`/`ConsensusBridge`.
    ///
    /// This method is the **canonical source of `TransportObservation` for the QUIC
    /// transport** (`ObservationSource.QUIC`). Each emission is a *local* observation —
    /// fast, partial-view, may flap. Cluster-canonical decisions about membership are
    /// emitted by the `MembershipDeltaProjector` as `MembershipDecision`.
    /// Subscribers must choose the appropriate stream:
    ///   - fast bootstrap-time reactions (e.g. `LeaderManager`) → `TransportObservation`
    ///   - canonical cluster-truth reactions (e.g. workload reassignment) → `MembershipDecision`
    ///
    /// R5 (spec §4.1): transport must NOT mutate topology. Emissions are advisory; the
    /// observer treats them as hints and does not derive membership from them.
    ///
    /// Mapping:
    ///   - ADD       → `TransportObservation.PeerJoined`
    ///   - REMOVE    → `TransportObservation.PeerDisconnected` (also fires
    ///                 `HealthSignal.QuicDisconnect` via `reportPeerRemoval`)
    ///   - SHUTDOWN  → `TransportObservation.SelfShutdown` (self-emit only)
    ///   - RECONNECT → `TransportObservation.PeerReconnected` (previously suppressed;
    ///                 now surfaced as a typed event so subscribers can invalidate
    ///                 disconnect-driven cleanup if appropriate)
    @SuppressWarnings("JBCT-PAT-01") // Switch expression with side effects
    private void processViewChange(ViewChangeOperation operation, NodeId peerId) {
        // Self should never appear in view changes — guard against cascading self-removal
        if (peerId.equals(self.id())) {
            log.warn("Ignoring view change {} for self node {}", operation, peerId);
            return;
        }
        var activePeerCount = activeConnectedCount();
        var quorumSize = topologyManager.quorumSize();
        var clusterSize = topologyManager.clusterSize();

        log.info("processViewChange: op={}, peer={}, activePeerCount={}, clusterSize={}, quorumSize={}",
                 operation, peerId, activePeerCount, clusterSize, quorumSize);

        var observation = switch (operation) {
            case ADD -> {
                // Cold-boot leader election needs synchronous `PeerJoined` to populate
                // `LeaderElectionContext.currentTopology` BEFORE the snapshot exists
                // (snapshot is only published after Rabia commits, which require an
                // elected leader, which requires currentTopology to be non-empty —
                // chicken/egg). The `MembershipDeltaProjector` augments
                // this for runtime edges via `MembershipDecision`; cluster bootstrap
                // relies on the synchronous transport emit.
                peerStateListener.onPeerJoined(peerId);
                // Connectivity observation feeds ReachabilityAggregator via the
                // PeerObservationBuffer drain path. Symmetric to REMOVE below.
                reportPeerConnection(peerId);
                yield TransportObservation.peerJoined(peerId, currentView(), ObservationSource.QUIC);
            }
            case REMOVE -> {
                // Advisory QUIC-level disconnect signal. On the leader this feeds the local
                // `HealthReconciler`; on a follower the observation is buffered into the next
                // outbound `ClusterSyncPong` so the leader folds it through PeerObservationReducer
                // (ClusterSync refactor commit 2 — followers are sensor-only).
                reportPeerRemoval(peerId);
                // Synchronous `PeerDisconnected` is load-bearing for receivers that cannot
                // wait on the membership-delta path during failure scenarios.
                // The `MembershipDeltaProjector` still fires its own
                // `MembershipDecision.NodeRemoved` on the FSM's REMOVED delta edge; subscribers
                // distinguish between the local (transport) and global (membership) facts.
                peerStateListener.onPeerLeft(peerId);
                yield TransportObservation.peerDisconnected(peerId, currentView(), ObservationSource.QUIC);
            }
            case SHUTDOWN -> {
                // Self-shutdown remains a transport-emitted event: it fires on the local
                // process's shutdown path before any consensus / KV writes can propagate, so
                // there is no `MembershipView` delta to drive it. `TopologyObserver` cannot
                // publish a decision for self because by the time the snapshot would reflect
                // the shutdown the local process has stopped.
                yield TransportObservation.selfShutdown(peerId, ObservationSource.QUIC);
            }
            case RECONNECT -> {
                // Transparent reconnect — peer is already known to upstream consumers; no
                // duplicate `PeerJoined` is emitted. SWIM owns the HEALTHY observation
                // (canonical source). Quorum is unaffected because EVICTED peers were
                // already counted as active. Subscribers may use `PeerReconnected` to
                // invalidate disconnect-driven cleanup if appropriate.
                //
                // We intentionally do NOT emit a fresh `PeerConnectivityObservation` here:
                // the peer was already considered connected before the EVICTED→CONNECTED
                // flap, so RECONNECT is not a "freshly observed REACHABLE" signal. During
                // noisy cluster B startup after a destructive predecessor suite, repeated
                // EVICTED→CONNECTED flaps would otherwise emit per-flap CONNECTED
                // observations that buffer for the full TTL and dominate the snapshot,
                // outvoting real UNREACHABLE quorum (12-network regression).
                peerStateListener.onPeerReconnected(peerId);
                log.debug("processViewChange RECONNECT for {} — emitting PeerReconnected (no connectivity observation)", peerId);
                yield TransportObservation.peerReconnected(peerId, currentView(), ObservationSource.QUIC);
            }
        };

        log.info("Routing transport observation: {}", observation);
        router.route(observation);
    }

    private int activeConnectedCount() {
        // Count CONNECTED and EVICTED peers as still active for quorum purposes. EVICTED is a
        // transient local-view state (stale QUIC link awaiting reconnect); the peer remains in
        // topology and its offline buffer is preserved. Only REMOVED (authoritative departure
        // via DisconnectNode) drops the peer from the quorum count. Without this, a brief QUIC
        // flap on any peer pushes `activeConnectedCount` below quorum and routes a spurious
        // `ClusterStateNotification.PASSIVE`, which calls `LeaderManager.stop()` and clears
        // `currentLeader` — then SWIM FAULTY on the (real) dead leader fires too late for the
        // "follower detects dead leader" re-election path, because `currentLeader` is already
        // empty and the narrow match fails.
        return (int) peers.values()
                          .stream()
                          .filter(p -> !p.isPassive())
                          .filter(p -> p.phase() == PeerState.Phase.CONNECTED
                                       || p.phase() == PeerState.Phase.EVICTED)
                          .count();
    }

    private void reportPeerRemoval(NodeId peerId) {
        // Leader path: route to the local disconnect listener (HealthReconciler fast path)
        // for liveness bookkeeping. Symmetric to follower-side reporting, ALSO emit a
        // connectivity transition via the reporter so the leader's local adapter can fold
        // the observation directly into ReachabilityAggregator (Step 4 of the topology-
        // observation refactor — eliminates the 5s self-fold tick latency on leader-side
        // QUIC drops). Followers skip the disconnectListener (leader-only consumer) and
        // only emit through the reporter, which buffers the observation for the next
        // outbound ClusterSyncPong → leader.
        if (isLeaderSupplier.getAsBoolean()) {
            disconnectListener.onDisconnect(peerId);
        }
        var epoch = observedEpochSupplier;
        connectivityReporter.onPeerDisconnected(peerId, epoch.term(), epoch.counter());
    }

    private void reportPeerConnection(NodeId peerId) {
        // Symmetric to reportPeerRemoval but reports CONNECTED transitions. The
        // leader-side path has no direct listener equivalent to disconnectListener —
        // HealthReconciler infers reachability from its own SWIM signals and from
        // ReachabilityAggregator output (built from connectivity observations folded
        // through the buffer). Both leader and follower route this signal through
        // the connectivity reporter; AetherNode's adapter pushes it to the local
        // PeerObservationBuffer, where it is drained at each cluster-sync tick:
        // leader-side into the local ReachabilityAggregator, follower-side into
        // the outbound ClusterSyncPong.
        var epoch = observedEpochSupplier;
        connectivityReporter.onPeerConnected(peerId, epoch.term(), epoch.counter());
    }

    private List<NodeId> currentView() {
        return Stream.concat(
                Stream.of(self.id()),
                peers.values()
                     .stream()
                     .filter(p -> p.phase() == PeerState.Phase.CONNECTED && !p.isPassive())
                     .map(PeerState::peerId))
            .sorted()
            .toList();
    }

    // --- Internal: shutdown ---

    private Promise<Unit> closePeerConnections() {
        var promises = new ArrayList<Promise<Unit>>();
        var now = System.nanoTime();
        for (var state : peers.values()) {
            state.authoritativeRemove(now)
                 .onPresent(conn -> promises.add(conn.close()));
        }
        peers.clear();
        reconnectBackoff.clear();
        if (promises.isEmpty()) {
            return Promise.unitPromise();
        }
        return Promise.allOf(promises).mapToUnit();
    }

    @SuppressWarnings("JBCT-PAT-01") // Sequential shutdown of server then client
    private Promise<Unit> stopServerAndClient(Unit ignored) {
        var serverInstance = server;
        var clientInstance = client;
        server = null;
        client = null;
        var stopServer = serverInstance != null ? serverInstance.stop() : Promise.unitPromise();
        var stopClient = clientInstance != null ? clientInstance.close() : Promise.unitPromise();
        return Promise.all(stopServer, stopClient).map((_, _) -> unit());
    }
}
