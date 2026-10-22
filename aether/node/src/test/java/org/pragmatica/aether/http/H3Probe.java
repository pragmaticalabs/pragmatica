// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

/// #2310: a standard HTTP/3 client — Netty's, offering the `h3` ALPN token as every HTTP/3 client does — sending one
/// `GET /`. It answers the response status, or `no-connection` when the QUIC handshake does not complete, so a test
/// tells "the listener serves HTTP/3" apart from "a UDP port is bound".
public final class H3Probe {
    private H3Probe() {}

    public static String getStatus(int port) throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            var ssl = QuicSslContextBuilder.forClient()
                                           .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                           .applicationProtocols(Http3.supportedApplicationProtocols())
                                           .build();
            var codec = Http3.newQuicClientCodecBuilder()
                             .sslContext(ssl)
                             .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                             .initialMaxData(10_000_000)
                             .initialMaxStreamDataBidirectionalLocal(1_000_000)
                             .build();
            var channel = new Bootstrap().group(group)
                                         .channel(NioDatagramChannel.class)
                                         .handler(codec)
                                         .bind(0)
                                         .sync()
                                         .channel();
            var connect = QuicChannel.newBootstrap(channel)
                                     .handler(new Http3ClientConnectionHandler())
                                     .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                                     .connect();

            if (!connect.await(10, TimeUnit.SECONDS) || !connect.isSuccess()) {
                channel.close().sync();

                return "no-connection";
            }

            var status = new CompletableFuture<String>();
            var stream = Http3.newRequestStream(connect.getNow(), new StatusReader(status)).sync().getNow();
            var headers = new DefaultHttp3HeadersFrame();

            headers.headers().method("GET").path("/").authority("127.0.0.1:" + port).scheme("https");
            stream.writeAndFlush(headers).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

            var outcome = status.get(10, TimeUnit.SECONDS);

            connect.getNow().close().sync();
            channel.close().sync();

            return outcome;
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static final class StatusReader extends Http3RequestStreamInboundHandler {
        private final CompletableFuture<String> status;

        StatusReader(CompletableFuture<String> status) {
            this.status = status;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            status.complete(String.valueOf(frame.headers().status()));
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            status.complete("closed");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            status.complete("closed");
            ctx.fireChannelInactive();
        }
    }
}
