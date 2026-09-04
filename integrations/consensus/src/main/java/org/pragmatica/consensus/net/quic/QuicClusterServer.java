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
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
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
import io.netty.util.AttributeKey;
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
    /// #487 self-loopback: one event loop from this server's group, for delivering a send-to-self on the
    /// same dispatch path (an event-loop thread) real inbound frames run on. Empty until the server has
    /// bound.
    Option<EventLoop> loopbackEventLoop();

    /// Callback for new peer connections after Hello handshake completes.
    @FunctionalInterface
    interface PeerConnectionHandler {
        @Contract
        void onPeerConnected(QuicPeerConnection connection, NodeAddress peerAddress, Map<String, String> peerLabels);
    }

    /// Callback for incoming messages after Hello handshake completes.
    @FunctionalInterface
    interface MessageReceiver {
        @Contract
        void onMessage(NodeId sender, Object message);
    }

    /// Create a new QUIC cluster server.
    ///
    /// @param selfId            this node's identity
    /// @param selfAddress       this node's cluster address
    /// @param selfLabels        this node's metadata labels
    /// @param serializer        message serializer
    /// @param deserializer      message deserializer
    /// @param quicMetrics       transport metrics sink (payload byte/message counters; #726)
    /// @param sslContext        QUIC server SSL context (TLS 1.3)
    /// @param sharedEventLoop   optional shared event loop group
    /// @param connectionHandler callback invoked when a peer completes Hello handshake
    /// @param messageReceiver   callback invoked for each message received after Hello
    static QuicClusterServer quicClusterServer(NodeId selfId,
                                               NodeAddress selfAddress,
                                               Map<String, String> selfLabels,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               QuicTransportMetrics quicMetrics,
                                               QuicSslContext sslContext,
                                               Option<EventLoopGroup> sharedEventLoop,
                                               PeerConnectionHandler connectionHandler,
                                               MessageReceiver messageReceiver) {
        return new QuicClusterServerInstance(selfId,
                                             selfAddress,
                                             selfLabels,
                                             serializer,
                                             deserializer,
                                             quicMetrics,
                                             sslContext,
                                             sharedEventLoop,
                                             connectionHandler,
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

        @Override
        public Option<EventLoop> loopbackEventLoop() {
            return Option.empty();
        }
    }
}

final class QuicClusterServerInstance implements QuicClusterServer {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterServerInstance.class);

    /// Parent-QuicChannel attribute carrying the per-peer connection, stamped by the CONTROL
    /// (Hello) stream and read by each subsequently-accepted data-lane stream so it can attach
    /// itself to the right [QuicPeerConnection]. Package-visible: stamped + read here.
    static final AttributeKey<QuicPeerConnection> PEER_CONNECTION = AttributeKey.valueOf("aether.quic.peerConnection");

    private static final long HELLO_TIMEOUT_MS = 15_000;
    private static final long MAX_IDLE_TIMEOUT_MS = 0;  // Disabled per QUIC RFC 9000 §10.1 — cluster connections are persistent
    private static final long INITIAL_MAX_DATA = 64_000_000;
    private static final long INITIAL_MAX_STREAM_DATA = 32_000_000;
    private static final long INITIAL_MAX_STREAMS = 64;
    private static final int MAX_FRAME_LENGTH = 32 * 1024 * 1024;

    private final NodeId selfId;
    private final NodeAddress selfAddress;
    private final Map<String, String> selfLabels;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final QuicTransportMetrics quicMetrics;
    private final QuicSslContext sslContext;
    private final Option<EventLoopGroup> sharedEventLoop;
    private final PeerConnectionHandler connectionHandler;
    private final MessageReceiver messageReceiver;
    private volatile Channel serverChannel;
    private volatile EventLoopGroup eventLoopGroup;
    private volatile boolean ownsEventLoop;

    QuicClusterServerInstance(NodeId selfId,
                              NodeAddress selfAddress,
                              Map<String, String> selfLabels,
                              Serializer serializer,
                              Deserializer deserializer,
                              QuicTransportMetrics quicMetrics,
                              QuicSslContext sslContext,
                              Option<EventLoopGroup> sharedEventLoop,
                              PeerConnectionHandler connectionHandler,
                              MessageReceiver messageReceiver) {
        this.selfId = selfId;
        this.selfAddress = selfAddress;
        this.selfLabels = Map.copyOf(selfLabels);
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.quicMetrics = quicMetrics;
        this.sslContext = sslContext;
        this.sharedEventLoop = sharedEventLoop;
        this.connectionHandler = connectionHandler;
        this.messageReceiver = messageReceiver;
    }

    @Override
    @SuppressWarnings("JBCT-UTIL-01")  // Netty bootstrap: side-effecting channel bind
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

        return option(channel).map(Channel::localAddress)
                     .map(addr -> ((InetSocketAddress) addr).getPort());
    }

    /// #487 self-loopback: one event loop from this server's group, used to deliver a send-to-self on the
    /// SAME dispatch path (an event-loop thread) that real inbound frames run on. Empty until the server
    /// has bound (`eventLoopGroup` is assigned in `bindServer`). The caller pins the returned loop once so
    /// self is a single, ordered inbound lane (per-sender FIFO, matching a real peer channel).
    @Override
    public Option<EventLoop> loopbackEventLoop() {
        return option(eventLoopGroup).map(EventLoopGroup::next);
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty bootstrap pattern with side-effecting handlers
    private void bindServer(int port, Promise<Unit> promise) {
        var group = resolveEventLoop();

        eventLoopGroup = group;
        var codec = buildQuicCodec();
        var bootstrap = new Bootstrap().group(group)
                                       .channel(NioDatagramChannel.class)
                                       // SO_REUSEADDR enables fast rebind when a node restarts and the kernel still holds
                                       // the cluster UDP port in TIME_WAIT — without it `BindException: Address already in
                                       // use` cascades through chaos tests every time a node is killed and restarted.
                                       .option(ChannelOption.SO_REUSEADDR, true)
                                       .handler(codec);

        bootstrap.bind(new InetSocketAddress(port)).addListener(future -> handleBind(port, promise, future));
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty future callback
    private void handleBind(int port, Promise<Unit> promise, io.netty.util.concurrent.Future<? super Void> future) {
        if (future.isSuccess()) {
            var channel = ((io.netty.channel.ChannelFuture) future).channel();

            serverChannel = channel;
            var actualPort = ((InetSocketAddress) channel.localAddress()).getPort();

            log.info("QUIC cluster server started on UDP port {}", actualPort);
            promise.succeed(unit());
        } else {
            promise.fail(QuicTransportError.BindFailed.FACTORY.apply(port,
                                                                     Causes.fromThrowable(future.cause())));
        }
    }

    private io.netty.channel.ChannelHandler buildQuicCodec() {
        return new QuicServerCodecBuilder().sslContext(sslContext)
                                           .maxIdleTimeout(MAX_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                           .initialMaxData(INITIAL_MAX_DATA)
                                           .initialMaxStreamDataBidirectionalLocal(INITIAL_MAX_STREAM_DATA)
                                           .initialMaxStreamDataBidirectionalRemote(INITIAL_MAX_STREAM_DATA)
                                           .initialMaxStreamsBidirectional(INITIAL_MAX_STREAMS)
                                           // Enables QUIC connection migration so a path change (not a socket teardown)
                                           // survives without a reconnect.
                                           .activeMigration(true)
                                           .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                                           .handler(new ServerConnectionInitializer())
                                           .streamHandler(new ServerStreamInitializer())
                                           .build();
    }

    private EventLoopGroup resolveEventLoop() {
        return sharedEventLoop.fold(this::createOwnedEventLoop, this::useSharedEventLoop);
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
        channel.close().addListener(_ -> shutdownEventLoop(promise));
    }

    private void shutdownEventLoop(Promise<Unit> promise) {
        if (!ownsEventLoop || eventLoopGroup == null) {
            promise.succeed(unit());

            return;
        }

        eventLoopGroup.shutdownGracefully().addListener(_ -> promise.succeed(unit()));
    }

    /// Per-connection initializer: logs connection events.
    private class ServerConnectionInitializer extends ChannelInitializer<QuicChannel> {
        @Override
        @Contract
        protected void initChannel(QuicChannel ch) {
            log.debug("New QUIC connection from {}", ch.remoteAddress());
        }
    }

    /// Per-stream initializer: installs framing + preamble/Hello handler on each new stream.
    /// QUIC streams are byte-oriented (like TCP) — LengthFieldBasedFrameDecoder is needed
    /// to delimit individual messages within the stream.
    private class ServerStreamInitializer extends ChannelInitializer<QuicStreamChannel> {
        @Override
        @Contract
        protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline()
              .addLast(new io.netty.handler.codec.LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4))
              .addLast(new io.netty.handler.codec.LengthFieldPrepender(4))
              .addLast(new ServerStreamHandler());
        }
    }

    /// Reads each accepted stream's 1-byte lane preamble and routes by lane.
    ///
    /// Phase machine:
    ///   - AWAITING_PREAMBLE: first framed message is the 1-byte lane index.
    ///       * CONTROL → transition to AWAITING_HELLO (the handshake rides CONTROL).
    ///       * any data lane → resolve the per-peer connection from the parent QuicChannel
    ///         attribute, register this stream under the lane, swap to the shared data handler.
    ///   - AWAITING_HELLO: decode the Hello, send the Hello response, build + register the
    ///       peer connection, stamp the parent-channel attribute, swap to the shared data
    ///       handler (CONTROL lane), then notify the connection handler.
    ///
    /// Installed at stream init so it receives `channelActive` for the handshake/preamble timeout.
    private class ServerStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
        private Phase phase = Phase.AWAITING_PREAMBLE;

        private enum Phase {
            AWAITING_PREAMBLE,
            AWAITING_HELLO
        }

        @Override
        @Contract
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            // #726: PAYLOAD bytes at the lane boundary — one channelRead0 invocation is exactly
            // one length-prefixed frame, so this covers BOTH the 1-byte lane preamble
            // (AWAITING_PREAMBLE) and the Hello frame (AWAITING_HELLO) without needing a hook in
            // each branch. Read before either handler consumes the buffer.
            quicMetrics.onBytesReceived(buf.readableBytes());
            switch (phase) {
                case AWAITING_PREAMBLE -> handlePreamble(ctx, buf);
                case AWAITING_HELLO -> handleHello(ctx, buf);
            }
        }

        @Override
        @Contract
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            super.channelActive(ctx);
            scheduleHelloTimeout(ctx);
        }

        @Override
        @Contract
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in QUIC server stream handler", cause);
            ctx.close();
        }

        private void scheduleHelloTimeout(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> onHelloTimeout(ctx), HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private void onHelloTimeout(ChannelHandlerContext ctx) {
            // Timeout only matters while we are still awaiting the preamble or Hello; once the
            // handler is swapped to the data handler the pipeline no longer contains this handler.
            if (ctx.channel().isActive() && ctx.pipeline().context(this) != null) {
                log.warn("Stream preamble/Hello timeout for connection {}",
                         ctx.channel().remoteAddress());
                ctx.close();
            }
        }

        private void handlePreamble(ChannelHandlerContext ctx, ByteBuf buf) {
            if (buf.readableBytes() < 1) {
                log.warn("Empty stream preamble from {} — closing",
                         ctx.channel().remoteAddress());
                ctx.close();

                return;
            }

            var idx = buf.readByte();

            StreamType.fromIndex(idx).fold(() -> onInvalidPreamble(ctx, idx), lane -> routePreamble(ctx, lane));
        }

        private Unit onInvalidPreamble(ChannelHandlerContext ctx, byte idx) {
            log.warn("Invalid stream preamble index {} from {} — closing",
                     idx,
                     ctx.channel().remoteAddress());
            ctx.close();

            return unit();
        }

        private Unit routePreamble(ChannelHandlerContext ctx, StreamType lane) {
            if (lane == StreamType.CONTROL) {
                phase = Phase.AWAITING_HELLO;

                return unit();
            }

            return attachDataLane(ctx, lane);
        }

        private Unit attachDataLane(ChannelHandlerContext ctx, StreamType lane) {
            var parentQuicChannel = (QuicChannel) ctx.channel().parent();
            var peerConnection = parentQuicChannel.attr(PEER_CONNECTION).get();

            if (peerConnection == null) {
                log.warn("No peer connection on parent channel for {} lane from {} — closing (handshake-first ordering violated)",
                         lane,
                         ctx.channel().remoteAddress());
                ctx.close();

                return unit();
            }

            peerConnection.registerStream(lane, (QuicStreamChannel) ctx.channel());
            ctx.pipeline()
               .replace(this,
                        "data-handler",
                        new QuicLaneDataHandler(peerConnection.peerId(),
                                                lane,
                                                deserializer,
                                                quicMetrics,
                                                messageReceiver,
                                                log));
            log.debug("Attached {} lane stream from peer {}", lane, peerConnection.peerId());

            return unit();
        }

        @SuppressWarnings("JBCT-PAT-01")  // Adapter boundary: catch deserialization errors from external input
        private void handleHello(ChannelHandlerContext ctx, ByteBuf buf) {
            Object message;

            try {
                message = decodeMessage(buf);
            } catch (Exception e) {
                log.error("Failed to deserialize Hello message from {}",
                          ctx.channel().remoteAddress(),
                          e);
                ctx.close();

                return;
            }

            if (message instanceof NetworkMessage.Hello hello) {
                sendHelloResponse(ctx);
                registerPeerConnection(ctx, hello);
            } else {
                log.warn("Expected Hello message but received: {}",
                         option(message).map(Object::getClass).map(Class::getSimpleName));
                ctx.close();
            }
        }

        private Object decodeMessage(ByteBuf buf) {
            var bytes = new byte[buf.readableBytes()];

            buf.readBytes(bytes);

            return deserializer.decode(bytes);
        }

        private void sendHelloResponse(ChannelHandlerContext ctx) {
            // Responses flowing back from the acceptor carry NO preamble.
            var helloBytes = serializer.encode(new NetworkMessage.Hello(selfId, selfAddress, selfLabels));

            ctx.writeAndFlush(Unpooled.wrappedBuffer(helloBytes));
            // #726: PAYLOAD bytes at the lane boundary — same honesty boundary as every other write.
            quicMetrics.onBytesSent(helloBytes.length);
        }

        private void registerPeerConnection(ChannelHandlerContext ctx, NetworkMessage.Hello hello) {
            var quicChannel = (QuicChannel) ctx.channel().parent();
            var peerConnection = quicPeerConnection(hello.sender(), quicChannel);
            // The handshake stream is the CONTROL lane.
            peerConnection.registerStream(StreamType.CONTROL, (QuicStreamChannel) ctx.channel());
            // Install the lazy lane-opener so a write that races the data-lane preamble window can
            // (re)open the missing lane on this live channel instead of failing "No stream available".
            // The acceptor formerly populated its stream table ONLY passively (as the dialer's
            // preamble frames arrived via attachDataLane), so onPeerConnected published the peer
            // CONNECTED with only CONTROL present — the QUIC reconnect stream-zombie.
            peerConnection.laneOpener(laneOpenerFor(quicChannel, hello.sender(), peerConnection));
            // Stamp the parent QuicChannel so subsequently-accepted data-lane streams can find
            // the peer connection by reading this attribute.
            quicChannel.attr(PEER_CONNECTION).set(peerConnection);
            // Replace the preamble/Hello handler with the shared data handler (CONTROL lane).
            ctx.pipeline()
               .replace(this,
                        "data-handler",
                        new QuicLaneDataHandler(hello.sender(),
                                                StreamType.CONTROL,
                                                deserializer,
                                                quicMetrics,
                                                messageReceiver,
                                                log));
            log.info("QUIC Hello handshake complete with peer {} (address={})", hello.sender(), hello.address());
            connectionHandler.onPeerConnected(peerConnection, hello.address(), hello.labels());
        }

        /// Build a [QuicPeerConnection.LaneOpener] that opens a fresh bidirectional stream on
        /// `quicChannel` for the requested lane, writes the lane preamble (so the dialer attributes
        /// the reverse-direction stream correctly), installs the shared framing + data handler,
        /// registers the stream on `peerConnection`, and reports the registered stream. Mirrors the
        /// dialer's `openDataLane` so a lazily-opened acceptor lane is byte-identical to a normally
        /// negotiated one.
        private QuicPeerConnection.LaneOpener laneOpenerFor(QuicChannel quicChannel,
                                                            NodeId peerNodeId,
                                                            QuicPeerConnection peerConnection) {
            return (lane, onResult) -> openLaneStream(quicChannel, peerNodeId, peerConnection, lane, onResult);
        }

        @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty stream creation for lazy lane re-open
        private void openLaneStream(QuicChannel quicChannel,
                                    NodeId peerNodeId,
                                    QuicPeerConnection peerConnection,
                                    StreamType lane,
                                    java.util.function.Consumer<Option<QuicStreamChannel>> onResult) {
            if (!quicChannel.isActive()) {
                onResult.accept(Option.empty());

                return;
            }

            var initializer = new ChannelInitializer<QuicStreamChannel>() {
                @Override
                @Contract
                protected void initChannel(QuicStreamChannel ch) {
                    ch.pipeline()
                      .addLast(new io.netty.handler.codec.LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4))
                      .addLast(new io.netty.handler.codec.LengthFieldPrepender(4))
                      .addLast(new QuicLaneDataHandler(peerNodeId, lane, deserializer, quicMetrics, messageReceiver, log));
                }
            };

            quicChannel.createStream(io.netty.handler.codec.quic.QuicStreamType.BIDIRECTIONAL, initializer)
                       .addListener(future -> completeLaneOpen(peerConnection, peerNodeId, lane, onResult, future));
        }

        @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty future callback for lazy lane re-open
        private void completeLaneOpen(QuicPeerConnection peerConnection,
                                      NodeId peerNodeId,
                                      StreamType lane,
                                      java.util.function.Consumer<Option<QuicStreamChannel>> onResult,
                                      io.netty.util.concurrent.Future<?> future) {
            if (!future.isSuccess()) {
                log.warn("Lazy re-open of {} lane to peer {} failed", lane, peerNodeId, future.cause());
                onResult.accept(Option.empty());

                return;
            }

            var streamChannel = (QuicStreamChannel) future.getNow();
            var preamble = new byte[]{(byte) lane.streamIndex()};

            streamChannel.writeAndFlush(Unpooled.wrappedBuffer(preamble));
            // #726: PAYLOAD bytes at the lane boundary — acceptor-side lazy-reopen preamble.
            quicMetrics.onBytesSent(preamble.length);
            peerConnection.registerStream(lane, streamChannel);
            log.info("Lazily (re)opened {} lane to peer {} — stream-zombie healed without re-dial", lane, peerNodeId);
            onResult.accept(option(streamChannel));
        }
    }
}
