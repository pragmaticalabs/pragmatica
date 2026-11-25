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

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

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
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.server.TlsCertificates.bundle;
import static org.pragmatica.http.server.TlsCertificates.certificate;
import static org.pragmatica.http.server.TlsCertificates.http1Client;
import static org.pragmatica.http.server.TlsCertificates.http1Config;
import static org.pragmatica.http.server.TlsCertificates.http1Server;
import static org.pragmatica.http.server.TlsCertificates.http3Client;
import static org.pragmatica.http.server.TlsCertificates.http3Server;
import static org.pragmatica.http.server.TlsCertificates.http3ServerWithoutH3;
import static org.pragmatica.http.server.TlsCertificates.nativeRefCnt;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.pragmatica.testing.PollUntil.pollUntil;

/// #2032 over real sockets, HTTP/1.1 and HTTP/3: the listener reads its [LiveTls] once per NEW connection. After a swap
/// a new connection is offered the new certificate, a connection opened before it keeps the certificate it handshook
/// with and still serves requests, the port never refuses a connection while swaps happen, and a refused swap leaves
/// the old certificate in force. Every request is answered `200 served`.
class LiveTlsRotationTest {
    private static final String SERVED = "200:served";
    private static final int LOOP_SWAPS = 20;
    private static final int LEAK_SWAPS = 100;

    private final List<HttpServer> servers = new ArrayList<>();
    private EventLoopGroup clientGroup;

    @BeforeEach
    void startClientGroup() {
        clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    @AfterEach
    void stopAll() {
        servers.forEach(server -> server.stop().await(timeSpan(10).seconds()));
        clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
    }

    // ---------------------------------------------------------------- HTTP/1.1

    /// Mutant "context read once at bind" (HTTP/1.1) makes the second connection see the first certificate.
    @Test
    void http1_swap_newConnectionSeesNewCertificate_openConnectionKeepsOldAndStillServes() throws Exception {
        var first = bundle("h1-first");
        var second = bundle("h1-second");
        var holder = LiveTls.liveTls(http1Server(first)).unwrap();
        var port = http1(holder);

        try (var before = H1Connection.open(port)) {
            assertThat(before.peer()).as("control: the first connection").isEqualTo(certificate(first));
            assertThat(before.get()).isEqualTo(SERVED);
            assertThat(holder.swap(http1Server(second)).isSuccess()).isTrue();

            try (var after = H1Connection.open(port)) {
                assertThat(after.peer()).as("a connection opened after the swap").isEqualTo(certificate(second));
                assertThat(after.get()).isEqualTo(SERVED);
            }
            assertThat(before.get()).as("the connection opened before the swap still serves").isEqualTo(SERVED);
            assertThat(before.peer()).as("and keeps its certificate").isEqualTo(certificate(first));
        }
    }

    @Test
    void http1_refusedSwap_keepsOldCertificate() throws Exception {
        var first = bundle("h1-refused-first");
        var holder = LiveTls.liveTls(http1Server(first)).unwrap();
        var port = http1(holder);

        assertThat(holder.swap(http1Client()).isFailure()).as("a client context is refused").isTrue();
        assertThat(holder.swap(http3Server(bundle("h1-refused-quic"))).isFailure())
            .as("a QUIC context is refused")
            .isTrue();

        try (var connection = H1Connection.open(port)) {
            assertThat(connection.peer()).isEqualTo(certificate(first));
            assertThat(connection.get()).isEqualTo(SERVED);
        }
    }

    /// A connection loop runs across 20 swaps: every connection is accepted and served, and after each swap a fresh
    /// connection sees the certificate just installed.
    @Test
    void http1_twentySwaps_portNeverRefuses() throws Exception {
        var first = bundle("h1-loop-first");
        var second = bundle("h1-loop-second");
        var contexts = List.of(http1Server(first), http1Server(second));
        var certificates = List.of(certificate(first), certificate(second));
        var holder = LiveTls.liveTls(contexts.get(0)).unwrap();
        var port = http1(holder);

        assertConnectionLoopAcrossSwaps(() -> h1Probe(port),
                                        index -> holder.swap(contexts.get(index % 2)).isSuccess(),
                                        certificates);
    }

    /// The default-provider (JDK) contexts carry no reference count, so a leak cannot show as a count here; 100 swaps,
    /// each followed by a served connection, show no swapped-out context is used after it stops being current.
    @Test
    void http1_hundredSwaps_everyConnectionServedOnTheCurrentCertificate() throws Exception {
        var first = bundle("h1-leak-first");
        var second = bundle("h1-leak-second");
        var contexts = List.of(http1Server(first), http1Server(second));
        var certificates = List.of(certificate(first), certificate(second));
        var holder = LiveTls.liveTls(contexts.get(0)).unwrap();
        var port = http1(holder);

        for (var index = 1; index <= LEAK_SWAPS; index++) {
            assertThat(holder.swap(contexts.get(index % 2)).isSuccess()).isTrue();
            assertThat(h1Probe(port)).as("swap %d", index).isEqualTo(certificates.get(index % 2));
        }
    }

    /// The existing `withTls` entry point is unchanged: it serves the configured certificate.
    @Test
    void http1_withTls_servesConfiguredCertificate() throws Exception {
        var only = bundle("h1-fixed");
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("h1-fixed", 0).withTls(http1Config(only)),
                                           (_, writer) -> writer.okText("served"))
                               .await(timeSpan(10).seconds())
                               .unwrap();

        servers.add(server);
        assertThat(h1Probe(server.port())).isEqualTo(certificate(only));
    }

    // ---------------------------------------------------------------- HTTP/3

    /// Mutant "context read once at bind" (HTTP/3) makes the second connection see the first certificate. The native
    /// count shows the open connection holds the old context (2 = the context + that connection) and the holder never
    /// released it at the swap; it returns to 1 once the connection is freed.
    @Test
    void http3_swap_newConnectionSeesNewCertificate_openConnectionKeepsOldAndStillServes() throws Exception {
        var first = bundle("h3-first");
        var second = bundle("h3-second");
        var firstContext = http3Server(first);
        var holder = LiveTls.liveQuicTls(firstContext).unwrap();
        var port = http3(holder);

        try (var before = H3Connection.open(clientGroup, port)) {
            assertThat(before.peer()).as("control: the first connection").isEqualTo(certificate(first));
            assertThat(before.get()).isEqualTo(SERVED);
            assertThat(holder.swap(http3Server(second)).isSuccess()).isTrue();

            try (var after = H3Connection.open(clientGroup, port)) {
                assertThat(after.peer()).as("a connection opened after the swap").isEqualTo(certificate(second));
                assertThat(after.get()).isEqualTo(SERVED);
            }
            assertThat(before.get()).as("the connection opened before the swap still serves").isEqualTo(SERVED);
            assertThat(before.peer()).as("and keeps its certificate").isEqualTo(certificate(first));
            assertThat(nativeRefCnt(firstContext)).as("the old context: itself + the open connection").isEqualTo(2);
        }
        awaitNativeRefCnt(firstContext, 1);
    }

    @Test
    void http3_refusedSwap_keepsOldCertificate() throws Exception {
        var first = bundle("h3-refused-first");
        var holder = LiveTls.liveQuicTls(http3Server(first)).unwrap();
        var port = http3(holder);

        assertThat(holder.swap(http3Client()).isFailure()).as("a client context is refused").isTrue();

        try (var connection = H3Connection.open(clientGroup, port)) {
            assertThat(connection.peer()).isEqualTo(certificate(first));
            assertThat(connection.get()).isEqualTo(SERVED);
        }
    }

    /// #3747: a swap to a server context that cannot serve HTTP/3 (no `h3` ALPN) is refused, the old context stays
    /// installed, and a connection opened afterwards is served with the old certificate.
    @Test
    void http3_swapToContextWithoutH3_isRefused_laterConnectionsStillServed() throws Exception {
        var first = bundle("h3-no-alpn-first");
        var holder = LiveTls.liveQuicTls(http3Server(first)).unwrap();
        var port = http3(holder);
        var outcome = holder.swap(http3ServerWithoutH3(bundle("h3-no-alpn-bad")));

        assertThat(outcome.isFailure()).as("a context without h3 is refused").isTrue();
        assertThat(h3Probe(port)).isEqualTo(certificate(first));
    }

    @Test
    void http3_twentySwaps_portNeverRefuses() throws Exception {
        var first = bundle("h3-loop-first");
        var second = bundle("h3-loop-second");
        var contexts = List.of(http3Server(first), http3Server(second));
        var certificates = List.of(certificate(first), certificate(second));
        var holder = LiveTls.liveQuicTls(contexts.get(0)).unwrap();
        var port = http3(holder);

        assertConnectionLoopAcrossSwaps(() -> h3Probe(port),
                                        index -> holder.swap(contexts.get(index % 2)).isSuccess(),
                                        certificates);
    }

    /// 100 swaps, each followed by a served connection (a context released while still in use would fail the next
    /// handshake), and afterwards each context's native count is back to 1: every connection released what it retained,
    /// and the holder retained nothing.
    @Test
    void http3_hundredSwaps_noCountLeftBehind() throws Exception {
        var first = bundle("h3-leak-first");
        var second = bundle("h3-leak-second");
        var contexts = List.of(http3Server(first), http3Server(second));
        var certificates = List.of(certificate(first), certificate(second));
        var holder = LiveTls.liveQuicTls(contexts.get(0)).unwrap();
        var port = http3(holder);

        for (var index = 1; index <= LEAK_SWAPS; index++) {
            assertThat(holder.swap(contexts.get(index % 2)).isSuccess()).isTrue();
            assertThat(h3Probe(port)).as("swap %d", index).isEqualTo(certificates.get(index % 2));
        }
        awaitNativeRefCnt(contexts.get(0), 1);
        awaitNativeRefCnt(contexts.get(1), 1);
    }

    /// The existing `http3Server(QuicSslContext)` entry point is unchanged: it serves the given certificate.
    @Test
    void http3_contextEntryPoint_servesGivenCertificate() throws Exception {
        var only = bundle("h3-fixed");
        var server = HttpServer.http3Server(HttpServerConfig.httpServerConfig("h3-fixed", 0),
                                            http3Server(only),
                                            (_, writer) -> writer.okText("served"))
                               .await(timeSpan(10).seconds())
                               .unwrap();

        servers.add(server);
        assertThat(h3Probe(server.port())).isEqualTo(certificate(only));
    }

    // ---------------------------------------------------------------- shared

    @FunctionalInterface
    private interface Probe {
        /// Opens a new connection, has one request served on it, and returns the certificate it was offered.
        X509Certificate connectAndServe() throws Exception;
    }

    @FunctionalInterface
    private interface Swap {
        boolean apply(int index);
    }

    /// Runs `probe` in a loop on another thread while 20 swaps alternate the two contexts; after each swap a fresh probe
    /// must see the certificate just installed. Every looped connection must be accepted, served, and offered one of
    /// the two certificates.
    private static void assertConnectionLoopAcrossSwaps(Probe probe, Swap swap, List<X509Certificate> certificates)
    throws Exception {
        var stop = new AtomicBoolean();
        var served = new AtomicInteger();
        var failures = new ConcurrentLinkedQueue<String>();
        var loop = Thread.ofPlatform()
                         .name("tls-connection-loop")
                         .start(() -> connectionLoop(probe, Set.copyOf(certificates), stop, served, failures));

        try {
            for (var index = 1; index <= LOOP_SWAPS; index++) {
                assertThat(swap.apply(index)).as("swap %d", index).isTrue();
                assertThat(probe.connectAndServe()).as("after swap %d", index).isEqualTo(certificates.get(index % 2));
            }
        } finally {
            stop.set(true);
            loop.join(TimeUnit.SECONDS.toMillis(30));
        }
        assertThat(loop.isAlive()).as("the connection loop ended").isFalse();
        assertThat(failures).as("no connection refused, failed or offered a third certificate").isEmpty();
        assertThat(served.get()).as("the loop connected while the swaps ran").isPositive();
    }

    private static void connectionLoop(Probe probe,
                                       Set<X509Certificate> allowed,
                                       AtomicBoolean stop,
                                       AtomicInteger served,
                                       ConcurrentLinkedQueue<String> failures) {
        while (!stop.get()) {
            try {
                var offered = probe.connectAndServe();

                if (allowed.contains(offered)) {
                    served.incrementAndGet();
                } else {
                    failures.add("unexpected certificate " + offered.getSubjectX500Principal());
                }
            } catch (Exception e) {
                failures.add(e.toString());
            }
        }
    }

    /// A server connection is freed once the client's close is processed, at the latest when it idles out (5 s), so
    /// 20 s bounds the wait.
    private static void awaitNativeRefCnt(QuicSslContext context, int expected) {
        pollUntil(() -> nativeRefCnt(context), count -> count == expected, Duration.ofSeconds(20))
            .assertMet("native context count " + expected + " once its connections are freed");
    }

    private int http1(LiveTls<SslContext> holder) {
        var server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("h1-live-tls", 0).withLiveTls(holder),
                                           (_, writer) -> writer.okText("served"))
                               .await(timeSpan(10).seconds())
                               .unwrap();

        servers.add(server);

        return server.port();
    }

    private int http3(LiveTls<QuicSslContext> holder) {
        var server = HttpServer.http3Server(HttpServerConfig.httpServerConfig("h3-live-tls", 0),
                                            holder,
                                            (_, writer) -> writer.okText("served"))
                               .await(timeSpan(10).seconds())
                               .unwrap();

        servers.add(server);

        return server.port();
    }

    private static X509Certificate h1Probe(int port) throws Exception {
        try (var connection = H1Connection.open(port)) {
            assertThat(connection.get()).isEqualTo(SERVED);

            return connection.peer();
        }
    }

    private X509Certificate h3Probe(int port) throws Exception {
        try (var connection = H3Connection.open(clientGroup, port)) {
            assertThat(connection.get()).isEqualTo(SERVED);

            return connection.peer();
        }
    }

    /// One keep-alive HTTP/1.1 connection over a JDK `SSLSocket` that trusts any certificate.
    private static final class H1Connection implements AutoCloseable {
        private final SSLSocket socket;
        private final InputStream in;

        private H1Connection(SSLSocket socket) throws IOException {
            this.socket = socket;
            this.in = new BufferedInputStream(socket.getInputStream());
        }

        static H1Connection open(int port) throws Exception {
            var context = SSLContext.getInstance("TLS");

            context.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);

            var socket = (SSLSocket) context.getSocketFactory().createSocket("127.0.0.1", port);

            socket.setSoTimeout(10_000);
            socket.startHandshake();

            return new H1Connection(socket);
        }

        X509Certificate peer() throws Exception {
            return (X509Certificate) socket.getSession().getPeerCertificates()[0];
        }

        /// `GET /` on this connection: `<status>:<body>`.
        String get() throws IOException {
            var out = socket.getOutputStream();

            out.write("GET / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            out.flush();

            var head = readHead().split("\r\n");
            var status = head[0].split(" ")[1];
            var length = 0;

            for (var line : head) {
                if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                    length = Integer.parseInt(line.substring("content-length:".length()).trim());
                }
            }

            return status + ":" + new String(in.readNBytes(length), StandardCharsets.UTF_8);
        }

        private String readHead() throws IOException {
            var head = new ByteArrayOutputStream();
            var tail = 0;

            while (tail != 0x0D0A0D0A) {
                var next = in.read();

                if (next < 0) {
                    throw new IOException("connection closed before the response head ended: " + head);
                }
                head.write(next);
                tail = (tail << 8) | next;
            }

            return head.toString(StandardCharsets.US_ASCII);
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }

    /// One HTTP/3 connection kept open across requests; each `get()` opens a new request stream on it.
    private static final class H3Connection implements AutoCloseable {
        private final Channel channel;
        private final QuicChannel quic;
        private final int port;

        private H3Connection(Channel channel, QuicChannel quic, int port) {
            this.channel = channel;
            this.quic = quic;
            this.port = port;
        }

        static H3Connection open(EventLoopGroup group, int port) throws Exception {
            var codec = Http3.newQuicClientCodecBuilder()
                             .sslContext(http3Client())
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
                var quic = QuicChannel.newBootstrap(channel)
                                      .handler(new Http3ClientConnectionHandler())
                                      .remoteAddress(new InetSocketAddress("127.0.0.1", port))
                                      .connect()
                                      .get(10, TimeUnit.SECONDS);

                return new H3Connection(channel, quic, port);
            } catch (Exception e) {
                channel.close().sync();
                throw e;
            }
        }

        X509Certificate peer() throws Exception {
            return (X509Certificate) quic.sslEngine().getSession().getPeerCertificates()[0];
        }

        /// `GET /` on a new request stream of this connection: `<status>:<body>`.
        String get() throws Exception {
            var answer = new CompletableFuture<String>();
            var stream = Http3.newRequestStream(quic, new AnswerReader(answer)).sync().getNow();
            var headers = new DefaultHttp3HeadersFrame();

            headers.headers().method("GET").path("/").authority("127.0.0.1:" + port).scheme("https");
            Http3TestClient.finishWith(stream, headers);

            return answer.get(10, TimeUnit.SECONDS);
        }

        @Override
        public void close() throws Exception {
            quic.close().sync();
            channel.close().sync();
        }
    }

    private static final class AnswerReader extends Http3RequestStreamInboundHandler {
        private final CompletableFuture<String> answer;
        private final StringBuilder body = new StringBuilder();
        private String status = "none";

        AnswerReader(CompletableFuture<String> answer) {
            this.answer = answer;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            status = String.valueOf(frame.headers().status());
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            body.append(frame.content().toString(StandardCharsets.UTF_8));
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            answer.complete(status + ":" + body);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            answer.complete(status + ":" + body + " (stream closed)");
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            answer.completeExceptionally(cause);
            ctx.close();
        }
    }
}
