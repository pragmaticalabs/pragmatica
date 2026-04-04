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
import java.util.stream.Stream;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.concurrent.Future;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.ConnectionError;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManager;
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
public class QuicClusterNetwork implements ClusterNetwork {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterNetwork.class);

    private static final int MAX_BACKPRESSURE_QUEUE_SIZE = 100;

    private final NodeInfo self;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final TopologyManager topologyManager;
    private final MessageRouter router;
    private volatile QuicSslContext serverSslContext;
    private volatile QuicSslContext clientSslContext;

    private final Map<NodeId, QuicPeerConnection> peerLinks = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> connectionEstablishedAt = new ConcurrentHashMap<>();
    private final Set<NodeId> connectingInProgress = ConcurrentHashMap.newKeySet();
    private final Set<NodeId> passivePeers = ConcurrentHashMap.newKeySet();
    private final Map<NodeId, Map<StreamType, Queue<byte[]>>> outboundQueues = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean quorumEstablished = new AtomicBoolean(false);
    private final QuicTransportMetrics quicMetrics = QuicTransportMetrics.quicTransportMetrics();

    private volatile QuicClusterServer server;
    private volatile QuicClusterClient client;

    enum ViewChangeOperation {
        ADD,
        REMOVE,
        SHUTDOWN
    }

    public QuicClusterNetwork(TopologyManager topologyManager,
                              Serializer serializer,
                              Deserializer deserializer,
                              MessageRouter router,
                              QuicSslContext serverSslContext,
                              QuicSslContext clientSslContext) {
        this.self = topologyManager.self();
        this.topologyManager = topologyManager;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.router = router;
        this.serverSslContext = serverSslContext;
        this.clientSslContext = clientSslContext;
    }

    @Override
    public void listNodes(ListConnectedNodes listConnectedNodes) {
        router.route(new NetworkServiceMessage.ConnectedNodesList(List.copyOf(peerLinks.keySet())));
    }

    @Override
    public void handleSend(NetworkServiceMessage.Send send) {
        sendToConnection(send.target(), send.payload(), peerLinks.get(send.target()));
    }

    @Override
    public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {
        peerLinks.forEach((peerId, conn) -> sendToConnection(peerId, broadcast.payload(), conn));
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
    @SuppressWarnings("JBCT-PAT-01") // Channel protection window check
    public void disconnect(DisconnectNode disconnectNode) {
        var nodeId = disconnectNode.nodeId();
        var connection = peerLinks.get(nodeId);
        if (connection == null) {
            log.debug("DisconnectNode for {} ignored: no connection in peerLinks", nodeId);
            return;
        }
        var establishedAt = connectionEstablishedAt.getOrDefault(nodeId, 0L);
        var connectionAge = System.nanoTime() - establishedAt;
        var protectionNanos = topologyManager.helloTimeout().nanos() * 3;
        if (connectionAge < protectionNanos) {
            log.debug("DisconnectNode for {} ignored: connection is fresh (protection window)", nodeId);
            return;
        }
        if (!peerLinks.remove(nodeId, connection)) {
            log.debug("DisconnectNode for {} ignored: connection already replaced", nodeId);
            return;
        }
        connectionEstablishedAt.remove(nodeId);
        passivePeers.remove(nodeId);
        cleanupPeerQueues(nodeId);
        quicMetrics.onConnectionClosed();
        processViewChange(REMOVE, nodeId);
        connection.close()
                  .onSuccess(_ -> log.info("Node {} disconnected from node {}", self.id(), nodeId))
                  .onFailure(cause -> log.warn("Node {} failed to disconnect from node {}: {}", self.id(), nodeId, cause.message()));
    }

    @Override
    public <M extends ProtocolMessage> Unit send(NodeId peerId, M message) {
        sendToConnection(peerId, message, peerLinks.get(peerId));
        return unit();
    }

    @Override
    public <M extends ProtocolMessage> Unit broadcast(M message) {
        peerLinks.forEach((peerId, conn) -> broadcastToEligiblePeer(peerId, conn, message));
        return unit();
    }

    @Override
    public int connectedNodeCount() {
        return peerLinks.size();
    }

    @Override
    public Set<NodeId> connectedPeers() {
        return Set.copyOf(peerLinks.keySet());
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

    // --- Internal: peer connection lifecycle ---

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback chain
    private void connectPeer(NodeInfo peer) {
        var peerId = peer.id();
        if (peerLinks.containsKey(peerId)) {
            return;
        }
        // Bypass ConnectionDirection when this node has NO connections — it's joining an existing cluster
        // and must initiate contact regardless of ID ordering. Once connected, normal rules apply.
        if (!peerLinks.isEmpty() && !ConnectionDirection.shouldInitiate(self.id(), peerId)) {
            log.debug("Skipping connection to {}: higher NodeId does not initiate", peerId);
            return;
        }
        // Prevent concurrent connection attempts to the same peer (TOCTOU race between
        // containsKey check above and the async connect call below).
        if (!connectingInProgress.add(peerId)) {
            return;
        }
        var address = new InetSocketAddress(peer.address().host(), peer.address().port());
        client.connect(peerId, address)
              .onResult(_ -> connectingInProgress.remove(peerId))
              .onSuccess(conn -> onPeerConnected(conn, peer.role(), peer.address(), peer.labels()))
              .onFailure(cause -> onConnectFailed(peer, cause));
    }

    private void onConnectFailed(NodeInfo peer, Cause cause) {
        quicMetrics.onHandshakeFailure();
        log.warn("Failed to connect from {} to {}: {}", self, peer, cause.message());
        router.route(new NetworkServiceMessage.ConnectionFailed(
            peer.id(), ConnectionError.networkError(peer.address().asString(), cause.message())));
    }

    @SuppressWarnings("JBCT-PAT-01") // Multi-step peer registration
    private void onPeerConnected(QuicPeerConnection connection, NodeRole peerRole, NodeAddress peerAddress, Map<String, String> peerLabels) {
        var peerId = connection.peerId();

        // Never register self as a peer — self-connections cause removal cascades
        // (processViewChange REMOVE for self → leader re-election → CDM rebuild)
        if (peerId.equals(self.id())) {
            log.debug("Ignoring self-connection from {}", peerId);
            connection.close();
            return;
        }

        // Track passive role directly from Hello handshake
        if (peerRole == NodeRole.PASSIVE) {
            passivePeers.add(peerId);
        }

        // Check for unknown node — build NodeInfo from Hello data (NodeId, role, address, labels)
        Option<NodeInfo> unknownNodeInfo = topologyManager.get(peerId).isEmpty()
            ? buildUnknownNodeInfo(peerId, peerRole, peerAddress, peerLabels)
            : Option.empty();

        var existing = peerLinks.putIfAbsent(peerId, connection);
        if (existing != null) {
            if (!existing.isActive()) {
                // Old connection is stale — replace with the new known-good one
                peerLinks.put(peerId, connection);
                existing.close();
                log.info("Replaced stale connection for peer {} (old was inactive)", peerId);
            } else {
                // Both connections are active — keep the existing one to avoid disrupting in-flight messages
                log.debug("Duplicate connection from {}, closing new (existing is active)", peerId);
                connection.close();
                return;
            }
        }

        connectionEstablishedAt.put(peerId, System.nanoTime());
        quicMetrics.onConnectionEstablished();
        installWritabilityHandler(connection, peerId);

        // Send AddNode BEFORE ConnectionEstablished if unknown
        unknownNodeInfo.onPresent(nodeInfo -> router.route(new TopologyManagementMessage.AddNode(nodeInfo)));
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

    private void trackPassiveRole(NodeId nodeId, Option<NodeInfo> unknownNodeInfo) {
        unknownNodeInfo.orElse(() -> topologyManager.get(nodeId))
                       .filter(info -> info.role() == NodeRole.PASSIVE)
                       .onPresent(_ -> passivePeers.add(nodeId));
    }

    // --- Internal: message send ---

    private void sendToConnection(NodeId peerId, Object message, QuicPeerConnection connection) {
        if (connection == null) {
            log.warn("Node {} is not connected", peerId);
            return;
        }
        if (!connection.isActive()) {
            if (peerLinks.remove(peerId, connection)) {
                connectionEstablishedAt.remove(peerId);
                quicMetrics.onConnectionClosed();
                processViewChange(REMOVE, peerId);
            }
            log.warn("Node {} connection is not active, removed stale link", peerId);
            return;
        }
        writeToStream(peerId, message, connection);
    }

    @SuppressWarnings("JBCT-PAT-01") // Stream selection and write
    private void writeToStream(NodeId peerId, Object message, QuicPeerConnection connection) {
        var bytes = serializer.encode(message);
        var streamType = StreamType.forMessage(message);

        // Use the appropriate stream, falling back to CONSENSUS if not available
        var stream = connection.stream(streamType)
                               .fold(() -> connection.stream(StreamType.CONSENSUS), Option::some);

        stream.onPresent(ch -> writeIfWritable(ch, bytes, peerId, streamType))
              .onEmpty(() -> log.warn("No stream available for peer {}", peerId));
    }

    @SuppressWarnings("JBCT-PAT-01") // Backpressure: enqueue-or-drop + drain-then-send
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
        var staleConnection = peerLinks.remove(peerId);

        if (staleConnection != null) {
            connectionEstablishedAt.remove(peerId);
            cleanupPeerQueues(peerId);
            quicMetrics.onConnectionClosed();
            staleConnection.close()
                           .onFailure(cause -> log.warn("Error closing stale connection to peer {}: {}", peerId, cause.message()));
            processViewChange(REMOVE, peerId);
        }
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

    private <M extends ProtocolMessage> void broadcastToEligiblePeer(NodeId peerId, QuicPeerConnection conn, M message) {
        if (!passivePeers.contains(peerId) || message.deliverToPassive()) {
            sendToConnection(peerId, message, conn);
        }
    }

    // --- Internal: view change ---

    @SuppressWarnings("JBCT-PAT-01") // Switch expression with side effects
    private void processViewChange(ViewChangeOperation operation, NodeId peerId) {
        // Self should never appear in view changes — guard against cascading self-removal
        if (peerId.equals(self.id())) {
            log.warn("Ignoring view change {} for self node {}", operation, peerId);
            return;
        }
        var activePeerCount = peerLinks.size() - passivePeers.size();
        var quorumSize = topologyManager.quorumSize();
        var clusterSize = topologyManager.clusterSize();
        var currentlyHaveQuorum = (activePeerCount + 1) >= quorumSize;

        log.info("processViewChange: op={}, peer={}, activePeerCount={}, clusterSize={}, quorumSize={}, haveQuorum={}, wasEstablished={}",
                 operation, peerId, activePeerCount, clusterSize, quorumSize, currentlyHaveQuorum, quorumEstablished.get());

        var viewChange = switch (operation) {
            case ADD -> {
                if (currentlyHaveQuorum && quorumEstablished.compareAndSet(false, true)) {
                    router.route(QuorumStateNotification.established());
                }
                yield TopologyChangeNotification.nodeAdded(peerId, currentView());
            }
            case REMOVE -> {
                if (!currentlyHaveQuorum && quorumEstablished.compareAndSet(true, false)) {
                    router.route(QuorumStateNotification.disappeared());
                }
                router.route(new TopologyManagementMessage.RemoveNode(peerId));
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

    private List<NodeId> currentView() {
        return Stream.concat(
                Stream.of(self.id()),
                peerLinks.keySet().stream().filter(id -> !passivePeers.contains(id)))
            .sorted()
            .toList();
    }

    // --- Internal: shutdown ---

    private Promise<Unit> closePeerConnections() {
        var promises = new ArrayList<Promise<Unit>>();
        for (var conn : peerLinks.values()) {
            promises.add(conn.close());
        }
        peerLinks.clear();
        connectionEstablishedAt.clear();
        passivePeers.clear();
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
