// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
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
import io.netty.util.concurrent.Future;

/// #2310: a standard HTTP/3 client — Netty's, offering the `h3` ALPN token as every HTTP/3 client does. [#getStatus]
/// sends one `GET /` and answers the response status, or `no-connection` when the QUIC handshake does not complete, so
/// a test tells "the listener serves HTTP/3" apart from "a UDP port is bound". [#exchange] lets the caller write the
/// request itself (a body, a stall, a trickle), and [#presentedCertificate] answers the certificate the listener
/// presents in the QUIC handshake.
public final class H3Probe {
    private H3Probe() {}

    /// The response to one request: its status and body. The status is `closed` when the stream or the connection ended
    /// without one, and `no-connection` when the QUIC handshake did not complete.
    public record Answer(String status, String body) {}

    /// Writes one request on a fresh request stream. It may block (to stall or trickle a body), and need not end the
    /// stream's output.
    @FunctionalInterface
    public interface RequestWriter {
        void write(QuicStreamChannel stream) throws Exception;
    }

    /// The status of a `GET /`. The answer is read when the response ends its stream (or the stream or connection
    /// closes), up to 10 s, not at the first HEADERS frame: a listener that sends a status and never ends the stream
    /// makes this time out instead of answering the status.
    public static String getStatus(int port) throws Exception {
        return exchange(port, stream -> stream.writeAndFlush(requestHeaders(port, "GET"))
                                              .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)).status();
    }

    /// The header section of a `<method> /` request, for [#exchange]'s writers to start with.
    public static DefaultHttp3HeadersFrame requestHeaders(int port, String method) {
        var headers = new DefaultHttp3HeadersFrame();

        headers.headers().method(method).path("/").authority("127.0.0.1:" + port).scheme("https");

        return headers;
    }

    /// Opens a connection, lets `writer` write one request, and waits up to 10 s after it returns for the response.
    public static Answer exchange(int port, RequestWriter writer) throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            var channel = clientChannel(group);
            var connect = connect(channel, port);

            if (!connect.await(10, TimeUnit.SECONDS) || !connect.isSuccess()) {
                channel.close().sync();

                return new Answer("no-connection", "");
            }

            var quic = connect.getNow();
            var reader = new AnswerReader();

            quic.closeFuture().addListener(_ -> reader.finish());

            var stream = Http3.newRequestStream(quic, reader).sync().getNow();

            writer.write(stream);

            var answer = reader.answer.get(10, TimeUnit.SECONDS);

            quic.close().sync();
            channel.close().sync();

            return answer;
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    /// Completes a QUIC handshake and returns the leaf certificate the listener presented.
    public static X509Certificate presentedCertificate(int port) throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            var channel = clientChannel(group);
            var quic = connect(channel, port).get(10, TimeUnit.SECONDS);
            var certificate = (X509Certificate) quic.sslEngine().getSession().getPeerCertificates()[0];

            quic.close().sync();
            channel.close().sync();

            return certificate;
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static Channel clientChannel(EventLoopGroup group) throws Exception {
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

        return new Bootstrap().group(group)
                              .channel(NioDatagramChannel.class)
                              .handler(codec)
                              .bind(0)
                              .sync()
                              .channel();
    }

    private static Future<QuicChannel> connect(Channel channel, int port) {
        return QuicChannel.newBootstrap(channel)
                          .handler(new Http3ClientConnectionHandler())
                          .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                          .connect();
    }

    /// Collects the first status and the body. Completes when the stream's input ends, when the stream or the
    /// connection closes, or when the stream fails.
    private static final class AnswerReader extends Http3RequestStreamInboundHandler {
        private final CompletableFuture<Answer> answer = new CompletableFuture<>();
        private final StringBuilder body = new StringBuilder();
        private String status = "closed";

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            synchronized (body) {
                if ("closed".equals(status)) {
                    status = String.valueOf(frame.headers().status());
                }
            }
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            synchronized (body) {
                body.append(frame.content().toString(StandardCharsets.UTF_8));
            }
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            finish();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            finish();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            finish();
            ctx.close();
        }

        void finish() {
            synchronized (body) {
                answer.complete(new Answer(status, body.toString()));
            }
        }
    }
}
