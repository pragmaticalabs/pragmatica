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

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.channel.socket.nio.NioDatagramChannel;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/// Netty-based UDP transport for SWIM protocol messages.
public final class NettySwimTransport implements SwimTransport {
    private static final Logger LOG = LoggerFactory.getLogger(NettySwimTransport.class);

    private final Serializer serializer;
    private final Deserializer deserializer;
    private volatile Channel channel;
    private volatile NioEventLoopGroup group;

    private NettySwimTransport(Serializer serializer, Deserializer deserializer) {
        this.serializer = serializer;
        this.deserializer = deserializer;
    }

    /// Factory creating a Netty-based SWIM transport.
    public static Result<SwimTransport> nettySwimTransport(Serializer serializer, Deserializer deserializer) {
        return Result.success(new NettySwimTransport(serializer, deserializer));
    }

    @Override
    public Promise<Unit> send(InetSocketAddress target, SwimMessage message) {
        var ch = channel;

        if (ch == null) {
            return SwimError.General.TRANSPORT_NOT_STARTED.promise();
        }

        return Promise.lift(SwimError.TransportFailure::new, () -> doSend(ch, target, message));
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
        var packet = new DatagramPacket(Unpooled.wrappedBuffer(bytes), target);
        ch.writeAndFlush(packet);
    }

    private void doBind(int port, SwimMessageHandler handler) throws InterruptedException {
        group = new NioEventLoopGroup(1);

        var bootstrap = new Bootstrap()
            .group(group)
            .channel(NioDatagramChannel.class)
            .handler(new ChannelInitializer<DatagramChannel>() {
                @Override
                protected void initChannel(DatagramChannel ch) {
                    ch.pipeline().addLast(inboundHandler(handler));
                }
            });

        channel = bootstrap.bind(port).sync().channel();
        LOG.info("SWIM transport started on port {}", port);
    }

    private void doStop() throws InterruptedException {
        var ch = channel;

        if (ch != null) {
            ch.close().sync();
            channel = null;
        }

        var g = group;

        if (g != null) {
            g.shutdownGracefully().sync();
            group = null;
        }

        LOG.info("SWIM transport stopped");
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

        SwimMessage message = deserializer.decode(bytes);
        handler.onMessage(packet.sender(), message);
    }
}
