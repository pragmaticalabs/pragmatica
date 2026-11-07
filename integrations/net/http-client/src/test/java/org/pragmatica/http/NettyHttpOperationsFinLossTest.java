/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
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
 *
 */
package org.pragmatica.http;

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2746, the client's twin of the server's `Http3ResponseFinLossTest`: a request survives the loss of its tail. The
/// HTTP/3 request writer used to end the stream with an empty write and `shutdownOutput()`, a FIN-only STREAM frame that
/// quiche never retransmits when its loss is detected together with the loss of earlier data on the stream
/// ([TailLossInjector]); the server then held the whole request, never saw its end, never dispatched it, and the
/// connection idled out after 5 s. The writer now sends `FIN` with the request's last bytes ([QuicStreamFinisher]).
///
/// The request is written by the client's own writer, on a stream the test opened, from the test thread. Inside
/// `NettyHttpOperations` the writer runs while the QUIC channel handles the handshake's last read, and Netty then defers
/// sending until that read completes, so the `FIN` happened to leave with the last data; written outside that window,
/// each write is packetised at once and the `FIN` travelled alone. The injector holds the client's 1-RTT datagrams from
/// just before the request and releases them 700 ms later, after the PTO probes.
class NettyHttpOperationsFinLossTest {
    private static final int BODY_BYTES = 6 * 1024;
    private static final String PAD = "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".repeat(80);

    private final TailLossInjector injector = new TailLossInjector();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
    private final MultiThreadIoEventLoopGroup group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    private HttpServer server;
    private int port;

    @BeforeEach
    void start() {
        port = freeUdpPort();
        server = HttpServer.http3Server(HttpServerConfig.httpServerConfig("client-fin-loss", port),
                                        QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                             .unwrap(),
                                        NettyHttpOperationsFinLossTest::answer)
                           .await(timeSpan(10).seconds())
                           .unwrap();
    }

    @AfterEach
    void stop() {
        scheduler.shutdownNow();
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        server.stop().await(timeSpan(10).seconds());
    }

    /// The control: holding the datagrams and sending all of them late does not stall the exchange, so a stall below
    /// comes from the drops.
    @Test
    void heldButNotDropped_requestIsAnswered() throws Exception {
        assertThat(exchange(post(), false)).isEqualTo("200 handled " + BODY_BYTES);
        assertThat(injector.burst()).as(injector.log()).isGreaterThanOrEqualTo(2);
    }

    @Test
    void tailLost_requestStillEnds() throws Exception {
        var answer = exchange(post(), true);

        assertThat(injector.burst()).as("the pattern was applied: " + injector.log()).isGreaterThanOrEqualTo(2);
        assertThat(injector.later()).as("probes followed the burst: " + injector.log()).isGreaterThanOrEqualTo(1);
        assertThat(answer).as(injector.log()).isEqualTo("200 handled " + BODY_BYTES);
    }

    /// A request without a body still ends the stream: the `FIN` rides on the HEADERS frame's bytes, padded over several
    /// datagrams so that their tail can be lost the same way.
    @Test
    void noBodyWithHeadersTailLost_requestStillEnds() throws Exception {
        var request = HttpRequest.newBuilder(uri())
                                 .header("x-pad", PAD)
                                 .GET()
                                 .build();
        var answer = exchange(request, true);

        assertThat(injector.burst()).as("the pattern was applied: " + injector.log()).isGreaterThanOrEqualTo(2);
        assertThat(injector.later()).as("probes followed the burst: " + injector.log()).isGreaterThanOrEqualTo(1);
        assertThat(answer).as(injector.log()).isEqualTo("200 handled 0");
    }

    private static void answer(org.pragmatica.http.HttpRequest request, ResponseWriter writer) {
        writer.okText("handled " + request.body().length);
    }

    private URI uri() {
        return URI.create("https://127.0.0.1:" + port + "/");
    }

    private HttpRequest post() {
        return HttpRequest.newBuilder(uri())
                          .POST(BodyPublishers.ofByteArray(new byte[BODY_BYTES]))
                          .build();
    }

    /// `status body` of the answer, or how the exchange ended without one.
    private String exchange(HttpRequest request, boolean drop) throws Exception {
        var ssl = QuicSslContextBuilder.forClient()
                                       .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                       .applicationProtocols(Http3.supportedApplicationProtocols())
                                       .build();
        var udp = new Bootstrap().group(group)
                                 .channel(NioDatagramChannel.class)
                                 .handler(Http3.newQuicClientCodecBuilder()
                                               .sslContext(ssl)
                                               .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                                               .initialMaxData(10_000_000)
                                               .initialMaxStreamDataBidirectionalLocal(1_000_000)
                                               .build())
                                 .bind(0)
                                 .sync()
                                 .channel();

        udp.pipeline().addFirst(injector);

        var quic = QuicChannel.newBootstrap(udp)
                              .handler(new Http3ClientConnectionHandler())
                              .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                              .connect()
                              .get(10, TimeUnit.SECONDS);
        var reader = new AnswerReader();

        quic.closeFuture().addListener(_ -> reader.answer.complete("connection closed"));

        var stream = Http3.newRequestStream(quic, reader).sync().getNow();

        Thread.sleep(100);
        injector.arm();
        NettyHttpOperations.writeHttp3Request(stream, request);
        scheduler.schedule(() -> injector.release(udp, drop), 700, TimeUnit.MILLISECONDS);

        try {
            return reader.answer.get(10, TimeUnit.SECONDS);
        } finally {
            quic.close().sync();
            udp.close().sync();
        }
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    /// Completes with `status body` once the response stream ends.
    private static final class AnswerReader extends Http3RequestStreamInboundHandler {
        private final CompletableFuture<String> answer = new CompletableFuture<>();
        private final StringBuilder text = new StringBuilder();

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            text.append(frame.headers().status()).append(' ');
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            text.append(frame.content().toString(StandardCharsets.UTF_8));
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            answer.complete(text.toString());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            answer.complete("failed: " + cause);
            ctx.close();
        }
    }
}
