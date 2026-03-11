package org.pragmatica.aether.worker.network;

import org.pragmatica.aether.worker.WorkerError;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// TCP network for inter-worker communication.
///
/// Lighter than the full NettyClusterNetwork: just TCP client/server with
/// length-prefix framing for Decision relay and mutation forwarding.
@SuppressWarnings({"JBCT-RET-01", "JBCT-EX-01"})
public final class WorkerNetwork {
    private static final Logger LOG = LoggerFactory.getLogger(WorkerNetwork.class);
    private static final int MAX_FRAME_LENGTH = 16 * 1024 * 1024;
    private static final int LENGTH_FIELD_LENGTH = 4;

    private final Serializer serializer;
    private final Deserializer deserializer;
    private final Map<NodeId, Channel> connections = new ConcurrentHashMap<>();
    private final Map<NodeId, InetSocketAddress> knownAddresses = new ConcurrentHashMap<>();
    private volatile Consumer<Object> messageHandler;
    private volatile Channel serverChannel;
    private volatile NioEventLoopGroup bossGroup;
    private volatile NioEventLoopGroup workerGroup;

    private WorkerNetwork(Serializer serializer, Deserializer deserializer) {
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    /// Factory method.
    public static WorkerNetwork workerNetwork(Serializer serializer, Deserializer deserializer) {
        return new WorkerNetwork(serializer, deserializer);
    }

    /// Start the TCP server on the given port.
    public Promise<Unit> start(int port, Consumer<Object> handler) {
        this.messageHandler = handler;
        return Promise.lift(WorkerError.NetworkFailure::new, () -> doBind(port));
    }

    /// Stop the network and close all connections.
    public Promise<Unit> stop() {
        return Promise.lift(WorkerError.NetworkFailure::new, this::doStop);
    }

    /// Register a known peer address.
    public void registerPeer(NodeId nodeId, InetSocketAddress address) {
        knownAddresses.put(nodeId, address);
    }

    /// Remove a peer and close its connection.
    public void removePeer(NodeId nodeId) {
        knownAddresses.remove(nodeId);
        var channel = connections.remove(nodeId);
        if (channel != null) {
            channel.close();
        }
    }

    /// Send a message to a specific worker node.
    public void send(NodeId target, Object message) {
        var channel = connections.get(target);
        if (channel != null && channel.isActive()) {
            writeMessage(channel, message);
            return;
        }
        var address = knownAddresses.get(target);
        if (address == null) {
            LOG.warn("No known address for worker {}", target.id());
            return;
        }
        connectAndSend(target, address, message);
    }

    /// Send raw pre-serialized bytes to a target node.
    /// Used by governor relay to avoid double serialization of DHT messages.
    public void sendRaw(NodeId target, byte[] rawBytes) {
        var channel = connections.get(target);
        if (channel != null && channel.isActive()) {
            channel.writeAndFlush(Unpooled.wrappedBuffer(rawBytes));
            return;
        }
        var address = knownAddresses.get(target);
        if (address == null) {
            LOG.warn("No known address for worker {} (raw send)", target.id());
            return;
        }
        connectAndSendRaw(target, address, rawBytes);
    }

    /// Broadcast a message to all connected workers.
    public void broadcast(Object message) {
        connections.values()
                   .stream()
                   .filter(Channel::isActive)
                   .forEach(ch -> writeMessage(ch, message));
    }

    /// Get the list of currently connected peer IDs.
    public List<NodeId> connectedPeers() {
        return connections.entrySet()
                          .stream()
                          .filter(e -> e.getValue()
                                        .isActive())
                          .map(Map.Entry::getKey)
                          .toList();
    }

    private void connectAndSendRaw(NodeId target, InetSocketAddress address, byte[] rawBytes) {
        var group = workerGroup;
        if (group == null) {
            LOG.warn("Worker network not started, cannot connect to {}", target.id());
            return;
        }
        new Bootstrap().group(group)
                       .channel(NioSocketChannel.class)
                       .handler(clientInitializer())
                       .connect(address)
                       .addListener(future -> handleConnectResultRaw(future.isSuccess(),
                                                                     target,
                                                                     future,
                                                                     rawBytes));
    }

    @SuppressWarnings("JBCT-STY-05")
    private void handleConnectResultRaw(boolean success,
                                        NodeId target,
                                        io.netty.util.concurrent.Future<?> future,
                                        byte[] rawBytes) {
        if (!success) {
            LOG.warn("Failed to connect to worker {}: {}",
                     target.id(),
                     future.cause()
                           .getMessage());
            return;
        }
        var ch = ((io.netty.channel.ChannelFuture) future).channel();
        connections.put(target, ch);
        ch.writeAndFlush(Unpooled.wrappedBuffer(rawBytes));
    }

    private void writeMessage(Channel channel, Object message) {
        var bytes = serializer.encode(message);
        channel.writeAndFlush(Unpooled.wrappedBuffer(bytes));
    }

    private void connectAndSend(NodeId target, InetSocketAddress address, Object message) {
        var group = workerGroup;
        if (group == null) {
            LOG.warn("Worker network not started, cannot connect to {}", target.id());
            return;
        }
        new Bootstrap().group(group)
                       .channel(NioSocketChannel.class)
                       .handler(clientInitializer())
                       .connect(address)
                       .addListener(future -> handleConnectResult(future.isSuccess(),
                                                                  target,
                                                                  future,
                                                                  message));
    }

    @SuppressWarnings("JBCT-STY-05")
    private void handleConnectResult(boolean success,
                                     NodeId target,
                                     io.netty.util.concurrent.Future<?> future,
                                     Object message) {
        if (!success) {
            LOG.warn("Failed to connect to worker {}: {}",
                     target.id(),
                     future.cause()
                           .getMessage());
            return;
        }
        var ch = ((io.netty.channel.ChannelFuture) future).channel();
        connections.put(target, ch);
        writeMessage(ch, message);
    }

    private void doBind(int port) throws InterruptedException {
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        var channel = new ServerBootstrap().group(bossGroup, workerGroup)
                                           .channel(NioServerSocketChannel.class)
                                           .childHandler(serverInitializer())
                                           .bind(port)
                                           .sync()
                                           .channel();
        serverChannel = channel;
        LOG.info("Worker network started on port {}", port);
    }

    private void doStop() throws InterruptedException {
        connections.values()
                   .forEach(Channel::close);
        connections.clear();
        var sc = serverChannel;
        if (sc != null) {
            sc.close()
              .sync();
            serverChannel = null;
        }
        shutdownGroup(bossGroup);
        bossGroup = null;
        shutdownGroup(workerGroup);
        workerGroup = null;
        LOG.info("Worker network stopped");
    }

    private void shutdownGroup(NioEventLoopGroup group) throws InterruptedException {
        if (group != null) {
            group.shutdownGracefully()
                 .sync();
        }
    }

    private ChannelInitializer<SocketChannel> serverInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                configurePipeline(ch);
            }
        };
    }

    private ChannelInitializer<SocketChannel> clientInitializer() {
        return new ChannelInitializer<>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                configurePipeline(ch);
            }
        };
    }

    private void configurePipeline(SocketChannel ch) {
        ch.pipeline()
          .addLast(new LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, LENGTH_FIELD_LENGTH, 0, LENGTH_FIELD_LENGTH))
          .addLast(new LengthFieldPrepender(LENGTH_FIELD_LENGTH))
          .addLast(inboundHandler());
    }

    private SimpleChannelInboundHandler<ByteBuf> inboundHandler() {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf msg) {
                handleIncoming(msg);
            }
        };
    }

    private void handleIncoming(ByteBuf buf) {
        var bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
        var handler = messageHandler;
        if (handler == null) {
            return;
        }
        Object message = deserializer.decode(bytes);
        handler.accept(message);
    }
}
