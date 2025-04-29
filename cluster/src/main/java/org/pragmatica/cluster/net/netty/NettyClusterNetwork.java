package org.pragmatica.cluster.net.netty;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.pragmatica.cluster.consensus.ProtocolMessage;
import org.pragmatica.cluster.net.ViewChange;
import org.pragmatica.cluster.net.*;
import org.pragmatica.lang.Option;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.pragmatica.cluster.net.netty.NettyClusterNetwork.ViewChangeOperation.*;

/**
 * Manages network connections between nodes using Netty.
 */
public class NettyClusterNetwork<T extends ProtocolMessage> implements ClusterNetwork<T> {
    private static final Logger logger = LoggerFactory.getLogger(NettyClusterNetwork.class);

    private final NodeId self;
    private final Map<NodeId, Channel> peerLinks = new ConcurrentHashMap<>();
    private final AtomicBoolean isRunning = new AtomicBoolean(false);
    private final int port;
    private final AddressBook addressBook;
    private final Supplier<List<ChannelHandler>> handlers;

    private Server server;
    private Option<Consumer<ViewChange>> viewObserver = Option.empty();
    private Option<Consumer<T>> messageListener = Option.empty();

    enum ViewChangeOperation {
        ADD, REMOVE, SHUTDOWN
    }

    public NettyClusterNetwork(NodeId self, int port, AddressBook addressBook, Serializer serializer) {
        this.self = self;
        this.port = port;
        this.addressBook = addressBook;
        this.handlers = () -> List.of(new MessageDecoder<>(serializer),
                                      new MessageEncoder<>(serializer),
                                      new ProtocolMessageHandler<>(this::peerConnected,
                                                                   this::peerDisconnected,
                                                                   this::messageReceived));
    }

    @Override
    public boolean quorumConnected() {
        return peerLinks.size() >= addressBook.quorumSize();
    }

    private void messageReceived(T protocolMessage) {
        messageListener.onPresent(listener -> listener.accept(protocolMessage));
    }

    private void peerConnected(Channel channel) {
        addressBook.reverseLookup(channel.remoteAddress())
                   .onPresent(nodeId -> peerLinks.put(nodeId, channel))
                   .onPresent(nodeId -> logger.info("Node {} connected.", nodeId))
                   .onPresent(nodeId -> processViewChange(ADD, nodeId))
                   .onEmpty(() -> logger.warn("Unknown node {}, disconnecting.", channel.remoteAddress()))
                   .onEmpty(() -> channel.close().syncUninterruptibly());
    }

    private void peerDisconnected(Channel channel) {
        addressBook.reverseLookup(channel.remoteAddress())
                   .onPresent(peerLinks::remove)
                   .onPresent(nodeId -> processViewChange(REMOVE, nodeId))
                   .onPresent(nodeId -> logger.info("Peer {} diconnected.", nodeId));
    }

    @Override
    public void start() {
        if (isRunning.compareAndSet(false, true)) {
            server = Server.create("PeerNetworking", port, handlers);
        }
    }

    @Override
    public void stop() {
        if (isRunning.compareAndSet(true, false)) {
            logger.info("Stopping peer networking: notifying view change");

            processViewChange(SHUTDOWN, self);

            server.stop(() -> {
                logger.info("Stopping {}: closing peer connections", server.name());

                for (var link : peerLinks.values()) {
                    link.close().sync();
                }
            });
        }
    }

    @Override
    public void connect(NodeId nodeId) {
        if (!isRunning.get()) {
            logger.error("Attempt to connect {} while node is not running", nodeId);
            return;
        }
        addressBook.get(nodeId)
                   .onPresent(this::connectPeer)
                   .onEmpty(() -> logger.error("Unknown {}", nodeId));
    }

    private void connectPeer(NodeInfo nodeInfo) {
        var peerId = nodeInfo.id();

        if (peerLinks.containsKey(peerId)) {
            logger.warn("Peer {} already connected", peerId);
            return;
        }

        peerLinks.put(peerId, server.connectTo(nodeInfo.address()));
        processViewChange(ADD, peerId);
    }

    @Override
    public void disconnect(NodeId peerId) {
        var channel = peerLinks.remove(peerId);
        processViewChange(REMOVE, peerId);

        if (channel != null) {
            try {
                channel.close().sync();
                logger.info("Disconnected from peer {}", peerId);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Failed to disconnect from peer " + peerId, e);
            }
        }
    }

    @Override
    public <M extends ProtocolMessage> void send(NodeId peerId, M message) {
        sendToChannel(peerId, message, peerLinks.get(peerId));
    }

    private <M extends ProtocolMessage> void sendToChannel(NodeId peerId, M message, Channel channel) {
        if (channel == null) {
            logger.warn("Peer {} is not connected", peerId);
            return;
        }

        if (!channel.isActive()) {
            peerLinks.remove(peerId);
            processViewChange(REMOVE, peerId);
            logger.warn("Peer {} is not active", peerId);
            return;
        }

        channel.writeAndFlush(message);
    }

    @Override
    public <M extends ProtocolMessage> void broadcast(M message) {
        peerLinks.forEach((peerId, channel) -> sendToChannel(peerId, message, channel));
    }

    @Override
    public void observeViewChanges(Consumer<ViewChange> observer) {
        viewObserver = Option.option(observer);
    }

    @Override
    public void listen(Consumer<T> listener) {
        messageListener = Option.option(listener);
    }

    private void processViewChange(ViewChangeOperation operation, NodeId peerId) {
        var viewChange = switch (operation) {
            case ADD -> ViewChange.nodeAdded(peerId, currentView());
            case REMOVE -> ViewChange.nodeRemoved(peerId, currentView());
            case SHUTDOWN -> ViewChange.nodeDown(peerId);
        };

        viewObserver.onPresent(observer -> observer.accept(viewChange));
    }

    private List<NodeId> currentView() {
        return peerLinks.keySet()
                        .stream()
                        .sorted()
                        .toList();
    }
}
