package org.pragmatica.consensus.net.netty;

import org.pragmatica.consensus.ProtocolMessage;
import org.pragmatica.consensus.net.ClusterNetwork;
import org.pragmatica.consensus.net.ConnectionError;
import org.pragmatica.consensus.net.NetworkServiceMessage;
import org.pragmatica.consensus.net.NetworkServiceMessage.ListConnectedNodes;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NetworkMessage.Hello;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.consensus.topology.QuorumStateNotification;
import org.pragmatica.consensus.topology.TopologyChangeNotification;
import org.pragmatica.consensus.topology.TopologyManagementMessage;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.lang.utils.SharedScheduler;
import org.pragmatica.messaging.Message;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.Server;
import org.pragmatica.net.tcp.ServerConfig;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.NetworkServiceMessage.ConnectNode;
import static org.pragmatica.consensus.net.NetworkServiceMessage.DisconnectNode;
import static org.pragmatica.consensus.net.netty.NettyClusterNetwork.ViewChangeOperation.*;

/// Manages network connections between nodes using Netty.
public class NettyClusterNetwork implements ClusterNetwork {
    private static final Logger log = LoggerFactory.getLogger(NettyClusterNetwork.class);
    private static final double SCALE = 0.3d;
    private static final int LENGTH_FIELD_LEN = 4;
    private static final int INITIAL_BYTES_TO_STRIP = LENGTH_FIELD_LEN;

    /// Default minimum age a channel must have before DisconnectNode can remove it.
    /// Channels younger than this are considered fresh reconnections that should survive
    /// SWIM's delayed FAULTY detection.
    public static final TimeSpan DEFAULT_CHANNEL_PROTECTION = TimeSpan.timeSpan(15).seconds();

    private final NodeInfo self;
    private final TimeSpan channelProtection;
    private final Map<NodeId, Channel> peerLinks = new ConcurrentHashMap<>();
    private final Map<NodeId, Long> channelEstablishedAt = new ConcurrentHashMap<>();
    private final Set<NodeId> passivePeers = ConcurrentHashMap.newKeySet();
    private final Map<Channel, NodeId> channelToNodeId = new ConcurrentHashMap<>();
    private final Set<Channel> pendingChannels = ConcurrentHashMap.newKeySet();
    private final Map<Channel, ScheduledFuture<?>> helloTimeouts = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final AtomicBoolean quorumEstablished = new AtomicBoolean(false);
    private final TopologyManager topologyManager;
    private final Supplier<List<ChannelHandler>> handlers;
    private final MessageRouter router;
    private final AtomicReference<Server> server = new AtomicReference<>();

    enum ViewChangeOperation {
        ADD,
        REMOVE,
        SHUTDOWN
    }

    public NettyClusterNetwork(TopologyManager topologyManager,
                               Serializer serializer,
                               Deserializer deserializer,
                               MessageRouter router) {
        this(topologyManager, serializer, deserializer, router, List.of(), DEFAULT_CHANNEL_PROTECTION);
    }

    public NettyClusterNetwork(TopologyManager topologyManager,
                               Serializer serializer,
                               Deserializer deserializer,
                               MessageRouter router,
                               List<ChannelHandler> additionalHandlers) {
        this(topologyManager, serializer, deserializer, router, additionalHandlers, DEFAULT_CHANNEL_PROTECTION);
    }

    public NettyClusterNetwork(TopologyManager topologyManager,
                               Serializer serializer,
                               Deserializer deserializer,
                               MessageRouter router,
                               List<ChannelHandler> additionalHandlers,
                               TimeSpan channelProtection) {
        this.self = topologyManager.self();
        this.topologyManager = topologyManager;
        this.router = router;
        this.channelProtection = channelProtection;
        this.handlers = () -> {
            var result = new ArrayList<ChannelHandler>();
            result.add(new LengthFieldBasedFrameDecoder(1048576, 0, LENGTH_FIELD_LEN, 0, INITIAL_BYTES_TO_STRIP));
            result.add(new LengthFieldPrepender(LENGTH_FIELD_LEN));
            result.add(new Decoder(deserializer));
            result.add(new Encoder(serializer));
            result.addAll(additionalHandlers);
            result.add(new Handler(this::peerConnected, this::peerDisconnected, this::handleHello, router::route));
            return result;
        };
    }

    @Override
    public void listNodes(ListConnectedNodes listConnectedNodes) {
        router.route(new NetworkServiceMessage.ConnectedNodesList(List.copyOf(peerLinks.keySet())));
    }

    @Override
    public void handleSend(NetworkServiceMessage.Send send) {
        sendToChannel(send.target(),
                      send.payload(),
                      peerLinks.get(send.target()));
    }

    @Override
    public void handleBroadcast(NetworkServiceMessage.Broadcast broadcast) {
        peerLinks.forEach((peerId, channel) -> sendToChannel(peerId, broadcast.payload(), channel));
    }

    private void peerConnected(Channel channel) {
        pendingChannels.add(channel);
        channel.writeAndFlush(new Hello(self.id(), self.role()));
        scheduleHelloTimeout(channel);
        log.debug("Channel active, sent Hello and waiting for response: {}", channel.remoteAddress());
    }

    private void scheduleHelloTimeout(Channel channel) {
        var timeout = SharedScheduler.schedule(() -> onHelloTimeout(channel), topologyManager.helloTimeout());
        helloTimeouts.put(channel, timeout);
    }

    private void onHelloTimeout(Channel channel) {
        helloTimeouts.remove(channel);
        if (pendingChannels.remove(channel)) {
            log.warn("Hello timeout for channel {}, closing", channel.remoteAddress());
            channel.close();
            topologyManager.reverseLookup(channel.remoteAddress())
                           .onPresent(nodeId -> router.route(new NetworkServiceMessage.ConnectionFailed(nodeId,
                                                                                                        ConnectionError.helloTimeout(channel.remoteAddress()
                                                                                                                                            .toString()))));
        }
    }

    private void handleHello(Hello hello, Channel channel) {
        Option.option(helloTimeouts.remove(channel))
              .onPresent(timeout -> timeout.cancel(false));
        if (!pendingChannels.remove(channel)) {
            log.debug("Received Hello from {} on already established channel", hello.sender());
            return;
        }
        // Check if unknown node - construct NodeInfo if so
        Option<NodeInfo> unknownNodeInfo = Option.none();
        if (topologyManager.get(hello.sender())
                           .isEmpty()) {
            var addressResult = NodeAddress.nodeAddress((java.net.InetSocketAddress) channel.remoteAddress());
            if (addressResult.isFailure()) {
                log.warn("Cannot add unknown node {}: invalid address", hello.sender());
                channel.close();
                return;
            }
            unknownNodeInfo = addressResult.map(addr -> new NodeInfo(hello.sender(),
                                                                     addr,
                                                                     hello.role()))
                                           .option();
            log.info("Unknown node {} connecting from {}", hello.sender(), channel.remoteAddress());
        }
        // Normal registration
        if (Option.option(peerLinks.putIfAbsent(hello.sender(),
                                                channel))
                  .isPresent()) {
            log.debug("Duplicate connection from {}, closing new channel", hello.sender());
            channel.close();
            return;
        }
        channelToNodeId.put(channel, hello.sender());
        channelEstablishedAt.put(hello.sender(), System.nanoTime());
        // Track passive peers
        trackPassiveRole(hello.sender(), unknownNodeInfo);
        // Send AddNode BEFORE ConnectionEstablished if unknown
        unknownNodeInfo.onPresent(nodeInfo -> router.route(new TopologyManagementMessage.AddNode(nodeInfo)));
        router.route(new NetworkServiceMessage.ConnectionEstablished(hello.sender()));
        processViewChange(ADD, hello.sender());
        // Initiate topology discovery only for unknown nodes
        unknownNodeInfo.onPresent(_ -> router.route(new NetworkServiceMessage.Send(hello.sender(),
                                                                                   new NetworkMessage.DiscoverNodes(self.id()))));
        log.debug("Node {} connected via Hello handshake", hello.sender());
    }

    private void trackPassiveRole(NodeId nodeId, Option<NodeInfo> unknownNodeInfo) {
        // For unknown nodes, check the constructed NodeInfo; for known nodes, look up topology
        unknownNodeInfo.orElse(() -> topologyManager.get(nodeId))
                       .filter(info -> info.role() == NodeRole.PASSIVE)
                       .onPresent(_ -> passivePeers.add(nodeId));
    }

    private void peerDisconnected(Channel channel) {
        helloTimeouts.remove(channel);
        pendingChannels.remove(channel);
        Option.option(channelToNodeId.remove(channel))
              .filter(nodeId -> peerLinks.remove(nodeId, channel))
              .onPresent(nodeId -> {
                             passivePeers.remove(nodeId);
                             channelEstablishedAt.remove(nodeId);
                             // Only process view change if quorum was already established.
                             // This prevents quorum oscillation during initial startup while
                             // ensuring proper quorum tracking after partition/disconnect events.
                             if (quorumEstablished.get()) {
                                 processViewChange(REMOVE, nodeId);
                             }
                             log.info("Node {} TCP disconnected, quorumWasEstablished={}", nodeId, quorumEstablished.get());
                         });
    }

    @Override
    public Promise<Unit> start() {
        if (isRunning.compareAndSet(false, true)) {
            var serverConfig = ServerConfig.serverConfig("NettyClusterNetwork",
                                                         self.address()
                                                             .port());
            // Apply TLS for both incoming (server) and outgoing (client) connections
            var effectiveConfig = topologyManager.tls()
                                                 .map(tls -> serverConfig.withTls(tls)
                                                                         .withClientTls(tls))
                                                 .or(serverConfig);
            return Server.server(effectiveConfig, handlers)
                         .onSuccess(NettyClusterNetwork.this.server::set)
                         .onFailure(_ -> isRunning.set(false))
                         .mapToUnit();
        }
        return Promise.unitPromise();
    }

    @Override
    public Promise<Unit> stop() {
        if (isRunning.compareAndSet(true, false)) {
            var serverInstance = server.get();
            if (serverInstance == null) {
                return Promise.unitPromise();
            }
            log.debug("Stopping {}: notifying view change", serverInstance.name());
            processViewChange(SHUTDOWN, self.id());
            return serverInstance.stop(this::onStop);
        }
        return Promise.unitPromise();
    }

    private Promise<Unit> onStop() {
        var serverInstance = server.get();
        var serverName = serverInstance != null
                         ? serverInstance.name()
                         : "unknown";
        log.debug("Stopping {}: closing peer connections", serverName);
        var promises = new ArrayList<Promise<Unit>>();
        for (var link : peerLinks.values()) {
            var promise = Promise.<Unit>promise();
            link.close()
                .addListener(future -> {
                                 if (future.isSuccess()) {
                                     promise.succeed(Unit.unit());
                                 } else {
                                     promise.fail(Causes.fromThrowable(future.cause()));
                                 }
                             });
            promises.add(promise);
        }
        return Promise.allOf(promises)
                      .mapToUnit();
    }

    @Override
    public void connect(ConnectNode connectNode) {
        server().onEmpty(() -> log.error("Attempt to connect {} while node is not running",
                                         connectNode.node()))
              .filter(_ -> !connectNode.node()
                                       .equals(self.id()))
              .onPresent(_ -> topologyManager.get(connectNode.node())
                                             .onPresent(this::connectPeer)
                                             .onEmpty(() -> log.error("Unknown {}",
                                                                      connectNode.node())));
    }

    private void connectPeer(NodeInfo peer) {
        var peerId = peer.id();
        if (peerLinks.containsKey(peerId)) {
            return;
        }
        server.get()
              .connectTo(peer.address())
              .trace()
              .onFailure(cause -> {
                             log.warn("Failed to connect from {} to {}: {}", self, peer, cause);
                             router.route(new NetworkServiceMessage.ConnectionFailed(peerId,
                                                                                     ConnectionError.networkError(peer.address()
                                                                                                                      .toString(),
                                                                                                                  cause.message())));
                         });
    }

    @Override
    public void disconnect(DisconnectNode disconnectNode) {
        var nodeId = disconnectNode.nodeId();
        var channel = peerLinks.get(nodeId);
        if (channel == null) {
            log.debug("DisconnectNode for {} ignored: no channel in peerLinks", nodeId);
            return;
        }
        // Protect fresh channels from SWIM's delayed FAULTY detection.
        // After a partition heals, the node reconnects. SWIM's FAULTY detection may arrive
        // after the fresh connection is established. Channels younger than the protection
        // window are guaranteed to be post-partition reconnections, not zombies.
        var establishedAt = channelEstablishedAt.getOrDefault(nodeId, 0L);
        var channelAge = System.nanoTime() - establishedAt;
        var protectionNanos = channelProtection.nanos();
        if (channelAge < protectionNanos) {
            log.debug("DisconnectNode for {} ignored: channel is {}ms old (protection window={}ms)",
                      nodeId,
                      java.time.Duration.ofNanos(channelAge).toMillis(),
                      channelProtection.millis());
            return;
        }
        if (!peerLinks.remove(nodeId, channel)) {
            log.debug("DisconnectNode for {} ignored: channel already replaced", nodeId);
            return;
        }
        channelToNodeId.remove(channel);
        channelEstablishedAt.remove(nodeId);
        passivePeers.remove(nodeId);
        processViewChange(REMOVE, nodeId);
        channel.close()
               .addListener(future -> {
                                if (future.isSuccess()) {
                                    log.info("Node {} disconnected from node {}",
                                             self.id(), nodeId);
                                } else {
                                    log.warn("Node {} failed to disconnect from node {}: ",
                                             self.id(), nodeId, future.cause());
                                }
                            });
    }

    @Override
    public <M extends ProtocolMessage> Unit send(NodeId peerId, M message) {
        sendToChannel(peerId, message, peerLinks.get(peerId));
        return Unit.unit();
    }

    private <M extends Message.Wired> void sendToChannel(NodeId peerId, M message, Channel channel) {
        Option.option(channel)
              .onEmpty(() -> log.warn("Node {} is not connected", peerId))
              .onPresent(ch -> {
                             if (!ch.isActive()) {
                                 // Use conditional remove to avoid removing a different channel
        if (peerLinks.remove(peerId, ch)) {
                                     channelToNodeId.remove(ch);
                                     processViewChange(REMOVE, peerId);
                                 }
                                 log.warn("Node {} is not active", peerId);
                             } else {
                                 ch.writeAndFlush(message);
                             }
                         });
    }

    @Override
    public <M extends ProtocolMessage> Unit broadcast(M message) {
        peerLinks.forEach((peerId, channel) -> broadcastToEligiblePeer(peerId, channel, message));
        return Unit.unit();
    }

    private <M extends ProtocolMessage> void broadcastToEligiblePeer(NodeId peerId, Channel channel, M message) {
        if (!passivePeers.contains(peerId) || message.deliverToPassive()) {
            sendToChannel(peerId, message, channel);
        }
    }

    private void processViewChange(ViewChangeOperation operation, NodeId peerId) {
        var activePeerCount = peerLinks.size() - passivePeers.size();
        var quorumSize = topologyManager.quorumSize();
        var clusterSize = topologyManager.clusterSize();
        var currentlyHaveQuorum = (activePeerCount + 1) >= quorumSize;
        log.info("processViewChange: op={}, peer={}, activePeerCount={}, clusterSize={}, quorumSize={}, haveQuorum={}, wasEstablished={}",
                 operation,
                 peerId,
                 activePeerCount,
                 clusterSize,
                 quorumSize,
                 currentlyHaveQuorum,
                 quorumEstablished.get());
        var viewChange = switch (operation) {
            case ADD -> {
                // Only notify on transition from below to at/above quorum
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
        // Include self in the view; exclude passive peers from leader election
        return java.util.stream.Stream.concat(java.util.stream.Stream.of(self.id()),
                                              peerLinks.keySet()
                                                       .stream()
                                                       .filter(id -> !passivePeers.contains(id)))
                   .sorted()
                   .toList();
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
        return Option.option(server.get());
    }
}
