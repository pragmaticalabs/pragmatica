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
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicClientCodecBuilder;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
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

/// QUIC client that initiates connections to peers and performs Hello handshake.
///
/// The client opens a QUIC connection, creates a bidirectional stream (consensus stream 0),
/// sends Hello, waits for Hello response, and returns the established [QuicPeerConnection].
public sealed interface QuicClusterClient {

    /// Connect to a peer and perform Hello handshake.
    ///
    /// @param peerId  the target peer's node identity
    /// @param address the target peer's UDP address
    /// @return promise resolving to the established peer connection
    Promise<QuicPeerConnection> connect(NodeId peerId, InetSocketAddress address);

    /// Shut down the client and release resources.
    Promise<Unit> close();

    /// Create a new QUIC cluster client.
    ///
    /// @param selfId          this node's identity
    /// @param selfRole        this node's role in the cluster
    /// @param serializer      message serializer
    /// @param deserializer    message deserializer
    /// @param sslContext      QUIC client SSL context (TLS 1.3)
    /// @param eventLoop       optional shared event loop group
    /// @param messageReceiver callback invoked for each message received after Hello
    static QuicClusterClient quicClusterClient(NodeId selfId,
                                               NodeRole selfRole,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               QuicSslContext sslContext,
                                               Option<EventLoopGroup> eventLoop,
                                               QuicClusterServer.MessageReceiver messageReceiver) {
        return new QuicClusterClientInstance(selfId, selfRole, serializer, deserializer,
                                            sslContext, eventLoop, messageReceiver);
    }

    record Unused() implements QuicClusterClient {
        @Override
        public Promise<QuicPeerConnection> connect(NodeId peerId, InetSocketAddress address) {
            return UNEXPECTED_MESSAGE.promise();
        }

        @Override
        public Promise<Unit> close() {
            return Promise.unitPromise();
        }
    }
}

final class QuicClusterClientInstance implements QuicClusterClient {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterClientInstance.class);
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
    private final EventLoopGroup eventLoopGroup;
    private final boolean ownsEventLoop;
    private final QuicClusterServer.MessageReceiver messageReceiver;
    private volatile Channel datagramChannel;

    QuicClusterClientInstance(NodeId selfId,
                              NodeRole selfRole,
                              Serializer serializer,
                              Deserializer deserializer,
                              QuicSslContext sslContext,
                              Option<EventLoopGroup> eventLoop,
                              QuicClusterServer.MessageReceiver messageReceiver) {
        this.selfId = selfId;
        this.selfRole = selfRole;
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.sslContext = sslContext;
        this.ownsEventLoop = eventLoop.isEmpty();
        this.eventLoopGroup = eventLoop.or(QuicClusterClientInstance::createEventLoop);
        this.messageReceiver = messageReceiver;
    }

    @Override
    public Promise<QuicPeerConnection> connect(NodeId peerId, InetSocketAddress address) {
        return Promise.promise(promise -> initiateConnection(peerId, address, promise));
    }

    @Override
    public Promise<Unit> close() {
        return Promise.promise(this::initiateClose);
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty bootstrap bind
    private void initiateConnection(NodeId peerId,
                                    InetSocketAddress address,
                                    Promise<QuicPeerConnection> promise) {
        var codec = buildQuicCodec();
        var bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioDatagramChannel.class)
            .handler(codec);

        bootstrap.bind(0)
                 .addListener(future -> handleBind(peerId, address, promise, future));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback
    private void handleBind(NodeId peerId,
                            InetSocketAddress address,
                            Promise<QuicPeerConnection> promise,
                            io.netty.util.concurrent.Future<? super Void> future) {
        if (!future.isSuccess()) {
            promise.fail(new QuicTransportError.ConnectFailed(address.toString(), future.cause()));
            return;
        }
        datagramChannel = ((io.netty.channel.ChannelFuture) future).channel();
        connectQuicChannel(datagramChannel, peerId, address, promise);
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty QUIC channel bootstrap
    private void connectQuicChannel(Channel channel,
                                    NodeId peerId,
                                    InetSocketAddress address,
                                    Promise<QuicPeerConnection> promise) {
        QuicChannel.newBootstrap(channel)
                   .handler(new ClientConnectionInitializer())
                   .remoteAddress(address)
                   .connect()
                   .addListener(future -> handleQuicConnect(peerId, address, promise, future));
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"}) // Netty future callback
    private void handleQuicConnect(NodeId peerId,
                                   InetSocketAddress address,
                                   Promise<QuicPeerConnection> promise,
                                   io.netty.util.concurrent.Future<?> future) {
        if (!future.isSuccess()) {
            promise.fail(new QuicTransportError.ConnectFailed(address.toString(), future.cause()));
            return;
        }
        var quicChannel = (QuicChannel) future.getNow();
        openStreamAndHandshake(quicChannel, peerId, promise);
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"}) // Netty stream creation
    private void openStreamAndHandshake(QuicChannel quicChannel,
                                        NodeId peerId,
                                        Promise<QuicPeerConnection> promise) {
        quicChannel.createStream(QuicStreamType.BIDIRECTIONAL,
                                 new ClientHelloHandler(peerId, quicChannel, promise))
                   .addListener(future -> handleStreamCreated(peerId, promise, future));
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"}) // Netty future callback
    private void handleStreamCreated(NodeId peerId,
                                     Promise<QuicPeerConnection> promise,
                                     io.netty.util.concurrent.Future<?> future) {
        if (!future.isSuccess()) {
            promise.fail(new QuicTransportError.StreamCreationFailed(future.cause()));
            return;
        }
        var streamChannel = (QuicStreamChannel) future.getNow();
        sendHello(streamChannel, peerId);
    }

    private void sendHello(QuicStreamChannel streamChannel, NodeId peerId) {
        var helloBytes = serializer.encode(new NetworkMessage.Hello(selfId, selfRole));
        streamChannel.writeAndFlush(Unpooled.wrappedBuffer(helloBytes));
        log.debug("Sent Hello to peer {} on stream", peerId);
    }

    private io.netty.channel.ChannelHandler buildQuicCodec() {
        return new QuicClientCodecBuilder()
            .sslContext(sslContext)
            .maxIdleTimeout(MAX_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            .initialMaxData(INITIAL_MAX_DATA)
            .initialMaxStreamDataBidirectionalLocal(INITIAL_MAX_STREAM_DATA)
            .initialMaxStreamDataBidirectionalRemote(INITIAL_MAX_STREAM_DATA)
            .initialMaxStreamsBidirectional(INITIAL_MAX_STREAMS)
            .build();
    }

    private static EventLoopGroup createEventLoop() {
        return new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    private void initiateClose(Promise<Unit> promise) {
        var channel = datagramChannel;
        if (channel != null) {
            channel.close().addListener(_ -> shutdownEventLoop(promise));
        } else {
            shutdownEventLoop(promise);
        }
    }

    private void shutdownEventLoop(Promise<Unit> promise) {
        if (!ownsEventLoop) {
            promise.succeed(unit());
            return;
        }
        eventLoopGroup.shutdownGracefully()
                      .addListener(_ -> promise.succeed(unit()));
    }

    /// Per-connection initializer (no-op for raw QUIC client).
    private static class ClientConnectionInitializer extends ChannelInitializer<QuicChannel> {
        @Override
        protected void initChannel(QuicChannel ch) {
            // No additional handlers needed for raw QUIC connections
        }
    }

    /// Handles the Hello handshake on the client side.
    ///
    /// After sending Hello, waits for the server's Hello response,
    /// then resolves the promise with the established peer connection.
    private class ClientHelloHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private final NodeId peerId;
        private final QuicChannel quicChannel;
        private final Promise<QuicPeerConnection> promise;
        private volatile boolean helloReceived;

        ClientHelloHandler(NodeId peerId,
                           QuicChannel quicChannel,
                           Promise<QuicPeerConnection> promise) {
            this.peerId = peerId;
            this.quicChannel = quicChannel;
            this.promise = promise;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            if (helloReceived) {
                return;
            }
            helloReceived = true;
            processHelloResponse(ctx, buf);
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            scheduleHelloTimeout(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in QUIC client Hello handler for peer {}", peerId, cause);
            promise.fail(new QuicTransportError.ConnectFailed(peerId.id(), cause));
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
                log.warn("Hello response timeout for peer {}", peerId);
                promise.fail(HELLO_TIMEOUT);
                ctx.close();
            }
        }

        private void processHelloResponse(ChannelHandlerContext ctx, ByteBuf buf) {
            var message = decodeMessage(buf);
            if (message instanceof NetworkMessage.Hello hello) {
                completePeerConnection(ctx, hello);
            } else {
                log.warn("Expected Hello response from peer {} but received: {}",
                         peerId, option(message).map(Object::getClass).map(Class::getSimpleName));
                promise.fail(UNEXPECTED_MESSAGE);
                ctx.close();
            }
        }

        private Object decodeMessage(ByteBuf buf) {
            var bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            return deserializer.decode(bytes);
        }

        private void completePeerConnection(ChannelHandlerContext ctx, NetworkMessage.Hello hello) {
            var peerConnection = quicPeerConnection(hello.sender(), quicChannel);
            peerConnection.registerStream(StreamType.CONSENSUS, (QuicStreamChannel) ctx.channel());

            // Replace Hello handler with data handler for ongoing messages
            ctx.pipeline().replace(this, "data-handler", new DataHandler(hello.sender()));

            log.info("QUIC Hello handshake complete with peer {} (role={})", hello.sender(), hello.role());
            promise.succeed(peerConnection);
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
