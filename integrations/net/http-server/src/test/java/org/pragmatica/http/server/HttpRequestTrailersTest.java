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

import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.lang.Option;
import org.pragmatica.net.tcp.ClientAuthPolicy;
import org.pragmatica.net.tcp.QuicSslContextFactory;
import org.pragmatica.net.tcp.TlsConfig;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2310: request trailers reach the handler on both transports, as [HttpRequest#trailers()], kept apart from the
/// headers. Each transport's populating step is what the delivery tests pin: without it the handler sees no
/// trailers. The forbidden-field tests pin the RFC 9110 §6.5.1 discard ([org.pragmatica.http.Headers#trailers]),
/// and the over-limit tests pin that a trailer section the transport could not read is never dispatched as a
/// complete request without it.
class HttpRequestTrailersTest {
    /// Past the 8192-byte field-section limit on both transports, even after QPACK's Huffman coding (5 bits per 'a').
    private static final String OVER_LIMIT = "a".repeat(16_384);

    @Nested
    class Http1 {
        private final Recorder recorder = new Recorder();
        private HttpServer server;

        @BeforeEach
        void start() {
            server = HttpServer.httpServer(HttpServerConfig.httpServerConfig("trailers-h1", 0), recorder)
                               .await(timeSpan(10).seconds())
                               .unwrap();
        }

        @AfterEach
        void stop() {
            server.stop().await(timeSpan(10).seconds());
        }

        @Test
        void chunkedRequest_withTrailers_deliversThemApartFromTheHeaders() throws IOException {
            var status = exchange(chunked("X-Checksum: abc123\r\nx-multi: one\r\nx-multi: two\r\n"));

            assertThat(status).isEqualTo(200);

            var request = recorder.single();

            assertThat(request.trailers().get("x-checksum")).isEqualTo(Option.some("abc123"));
            assertThat(request.trailers().getAll("x-multi")).containsExactly("one", "two");
            assertThat(request.headers().get("x-checksum").isEmpty()).as("a trailer is never merged into the headers")
                                                                     .isTrue();
            assertThat(request.bodyAsString()).isEqualTo("hello");
        }

        /// The request carries a header that is not a forbidden trailer name (`X-Client`), so trailers wrongly
        /// filled from the header section cannot pass as empty.
        @Test
        void chunkedRequest_withoutTrailers_deliversEmptyTrailers() throws IOException {
            assertThat(exchange(chunked(""))).isEqualTo(200);
            assertThat(recorder.single().trailers().asMap()).isEmpty();
        }

        @Test
        void contentLengthRequest_deliversEmptyTrailers() throws IOException {
            var raw = "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-Client: probe\r\nContent-Length: 5\r\n"
                      + "Connection: close\r\n\r\nhello";

            assertThat(exchange(raw)).isEqualTo(200);
            assertThat(recorder.single().trailers().asMap()).isEmpty();
        }

        @Test
        void forbiddenTrailerFields_areDiscarded_andTheRequestIsServed() throws IOException {
            var status = exchange(chunked("Authorization: Bearer forged\r\nContent-Type: text/evil\r\nHost: evil\r\n"
                                          + "Cookie: s=1\r\nx-ok: yes\r\n"));

            assertThat(status).isEqualTo(200);

            var request = recorder.single();

            assertThat(request.trailers().asMap().keySet()).containsExactly("x-ok");
            assertThat(request.headers().get("content-type")).isEqualTo(Option.some("text/plain"));
        }

        @Test
        void trailerSectionOverTheHeaderLimit_isRefused431_andNeverDispatched() throws IOException {
            assertThat(exchange(chunked("x-big: " + OVER_LIMIT + "\r\n"))).isEqualTo(431);
            assertThat(recorder.requests()).as("a request whose trailers could not be read must not be dispatched")
                                           .isEmpty();
        }

        private int exchange(String raw) throws IOException {
            try (var socket = new Socket("127.0.0.1", server.port())) {
                socket.setSoTimeout(10_000);
                socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
                socket.getOutputStream().flush();

                var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

                return Integer.parseInt(response.substring(9, 12));
            }
        }

        private static String chunked(String trailerSection) {
            return "POST /upload HTTP/1.1\r\nHost: localhost\r\nX-Client: probe\r\nContent-Type: text/plain\r\n"
                   + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                   + "5\r\nhello\r\n0\r\n" + trailerSection + "\r\n";
        }
    }

    @Nested
    class Http3Transport {
        private final Recorder recorder = new Recorder();
        private Http3Server server;
        private EventLoopGroup clientGroup;
        private int port;

        @BeforeEach
        void start() {
            port = freeUdpPort();
            server = Http3Server.create(HttpServerConfig.httpServerConfig("trailers-h3", port), serverSsl(), recorder)
                                .await(timeSpan(10).seconds())
                                .unwrap();
            clientGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        }

        @AfterEach
        void stop() {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
            server.stop().await(timeSpan(10).seconds());
        }

        @Test
        void trailingHeadersFrame_isDeliveredAsTrailers_apartFromTheHeaders() throws Exception {
            var trailers = new DefaultHttp3Headers();

            trailers.add("x-checksum", "abc123");
            trailers.add("x-multi", "one");
            trailers.add("x-multi", "two");

            assertThat(exchange(Option.some(trailers))).isEqualTo("200");

            var request = recorder.single();

            assertThat(request.trailers().get("x-checksum")).isEqualTo(Option.some("abc123"));
            assertThat(request.trailers().getAll("x-multi")).containsExactly("one", "two");
            assertThat(request.headers().get("x-checksum").isEmpty()).as("a trailer is never merged into the headers")
                                                                     .isTrue();
            assertThat(request.method().name()).as("the trailing frame must not replace the header section")
                                               .isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/upload");
            assertThat(request.bodyAsString()).isEqualTo("hello");
        }

        @Test
        void noTrailingHeadersFrame_deliversEmptyTrailers() throws Exception {
            assertThat(exchange(Option.none())).isEqualTo("200");
            assertThat(recorder.single().trailers().asMap()).isEmpty();
        }

        @Test
        void forbiddenTrailerFields_areDiscarded_andTheRequestIsServed() throws Exception {
            var trailers = new DefaultHttp3Headers();

            trailers.add("authorization", "Bearer forged");
            trailers.add("content-type", "text/evil");
            trailers.add("x-ok", "yes");

            assertThat(exchange(Option.some(trailers))).isEqualTo("200");

            var request = recorder.single();

            assertThat(request.trailers().asMap().keySet()).containsExactly("x-ok");
            assertThat(request.headers().get("content-type")).isEqualTo(Option.some("text/plain"));
        }

        @Test
        void trailerSectionOverTheFieldSectionLimit_isNeverDispatched() throws Exception {
            var trailers = new DefaultHttp3Headers();

            trailers.add("x-big", OVER_LIMIT);

            assertThat(exchange(Option.some(trailers))).as("the server answers no status for an unreadable trailer section")
                                                       .isNotEqualTo("200");
            assertThat(recorder.requests()).as("a request whose trailers could not be read must not be dispatched")
                                           .isEmpty();
        }

        /// The response status, or `closed` when the stream or connection ended without one.
        private String exchange(Option<Http3Headers> trailers) throws Exception {
            var codec = Http3.newQuicClientCodecBuilder()
                             .sslContext(clientSsl())
                             .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                             .initialMaxData(10_000_000)
                             .initialMaxStreamDataBidirectionalLocal(1_000_000)
                             .build();
            var channel = new Bootstrap().group(clientGroup)
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
                var status = new CompletableFuture<String>();

                quic.closeFuture().addListener(_ -> status.complete("closed"));

                var stream = Http3.newRequestStream(quic, new StatusReader(status)).sync().getNow();

                send(stream, trailers);

                var outcome = status.get(10, TimeUnit.SECONDS);

                quic.close().sync();

                return outcome;
            } finally {
                channel.close().sync();
            }
        }

        private void send(QuicStreamChannel stream, Option<Http3Headers> trailers) {
            var headers = new DefaultHttp3HeadersFrame();

            headers.headers()
                   .method("POST")
                   .path("/upload")
                   .authority("127.0.0.1:" + port)
                   .scheme("https")
                   .add("content-type", "text/plain");
            stream.write(headers);

            var body = new DefaultHttp3DataFrame(Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8));

            trailers.onEmpty(() -> stream.writeAndFlush(body).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT))
                    .onPresent(section -> sendWithTrailers(stream, body, section));
        }

        private static void sendWithTrailers(QuicStreamChannel stream, Http3DataFrame body, Http3Headers section) {
            stream.write(body);
            stream.writeAndFlush(new DefaultHttp3HeadersFrame(section)).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
        }

        /// The server context offers the `h3` ALPN token: QUIC requires ALPN, and without it no HTTP/3 client completes
        /// the handshake.
        private static QuicSslContext serverSsl() {
            return QuicSslContextFactory.createServer(TlsConfig.selfSignedServer(),
                                                      ClientAuthPolicy.NOT_REQUESTED,
                                                      Http3.supportedApplicationProtocols())
                                        .unwrap();
        }

        private static QuicSslContext clientSsl() {
            return QuicSslContextBuilder.forClient()
                                        .trustManager(InsecureTrustManagerFactory.INSTANCE)
                                        .applicationProtocols(Http3.supportedApplicationProtocols())
                                        .build();
        }
    }

    /// Completes with the response status, or with `closed` when the stream ends without one.
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

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            status.complete("closed");
            ctx.close();
        }
    }

    /// Keeps every request the server dispatched and answers 200.
    private static final class Recorder implements BiConsumer<HttpRequest, ResponseWriter> {
        private final List<HttpRequest> requests = new CopyOnWriteArrayList<>();

        @Override
        public void accept(HttpRequest request, ResponseWriter writer) {
            requests.add(request);
            writer.ok("{}");
        }

        List<HttpRequest> requests() {
            return List.copyOf(requests);
        }

        HttpRequest single() {
            assertThat(requests).as("exactly one request was dispatched").hasSize(1);

            return requests.getFirst();
        }
    }

    private static int freeUdpPort() {
        try (var socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }
}
