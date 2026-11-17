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
import io.netty.channel.ChannelFuture;
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

    /// The response to one request: its status, body, header section and `diagnostics`, the timed event log of the
    /// exchange (stream and connection events, close causes, why the answer was finished). The status is `closed`, and
    /// the headers empty, when the stream or the connection ended without one (#2746/#2605: a bare `closed` named no
    /// cause). `statusAfterMs` is the time from the request stream's opening to the status's arrival, `-1` when none
    /// arrived (#2873: a status alone cannot tell a 408 on time from one that came after the wait was raised to 25 s).
    record Answer(String status, String body, Http3Headers headers, String diagnostics, long statusAfterMs) {
        /// The status, or an [IllegalStateException] carrying the diagnostics when the exchange produced none.
        String statusText() {
            if (status.isEmpty() || !status.chars().allMatch(Character::isDigit)) {
                throw new IllegalStateException("no HTTP status: '" + status + "'; " + diagnostics);
            }

            return status;
        }
    }

    /// Timed event log of one exchange; events arrive from the client's event loop and the test thread.
    private static final class Trace {
        private final long start = System.nanoTime();
        private final StringBuilder events = new StringBuilder();
        private volatile long opened = start;

        /// Marks the request stream's opening, the origin of [#sinceOpenedMs].
        void markOpened() {
            opened = System.nanoTime();
        }

        long sinceOpenedMs() {
            return (System.nanoTime() - opened) / 1_000_000;
        }

        synchronized void add(String event) {
            events.append(event).append('@').append((System.nanoTime() - start) / 1_000_000).append("ms ");
        }

        synchronized String render() {
            return "events: " + events.toString().trim();
        }
    }

    /// Writes one request on a fresh request stream. It may block (to stall or trickle a body), and need not end the
    /// stream's output.
    @FunctionalInterface
    interface RequestWriter {
        void write(QuicStreamChannel stream) throws Exception;
    }

    /// #2746: writes `frame` as the request's last, its bytes carrying the stream's `FIN` ([QuicStreamFinisher]), as the
    /// server's response writer does. `shutdownOutput()` after the last write sends a FIN-only frame instead, which
    /// quiche does not retransmit when its loss is detected together with earlier data's: the request then never ends,
    /// and the exchange reads `closed` once the connection idles out.
    static ChannelFuture finishWith(QuicStreamChannel stream, Object frame) {
        stream.pipeline().addFirst(QuicStreamFinisher.INSTANCE);

        return QuicStreamFinisher.writeAndFinish(stream.pipeline().lastContext(), frame);
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

    /// Opens a connection, lets `writer` write one request, and waits up to 25 s after it returns for the response. A
    /// stalled exchange ends when a connection idles out: the server's at 5 s, at the latest the client's at 20 s
    /// (`maxIdleTimeout` below), so 25 s covers every way an exchange can end (#2866: 10 s was missed on a starved host).
    static Answer exchange(int port, RequestWriter writer) throws Exception {
        return connect(port, writer, (reader, _) -> reader.answer.get(25, TimeUnit.SECONDS));
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
        var trace = new Trace();

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
                                                         new PeerCloseRecorder(peerClose, trace));
                                          }
                                      })
                                      .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                                      .connect()
                                      .get(10, TimeUnit.SECONDS);
                var reader = new AnswerReader(trace);

                trace.add("connected");
                quic.closeFuture().addListener(_ -> reader.finish("quic-closed"));

                var stream = Http3.newRequestStream(quic, reader).sync().getNow();

                trace.markOpened();
                trace.add("stream-open");
                writer.write(stream);
                trace.add("request-written");

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
        private final Trace trace;

        PeerCloseRecorder(CompletableFuture<QuicConnectionCloseEvent> peerClose, Trace trace) {
            this.peerClose = peerClose;
            this.trace = trace;
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            trace.add("quic-event(" + evt + ")");

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
        private final Trace trace;
        private String status = "closed";
        private long statusAfterMs = -1;
        private Http3Headers headers = new DefaultHttp3Headers();

        AnswerReader(Trace trace) {
            this.trace = trace;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            synchronized (body) {
                if ("closed".equals(status)) {
                    status = String.valueOf(frame.headers().status());
                    headers = frame.headers();
                    statusAfterMs = trace.sinceOpenedMs();
                    trace.add("headers(" + status + ")");
                }
            }
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            synchronized (body) {
                trace.add("data(" + frame.content().readableBytes() + "B)");
                body.append(frame.content().toString(StandardCharsets.UTF_8));
            }
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            finish("input-closed");
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            finish("stream-inactive");
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            finish("exception(" + cause + ")");
            ctx.close();
        }

        void finish(String reason) {
            synchronized (body) {
                trace.add("finish:" + reason);
                answer.complete(new Answer(status, body.toString(), headers, trace.render(), statusAfterMs));
            }
        }
    }
}
