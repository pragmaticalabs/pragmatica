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

package org.pragmatica.http.server;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;

/// A real HTTP/3 client (Netty's, offering the `h3` ALPN token, as in [Http3ResponseFailureTest]) for tests that write
/// the request themselves: a body, a stall, a trickle, or a connection torn down mid-body.
final class Http3TestClient {
    private Http3TestClient() {}

    /// The response to one request: its status, body and header section. The status is `closed`, and the headers empty,
    /// when the stream or the connection ended without one.
    record Answer(String status, String body, Http3Headers headers) {}

    /// Writes one request on a fresh request stream. It may block (to stall or trickle a body), and need not end the
    /// stream's output.
    @FunctionalInterface
    interface RequestWriter {
        void write(QuicStreamChannel stream) throws Exception;
    }

    /// The header section of a `POST /` request, for [#exchange]'s writers to start with.
    static DefaultHttp3HeadersFrame postHeaders(int port) {
        var headers = new DefaultHttp3HeadersFrame();

        headers.headers().method("POST").path("/").authority("127.0.0.1:" + port).scheme("https");

        return headers;
    }

    /// The port an [Http3Server] bound when it was configured with port 0.
    static int boundPort(Http3Server server) {
        return ((InetSocketAddress) server.serverChannel().unwrap().localAddress()).getPort();
    }

    /// Opens a connection, lets `writer` write one request, and waits up to 10 s after it returns for the response.
    static Answer exchange(int port, RequestWriter writer) throws Exception {
        return connect(port, writer, (reader, _) -> reader.answer.get(10, TimeUnit.SECONDS));
    }

    /// Opens a connection, lets `writer` write one request, and waits up to 10 s after it returns for the server to close
    /// the connection: the close the client received, with its error code.
    static QuicConnectionCloseEvent peerClose(int port, RequestWriter writer) throws Exception {
        return connect(port, writer, (_, peerClose) -> peerClose.get(10, TimeUnit.SECONDS));
    }

    /// What a connection waits for once the request is written.
    @FunctionalInterface
    private interface Outcome<T> {
        T await(AnswerReader reader, CompletableFuture<QuicConnectionCloseEvent> peerClose) throws Exception;
    }

    private static <T> T connect(int port, RequestWriter writer, Outcome<T> outcome) throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            var ssl = QuicSslContextBuilder.forClient()
                                           .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                           .applicationProtocols(Http3.supportedApplicationProtocols())
                                           .build();
            var codec = Http3.newQuicClientCodecBuilder()
                             .sslContext(ssl)
                             .maxIdleTimeout(20_000, TimeUnit.MILLISECONDS)
                             .initialMaxData(10_000_000)
                             .initialMaxStreamDataBidirectionalLocal(1_000_000)
                             .build();
            var channel = new Bootstrap().group(group)
                                         .channel(NioDatagramChannel.class)
                                         .handler(codec)
                                         .bind(0)
                                         .sync()
                                         .channel();

            try {
                var peerClose = new CompletableFuture<QuicConnectionCloseEvent>();
                var quic = QuicChannel.newBootstrap(channel)
                                      .handler(new ChannelInitializer<QuicChannel>() {
                                          @Override
                                          protected void initChannel(QuicChannel ch) {
                                              ch.pipeline()
                                                .addLast(new Http3ClientConnectionHandler(),
                                                         new PeerCloseRecorder(peerClose));
                                          }
                                      })
                                      .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                                      .connect()
                                      .get(10, TimeUnit.SECONDS);
                var reader = new AnswerReader();

                quic.closeFuture().addListener(_ -> reader.finish());

                var stream = Http3.newRequestStream(quic, reader).sync().getNow();

                writer.write(stream);

                var result = outcome.await(reader, peerClose);

                quic.close().sync();

                return result;
            } finally {
                channel.close().sync();
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    /// Completes with the CONNECTION_CLOSE the server sent.
    private static final class PeerCloseRecorder extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<QuicConnectionCloseEvent> peerClose;

        PeerCloseRecorder(CompletableFuture<QuicConnectionCloseEvent> peerClose) {
            this.peerClose = peerClose;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof QuicConnectionCloseEvent close) {
                peerClose.complete(close);
            }
            super.userEventTriggered(ctx, evt);
        }
    }

    /// Collects the first status, its header section, and the body. Completes when the stream's input ends, when the
    /// stream or the connection closes, or when the stream fails.
    private static final class AnswerReader extends Http3RequestStreamInboundHandler {
        private final CompletableFuture<Answer> answer = new CompletableFuture<>();
        private final StringBuilder body = new StringBuilder();
        private String status = "closed";
        private Http3Headers headers = new DefaultHttp3Headers();

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            synchronized (body) {
                if ("closed".equals(status)) {
                    status = String.valueOf(frame.headers().status());
                    headers = frame.headers();
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
                answer.complete(new Answer(status, body.toString(), headers));
            }
        }
    }
}
