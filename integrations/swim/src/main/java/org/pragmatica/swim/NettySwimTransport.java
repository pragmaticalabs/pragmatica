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

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicReference;

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
import io.netty.util.concurrent.Future;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;

/// Netty-based UDP transport for SWIM protocol messages.
///
/// Currently SWIM binds its own UDP port but can share the Server's EventLoopGroup
/// (workerGroup) to avoid creating a separate thread pool. The intended next step is
/// to move UDP binding into Server itself — SWIM would then use Server's pre-bound
/// UDP channel directly, eliminating the separate Bootstrap. This requires threading
/// SWIM's handler supplier through PassiveNode/RabiaNode to Server creation, since
/// Server is created at a lower layer than SWIM.
public final class NettySwimTransport implements SwimTransport {
    private static final Logger LOG = LoggerFactory.getLogger(NettySwimTransport.class);

    private final Serializer serializer;
    private final Deserializer deserializer;
    private final GossipEncryptor encryptor;
    private final Option<EventLoopGroup> externalGroup;
    private final AtomicReference<Option<Channel>> channel = new AtomicReference<>(none());
    private final AtomicReference<Option<EventLoopGroup>> group = new AtomicReference<>(none());

    private NettySwimTransport(Serializer serializer, Deserializer deserializer,
                                GossipEncryptor encryptor, Option<EventLoopGroup> externalGroup) {
        this.serializer = serializer;
        this.deserializer = deserializer;
        this.encryptor = encryptor;
        this.externalGroup = externalGroup;
    }

    /// Factory creating a Netty-based SWIM transport with gossip encryption.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer, Deserializer deserializer,
                                                            GossipEncryptor encryptor) {
        return Result.success(new NettySwimTransport(serializer, deserializer, encryptor, none()));
    }

    /// Factory creating a Netty-based SWIM transport without encryption.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer, Deserializer deserializer) {
        return nettySwimTransport(serializer, deserializer, GossipEncryptor.none());
    }

    /// Factory creating a Netty-based SWIM transport using a shared EventLoopGroup.
    /// The provided group will NOT be shut down when this transport stops.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer, Deserializer deserializer,
                                                            GossipEncryptor encryptor, EventLoopGroup eventLoopGroup) {
        return Result.success(new NettySwimTransport(serializer, deserializer, encryptor, option(eventLoopGroup)));
    }

    @Override
    public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
        return channel.get()
                      .map(ch -> Promise.lift(SwimError.TransportFailure::new, () -> doSend(ch, target, message)))
                      .or(SwimError.General.TRANSPORT_NOT_STARTED.promise());
    }

    @Override
    public Promise<Unit> start(int port, SwimMessageHandler handler) {
        return Promise.lift(SwimError.TransportFailure::new, () -> doBind(port, handler));
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.lift(SwimError.TransportFailure::new, this::doStop);
    }

    private void doSend(Channel ch, InetSocketAddress target, SwimMessage message) {
        var bytes = serializer.encode(message);
        encryptor.encrypt(bytes)
                 .onSuccess(encrypted -> sendEncrypted(ch, target, encrypted))
                 .onFailure(cause -> LOG.error("Failed to encrypt gossip message: {}", cause.message()));
    }

    private static void sendEncrypted(Channel ch, InetSocketAddress target, byte[] encrypted) {
        var packet = new DatagramPacket(Unpooled.wrappedBuffer(encrypted), target);
        ch.writeAndFlush(packet).addListener(future -> logSendFailure(future, target));
    }

    private static void logSendFailure(Future<?> future, InetSocketAddress target) {
        if (!future.isSuccess()) {
            LOG.warn("UDP send failed to {}: {}", target, future.cause().getMessage());
        }
    }

    private void doBind(int port, SwimMessageHandler handler) throws InterruptedException {
        var eventLoopGroup = externalGroup.or(() -> new NioEventLoopGroup(1));
        group.set(option(eventLoopGroup));

        var bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline().addLast(inboundHandler(handler));
                }
            });

        channel.set(option(bootstrap.bind(port).sync().channel()));
        LOG.info("SWIM transport started on port {}", port);
    }

    private void doStop() throws InterruptedException {
        channel.getAndSet(none())
               .onPresent(NettySwimTransport::closeChannel);

        if (!externalGroup.isPresent()) {
            group.getAndSet(none())
                 .onPresent(NettySwimTransport::shutdownGroup);
        }

        LOG.info("SWIM transport stopped");
    }

    @SuppressWarnings("JBCT-EX-01") // Adapter boundary: wrapping Netty I/O
    private static void closeChannel(Channel ch) {
        try {
            ch.close().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @SuppressWarnings("JBCT-EX-01") // Adapter boundary: wrapping Netty I/O
    private static void shutdownGroup(EventLoopGroup g) {
        try {
            g.shutdownGracefully().sync();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private SimpleChannelInboundHandler<DatagramPacket> inboundHandler(SwimMessageHandler handler) {
        return new SimpleChannelInboundHandler<>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, DatagramPacket packet) {
                handleIncoming(handler, packet);
            }
        };
    }

    private void handleIncoming(SwimMessageHandler handler, DatagramPacket packet) {
        var buf = packet.content();
        var bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);

        encryptor.decrypt(bytes)
                 .onSuccess(decrypted -> dispatchDecrypted(handler, packet.sender(), decrypted))
                 .onFailure(cause -> LOG.warn("Failed to decrypt gossip from {}: {}",
                                               packet.sender(), cause.message()));
    }

    private void dispatchDecrypted(SwimMessageHandler handler, InetSocketAddress sender, byte[] decrypted) {
        SwimMessage message = deserializer.decode(decrypted);
        handler.onMessage(sender, message);
    }
}
