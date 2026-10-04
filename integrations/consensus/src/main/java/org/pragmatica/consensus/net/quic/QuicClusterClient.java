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
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.BootTokens;
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

    /// #1578: the peer went CONNECTED over another link, so a dial to it that is still pending is dropped —
    /// but ONLY while its QUIC handshake has not completed. That is the safety line: this client sends its
    /// Hello only after the QUIC connect succeeds (`handleQuicConnect` → `sendHello`), and the acceptor
    /// registers a connection only on receiving a Hello (`QuicClusterServer` `admitHello` →
    /// `registerPeerConnection`), so the peer can never have adopted an attempt abandoned here. An attempt
    /// past its handshake is left alone; the lower-id convergence rule in `PeerState` resolves it. The
    /// abandoned dial fails with [QuicTransportError.DialAbandoned]. No-op when nothing is pending.
    Unit abandonPendingDial(NodeId peerId);

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
        return quicClusterClient(selfId,
                                 selfAddress,
                                 selfLabels,
                                 serializer,
                                 deserializer,
                                 quicMetrics,
                                 sslContext,
                                 eventLoop,
                                 messageReceiver,
                                 BootTokens.bootTokens(0L));
    }

    /// As above, admitting every Hello response through `bootTokens` (shared with SWIM) and
    /// carrying `bootTokens.self()` on this node's own Hello.
    static QuicClusterClient quicClusterClient(NodeId selfId,
                                               NodeAddress selfAddress,
                                               Map<String, String> selfLabels,
                                               Serializer serializer,
                                               Deserializer deserializer,
                                               QuicTransportMetrics quicMetrics,
                                               QuicSslContext sslContext,
                                               Option<EventLoopGroup> eventLoop,
                                               QuicClusterServer.MessageReceiver messageReceiver,
                                               BootTokens bootTokens) {
        return new QuicClusterClientInstance(selfId,
                                             selfAddress,
                                             selfLabels,
                                             serializer,
                                             deserializer,
                                             quicMetrics,
                                             sslContext,
                                             eventLoop,
                                             messageReceiver,
                                             bootTokens);
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

        @Override
        public Unit abandonPendingDial(NodeId peerId) {
            return unit();
        }
    }
}

final class QuicClusterClientInstance implements QuicClusterClient {
    private static final Logger log = LoggerFactory.getLogger(QuicClusterClientInstance.class);
    private static final long DEFAULT_HELLO_TIMEOUT_MS = 15_000;
    private static final long MAX_IDLE_TIMEOUT_MS = 0;  // Disabled per QUIC RFC 9000 §10.1 — cluster connections are persistent
    private static final long INITIAL_MAX_DATA = 64_000_000;

    private static final int MAX_FRAME_LENGTH = org.pragmatica.consensus.net.OutboundMessageLimit.MAX_FRAME_BYTES;

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

    /// The bound on the Hello answer. A field only so a test can shorten it; production leaves the default.
    private volatile long helloTimeoutMs = DEFAULT_HELLO_TIMEOUT_MS;

    /// Test seam: shorten the Hello bound (applies to dials started after the call).
    @Contract
    void helloTimeoutForTest(long millis) {
        helloTimeoutMs = millis;
    }

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
    private final BootTokens bootTokens;
    private final PeerOpenedLaneRouter laneRouter;
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
    /// #1578: the newest dial attempt per peer whose promise has not resolved yet, for [#abandonPendingDial].
    private final Map<NodeId, DialAttempt> pendingDials = new ConcurrentHashMap<>();

    private enum DialStage {
        PENDING,
        ESTABLISHED,
        ABANDONED
    }

    /// One dial attempt. Its stage leaves PENDING exactly once: to ESTABLISHED when the QUIC connect succeeds
    /// (before any Hello is sent), or to ABANDONED — the compare-and-set makes the two mutually exclusive, so
    /// an attempt is never abandoned after its Hello can have gone out.
    private record DialAttempt(Promise<QuicPeerConnection> promise,
                               AtomicReference<DialStage> stage,
                               AtomicReference<Channel> datagram) {
        static DialAttempt dialAttempt(Promise<QuicPeerConnection> promise) {
            return new DialAttempt(promise, new AtomicReference<>(DialStage.PENDING), new AtomicReference<>());
        }

        boolean establish() {
            return stage.compareAndSet(DialStage.PENDING, DialStage.ESTABLISHED);
        }

        boolean abandon() {
            return stage.compareAndSet(DialStage.PENDING, DialStage.ABANDONED);
        }

        boolean abandoned() {
            return stage.get() == DialStage.ABANDONED;
        }
    }

    QuicClusterClientInstance(NodeId selfId,
                              NodeAddress selfAddress,
                              Map<String, String> selfLabels,
                              Serializer serializer,
                              Deserializer deserializer,
                              QuicTransportMetrics quicMetrics,
                              QuicSslContext sslContext,
                              Option<EventLoopGroup> eventLoop,
                              QuicClusterServer.MessageReceiver messageReceiver,
                              BootTokens bootTokens) {
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
        this.bootTokens = bootTokens;
        this.laneRouter = new PeerOpenedLaneRouter(deserializer, quicMetrics, messageReceiver, log);
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

    /// A resolved address with a real `InetAddress` behind it — the two things Netty's QUIC
    /// `SockaddrIn` dereferences. Named so the dial guard reads as one question.
    private static boolean isDialable(InetSocketAddress address) {
        return ! address.isUnresolved() && address.getAddress() != null;
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty bootstrap bind
    private void initiateConnection(NodeId peerId, InetSocketAddress address, Promise<QuicPeerConnection> promise) {
        // Guard against an unresolved/null peer address (e.g. a stale or unknown DNS name).
        // Handing such an address to Netty's QUIC SockaddrIn would dereference a null
        // InetAddress and crash the node with an NPE. Instead fail the dial cleanly down the
        // same connection-failure path a normal dial failure takes, so the caller retries on
        // a later tick.
        // #1442: wrapped at the boundary rather than null-checked in place. Behaviour is unchanged —
        // the same three conditions still refuse the dial, and `String.valueOf` still renders a null
        // address as "null" in the cause.
        if (Option.option(address).filter(QuicClusterClientInstance::isDialable).isEmpty()) {
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

        var attempt = DialAttempt.dialAttempt(promise);

        pendingDials.put(peerId, attempt);
        promise.onResultRun(() -> pendingDials.remove(peerId, attempt));
        var codec = buildQuicCodec();
        // NO SO_REUSEADDR on the dial socket (#1578). UDP has no TIME_WAIT to escape, and on Linux a reuse-enabled
        // bind(0) may be handed the port of another reuse-enabled socket — a QUIC server's — after which that port's
        // inbound datagrams go to this socket, silencing the server. Measured: 7 of 36,000 such binds landed on one of
        // 5 server ports; 0 of 20,000 without the option.
        var bootstrap = new Bootstrap().group(eventLoopGroup).channel(NioDatagramChannel.class).handler(codec);

        bootstrap.bind(0).addListener(future -> handleBind(peerId, address, attempt, future));
    }

    @Override
    public Unit abandonPendingDial(NodeId peerId) {
        option(pendingDials.get(peerId)).filter(DialAttempt::abandon)
              .onPresent(attempt -> releaseAbandoned(peerId, attempt));

        return unit();
    }

    /// The attempt is ABANDONED (its QUIC handshake never completed, so no Hello went out on it): release its
    /// socket if it has one yet, and fail its dial with the typed cause. A socket bound later is released
    /// by [#handleBind], which sees the stage.
    private void releaseAbandoned(NodeId peerId, DialAttempt attempt) {
        log.debug("Abandoning pending QUIC dial to {}: the peer is connected over another link", peerId);
        // Fail the dial BEFORE closing its socket: the close fails Netty's pending connect on the client event loop
        // (QuicClosedChannelException → a generic ConnectFailed), and whichever resolves the promise first wins. The
        // typed cause must win, or the abandoned dial is reported as a connect failure of a CONNECTED peer.
        attempt.promise().fail(QuicTransportError.DialAbandoned.FACTORY.apply(peerId));
        option(attempt.datagram().get()).onPresent(channel -> releaseDatagram(peerId, channel));
    }

    private void releaseDatagram(NodeId peerId, Channel channel) {
        var _ = datagramChannels.remove(peerId, channel);

        channel.close();
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty future callback
    private void handleBind(NodeId peerId,
                            InetSocketAddress address,
                            DialAttempt attempt,
                            io.netty.util.concurrent.Future<? super Void> future) {
        var promise = attempt.promise();

        if (!future.isSuccess()) {
            promise.fail(QuicTransportError.ConnectFailed.FACTORY.apply(address.toString(),
                                                                        Causes.fromThrowable(future.cause())));

            return;
        }

        var newChannel = ((io.netty.channel.ChannelFuture) future).channel();

        attempt.datagram().set(newChannel);
        if (attempt.abandoned()) {
            newChannel.close();

            return;
        }
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

        connectQuicChannel(newChannel, peerId, address, attempt);
    }

    @SuppressWarnings("JBCT-PAT-01")  // Netty QUIC channel bootstrap
    private void connectQuicChannel(Channel channel, NodeId peerId, InetSocketAddress address, DialAttempt attempt) {
        QuicChannel.newBootstrap(channel)
                   .handler(new ClientConnectionInitializer())
                   .streamHandler(new DialerStreamInitializer())
                   .remoteAddress(address)
                   .connect()
                   .addListener(future -> handleQuicConnect(peerId, address, attempt, future));
    }

    /// #1489: a TLS handshake failure (an [javax.net.ssl.SSLException] anywhere in the cause chain) is reported as
    /// [QuicTransportError.HandshakeFailed]; every other connect failure stays [QuicTransportError.ConnectFailed].
    private static QuicTransportError connectFailure(InetSocketAddress address, Throwable failure) {
        return isTlsFailure(failure)
               ? QuicTransportError.HandshakeFailed.FACTORY.apply(address.toString(), Causes.fromThrowable(failure))
               : QuicTransportError.ConnectFailed.FACTORY.apply(address.toString(), Causes.fromThrowable(failure));
    }

    private static boolean isTlsFailure(Throwable failure) {
        return java.util.stream.Stream.iterate(failure, java.util.Objects::nonNull, Throwable::getCause)
                                      .limit(16)
                                      .anyMatch(javax.net.ssl.SSLException.class::isInstance);
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"})  // Netty future callback
    private void handleQuicConnect(NodeId peerId,
                                   InetSocketAddress address,
                                   DialAttempt attempt,
                                   io.netty.util.concurrent.Future<?> future) {
        var promise = attempt.promise();

        if (!future.isSuccess()) {
            promise.fail(connectFailure(address, future.cause()));

            return;
        }

        var quicChannel = (QuicChannel) future.getNow();
        // #1578: the QUIC handshake is complete. Leave PENDING before any Hello can be written; if the attempt
        // was abandoned first, nothing has been sent on this connection, so close it and send nothing.
        if (!attempt.establish()) {
            quicChannel.close();

            return;
        }

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
        var preamble = new byte[]{(byte) StreamType.CONTROL.streamIndex()};

        streamChannel.writeAndFlush(Unpooled.wrappedBuffer(preamble));
        // #726: PAYLOAD bytes at the lane boundary — the handshake preamble is a real frame
        // handed to the channel, same honesty boundary as every other write.
        quicMetrics.onBytesSent(preamble.length);
        var helloBytes = serializer.encode(new NetworkMessage.Hello(selfId,
                                                                    selfAddress,
                                                                    selfLabels,
                                                                    bootTokens.self(),
                                                                    Option.some(peerId)));

        streamChannel.writeAndFlush(Unpooled.wrappedBuffer(helloBytes));
        quicMetrics.onBytesSent(helloBytes.length);
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

    /// #1578 — initializer for streams the ACCEPTOR opens on this connection. There was none: a lane
    /// the acceptor lazily opened (a write racing this dialer's preamble frames) was read by nobody,
    /// so everything written on it vanished while the acceptor saw successful writes. Same framing as
    /// every other lane; the preamble is routed through the acceptor's own [PeerOpenedLaneRouter].
    private class DialerStreamInitializer extends ChannelInitializer<QuicStreamChannel> {
        @Override
        @Contract
        protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline()
              .addLast(new io.netty.handler.codec.LengthFieldBasedFrameDecoder(MAX_FRAME_LENGTH, 0, 4, 0, 4))
              .addLast(new io.netty.handler.codec.LengthFieldPrepender(4))
              .addLast(new DialerStreamHandler());
        }
    }

    /// Reads the 1-byte lane preamble of an acceptor-opened stream and hands the stream to
    /// [PeerOpenedLaneRouter]. CONTROL is refused: the handshake lane is always dialer-opened.
    private class DialerStreamHandler extends SimpleChannelInboundHandler<ByteBuf> {
        @Override
        @Contract
        protected void channelRead0(ChannelHandlerContext ctx, ByteBuf buf) {
            // #726: the preamble is a real frame at the lane boundary, counted like the acceptor's.
            quicMetrics.onBytesReceived(buf.readableBytes());
            PeerOpenedLaneRouter.preambleLane(buf)
                                .filter(lane -> lane != StreamType.CONTROL)
                                .fold(() -> refusePreamble(ctx),
                                      lane -> laneRouter.attach(ctx, this, lane));
        }

        private Unit refusePreamble(ChannelHandlerContext ctx) {
            log.warn("Missing, invalid or CONTROL preamble on an acceptor-opened stream from {} — closing",
                     ctx.channel().remoteAddress());
            ctx.close();

            return unit();
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
            // #726: PAYLOAD bytes at the lane boundary — the Hello-response frame received on
            // CONTROL before the pipeline hands off to QuicLaneDataHandler for ongoing traffic.
            // Read before processHelloResponse touches the buffer, so it reflects the full frame.
            quicMetrics.onBytesReceived(buf.readableBytes());
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

        /// #1694: the peer closed the connection before answering our Hello — e.g. the server refused our client
        /// certificate after the TLS handshake had completed on our side. Nothing failed the dial on this path: the
        /// Hello timeout below guarded on the channel still being active, so the connect promise never settled.
        /// Failing an already-settled promise is a no-op, so a close after the Hello answer changes nothing.
        @Override
        @Contract
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            if (!helloReceived) {
                promise.fail(QuicTransportError.ConnectFailed.FACTORY.apply(peerId.id(),
                                                                            Causes.cause("the peer closed the connection before answering the Hello")));
            }

            super.channelInactive(ctx);
        }

        private void scheduleHelloTimeout(ChannelHandlerContext ctx) {
            ctx.executor().schedule(() -> onHelloTimeout(ctx), helloTimeoutMs, TimeUnit.MILLISECONDS);
        }

        /// #1694: the timeout settles the dial whether or not the channel is still active; it only closes an active one.
        private void onHelloTimeout(ChannelHandlerContext ctx) {
            if (helloReceived) {
                return;
            }

            log.warn("Hello response timeout for peer {}", peerId);
            promise.fail(HELLO_TIMEOUT);
            if (ctx.channel().isActive()) {
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
            } else if (message instanceof NetworkMessage.HelloRefused refused) {
                onHelloRefused(ctx, refused);
            } else {
                log.warn("Expected Hello response from peer {} but received: {}",
                         peerId,
                         option(message).map(Object::getClass).map(Class::getSimpleName));
                promise.fail(UNEXPECTED_MESSAGE);
                ctx.close();
            }
        }

        /// The acceptor refused THIS process's identity (terminal removal). Hand it to the shared registry,
        /// whose self-refusal listener makes the node log ERROR and exit, and fail the dial.
        private void onHelloRefused(ChannelHandlerContext ctx, NetworkMessage.HelloRefused refused) {
            if (refused.refused().equals(selfId)) {
                log.error("QUIC peer {} refused this process's identity {}: {}",
                          refused.sender(),
                          selfId,
                          refused.reason());
                bootTokens.selfRefused(refused.reason());
            }

            promise.fail(QuicTransportError.BootTokenRefused.FACTORY.apply(selfId, "SELF_REFUSED"));
            ctx.close();
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
            // Boot-token gate (terminal removal): a different process answering for a known
            // NodeId — or any process for a retired one — is refused before any attach, on the
            // same connect-failure path as an identity mismatch.
            var admission = bootTokens.admit(peerId, hello.bootToken());

            if (!admission.admitted()) {
                log.warn("QUIC dialer refused {} (token {}) by the boot-token gate: {} (refusals={})",
                         peerId,
                         hello.bootToken(),
                         admission,
                         bootTokens.refusals());
                promise.fail(QuicTransportError.BootTokenRefused.FACTORY.apply(peerId, admission.name()));
                quicChannel.close();

                return;
            }
            // peerId == hello.sender() (verified above): the connection is registered under the
            // VERIFIED identity, consistent with `datagramChannels` (keyed by the dialed peerId
            // at bind time) — eviction closes the right channel, and no code path can register
            // a connection under an unverified id.
            var peerConnection = quicPeerConnection(peerId, selfId, quicChannel);
            // The handshake stream is the CONTROL lane.
            var _ = peerConnection.registerStream(StreamType.CONTROL, (QuicStreamChannel) ctx.channel());
            // #1578: stamp the VERIFIED connection so a lane the acceptor opens finds it (the acceptor
            // opens lanes only after answering this Hello, so the stamp precedes them).
            quicChannel.attr(PeerOpenedLaneRouter.PEER_CONNECTION).set(peerConnection);
            // Install the lazy lane-opener so a write that finds a lost data lane can re-open it on
            // the live channel instead of failing "No stream available" (symmetry with the acceptor).
            peerConnection.laneOpener((lane, onResult) -> openLaneStream(peerConnection, peerId, lane, onResult));
            // Swap the CONTROL stream's Hello handler for the shared data handler (CONTROL lane).
            ctx.pipeline()
               .replace(this,
                        "data-handler",
                        new QuicLaneDataHandler(peerId,
                                                StreamType.CONTROL,
                                                deserializer,
                                                quicMetrics,
                                                messageReceiver,
                                                log));
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
            var preamble = new byte[]{(byte) lane.streamIndex()};

            streamChannel.writeAndFlush(Unpooled.wrappedBuffer(preamble));
            // #726: PAYLOAD bytes at the lane boundary — the lane preamble is a real frame.
            quicMetrics.onBytesSent(preamble.length);
            var _ = peerConnection.registerStream(lane, streamChannel);

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
            var preamble = new byte[]{(byte) lane.streamIndex()};

            streamChannel.writeAndFlush(Unpooled.wrappedBuffer(preamble));
            // #726: PAYLOAD bytes at the lane boundary — lazy-reopen preamble is a real frame too.
            quicMetrics.onBytesSent(preamble.length);
            // #1578: report the stream the lane KEEPS, which is where messages waiting on this open belong.
            var kept = peerConnection.registerStream(lane, streamChannel);

            log.info("Lazily (re)opened {} lane to peer {} — stream-zombie healed without re-dial", lane, peerNodeId);
            onResult.accept(option(kept));
        }
    }
}
