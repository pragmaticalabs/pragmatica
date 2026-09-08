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
package org.pragmatica.swim;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.RateLimiter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.resolver.DefaultHostsFileEntriesResolver;
import io.netty.resolver.dns.DnsNameResolver;
import io.netty.resolver.dns.DnsNameResolverBuilder;
import io.netty.util.concurrent.Future;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Netty-based UDP transport for SWIM protocol messages with async DNS resolution.
///
/// Member addresses are stored unresolved (hostname + port). DNS resolution happens
/// asynchronously at send time via Netty's built-in DnsNameResolver, with TTL-based caching.
/// This eliminates stale IPs from cached InetSocketAddress and handles containers
/// whose DNS entries appear after SWIM starts.
public final class NettySwimTransport implements SwimTransport {
    private static final Logger LOG = LoggerFactory.getLogger(NettySwimTransport.class);
    /// ANNOUNCE rate: 10 per second per source IP.
    private static final int ANNOUNCE_RATE_PER_SECOND = 10;
    /// Evict per-source rate limiter entries idle longer than this.
    private static final long ANNOUNCE_LIMITER_IDLE_EVICT_MS = 5 * 60 * 1_000L;
    /// Bound on EACH of the two transport-shutdown waits (#929). Netty's own `shutdownGracefully()`
    /// timeout cannot supply this: it is enforced by `confirmShutdown()`, which runs ON the event
    /// loop, so a wedged loop cannot enforce its own timeout — the caller must. 5 s is far above the
    /// healthy path, which a green nine-node forge teardown measures at a constant 2.00 s per node,
    /// and that 2 s was entirely the quiet period dropped below.
    private static final long SHUTDOWN_TIMEOUT_MS = 5_000L;
    /// No quiet period (#929). Netty defaults to 2 s so a graceful protocol can drain in-flight work.
    /// SWIM is fire-and-forget UDP with nothing to drain, so the wait bought nothing and cost ~2 s
    /// per node — ~18 s of every nine-node forge teardown, on the caller's thread.
    private static final long SHUTDOWN_QUIET_PERIOD_MS = 0L;
    private static final Result<Unit> SHUTDOWN_OK = Result.success(unit());

    private final Serializer serializer;
    private final Deserializer deserializer;
    private final GossipEncryptor encryptor;
    private final Option<EventLoopGroup> externalGroup;
    private final AtomicReference<Option<Channel>> channel = new AtomicReference<>(none());
    private final AtomicReference<Option<EventLoopGroup>> group = new AtomicReference<>(none());
    private final AtomicReference<Option<DnsNameResolver>> nettyResolver = new AtomicReference<>(none());

    /// Test-only silent-death fault injection for the SWIM UDP plane. Mirrors
    /// `QuicClusterNetwork.blackholed`: when true, outbound sends are dropped and inbound
    /// datagrams are discarded before dispatch, so this node neither acks nor observes SWIM
    /// probes — simulating genuine silent death across BOTH transport planes. Default false
    /// (zero effect in normal operation).
    private volatile boolean blackholed = false;

    /// Per-source IP rate limiter map for ANNOUNCE flood protection.
    /// Entries idle > {@value #ANNOUNCE_LIMITER_IDLE_EVICT_MS} ms are evicted lazily.
    private final Map<InetAddress, AnnounceRateLimiterEntry> announceRateLimiters = new ConcurrentHashMap<>();

    private NettySwimTransport(Serializer serializer,
                               Deserializer deserializer,
                               GossipEncryptor encryptor,
                               Option<EventLoopGroup> externalGroup) {
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.encryptor = encryptor;
        this.externalGroup = externalGroup;
    }

    /// Factory creating a SWIM transport with gossip encryption.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer,
                                                           Deserializer deserializer,
                                                           GossipEncryptor encryptor) {
        return Result.success(new NettySwimTransport(serializer, deserializer, encryptor, none()));
    }

    /// Factory creating a SWIM transport without encryption.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer, Deserializer deserializer) {
        return nettySwimTransport(serializer, deserializer, GossipEncryptor.none());
    }

    /// Factory creating a SWIM transport using a shared EventLoopGroup.
    /// Netty's DnsNameResolver is created internally during bind using this event loop.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer,
                                                           Deserializer deserializer,
                                                           GossipEncryptor encryptor,
                                                           EventLoopGroup eventLoopGroup) {
        return Result.success(new NettySwimTransport(serializer, deserializer, encryptor, option(eventLoopGroup)));
    }

    @Override
    public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
        if (blackholed) {
            return Promise.unitPromise();
        }

        return channel.get()
                      .map(ch -> resolveAndSend(ch, target, message))
                      .or(SwimError.General.TRANSPORT_NOT_STARTED.promise());
    }

    @Override
    @Contract
    public void blackhole(boolean enabled) {
        blackholed = enabled;
        LOG.warn("SWIM transport blackhole={} (socket stays open; UDP gossip {})",
                 enabled,
                 enabled
                 ? "DROPPED"
                 : "FLOWING");
    }

    @Override
    public Promise<Unit> start(int port, SwimMessageHandler handler) {
        return Promise.resolved(doBind(port, handler));
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.resolved(doStop());
    }

    /// Send an ANNOUNCE message to the given seed address, advertising this node's join.
    public Promise<Unit> sendAnnounce(NodeInfo self, String clusterName, long incarnation, InetSocketAddress target) {
        return send(target, SwimMessage.Announce.announce(self, clusterName, incarnation));
    }

    /// Check whether the given source IP is within its per-source ANNOUNCE rate limit.
    /// Returns {@code true} if the message should be processed, {@code false} if it should be dropped.
    /// Evicts entries that have been idle longer than {@value #ANNOUNCE_LIMITER_IDLE_EVICT_MS} ms.
    /// Lock-free, allocation-free admission check.
    boolean isAnnounceAllowed(InetAddress source) {
        evictIdleAnnounceLimiters();
        var entry = announceRateLimiters.computeIfAbsent(source, this::newAnnounceEntry);

        entry.touch();

        return entry.limiter()
                    .tryAcquire();
    }

    /// Resolve DNS asynchronously if target is unresolved, then send.
    private Promise<Unit> resolveAndSend(Channel ch, InetSocketAddress target, SwimMessage message) {
        if (!target.isUnresolved()) {
            return Promise.lift(SwimError.TransportFailure::new, () -> doSend(ch, target, message));
        }

        return nettyResolver.get()
                            .map(resolver -> resolveWithNetty(resolver, ch, target, message))
                            .or(() -> resolveSynchronously(ch, target, message));
    }

    /// Async DNS resolution via Netty's built-in DnsNameResolver.
    private Promise<Unit> resolveWithNetty(DnsNameResolver resolver,
                                           Channel ch,
                                           InetSocketAddress target,
                                           SwimMessage message) {
        return Promise.promise(promise -> resolver.resolve(target.getHostString())
                                                  .addListener((Future<InetAddress> f) -> completeResolution(f,
                                                                                                             ch,
                                                                                                             target,
                                                                                                             message,
                                                                                                             promise)));
    }

    @SuppressWarnings("JBCT-EX-01")  // Adapter boundary: bridging Netty Future callback to Promise
    private void completeResolution(Future<InetAddress> future,
                                    Channel ch,
                                    InetSocketAddress target,
                                    SwimMessage message,
                                    Promise<Unit> promise) {
        if (future.isSuccess()) {
            var resolved = new InetSocketAddress(future.getNow(), target.getPort());

            try {
                doSend(ch, resolved, message);
                promise.succeed(unit());
            } catch (Exception e) {
                promise.fail(new SwimError.TransportFailure(e));
            }
        } else {
            LOG.warn("DNS resolution failed for {}: {}",
                     target.getHostString(),
                     future.cause().getMessage());
            promise.fail(new SwimError.TransportFailure(future.cause()));
        }
    }

    /// Fallback: synchronous DNS resolution when no Netty resolver is available.
    private Promise<Unit> resolveSynchronously(Channel ch, InetSocketAddress target, SwimMessage message) {
        var resolved = new InetSocketAddress(target.getHostString(), target.getPort());

        if (resolved.isUnresolved()) {
            LOG.warn("UDP send failed — cannot resolve {}", target.getHostString());

            return SwimError.General.TRANSPORT_NOT_STARTED.promise();
        }

        return Promise.lift(SwimError.TransportFailure::new, () -> doSend(ch, resolved, message));
    }

    private void doSend(Channel ch, InetSocketAddress target, SwimMessage message) {
        var bytes = serializer.encode(message);

        encryptor.encrypt(bytes)
                 .onSuccess(encrypted -> sendEncrypted(ch, target, encrypted))
                 .onFailure(cause -> LOG.error("Failed to encrypt gossip message: {}",
                                               cause.message()));
    }

    private static void sendEncrypted(Channel ch, InetSocketAddress target, byte[] encrypted) {
        var packet = new DatagramPacket(Unpooled.wrappedBuffer(encrypted), target);

        ch.writeAndFlush(packet).addListener(future -> logSendFailure(future, target));
    }

    private static void logSendFailure(Future<?> future, InetSocketAddress target) {
        if (!future.isSuccess()) {
            LOG.warn("UDP send failed to {}: {}",
                     target,
                     future.cause().getMessage());
        }
    }

    private Result<Unit> doBind(int port, SwimMessageHandler handler) {
        return Result.lift(SwimError.TransportFailure::new, () -> bindChannel(port, handler));
    }

    @SuppressWarnings("JBCT-EX-01")  // Adapter boundary: Netty bind().sync() throwing supplier for Result.lift
    private void bindChannel(int port, SwimMessageHandler handler) throws InterruptedException {
        var eventLoopGroup = externalGroup.or(() -> new NioEventLoopGroup(1));

        group.set(option(eventLoopGroup));
        var dnsResolver = new DnsNameResolverBuilder(eventLoopGroup.next()).channelType(NioDatagramChannel.class)
                                                                           .negativeTtl(0)
                                                                           .ndots(1)
                                                                           .hostsFileEntriesResolver(new DefaultHostsFileEntriesResolver())
                                                                           .build();

        nettyResolver.set(option(dnsResolver));
        var bootstrap = new Bootstrap().group(eventLoopGroup)
                                       .channel(NioDatagramChannel.class)
                                       .handler(new ChannelInitializer<DatagramChannel>() {
            @Override
            @Contract
            protected void initChannel(DatagramChannel ch) {
                                                    ch.pipeline().addLast(inboundHandler(handler));
                                                }
        });

        channel.set(option(bootstrap.bind(port).sync().channel()));
        LOG.info("SWIM transport started on port {}", port);
    }

    /// Exception lifting restored (#929 verification round 1). `stopChannel` calls
    /// `DnsNameResolver::close` and `ch.close()`, neither inside a `Result.lift`, and
    /// [`#awaitBounded`] catches only `InterruptedException` — so without this an unchecked exception
    /// from either would leave `stop()` as a THROWN exception rather than a `Result` failure. The
    /// inner `Result` is flattened so the two error channels stay one.
    private Result<Unit> doStop() {
        return Result.<Result<Unit>> lift(SwimError.TransportFailure::new, this::stopChannel).flatMap(inner -> inner);
    }

    /// Stop the transport under a BOUND, and report what actually happened (#929).
    ///
    /// Both waits used to be bare `.sync()` — unbounded — and this method logged
    /// "SWIM transport stopped" unconditionally at the end. When a deadlocked SWIM event loop could
    /// neither run the pending close task nor reach `confirmShutdown()`, the wait ran until JUnit's
    /// 8-minute lifecycle backstop interrupted it; `.sync()` then threw, the interrupt flag was
    /// re-asserted, and the success line was logged anyway over a transport that was never stopped
    /// (the surviving `nioEventLoopGroup` thread is visible in the dumps 10 s later). That false
    /// line is why #727, #749 and #750 each looked past the wedge underneath it.
    ///
    /// So a shutdown that does NOT complete now returns a FAILURE and logs a warning INSTEAD of the
    /// success line — never as well as it. Expect this to surface teardown failures that previously
    /// read as clean: those are correctly red, not a regression.
    private Result<Unit> stopChannel() {
        nettyResolver.getAndSet(none()).onPresent(DnsNameResolver::close);
        var channelClosed = channel.getAndSet(none()).map(NettySwimTransport::closeChannel).or(SHUTDOWN_OK);
        var groupStopped = externalGroup.isPresent()
                           ? SHUTDOWN_OK
                           : group.getAndSet(none()).map(NettySwimTransport::shutdownGroup).or(SHUTDOWN_OK);

        return channelClosed.flatMap(() -> groupStopped)
                            .onSuccess(_ -> LOG.info("SWIM transport stopped"))
                            .onFailure(cause -> LOG.warn("SWIM transport shutdown did NOT complete: {}",
                                                         cause.message()));
    }

    private static Result<Unit> closeChannel(Channel ch) {
        return awaitBounded(ch.close(), "channel close");
    }

    private static Result<Unit> shutdownGroup(EventLoopGroup g) {
        return awaitBounded(g.shutdownGracefully(SHUTDOWN_QUIET_PERIOD_MS, SHUTDOWN_TIMEOUT_MS, TimeUnit.MILLISECONDS),
                            "event loop group shutdown");
    }

    /// Bounded wait on a Netty future, reporting the THREE outcomes that are not success: the bound
    /// elapsing, an interrupt aborting the wait, and the future completing with a failure.
    ///
    /// That third case is why `isSuccess()` is consulted separately (#929 verification round 1).
    /// `Future.await(long)` returns true when the future is DONE, irrespective of outcome — so
    /// testing only its boolean reports a failed close as a clean stop and logs the success line over
    /// it. The pre-#929 `.sync()` rethrew that cause, so treating it as success would have been a NEW
    /// dishonesty introduced by the very change that exists to remove one.
    ///
    /// Package-private rather than private so the failed-but-completed branch can be pinned directly:
    /// it is not reachable through `stopChannel` with a real channel, and an honesty claim whose
    /// third branch no test can reach is exactly the shape this ticket is about.
    @SuppressWarnings("JBCT-EX-01")  // Adapter boundary: wrapping Netty I/O
    static Result<Unit> awaitBounded(Future<?> future, String stage) {
        try {
            if (!future.await(SHUTDOWN_TIMEOUT_MS)) {
                return new SwimError.ShutdownTimeout(stage, SHUTDOWN_TIMEOUT_MS).result();
            }

            return future.isSuccess()
                   ? SHUTDOWN_OK
                   : new SwimError.ShutdownFailed(stage, future.cause()).result();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();

            return new SwimError.ShutdownInterrupted(stage).result();
        }
    }

    private SimpleChannelInboundHandler<DatagramPacket> inboundHandler(SwimMessageHandler handler) {
        return new SimpleChannelInboundHandler<>() {
            @Override
            @Contract
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                handleIncoming(handler, packet);
            }
        };
    }

    private void handleIncoming(SwimMessageHandler handler, DatagramPacket packet) {
        if (blackholed) {
            // Silent death: drop the inbound datagram before decrypt/dispatch so this node
            // never observes peers' probes nor acks them — mirroring QuicClusterNetwork.
            return;
        }

        var buf = packet.content();
        var bytes = new byte[buf.readableBytes()];

        buf.readBytes(bytes);
        encryptor.decrypt(bytes)
                 .onSuccess(decrypted -> dispatchDecrypted(handler,
                                                           packet.sender(),
                                                           decrypted))
                 .onFailure(cause -> LOG.warn("Failed to decrypt gossip from {}: {}",
                                              packet.sender(),
                                              cause.message()));
    }

    private void dispatchDecrypted(SwimMessageHandler handler, InetSocketAddress sender, byte[] decrypted) {
        SwimMessage message = deserializer.decode(decrypted);

        if (message instanceof SwimMessage.Announce && !isAnnounceAllowed(sender.getAddress())) {
            LOG.debug("ANNOUNCE rate limit exceeded from {}; dropping", sender.getAddress());

            return;
        }

        handler.onMessage(sender, message);
    }

    private AnnounceRateLimiterEntry newAnnounceEntry(InetAddress ignored) {
        return new AnnounceRateLimiterEntry(RateLimiter.rateLimiter(ANNOUNCE_RATE_PER_SECOND, timeSpan(1).seconds()),
                                            System.currentTimeMillis());
    }

    private void evictIdleAnnounceLimiters() {
        var now = System.currentTimeMillis();
        Iterator<Map.Entry<InetAddress, AnnounceRateLimiterEntry>> it = announceRateLimiters.entrySet().iterator();

        while (it.hasNext()) {
            if (now - it.next().getValue().lastUsedMs() > ANNOUNCE_LIMITER_IDLE_EVICT_MS) {
                it.remove();
            }
        }
    }

    /// Holds a per-source rate limiter and tracks last-access time for idle eviction.
    private static final class AnnounceRateLimiterEntry {
        private final RateLimiter limiter;
        private volatile long lastUsedMs;

        AnnounceRateLimiterEntry(RateLimiter limiter, long lastUsedMs) {
            this.limiter = limiter;
            this.lastUsedMs = lastUsedMs;
        }

        RateLimiter limiter() {
            return limiter;
        }

        long lastUsedMs() {
            return lastUsedMs;
        }

        @Contract
        void touch() {
            lastUsedMs = System.currentTimeMillis();
        }
    }
}
