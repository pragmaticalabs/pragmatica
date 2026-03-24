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
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicServerCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.NodeRole;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.consensus.net.quic.QuicPeerConnection.quicPeerConnection;
import static org.pragmatica.consensus.net.quic.QuicTransportError.General.HELLO_TIMEOUT;
import static org.pragmatica.consensus.net.quic.QuicTransportError.General.UNEXPECTED_MESSAGE;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// QUIC server that accepts incoming connections and performs Hello handshake.
///
/// For each new QUIC connection, the server waits for a Hello message on the first
/// bidirectional stream, sends a Hello response, and notifies the connection handler
/// with the established [QuicPeerConnection].
public sealed interface QuicClusterServer {

    /// Start listening on the given UDP port.
    Promise<Unit> start(int port);

    /// Stop the server and release resources.
    Promise<Unit> stop();

    /// Get the UDP port the server is bound to.
    /// Returns empty if the server is not started.
    Option<Integer> boundPort();

    /// Callback for new peer connections after Hello handshake completes.
    @FunctionalInterface
    interface PeerConnectionHandler {
        void onPeerConnected(QuicPeerConnection connection);
    }

    /// Callback for incoming messages after Hello handshake completes.
    @FunctionalInterface
    interface MessageReceiver {
        void onMessage(NodeId sender, Object message);
    }

    /// Create a new QUIC cluster server.
    ///
    /// @param selfId            this node's identity
    /// @param selfRole          this node's role in the cluster
    /// @param serializer        message serializer
    /// @param deserializer      message deserializer
    /// @param sslContext        QUIC server SSL context (TLS 1.3)
    /// @param sharedEventLoop   optional shared event loop group
    /// @param connectionHandler callback invoked when a peer completes Hello handshake
    /// @param messageReceiver   callback invoked for each message received after Hello
    static QuicClusterServer quicClusterServer(NodeId selfId,
                                               NodeRole selfRole,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               QuicSslContext sslContext,
                                               Option<EventLoopGroup> sharedEventLoop,
                                               PeerConnectionHandler connectionHandler,
                                               MessageReceiver messageReceiver) {
        return new QuicClusterServerInstance(selfId, selfRole, serializer, deserializer,
                                            sslContext, sharedEventLoop, connectionHandler,
                                            messageReceiver);
    }

    record Unused() implements QuicClusterServer {
        @Override
        public Promise<Unit> start(int port) {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        public Option<Integer> boundPort() {
            return Option.empty();
        }
    }
}

final class QuicClusterServerInstance implements QuicClusterServer {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterServerInstance.class);
    private static final long HELLO_TIMEOUT_MS = 15_000;
    private static final long MAX_IDLE_TIMEOUT_MS = 30_000;
    private static final long INITIAL_MAX_DATA = 16_000_000;
    private static final long INITIAL_MAX_STREAM_DATA = 4_000_000;
    private static final long INITIAL_MAX_STREAMS = 64;

    private final NodeId selfId;
    private final NodeRole selfRole;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final QuicSslContext sslContext;
    private final Option<EventLoopGroup> sharedEventLoop;
    private final PeerConnectionHandler connectionHandler;
    private final MessageReceiver messageReceiver;

    private volatile Channel serverChannel;
    private volatile EventLoopGroup eventLoopGroup;
    private volatile boolean ownsEventLoop;

    QuicClusterServerInstance(NodeId selfId,
                              NodeRole selfRole,
                              Serializer serializer,
                              Deserializer deserializer,
                              QuicSslContext sslContext,
                              Option<EventLoopGroup> sharedEventLoop,
                              PeerConnectionHandler connectionHandler,
                              MessageReceiver messageReceiver) {
        this.selfId = selfId;
        this.selfRole = selfRole;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.sslContext = sslContext;
        this.sharedEventLoop = sharedEventLoop;
        this.connectionHandler = connectionHandler;
        this.messageReceiver = messageReceiver;
    }

    @Override
    @SuppressWarnings("JBCT-UTIL-01") // Netty bootstrap: side-effecting channel bind
    public Promise<Unit> start(int port) {
        return Promise.promise(promise -> bindServer(port, promise));
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.promise(this::initiateShutdown);
    }

    @Override
    public Option<Integer> boundPort() {
        var channel = serverChannel;
        return option(channel)
            .map(Channel::localAddress)
            .map(addr -> ((InetSocketAddress) addr).getPort());
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty bootstrap pattern with side-effecting handlers
    private void bindServer(int port, Promise<Unit> promise) {
        var group = resolveEventLoop();
        eventLoopGroup = group;

        var codec = buildQuicCodec();
        var bootstrap = new Bootstrap()
            .group(group)
            .channel(NioDatagramChannel.class)
            .handler(codec);

        bootstrap.bind(new InetSocketAddress(port))
                 .addListener(future -> handleBind(port, promise, future));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback
    private void handleBind(int port,
                            Promise<Unit> promise,
                            io.netty.util.concurrent.Future<? super Void> future) {
        if (future.isSuccess()) {
            var channel = ((io.netty.channel.ChannelFuture) future).channel();
            serverChannel = channel;
            var actualPort = ((InetSocketAddress) channel.localAddress()).getPort();
            log.info("QUIC cluster server started on UDP port {}", actualPort);
            promise.succeed(unit());
        } else {
            promise.fail(new QuicTransportError.BindFailed(port, future.cause()));
        }
    }

    private io.netty.channel.ChannelHandler buildQuicCodec() {
        return new QuicServerCodecBuilder()
            .sslContext(sslContext)
            .maxIdleTimeout(MAX_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .initialMaxData(INITIAL_MAX_DATA)
            .initialMaxStreamDataBidirectionalLocal(INITIAL_MAX_STREAM_DATA)
            .initialMaxStreamDataBidirectionalRemote(INITIAL_MAX_STREAM_DATA)
            .initialMaxStreamsBidirectional(INITIAL_MAX_STREAMS)
            .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
            .handler(new ServerConnectionInitializer())
            .streamHandler(new ServerStreamInitializer())
            .build();
    }

    private EventLoopGroup resolveEventLoop() {
        return sharedEventLoop.fold(
            this::createOwnedEventLoop,
            this::useSharedEventLoop
        );
    }

    private EventLoopGroup createOwnedEventLoop() {
        ownsEventLoop = true;
        return new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    private EventLoopGroup useSharedEventLoop(EventLoopGroup shared) {
        ownsEventLoop = false;
        return shared;
    }

    private void initiateShutdown(Promise<Unit> promise) {
        var channel = serverChannel;
        serverChannel = null;
        if (channel == null || !channel.isOpen()) {
            shutdownEventLoop(promise);
            return;
        }
        log.info("Stopping QUIC cluster server");
        channel.close()
               .addListener(_ -> shutdownEventLoop(promise));
    }

    private void shutdownEventLoop(Promise<Unit> promise) {
        if (!ownsEventLoop || eventLoopGroup == null) {
            promise.succeed(unit());
            return;
        }
        eventLoopGroup.shutdownGracefully()
                      .addListener(_ -> promise.succeed(unit()));
    }

    /// Per-connection initializer: logs connection events.
    private class ServerConnectionInitializer extends ChannelInitializer<QuicChannel> {
        @Override
        protected void initChannel(QuicChannel ch) {
            log.debug("New QUIC connection from {}", ch.remoteAddress());
        }
    }

    /// Per-stream initializer: installs the Hello handshake handler on each new stream.
    private class ServerStreamInitializer extends ChannelInitializer<QuicStreamChannel> {
        @Override
        protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline().addLast(new ServerHelloHandler());
        }
    }

    /// Handles the Hello handshake on the server side.
    ///
    /// Reads the first message on a new stream, validates it as a Hello,
    /// sends Hello response, then notifies the connection handler.
    private class ServerHelloHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private volatile boolean helloReceived;

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            if (helloReceived) {
                return;
            }
            helloReceived = true;
            processHello(ctx, buf);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            scheduleHelloTimeout(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in QUIC server Hello handler", cause);
            ctx.close();
        }

        private void scheduleHelloTimeout(ChannelHandlerContext ctx) {
            ctx.executor().schedule(
                () -> onHelloTimeout(ctx),
                HELLO_TIMEOUT_MS,
                TimeUnit.MILLISECONDS
            );
        }

        private void onHelloTimeout(ChannelHandlerContext ctx) {
            if (!helloReceived && ctx.channel().isActive()) {
                log.warn("Hello timeout for connection {}", ctx.channel().remoteAddress());
                ctx.close();
            }
        }

        private void processHello(ChannelHandlerContext ctx, ByteBuf buf) {
            var message = decodeMessage(buf);
            if (message instanceof NetworkMessage.Hello hello) {
                sendHelloResponse(ctx);
                registerPeerConnection(ctx, hello);
            } else {
                log.warn("Expected Hello message but received: {}", option(message).map(Object::getClass).map(Class::getSimpleName));
                ctx.close();
            }
        }

        private Object decodeMessage(ByteBuf buf) {
            var bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return deserializer.decode(bytes);
        }

        private void sendHelloResponse(ChannelHandlerContext ctx) {
            var helloBytes = serializer.encode(new NetworkMessage.Hello(selfId, selfRole));
            ctx.writeAndFlush(Unpooled.wrappedBuffer(helloBytes));
        }

        private void registerPeerConnection(ChannelHandlerContext ctx, NetworkMessage.Hello hello) {
            var quicChannel = (QuicChannel) ctx.channel().parent();
            var peerConnection = quicPeerConnection(hello.sender(), quicChannel);
            peerConnection.registerStream(StreamType.CONSENSUS, (QuicStreamChannel) ctx.channel());

            // Replace Hello handler with data handler for ongoing messages
            ctx.pipeline().replace(this, "data-handler", new DataHandler(hello.sender()));

            log.info("QUIC Hello handshake complete with peer {} (role={})", hello.sender(), hello.role());
            connectionHandler.onPeerConnected(peerConnection);
        }
    }

    /// Handles ongoing data messages after Hello handshake completes.
    /// Deserializes incoming bytes and routes them via the message receiver callback.
    private class DataHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final NodeId peerId;

        DataHandler(NodeId peerId) {
            this.peerId = peerId;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            var bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            var message = deserializer.decode(bytes);
            messageReceiver.onMessage(peerId, message);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error processing message from peer {}", peerId, cause);
        }
    }
}
