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

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
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
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyObserver;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.Message;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
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
/// The [outboundQueues] map remains for Netty stream-level writability backpressure — that
/// queue is channel-specific (bytes are for the current `QuicStreamChannel`) and is wiped on
/// eviction. The [PeerState] offline buffer is peer-level and survives transient evictions so
/// consensus broadcasts delivered during a reconnect storm are not lost.
public class QuicClusterNetwork implements ClusterNetwork {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterNetwork.class);

    private static final int MAX_BACKPRESSURE_QUEUE_SIZE = 100;

    private final NodeInfo self;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final TopologyObserver topologyManager;
    private final MessageRouter router;
    private volatile QuicSslContext serverSslContext;
    private volatile QuicSslContext clientSslContext;

    private final Map<NodeId, PeerState> peers = new ConcurrentHashMap<>();
    private final Map<NodeId, Map<StreamType, Queue<byte[]>>> outboundQueues = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean quorumEstablished = new AtomicBoolean(false);
    private final QuicTransportMetrics quicMetrics = QuicTransportMetrics.quicTransportMetrics();

    /// Retained for API compatibility with existing callers. All hysteresis/grace-window
    /// buffering was removed in favour of authoritative single-writer semantics (spec §8) —
    /// `HealthReconciler` decides whether membership atoms change; the QUIC view is purely
    /// informational. Field is kept so constructors continue to accept the configuration
    /// record without forcing a callsite rewrite.
    @SuppressWarnings("unused")
    private final ClusterFormationConfig formationConfig;
    private volatile QuicDisconnectListener disconnectListener;

    /// Leader-gate supplier. When `false`, REMOVE view-changes report a
    /// connectivity observation upstream via `PeerConnectivityReporter`
    /// instead of invoking the local disconnect listener (which feeds the
    /// local `HealthReconciler`).
    /// See `aether/docs/specs/clustersync-refactor-spec.md` commit 2.
    private volatile BooleanSupplier isLeaderSupplier;
    private volatile PeerConnectivityReporter connectivityReporter;
    private volatile ObservedEpochSupplier observedEpochSupplier;

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
        SHUTDOWN
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
        this.self = topologyManager.self();
        this.topologyManager = topologyManager;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.router = router;
        this.serverSslContext = serverSslContext;
        this.clientSslContext = clientSslContext;
        this.formationConfig = formationConfig;
        this.disconnectListener = disconnectListener;
        this.isLeaderSupplier = isLeaderSupplier == null ? () -> true : isLeaderSupplier;
        this.connectivityReporter = connectivityReporter == null ? PeerConnectivityReporter.noop() : connectivityReporter;
        this.observedEpochSupplier = observedEpochSupplier == null ? ObservedEpochSupplier.zero() : observedEpochSupplier;
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

    /// Attach a QUIC-disconnect listener post-construction. Higher layers (e.g.
    /// `AetherNode`) need to wire the listener after the enclosing `RabiaNode`
    /// — which owns this network — has already been built. A `null` argument
    /// resets the listener to the no-op implementation.
    public void setDisconnectListener(QuicDisconnectListener listener) {
        this.disconnectListener = listener == null
                                 ? QuicDisconnectListener.noop()
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
                     .onFailure(this::onStartFailed)
                     .mapToUnit();
    }

    @Override
    public Promise<Unit> stop() {
        if (!isRunning.compareAndSet(true, false)) {
            return Promise.unitPromise();
        }
        log.debug("Stopping QuicClusterNetwork: notifying view change");
        processViewChange(SHUTDOWN, self.id());
        return closePeerConnections()
            .flatMap(this::stopServerAndClient);
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
    @SuppressWarnings("JBCT-PAT-01") // Channel protection window check + authoritative remove + view change
    public void disconnect(DisconnectNode disconnectNode) {
        var nodeId = disconnectNode.nodeId();
        var peer = peers.get(nodeId);
        // SWIM-driven DisconnectNode is the authoritative "this peer is gone" signal.
        // We must always propagate REMOVE to topology so the snapshot prunes the peer,
        // even if the QUIC link is already torn down. Otherwise CTM-provisioned
        // replacements that die mid-flight stay in coreNodes forever.
        if (peer != null && peer.phase() == PeerState.Phase.CONNECTED) {
            var protectionNanos = topologyManager.helloTimeout().nanos() * 3;
            if (peer.phaseAgeNanos(System.nanoTime()) < protectionNanos) {
                log.debug("DisconnectNode for {} ignored: connection is fresh (protection window)", nodeId);
                return;
            }
        }
        // Authoritative removal is idempotent; always fire. Drops the offline buffer.
        if (peer != null) {
            peer.authoritativeRemove(System.nanoTime())
                .onPresent(this::closeDroppedConnection);
        }
        // Channel-level writability backpressure queue is bytes-for-the-dead-channel; wipe.
        cleanupPeerQueues(nodeId);
        quicMetrics.onConnectionClosed();
        processViewChange(REMOVE, nodeId);
    }

    private void closeDroppedConnection(QuicPeerConnection connection) {
        connection.close()
                  .onFailure(cause -> log.warn("Failed to close dropped connection for peer {}: {}",
                                               connection.peerId(), cause.message()));
    }

    @Override
    public <M extends ProtocolMessage> Unit send(NodeId peerId, M message) {
        dispatchPayload(peerId, message);
        return unit();
    }

    @Override
    public <M extends ProtocolMessage> Unit broadcast(M message) {
        broadcastPayload(message, !message.deliverToPassive());
        return unit();
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
        quicMetrics.onMessageReceived();
        if (message instanceof Message.Wired wired) {
            router.route(wired);
        } else {
            log.trace("Non-routable message from {}: {}", sender, option(message).map(Object::getClass).map(Class::getSimpleName));
        }
    }

    // --- Internal: peer state lookup ---

    private PeerState getOrCreatePeer(NodeId peerId) {
        return peers.computeIfAbsent(peerId, id -> PeerState.peerState(id, System.nanoTime()));
    }

    // --- Internal: peer connection lifecycle ---

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
        var state = getOrCreatePeer(peerId);
        if (!state.beginConnecting(System.nanoTime())) {
            // Already CONNECTING, CONNECTED, or REMOVED — nothing to do.
            return;
        }
        var address = new InetSocketAddress(peer.address().host(), peer.address().port());
        client.connect(peerId, address)
              .onSuccess(conn -> onPeerConnected(conn, peer.role(), peer.address(), peer.labels()))
              .onFailure(cause -> onConnectFailed(peer, cause));
    }

    private void onConnectFailed(NodeInfo peer, Cause cause) {
        quicMetrics.onHandshakeFailure();
        log.warn("Failed to connect from {} to {}: {}", self, peer, cause.message());
        // Reset phase to EVICTED so a subsequent retry (via topology reconciler) can re-enter CONNECTING.
        var state = peers.get(peer.id());
        if (state != null && state.phase() == PeerState.Phase.CONNECTING) {
            state.evict(System.nanoTime());
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

        var outcome = state.attach(connection, System.nanoTime());
        switch (outcome) {
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
            case PeerState.AttachResult.ACCEPTED -> quicMetrics.onConnectionEstablished();
        }

        installWritabilityHandler(connection, peerId);
        drainOfflineBufferInto(state, connection);

        // Register BEFORE ConnectionEstablished if unknown — direct call on topology observer.
        unknownNodeInfo.onPresent(topologyManager::registerPeer);
        router.route(new NetworkServiceMessage.ConnectionEstablished(peerId));
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
        var stream = connection.stream(StreamType.CONSENSUS);
        if (stream.isEmpty()) {
            log.warn("Cannot drain {} offline messages for peer {} — no CONSENSUS stream",
                     drained.size(), state.peerId());
            return;
        }
        var ch = stream.unwrap();
        for (var bytes : drained) {
            writeIfWritable(ch, bytes, state.peerId(), StreamType.CONSENSUS);
        }
        log.debug("Drained {} offline messages to newly-connected peer {}", drained.size(), state.peerId());
    }

    // --- Internal: message send ---

    /// Dispatch a typed message to a single peer — runs through the PeerState machine:
    /// SendNow → write to captured connection; Queued → buffered for reconnect; Dropped → REMOVED.
    private void dispatchPayload(NodeId peerId, Object message) {
        var state = peers.get(peerId);
        if (state == null) {
            log.debug("No peer state for {} — dropping message", peerId);
            return;
        }
        var bytes = serializer.encode(message);
        dispatchSerialized(state, message, bytes);
    }

    /// Broadcast a typed message to all known peers. When `skipPassive` is true, peers whose
    /// role is PASSIVE are filtered (used by `broadcast(ProtocolMessage)` when the message
    /// opts out of passive delivery via `deliverToPassive() == false`).
    ///
    /// Serialization is lazy — performed at most once, and only when at least one eligible peer
    /// is about to receive the message. This keeps `broadcast` a true no-op when `peers` is
    /// empty or all peers are filtered, preserving test fixtures that broadcast unregistered
    /// codec types in isolation.
    @SuppressWarnings("JBCT-PAT-01") // Iterate, lazy-serialize on first eligible, dispatch
    private void broadcastPayload(Object message, boolean skipPassive) {
        byte[] bytes = null;
        for (var state : peers.values()) {
            if (skipPassive && state.isPassive()) {
                continue;
            }
            if (bytes == null) {
                bytes = serializer.encode(message);
            }
            dispatchSerialized(state, message, bytes);
        }
    }

    @SuppressWarnings("JBCT-PAT-01") // Outcome dispatch with metrics + write
    private void dispatchSerialized(PeerState state, Object message, byte[] bytes) {
        var outcome = state.offerOutbound(bytes);
        switch (outcome) {
            case PeerState.OfferOutcome.SendNow(QuicPeerConnection connection) ->
                writeToStream(state.peerId(), message, bytes, connection);
            case PeerState.OfferOutcome.Queued(boolean oldestEvicted) -> {
                quicMetrics.onBackpressureQueued();
                if (oldestEvicted) {
                    quicMetrics.onBackpressureDrop();
                    log.debug("Offline buffer for peer {} at capacity — dropped oldest", state.peerId());
                }
            }
            case PeerState.OfferOutcome.Dropped ignored ->
                log.debug("Message to REMOVED peer {} dropped", state.peerId());
        }
    }

    @SuppressWarnings("JBCT-PAT-01") // Stream selection and write
    private void writeToStream(NodeId peerId, Object message, byte[] bytes, QuicPeerConnection connection) {
        if (!connection.isActive()) {
            // Connection went dead between offerOutbound capture and write. Evict and re-dispatch
            // so the bytes land in the offline buffer for the next attach.
            evictStaleConnection(peerId, connection);
            var state = peers.get(peerId);
            if (state != null) {
                dispatchSerialized(state, message, bytes);
            }
            return;
        }
        var streamType = StreamType.forMessage(message);
        var stream = connection.stream(streamType)
                               .fold(() -> connection.stream(StreamType.CONSENSUS), Option::some);
        stream.onPresent(ch -> writeIfWritable(ch, bytes, peerId, streamType))
              .onEmpty(() -> log.warn("No stream available for peer {}", peerId));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty-writability: drain-then-send or enqueue
    private void writeIfWritable(QuicStreamChannel ch, byte[] bytes, NodeId peerId, StreamType streamType) {
        if (!ch.isWritable()) {
            enqueueOrDrop(bytes, peerId, streamType);
            return;
        }
        drainQueue(ch, peerId, streamType);
        quicMetrics.onMessageSent();
        ch.writeAndFlush(Unpooled.wrappedBuffer(bytes))
          .addListener(future -> handleWriteResult(future, peerId, streamType));
    }

    private void enqueueOrDrop(byte[] bytes, NodeId peerId, StreamType streamType) {
        var queue = getOrCreateQueue(peerId, streamType);
        if (queue.size() < MAX_BACKPRESSURE_QUEUE_SIZE) {
            queue.offer(bytes);
            quicMetrics.onBackpressureQueued();
            log.trace("Channel to peer {} not writable, queued message on stream {}", peerId, streamType);
        } else {
            quicMetrics.onBackpressureDrop();
            log.warn("Backpressure queue full for peer {} stream {}, dropping message", peerId, streamType);
        }
    }

    private Queue<byte[]> getOrCreateQueue(NodeId peerId, StreamType streamType) {
        return outboundQueues.computeIfAbsent(peerId, _ -> new ConcurrentHashMap<>())
                             .computeIfAbsent(streamType, _ -> new ConcurrentLinkedQueue<>());
    }

    private void drainQueue(QuicStreamChannel ch, NodeId peerId, StreamType streamType) {
        var peerQueues = outboundQueues.get(peerId);
        if (peerQueues == null) {
            return;
        }
        var queue = peerQueues.get(streamType);
        if (queue == null) {
            return;
        }
        drainQueueMessages(ch, queue, peerId, streamType);
    }

    private void drainQueueMessages(QuicStreamChannel ch, Queue<byte[]> queue, NodeId peerId, StreamType streamType) {
        byte[] queued;
        while (ch.isWritable() && (queued = queue.poll()) != null) {
            quicMetrics.onBackpressureDrained();
            quicMetrics.onMessageSent();
            ch.writeAndFlush(Unpooled.wrappedBuffer(queued))
              .addListener(future -> handleWriteResult(future, peerId, streamType));
        }
    }

    /// Called when a channel becomes writable again — drains queued messages for the peer/stream.
    void onChannelWritable(NodeId peerId, StreamType streamType, QuicStreamChannel ch) {
        drainQueue(ch, peerId, streamType);
    }

    /// Install a writability handler on the consensus stream to drain backpressure queues
    /// when the channel becomes writable again.
    private void installWritabilityHandler(QuicPeerConnection connection, NodeId peerId) {
        connection.stream(StreamType.CONSENSUS)
                  .onPresent(ch -> addWritabilityHandler(ch, peerId, StreamType.CONSENSUS));
    }

    private void addWritabilityHandler(QuicStreamChannel ch, NodeId peerId, StreamType streamType) {
        ch.pipeline().addLast("backpressure-drain", new BackpressureDrainHandler(peerId, streamType));
    }

    /// Netty handler that drains queued messages when a channel becomes writable.
    private class BackpressureDrainHandler extends ChannelInboundHandlerAdapter {
        private final NodeId peerId;
        private final StreamType streamType;

        BackpressureDrainHandler(NodeId peerId, StreamType streamType) {
            this.peerId = peerId;
            this.streamType = streamType;
        }

        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) throws Exception {
            if (ctx.channel().isWritable()) {
                onChannelWritable(peerId, streamType, (QuicStreamChannel) ctx.channel());
            }
            super.channelWritabilityChanged(ctx);
        }
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
        // peer removal (unregisterPeer + processViewChange(REMOVE)). Clearing peer state
        // here races with handshake completion from the reverse direction and prematurely
        // drops the peer from Rabia's membership view.
        log.debug("Write to {} failed; deferring removal to QUIC channel lifecycle", peerId);
    }

    @SuppressWarnings("JBCT-PAT-01") // Queue cleanup with size tracking
    private void cleanupPeerQueues(NodeId peerId) {
        var peerQueues = outboundQueues.remove(peerId);
        if (peerQueues != null) {
            var totalDropped = peerQueues.values()
                                         .stream()
                                         .mapToInt(Queue::size)
                                         .sum();
            if (totalDropped > 0) {
                quicMetrics.onBackpressureQueueCleared(totalDropped);
                log.debug("Cleaned up {} queued messages for disconnected peer {}", totalDropped, peerId);
            }
        }
    }

    @SuppressWarnings("JBCT-PAT-01") // Evict transition + channel close + reconnect attempt
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
        // Wipe channel-level writability backpressure — bytes were for the dead channel.
        cleanupPeerQueues(peerId);
        quicMetrics.onConnectionClosed();
        evicted.onPresent(this::closeDroppedConnection);
        log.warn("Node {} evicted stale (inactive) link — peer remains in topology, offline buffer preserved for reconnect",
                 peerId);
        // Re-dial the peer if we are the initiator side. Higher NodeIds wait for inbound.
        if (ConnectionDirection.shouldInitiate(self.id(), peerId)) {
            topologyManager.get(peerId).onPresent(this::connectPeer);
        }
        // Explicit use of the `connection` parameter to satisfy the API contract — the
        // identity check already happened inside `state.evict()` which matches by phase.
        if (connection != null && connection.isActive()) {
            connection.close();
        }
    }

    // --- Internal: view change ---

    /// Authoritative membership state for QUIC lives in `TopologyObserver`. This method:
    ///   - ADD: registers the peer with `TopologyObserver` (already done at `onPeerConnected`) and
    ///     emits an informational `TopologyChangeNotification.nodeAdded`. If quorum is now reached,
    ///     fires `QuorumStateNotification.established` immediately.
    ///   - REMOVE: fires a `HealthSignal.QuicDisconnect` via the listener (advisory; leader's
    ///     `HealthReconciler` counts it), calls `topologyManager.unregisterPeer` directly (spec §12:
    ///     QUIC is the authoritative source of "this peer is gone from my view"), and emits an
    ///     informational `TopologyChangeNotification.nodeRemoved`. No hysteresis buffering — §8
    ///     single-writer rule means `HealthReconciler` is the only component that decides whether
    ///     membership atoms actually change.
    ///   - SHUTDOWN: fires `QuorumStateNotification.disappeared` immediately.
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
        var currentlyHaveQuorum = (activePeerCount + 1) >= quorumSize;

        log.info("processViewChange: op={}, peer={}, activePeerCount={}, clusterSize={}, quorumSize={}, haveQuorum={}, wasEstablished={}",
                 operation, peerId, activePeerCount, clusterSize, quorumSize, currentlyHaveQuorum, quorumEstablished.get());

        var viewChange = switch (operation) {
            case ADD -> {
                if (currentlyHaveQuorum && quorumEstablished.compareAndSet(false, true)) {
                    log.info("Quorum established with {} active peer(s) (need {})", activePeerCount, quorumSize);
                    router.route(QuorumStateNotification.established());
                }
                yield TopologyChangeNotification.nodeAdded(peerId, currentView());
            }
            case REMOVE -> {
                // Advisory QUIC-level disconnect signal. On the leader this feeds the local
                // `HealthReconciler`; on a follower the observation is buffered into the next
                // outbound `ClusterSyncPong` so the leader folds it through PeerObservationReducer
                // (ClusterSync refactor commit 2 — followers are sensor-only).
                // `topologyManager.unregisterPeer` stays put on both roles: it is local transport
                // hygiene (drops the peer from the QUIC peer table), not a cluster-membership decision.
                reportPeerRemoval(peerId);
                topologyManager.unregisterPeer(peerId);
                if (!currentlyHaveQuorum && quorumEstablished.compareAndSet(true, false)) {
                    log.warn("Quorum lost — {} active peer(s), need {}", activePeerCount, quorumSize);
                    router.route(QuorumStateNotification.disappeared());
                }
                yield TopologyChangeNotification.nodeRemoved(peerId, currentView());
            }
            case SHUTDOWN -> {
                quorumEstablished.set(false);
                router.route(QuorumStateNotification.disappeared());
                yield TopologyChangeNotification.nodeDown(peerId);
            }
        };

        log.info("Routing topology change: {}", viewChange);
        router.route(viewChange);
    }

    private int activeConnectedCount() {
        return (int) peers.values()
                          .stream()
                          .filter(p -> p.phase() == PeerState.Phase.CONNECTED && !p.isPassive())
                          .count();
    }

    private void reportPeerRemoval(NodeId peerId) {
        if (isLeaderSupplier.getAsBoolean()) {
            disconnectListener.onDisconnect(peerId);
            return;
        }
        var epoch = observedEpochSupplier;
        connectivityReporter.onPeerDisconnected(peerId, epoch.term(), epoch.counter());
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
        outboundQueues.clear();
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
