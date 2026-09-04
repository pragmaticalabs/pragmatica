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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

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
import io.netty.resolver.DefaultNameResolver;
import io.netty.resolver.NameResolver;
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
    /// Resolve a hostname to an [InetAddress] **non-blocking**, on the client's Netty event loop
    /// (via [io.netty.resolver.DefaultNameResolver]). Used by the dialer to defer DNS resolution to
    /// dial time — a stale hostname that fails to resolve fails the returned promise cleanly instead
    /// of producing an eagerly-constructed *unresolved* `InetSocketAddress` that
    /// [#connect] would reject on every reconciler tick. The caller constructs the dial target from
    /// the resolved address (`new InetSocketAddress(inetAddress, port)`, which never re-resolves).
    Promise<InetAddress> resolve(String host);
    /// Shut down the client and release resources.
    Promise<Unit> close();
    /// Close the per-peer datagram (UDP) channel allocated by [#connect].
    ///
    /// Each successful or attempted `connect` allocates a fresh ephemeral UDP socket
    /// via `bootstrap.bind(0)`. When the QUIC link to that peer is evicted, the
    /// underlying datagram channel must be closed too — otherwise the kernel-level
    /// socket leaks until JVM exit. Idempotent: a missing entry resolves immediately.
    Promise<Unit> closeDatagramChannel(NodeId peerId);

    /// Snapshot the count of currently-tracked datagram channels. Test/diagnostic only.
    int datagramChannelCount();

    /// Create a new QUIC cluster client.
    ///
    /// @param selfId          this node's identity
    /// @param selfAddress     this node's cluster address
    /// @param selfLabels      this node's metadata labels
    /// @param serializer      message serializer
    /// @param deserializer    message deserializer
    /// @param quicMetrics     transport metrics sink (payload byte/message counters; #726)
    /// @param sslContext      QUIC client SSL context (TLS 1.3)
    /// @param eventLoop       optional shared event loop group
    /// @param messageReceiver callback invoked for each message received after Hello
    static QuicClusterClient quicClusterClient(NodeId selfId,
                                               NodeAddress selfAddress,
                                               Map<String, String> selfLabels,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               QuicTransportMetrics quicMetrics,
                                               QuicSslContext sslContext,
                                               Option<EventLoopGroup> eventLoop,
                                               QuicClusterServer.MessageReceiver messageReceiver) {
        return new QuicClusterClientInstance(selfId,
                                             selfAddress,
                                             selfLabels,
                                             serializer,
                                             deserializer,
                                             quicMetrics,
                                             sslContext,
                                             eventLoop,
                                             messageReceiver);
    }

    record Unused() implements QuicClusterClient {
        @Override
        public Promise<QuicPeerConnection> connect(NodeId peerId, InetSocketAddress address) {
            return UNEXPECTED_MESSAGE.promise();
        }

        @Override
        public Promise<InetAddress> resolve(String host) {
            return UNEXPECTED_MESSAGE.promise();
        }

        @Override
        public Promise<Unit> close() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> closeDatagramChannel(NodeId peerId) {
            return Promise.unitPromise();
        }

        @Override
        public int datagramChannelCount() {
            return 0;
        }
    }
}

final class QuicClusterClientInstance implements QuicClusterClient {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterClientInstance.class);
    private static final long HELLO_TIMEOUT_MS = 15_000;
    private static final long MAX_IDLE_TIMEOUT_MS = 0;  // Disabled per QUIC RFC 9000 §10.1 — cluster connections are persistent
    private static final long INITIAL_MAX_DATA = 64_000_000;
    private static final int MAX_FRAME_LENGTH = 32 * 1024 * 1024;
    private static final long INITIAL_MAX_STREAM_DATA = 32_000_000;
    private static final long INITIAL_MAX_STREAMS = 64;

    /// Data-lane streams the dialer opens after the CONTROL handshake stream, in this fixed
    /// order. Excludes CONTROL (already established by the handshake). Seven lanes.
    private static final StreamType[] DATA_LANES = {StreamType.CONSENSUS,
                                                    StreamType.KV,
                                                    StreamType.METRICS,
                                                    StreamType.INVOKE,
                                                    StreamType.FORWARD,
                                                    StreamType.DHT,
                                                    StreamType.SYNC};

    private final NodeId selfId;
    private final NodeAddress selfAddress;
    private final Map<String, String> selfLabels;
    private final Serializer serializer;
    private final Deserializer deserializer;
    private final QuicTransportMetrics quicMetrics;
    private final QuicSslContext sslContext;
    private final EventLoopGroup eventLoopGroup;
    private final boolean ownsEventLoop;
    private final QuicClusterServer.MessageReceiver messageReceiver;
    /// Non-blocking DNS resolver backed by the client's Netty event loop. Lazily built on first
    /// [#resolve] so construction stays cheap and tests that never dial never allocate it. The
    /// `DefaultNameResolver` performs the JDK lookup on the supplied [io.netty.util.concurrent.EventExecutor],
    /// never on the reconciler thread that initiates the dial.
    private volatile NameResolver<InetAddress> nameResolver;
    /// Per-peer ephemeral UDP socket. `bootstrap.bind(0)` is invoked on every
    /// `connect(peerId, ...)` call, so without per-peer tracking the previous channel
    /// reference would be dropped (and the kernel-level socket leaked) on every
    /// reconnect. The map is the single ownership root for client-side datagram
    /// channels and is drained by [#closeDatagramChannel] / [#initiateClose].
    private final Map<NodeId, Channel> datagramChannels = new ConcurrentHashMap<>();

    QuicClusterClientInstance(NodeId selfId,
                              NodeAddress selfAddress,
                              Map<String, String> selfLabels,
                              Serializer serializer,
                              Deserializer deserializer,
                              QuicTransportMetrics quicMetrics,
                              QuicSslContext sslContext,
                              Option<EventLoopGroup> eventLoop,
                              QuicClusterServer.MessageReceiver messageReceiver) {
        this.selfId = selfId;
        this.selfAddress = selfAddress;
        this.selfLabels = Map.copyOf(selfLabels);
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.quicMetrics = quicMetrics;
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
    public Promise<InetAddress> resolve(String host) {
        return Promise.promise(promise -> resolveHost(host, promise));
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty resolver future callback
    private void resolveHost(String host, Promise<InetAddress> promise) {
        nameResolver().resolve(host).addListener(future -> completeResolve(host, promise, future));
    }

    private void completeResolve(String host,
                                 Promise<InetAddress> promise,
                                 io.netty.util.concurrent.Future<? super InetAddress> future) {
        if (future.isSuccess()) {
            promise.succeed((InetAddress) future.getNow());
        } else {
            promise.fail(QuicTransportError.UnresolvedAddress.FACTORY.apply(host));
        }
    }

    /// Lazily build the event-loop-backed name resolver. Double-checked under the instance monitor
    /// so concurrent first-dials share one resolver bound to one event executor.
    private NameResolver<InetAddress> nameResolver() {
        var existing = nameResolver;

        if (existing != null) {
            return existing;
        }

        return buildNameResolver();
    }

    private synchronized NameResolver<InetAddress> buildNameResolver() {
        if (nameResolver == null) {
            nameResolver = new DefaultNameResolver(eventLoopGroup.next());
        }

        return nameResolver;
    }

    @Override
    public Promise<Unit> close() {
        return Promise.promise(this::initiateClose);
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty bootstrap bind
    private void initiateConnection(NodeId peerId, InetSocketAddress address, Promise<QuicPeerConnection> promise) {
        // Guard against an unresolved/null peer address (e.g. a stale or unknown DNS name).
        // Handing such an address to Netty's QUIC SockaddrIn would dereference a null
        // InetAddress and crash the node with an NPE. Instead fail the dial cleanly down the
        // same connection-failure path a normal dial failure takes, so the caller retries on
        // a later tick.
        if (address == null || address.isUnresolved() || address.getAddress() == null) {
            log.debug("Skipping QUIC dial to peer {}: unresolved address {}", peerId, address);
            promise.fail(QuicTransportError.UnresolvedAddress.FACTORY.apply(String.valueOf(address)));

            return;
        }
        // Close any previously-tracked datagram channel for this peer BEFORE allocating a new
        // ephemeral UDP socket. Without this, repeated reconnects (e.g. eviction storm at 1Hz
        // during chaos tests) leaked one socket per reconnect to JVM exit.
        var stale = datagramChannels.remove(peerId);

        if (stale != null) {
            stale.close();
        }

        var codec = buildQuicCodec();
        var bootstrap = new Bootstrap().group(eventLoopGroup)
                                       .channel(NioDatagramChannel.class)
                                       // SO_REUSEADDR: defensive on the client side — eliminates rebind hangs if a previous
                                       // ephemeral binding lingers in TIME_WAIT during rapid reconnect storms.
                                       .option(ChannelOption.SO_REUSEADDR, true)
                                       .handler(codec);

        bootstrap.bind(0).addListener(future -> handleBind(peerId, address, promise, future));
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty future callback
    private void handleBind(NodeId peerId,
                            InetSocketAddress address,
                            Promise<QuicPeerConnection> promise,
                            io.netty.util.concurrent.Future<? super Void> future) {
        if (!future.isSuccess()) {
            promise.fail(QuicTransportError.ConnectFailed.FACTORY.apply(address.toString(),
                                                                        Causes.fromThrowable(future.cause())));

            return;
        }

        var newChannel = ((io.netty.channel.ChannelFuture) future).channel();
        // Stash by peerId so eviction / shutdown can close it deterministically. If a
        // concurrent connect for the same peer raced ahead, close the previous entry
        // (defence-in-depth — initiateConnection already removed any stale entry).
        // The dialed peerId IS the verified registration identity: the Wave-3 Hello identity
        // check rejects a mismatched sender before any attach, so the key here always matches
        // the id the connection registers (and is later evicted) under.
        var racedOut = datagramChannels.put(peerId, newChannel);

        if (racedOut != null && racedOut != newChannel) {
            racedOut.close();
        }

        connectQuicChannel(newChannel, peerId, address, promise);
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty QUIC channel bootstrap
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

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty future callback
    private void handleQuicConnect(NodeId peerId,
                                   InetSocketAddress address,
                                   Promise<QuicPeerConnection> promise,
                                   io.netty.util.concurrent.Future<?> future) {
        if (!future.isSuccess()) {
            promise.fail(QuicTransportError.ConnectFailed.FACTORY.apply(address.toString(),
                                                                        Causes.fromThrowable(future.cause())));

            return;
        }

        var quicChannel = (QuicChannel) future.getNow();

        openStreamAndHandshake(quicChannel, peerId, promise);
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty stream creation
    private void openStreamAndHandshake(QuicChannel quicChannel, NodeId peerId, Promise<QuicPeerConnection> promise) {
        // QUIC streams are byte-oriented — need framing to delimit messages
        var streamInitializer = new ChannelInitializer<QuicStreamChannel>() {
            @Override
            @Contract
            protected void initChannel(QuicStreamChannel ch) {
                ch.pipeline()
                  .addLast(new io.netty.handler.codec.LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4))
                  .addLast(new io.netty.handler.codec.LengthFieldPrepender(4))
                  .addLast(new ClientHelloHandler(peerId, quicChannel, promise));
            }
        };

        quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, streamInitializer)
                   .addListener(future -> handleStreamCreated(peerId, promise, future));
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty future callback
    private void handleStreamCreated(NodeId peerId,
                                     Promise<QuicPeerConnection> promise,
                                     io.netty.util.concurrent.Future<?> future) {
        if (!future.isSuccess()) {
            promise.fail(QuicTransportError.StreamCreationFailed.FACTORY.apply(Causes.fromThrowable(future.cause())));

            return;
        }

        var streamChannel = (QuicStreamChannel) future.getNow();

        sendHello(streamChannel, peerId);
    }

    private void sendHello(QuicStreamChannel streamChannel, NodeId peerId) {
        // The handshake stream is now the CONTROL lane. Write the 1-byte lane preamble first
        // (its own framed message), then the Hello frame, so the acceptor attributes this
        // stream to CONTROL before reading the Hello.
        streamChannel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{(byte) StreamType.CONTROL.streamIndex()}));
        var helloBytes = serializer.encode(new NetworkMessage.Hello(selfId, selfAddress, selfLabels));

        streamChannel.writeAndFlush(Unpooled.wrappedBuffer(helloBytes));
        log.debug("Sent CONTROL preamble + Hello to peer {} on stream", peerId);
    }

    private io.netty.channel.ChannelHandler buildQuicCodec() {
        return new QuicClientCodecBuilder().sslContext(sslContext)
                                           .maxIdleTimeout(MAX_IDLE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                                           .initialMaxData(INITIAL_MAX_DATA)
                                           .initialMaxStreamDataBidirectionalLocal(INITIAL_MAX_STREAM_DATA)
                                           .initialMaxStreamDataBidirectionalRemote(INITIAL_MAX_STREAM_DATA)
                                           .initialMaxStreamsBidirectional(INITIAL_MAX_STREAMS)
                                           // Enables QUIC connection migration so a path change (not a socket teardown)
                                           // survives without a reconnect.
                                           .activeMigration(true)
                                           .build();
    }

    private static EventLoopGroup createEventLoop() {
        return new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
    }

    @SuppressWarnings("JBCT-PAT-01")  // Lifecycle: close all per-peer channels then shut event loop
    private void initiateClose(Promise<Unit> promise) {
        var snapshot = new ArrayList<Channel>(datagramChannels.values());

        datagramChannels.clear();
        if (snapshot.isEmpty()) {
            shutdownEventLoop(promise);

            return;
        }

        var pending = new AtomicInteger(snapshot.size());

        for (var ch : snapshot) {
            ch.close().addListener(_ -> {
                if (pending.decrementAndGet() == 0) {
                    shutdownEventLoop(promise);
                }
            });
        }
    }

    @Override
    public Promise<Unit> closeDatagramChannel(NodeId peerId) {
        var channel = datagramChannels.remove(peerId);

        if (channel == null) {
            return Promise.unitPromise();
        }

        return Promise.promise(promise -> channel.close()
                                                 .addListener(_ -> promise.succeed(unit())));
    }

    @Override
    public int datagramChannelCount() {
        return datagramChannels.size();
    }

    private void shutdownEventLoop(Promise<Unit> promise) {
        var resolver = nameResolver;

        if (resolver != null) {
            resolver.close();
        }

        if (!ownsEventLoop) {
            promise.succeed(unit());

            return;
        }

        eventLoopGroup.shutdownGracefully().addListener(_ -> promise.succeed(unit()));
    }

    /// Per-connection initializer (no-op for raw QUIC client).
    private static class ClientConnectionInitializer extends ChannelInitializer<QuicChannel> {
        @Override
        @Contract
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

        ClientHelloHandler(NodeId peerId, QuicChannel quicChannel, Promise<QuicPeerConnection> promise) {
            this.peerId = peerId;
            this.quicChannel = quicChannel;
            this.promise = promise;
        }

        @Override
        @Contract
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            if (helloReceived) {
                return;
            }

            helloReceived = true;
            processHelloResponse(ctx, buf);
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
            log.error("Error in QUIC client Hello handler for peer {}", peerId, cause);
            promise.fail(QuicTransportError.ConnectFailed.FACTORY.apply(peerId.id(), Causes.fromThrowable(cause)));
            ctx.close();
        }

        private void scheduleHelloTimeout(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> onHelloTimeout(ctx), HELLO_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        }

        private void onHelloTimeout(ChannelHandlerContext ctx) {
            if (!helloReceived && ctx.channel().isActive()) {
                log.warn("Hello response timeout for peer {}", peerId);
                promise.fail(HELLO_TIMEOUT);
                ctx.close();
            }
        }

        @SuppressWarnings("JBCT-PAT-01")  // Adapter boundary: catch deserialization errors from external input
        private void processHelloResponse(ChannelHandlerContext ctx, ByteBuf buf) {
            Object message;

            try {
                message = decodeMessage(buf);
            } catch (Exception e) {
                log.error("Failed to deserialize Hello response from peer {}", peerId, e);
                promise.fail(QuicTransportError.ConnectFailed.FACTORY.apply(peerId.id(), Causes.fromThrowable(e)));
                ctx.close();

                return;
            }

            if (message instanceof NetworkMessage.Hello hello) {
                completePeerConnection(ctx, hello);
            } else {
                log.warn("Expected Hello response from peer {} but received: {}",
                         peerId,
                         option(message).map(Object::getClass).map(Class::getSimpleName));
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
            // Wave-1 §6.1 dialer expected-vs-actual diagnostic: record the dialed identity vs the
            // Hello sender's claimed identity vs the address the dial actually resolved to, on
            // EVERY completed outbound handshake.
            log.info("QUIC dialer Hello identity: dialed={} helloSender={} resolvedAddress={}",
                     peerId,
                     hello.sender(),
                     quicChannel.remoteSocketAddress());
            // Wave-3 dialer-side identity verification: a misdirected dial (e.g. a DNS
            // re-resolution landing on whatever answers) must NOT attach under the wrong identity
            // or supersede a healthy incumbent via adopt-newer. On mismatch: close the connection,
            // do NOT attach, and fail the dial down the normal connect-failure path so the
            // caller's backoff/eviction machinery engages exactly as for any failed dial.
            if (!hello.sender().equals(peerId)) {
                log.warn("QUIC dialer Hello identity mismatch — rejecting connection: dialed={} helloSender={} resolvedAddress={}",
                         peerId,
                         hello.sender(),
                         quicChannel.remoteSocketAddress());
                promise.fail(QuicTransportError.IdentityMismatch.identityMismatch(peerId,
                                                                                  hello.sender(),
                                                                                  String.valueOf(quicChannel.remoteSocketAddress())));
                quicChannel.close();

                return;
            }
            // peerId == hello.sender() (verified above): the connection is registered under the
            // VERIFIED identity, consistent with `datagramChannels` (keyed by the dialed peerId
            // at bind time) — eviction closes the right channel, and no code path can register
            // a connection under an unverified id.
            var peerConnection = quicPeerConnection(peerId, quicChannel);
            // The handshake stream is the CONTROL lane.
            peerConnection.registerStream(StreamType.CONTROL, (QuicStreamChannel) ctx.channel());
            // Install the lazy lane-opener so a write that finds a lost data lane can re-open it on
            // the live channel instead of failing "No stream available" (symmetry with the acceptor).
            peerConnection.laneOpener((lane, onResult) -> openLaneStream(peerConnection, peerId, lane, onResult));
            // Swap the CONTROL stream's Hello handler for the shared data handler (CONTROL lane).
            ctx.pipeline()
               .replace(this,
                        "data-handler",
                        new QuicLaneDataHandler(peerId, StreamType.CONTROL, deserializer, quicMetrics, messageReceiver, log));
            log.info("QUIC Hello handshake complete with peer {} — opening data lanes", peerId);
            openDataLanes(peerConnection, peerId);
        }

        /// Open the 6 data-lane streams (CONSENSUS, KV, METRICS, INVOKE, FORWARD, DHT) and only
        /// succeed the connect promise once ALL of them are created + registered. This guarantees
        /// the dialer attaches (onPeerConnected via promise success) with all 7 lanes present.
        private void openDataLanes(QuicPeerConnection peerConnection, NodeId peerNodeId) {
            var pending = new AtomicInteger(DATA_LANES.length);

            for (var lane : DATA_LANES) {
                openDataLane(peerConnection, peerNodeId, lane, pending);
            }
        }

        @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty stream creation
        private void openDataLane(QuicPeerConnection peerConnection,
                                  NodeId peerNodeId,
                                  StreamType lane,
                                  AtomicInteger pending) {
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

            quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, initializer)
                       .addListener(future -> handleDataLaneCreated(peerConnection, peerNodeId, lane, pending, future));
        }

        @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty future callback
        private void handleDataLaneCreated(QuicPeerConnection peerConnection,
                                           NodeId peerNodeId,
                                           StreamType lane,
                                           AtomicInteger pending,
                                           io.netty.util.concurrent.Future<?> future) {
            if (!future.isSuccess()) {
                log.warn("Failed to open {} lane stream to peer {} — failing connect", lane, peerNodeId, future.cause());
                promise.fail(QuicTransportError.StreamCreationFailed.FACTORY.apply(Causes.fromThrowable(future.cause())));
                quicChannel.close();

                return;
            }

            var streamChannel = (QuicStreamChannel) future.getNow();
            // Write this lane's 1-byte preamble (opener→acceptor, once) so the acceptor
            // attributes the inbound stream to its lane.
            streamChannel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{(byte) lane.streamIndex()}));
            peerConnection.registerStream(lane, streamChannel);
            if (pending.decrementAndGet() == 0) {
                log.info("All 8 lanes registered for peer {} — connection ready", peerNodeId);
                promise.succeed(peerConnection);
            }
        }

        /// Lazily (re)open a single missing `lane` on the live channel — the dial-side mirror of the
        /// acceptor's lane-opener. Reports the registered stream (some) or empty on failure so the
        /// transport write path can heal a lost lane without a full re-dial.
        @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty stream creation for lazy lane re-open
        private void openLaneStream(QuicPeerConnection peerConnection,
                                    NodeId peerNodeId,
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

            quicChannel.createStream(QuicStreamType.BIDIRECTIONAL, initializer)
                       .addListener(future -> completeLazyLaneOpen(peerConnection, peerNodeId, lane, onResult, future));
        }

        @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty future callback for lazy lane re-open
        private void completeLazyLaneOpen(QuicPeerConnection peerConnection,
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

            streamChannel.writeAndFlush(Unpooled.wrappedBuffer(new byte[]{(byte) lane.streamIndex()}));
            peerConnection.registerStream(lane, streamChannel);
            log.info("Lazily (re)opened {} lane to peer {} — stream-zombie healed without re-dial", lane, peerNodeId);
            onResult.accept(option(streamChannel));
        }
    }
}
