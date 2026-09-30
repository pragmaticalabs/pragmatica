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
import org.pragmatica.consensus.net.BootTokens;
import org.pragmatica.consensus.net.NetworkMessage;
import org.pragmatica.consensus.net.OutboundMessageLimit;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.concurrent.PublishSlot;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.StreamType;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
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
    /// The largest length-prefixed frame the cluster transport's decoder accepts, in bytes. A delegating
    /// alias of [OutboundMessageLimit#MAX_FRAME_BYTES], which is the single source for both the server
    /// and the client pipelines; kept because message producers outside this module split by it
    /// (e.g. stream replication, #1287).
    int MAX_FRAME_LENGTH = OutboundMessageLimit.MAX_FRAME_BYTES;
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
        return quicClusterServer(selfId,
                                 selfAddress,
                                 selfLabels,
                                 serializer,
                                 deserializer,
                                 quicMetrics,
                                 sslContext,
                                 sharedEventLoop,
                                 connectionHandler,
                                 messageReceiver,
                                 BootTokens.bootTokens(0L));
    }

    /// As above, admitting every inbound Hello through `bootTokens` (shared with SWIM) before the
    /// connection is registered, and carrying `bootTokens.self()` on this node's Hello response.
    static QuicClusterServer quicClusterServer(NodeId selfId,
                                               NodeAddress selfAddress,
                                               Map<String, String> selfLabels,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               QuicTransportMetrics quicMetrics,
                                               QuicSslContext sslContext,
                                               Option<EventLoopGroup> sharedEventLoop,
                                               PeerConnectionHandler connectionHandler,
                                               MessageReceiver messageReceiver,
                                               BootTokens bootTokens) {
        return new QuicClusterServerInstance(selfId,
                                             selfAddress,
                                             selfLabels,
                                             serializer,
                                             deserializer,
                                             quicMetrics,
                                             sslContext,
                                             sharedEventLoop,
                                             connectionHandler,
                                             messageReceiver,
                                             bootTokens);
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
    private static final long HELLO_TIMEOUT_MS = 15_000;
    private static final long MAX_IDLE_TIMEOUT_MS = 0;  // Disabled per QUIC RFC 9000 §10.1 — cluster connections are persistent
    private static final long INITIAL_MAX_DATA = 64_000_000;
    private static final long INITIAL_MAX_STREAM_DATA = 32_000_000;
    private static final long INITIAL_MAX_STREAMS = 64;

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
    private final BootTokens bootTokens;
    private final PeerOpenedLaneRouter laneRouter;
    /// #1456: a publish slot, not a bare reference. `stop()` closes it, so a bind completing after
    /// stop had already read an empty field is handed back to `handleBind` to close, instead of
    /// leaving the cluster UDP port bound with nothing owning it for the life of the process.
    private final PublishSlot<Channel> serverChannel = PublishSlot.publishSlot();
    private volatile EventLoopGroup eventLoopGroup;
    private volatile boolean ownsEventLoop;

    /// #1456 test seam: runs between the bind completing and the just-bound channel being published,
    /// so a test can call `stop()` from inside that window. The window is sub-millisecond in
    /// production, which is why the race is only reachable deterministically from here. No-op unless
    /// a test installs a hook.
    private volatile Runnable beforePublish = () -> {};

    QuicClusterServerInstance(NodeId selfId,
                              NodeAddress selfAddress,
                              Map<String, String> selfLabels,
                              Serializer serializer,
                              Deserializer deserializer,
                              QuicTransportMetrics quicMetrics,
                              QuicSslContext sslContext,
                              Option<EventLoopGroup> sharedEventLoop,
                              PeerConnectionHandler connectionHandler,
                              MessageReceiver messageReceiver,
                              BootTokens bootTokens) {
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
        this.bootTokens = bootTokens;
        this.laneRouter = new PeerOpenedLaneRouter(deserializer, quicMetrics, messageReceiver, log);
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

    /// #1456 test seam — see [#beforePublish].
    @Contract
    void beforePublishForTest(Runnable hook) {
        beforePublish = hook;
    }

    @Override
    public Option<Integer> boundPort() {
        return serverChannel.current()
                            .map(Channel::localAddress)
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
        // #1719 / #1015 — the cluster port is bound EXCLUSIVELY (no SO_REUSEADDR). With it, a second reuse-enabled socket
        // (another node or process on the host) binds the same port silently, and on Linux the later socket then
        // receives every datagram for it: this node goes deaf mid-stream (#1727) and the BindFailed guard below could
        // never fire. UDP has no TIME_WAIT to escape, so exclusivity costs nothing on restart once the old socket has
        // closed; a port conflict now fails the start with BindFailed naming the port.
        var bootstrap = new Bootstrap().group(group).channel(NioDatagramChannel.class).handler(codec);

        bootstrap.bind(new InetSocketAddress(port)).addListener(future -> handleBind(port, promise, future));
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty future callback
    private void handleBind(int port, Promise<Unit> promise, io.netty.util.concurrent.Future<? super Void> future) {
        if (future.isSuccess()) {
            publishBoundChannel(((io.netty.channel.ChannelFuture) future).channel(), promise);
        } else {
            promise.fail(QuicTransportError.BindFailed.FACTORY.apply(port,
                                                                     Causes.fromThrowable(future.cause())));
        }
    }

    /// #1456: the bind can land after `stop()` has already run. The slot hands the channel straight
    /// back when it is closed, and this side closes it — `initiateShutdown` has already been and gone,
    /// so nothing else ever will. The start is FAILED rather than succeeded in that case, which is
    /// both the honest answer (this server is not running) and what keeps the caller's post-start
    /// hooks — `QuicClusterNetwork`'s reconciler and keepalive schedules — from arming a transport
    /// that was stopped. A leaked reconciler was observed still dialling 2m10s later, inside
    /// unrelated test classes.
    private void publishBoundChannel(Channel channel, Promise<Unit> promise) {
        beforePublish.run();
        serverChannel.publishOrReclaim(channel)
                     .onPresent(orphan -> closeOrphanedChannel(orphan, promise))
                     .onEmpty(() -> announceBoundChannel(channel, promise));
    }

    private void announceBoundChannel(Channel channel, Promise<Unit> promise) {
        log.info("QUIC cluster server started on UDP port {}",
                 ((InetSocketAddress) channel.localAddress()).getPort());
        promise.succeed(unit());
    }

    /// Failing the start here is a change to this server's start contract: before #1456 a bind that
    /// landed after `stop()` reported SUCCESS. The failing matters — it is what stops
    /// `QuicClusterNetwork`'s post-start `.onSuccess` hooks arming a reconciler on a stopped transport
    /// — but it also introduces a second candidate cause into `EmberCluster.start()`'s abort path,
    /// whose surfaced cause must stay the failure that TRIGGERED the abort.
    ///
    /// It does, for a reason stronger than a race: `EmberCluster.start()` selects via
    /// `firstFailure::fail`, and promise resolution is compare-and-set, so the first failure wins.
    /// Inside that path the only thing that stops a node is `abortStart`, which runs only once
    /// `firstFailure` has ALREADY resolved — so this cause cannot exist before the one it would have
    /// to displace. **Pinned, not merely argued:** `EmberClusterSwimStartFailureTest` asserts the
    /// surfaced cause `contains("Address already in use")`, which this message cannot satisfy.
    /// Measured 2026-09-23 — forcing this branch unconditionally reddens it at line 78 with
    /// `start() settled after 2144 ms with: QUIC cluster server was stopped while its bind was still
    /// in flight`. Change the cause or the selection and that test is what catches you.
    private void closeOrphanedChannel(Channel orphan, Promise<Unit> promise) {
        log.warn("QUIC cluster server bound on UDP port {} after stop() had already run — closing the orphan (#1456)",
                 ((InetSocketAddress) orphan.localAddress()).getPort());
        orphan.close().addListener(_ -> promise.fail(QuicTransportError.General.STOPPED_DURING_START));
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

    /// #1456: `close()` is what orders this against an in-flight bind. It is terminal — any channel
    /// published afterwards comes straight back to [#publishBoundChannel] to be closed there.
    private void initiateShutdown(Promise<Unit> promise) {
        serverChannel.close()
                     .filter(Channel::isOpen)
                     .onPresent(channel -> closeAndShutdown(channel, promise))
                     .onEmpty(() -> shutdownEventLoop(promise));
    }

    private void closeAndShutdown(Channel channel, Promise<Unit> promise) {
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
            PeerOpenedLaneRouter.preambleLane(buf).fold(() -> onInvalidPreamble(ctx), lane -> routePreamble(ctx, lane));
        }

        private Unit onInvalidPreamble(ChannelHandlerContext ctx) {
            log.warn("Missing or invalid stream preamble from {} — closing",
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
            return laneRouter.attach(ctx, this, lane);
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
                admitHello(ctx, hello);
            } else {
                log.warn("Expected Hello message but received: {}",
                         option(message).map(Object::getClass).map(Class::getSimpleName));
                ctx.close();
            }
        }

        /// Boot-token gate (terminal removal): a different process for a known NodeId — or any
        /// process for a retired one — is refused before any response or registration, so no
        /// admission path downstream (fresh attach, RECONNECT of an EVICTED/SUSPECT peer, tombstone
        /// re-admission) is ever reached by a refused process.
        private void admitHello(ChannelHandlerContext ctx, NetworkMessage.Hello hello) {
            if (isMisdirected(hello)) {
                refuseMisdirectedHello(ctx, hello);

                return;
            }

            var admission = bootTokens.admit(hello.sender(), hello.bootToken());

            if (!admission.admitted()) {
                log.warn("QUIC acceptor refused Hello from {} (token {}) by the boot-token gate: {} (refusals={})",
                         hello.sender(),
                         hello.bootToken(),
                         admission,
                         bootTokens.refusals());
                refuseHello(ctx, hello.sender());

                return;
            }

            sendHelloResponse(ctx);
            registerPeerConnection(ctx, hello);
        }

        /// A Hello naming a different intended peer than this node was dialed at an address that now
        /// belongs to us (recycled IP, stale DNS). An absent `intendedPeer` is never misdirected.
        private boolean isMisdirected(NetworkMessage.Hello hello) {
            return hello.intendedPeer()
                        .filter(intended -> !intended.equals(selfId))
                        .isPresent();
        }

        /// The dialer verifies identity only AFTER the acceptor has attached, and the acceptor's attach
        /// can supersede a healthy incumbent link (`PeerState.attachOverConnected`). So answer with our
        /// own Hello (the dialer's existing identity check then fails the dial at once, on the ordinary
        /// connect-failure path — closing silently would leave it pinned CONNECTING until the staleness
        /// sweep) but NEVER register the connection, then close it. Deliberately NOT a
        /// [NetworkMessage.HelloRefused] — that tells the dialer its own identity is retired and makes
        /// it exit.
        private void refuseMisdirectedHello(ChannelHandlerContext ctx, NetworkMessage.Hello hello) {
            log.warn("QUIC acceptor refused misdirected Hello from {}: intended={} self={}",
                     hello.sender(),
                     hello.intendedPeer(),
                     selfId);
            sendHelloResponse(ctx).addListener(_ -> ctx.channel()
                                                       .parent()
                                                       .close());
        }

        /// Answer a refused Hello with an explicit [NetworkMessage.HelloRefused] — in place of the Hello
        /// response — so the refused process learns it can never be admitted and exits, then close.
        private void refuseHello(ChannelHandlerContext ctx, NodeId refused) {
            var refusal = serializer.encode(new NetworkMessage.HelloRefused(selfId,
                                                                            refused,
                                                                            "NodeId " + refused.id()
                                                                           + " belongs to a retired process; start with a fresh identity"));

            ctx.writeAndFlush(Unpooled.wrappedBuffer(refusal)).addListener(_ -> ctx.channel()
                                                                                   .parent()
                                                                                   .close());
        }

        private Object decodeMessage(ByteBuf buf) {
            var bytes = new byte[buf.readableBytes()];

            buf.readBytes(bytes);

            return deserializer.decode(bytes);
        }

        private ChannelFuture sendHelloResponse(ChannelHandlerContext ctx) {
            // Responses flowing back from the acceptor carry NO preamble.
            var helloBytes = serializer.encode(new NetworkMessage.Hello(selfId,
                                                                        selfAddress,
                                                                        selfLabels,
                                                                        bootTokens.self(),
                                                                        Option.none()));
            var written = ctx.writeAndFlush(Unpooled.wrappedBuffer(helloBytes));
            // #726: PAYLOAD bytes at the lane boundary — same honesty boundary as every other write.
            quicMetrics.onBytesSent(helloBytes.length);

            return written;
        }

        private void registerPeerConnection(ChannelHandlerContext ctx, NetworkMessage.Hello hello) {
            var quicChannel = (QuicChannel) ctx.channel().parent();
            var peerConnection = quicPeerConnection(hello.sender(), hello.sender(), quicChannel);
            // The handshake stream is the CONTROL lane.
            var _ = peerConnection.registerStream(StreamType.CONTROL, (QuicStreamChannel) ctx.channel());
            // Install the lazy lane-opener so a write that races the data-lane preamble window can
            // (re)open the missing lane on this live channel instead of failing "No stream available".
            // The acceptor formerly populated its stream table ONLY passively (as the dialer's
            // preamble frames arrived via attachDataLane), so onPeerConnected published the peer
            // CONNECTED with only CONTROL present — the QUIC reconnect stream-zombie.
            peerConnection.laneOpener(laneOpenerFor(quicChannel, hello.sender(), peerConnection));
            // Stamp the parent QuicChannel so subsequently-accepted data-lane streams can find
            // the peer connection by reading this attribute.
            quicChannel.attr(PeerOpenedLaneRouter.PEER_CONNECTION).set(peerConnection);
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
            // #1578: report the stream the lane KEEPS — a dialer-opened stream that arrived while this
            // open was in flight outranks it, and the messages waiting on the open belong on that one.
            var kept = peerConnection.registerStream(lane, streamChannel);

            log.info("Lazily (re)opened {} lane to peer {} — stream-zombie healed without re-dial", lane, peerNodeId);
            onResult.accept(option(kept));
        }
    }
}
