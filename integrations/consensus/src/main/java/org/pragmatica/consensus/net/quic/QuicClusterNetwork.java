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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.quic.QuicSslContext;
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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
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

    private final NodeInfo self;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final TopologyManager topologyManager;
    private final MessageRouter router;
    private final QuicSslContext serverSslContext;
    private final QuicSslContext clientSslContext;

    private final Map<NodeId, QuicPeerConnection> peerLinks = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> connectionEstablishedAt = new ConcurrentHashMap<>();
    private final Set<NodeId> passivePeers = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean quorumEstablished = new AtomicBoolean(false);

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
            self.id(), self.role(), serializer, deserializer,
            serverSslContext, Option.empty(), this::onPeerConnected
        );
        client = QuicClusterClient.quicClusterClient(
            self.id(), self.role(), serializer, deserializer,
            clientSslContext, Option.empty()
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

    /// Get the actual UDP port the QUIC server is bound to.
    /// Useful when started on port 0 (OS-assigned).
    Option<Integer> boundPort() {
        var srv = server;
        return srv != null ? srv.boundPort() : Option.empty();
    }

    private void onStartFailed(Cause cause) {
        log.error("Failed to start QUIC server: {}", cause.message());
        isRunning.set(false);
    }

    // --- Internal: peer connection lifecycle ---

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback chain
    private void connectPeer(NodeInfo peer) {
        var peerId = peer.id();
        if (peerLinks.containsKey(peerId)) {
            return;
        }
        if (!ConnectionDirection.shouldInitiate(self.id(), peerId)) {
            log.debug("Skipping connection to {}: higher NodeId does not initiate", peerId);
            return;
        }
        var address = new InetSocketAddress(peer.address().host(), peer.address().port());
        client.connect(peerId, address)
              .onSuccess(this::onPeerConnected)
              .onFailure(cause -> onConnectFailed(peer, cause));
    }

    private void onConnectFailed(NodeInfo peer, Cause cause) {
        log.warn("Failed to connect from {} to {}: {}", self, peer, cause.message());
        router.route(new NetworkServiceMessage.ConnectionFailed(
            peer.id(), ConnectionError.networkError(peer.address().asString(), cause.message())));
    }

    @SuppressWarnings("JBCT-PAT-01") // Multi-step peer registration
    private void onPeerConnected(QuicPeerConnection connection) {
        var peerId = connection.peerId();

        // Check for unknown node
        Option<NodeInfo> unknownNodeInfo = topologyManager.get(peerId).isEmpty()
            ? buildUnknownNodeInfo(connection)
            : Option.empty();

        if (option(peerLinks.putIfAbsent(peerId, connection)).isPresent()) {
            log.debug("Duplicate connection from {}, closing new connection", peerId);
            connection.close();
            return;
        }

        connectionEstablishedAt.put(peerId, System.nanoTime());
        trackPassiveRole(peerId, unknownNodeInfo);

        // Send AddNode BEFORE ConnectionEstablished if unknown
        unknownNodeInfo.onPresent(nodeInfo -> router.route(new TopologyManagementMessage.AddNode(nodeInfo)));
        router.route(new NetworkServiceMessage.ConnectionEstablished(peerId));
        processViewChange(ADD, peerId);

        // Initiate topology discovery only for unknown nodes
        unknownNodeInfo.onPresent(_ -> router.route(new NetworkServiceMessage.Send(
            peerId, new NetworkMessage.DiscoverNodes(self.id()))));

        log.debug("Node {} connected via QUIC Hello handshake", peerId);
    }

    private Option<NodeInfo> buildUnknownNodeInfo(QuicPeerConnection connection) {
        // QUIC connections use QuicConnectionAddress, not InetSocketAddress.
        // For unknown nodes connecting inbound, we cannot reliably extract a routable address.
        // The peer will be discovered via topology discovery protocol instead.
        log.info("Unknown node {} connected via QUIC; awaiting topology discovery", connection.peerId());
        return Option.empty();
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
                processViewChange(REMOVE, peerId);
            }
            log.warn("Node {} connection is not active", peerId);
            return;
        }
        writeToStream(peerId, message, connection);
    }

    @SuppressWarnings("JBCT-PAT-01") // Stream selection and write
    private void writeToStream(NodeId peerId, Object message, QuicPeerConnection connection) {
        var bytes = serializer.encode(message);
        // Use consensus stream (0) for all messages on long-lived connections.
        // Stream routing by message type will be added when short-lived streams are implemented.
        connection.stream(StreamType.CONSENSUS)
                  .onPresent(stream -> stream.writeAndFlush(Unpooled.wrappedBuffer(bytes)))
                  .onEmpty(() -> log.warn("No consensus stream available for peer {}", peerId));
    }

    private <M extends ProtocolMessage> void broadcastToEligiblePeer(NodeId peerId, QuicPeerConnection conn, M message) {
        if (!passivePeers.contains(peerId) || message.deliverToPassive()) {
            sendToConnection(peerId, message, conn);
        }
    }

    // --- Internal: view change ---

    @SuppressWarnings("JBCT-PAT-01") // Switch expression with side effects
    private void processViewChange(ViewChangeOperation operation, NodeId peerId) {
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
