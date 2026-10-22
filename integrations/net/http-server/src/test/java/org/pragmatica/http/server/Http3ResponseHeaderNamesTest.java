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
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.net.tcp.QuicSslContextFactory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2310: a handler that sets a mixed-case response header (`X-Request-Id`, as the node's app server does) still gets
/// its response to an HTTP/3 client. HTTP/3 field names are lowercase (RFC 9114 §4.2) and Netty refuses a HEADERS frame
/// with an uppercase one; before the fix the response failed and the stream went unanswered until the idle timeout.
class Http3ResponseHeaderNamesTest {
    private Http3Server server;
    private EventLoopGroup clientGroup;
    private int port;

    @BeforeEach
    void start() {
        port = freeUdpPort();
        server = Http3Server.create(HttpServerConfig.httpServerConfig("h3-header-names", port),
                                    QuicSslContextFactory.createSelfSignedServer(Http3.supportedApplicationProtocols())
                                                         .unwrap(),
                                    (_, writer) -> writer.header("X-Request-Id", "req-upper").ok("{}"))
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
    void mixedCaseResponseHeader_isSentLowercase_andTheResponseArrives() throws Exception {
        var headers = exchange();

        assertThat(headers.status()).as("the response arrives").isNotNull();
        assertThat(headers.status().toString()).isEqualTo("200");
        assertThat(headers.get("x-request-id")).hasToString("req-upper");
    }

    /// Under `tr-TR` a default-locale lower case turns the `I` of `X-Request-Id` into a dotless `ı`, which is not the
    /// name the client asked for: response names are folded with `Locale.ROOT`.
    @Test
    void mixedCaseResponseHeader_isSentLowercase_underATurkishDefaultLocale() throws Exception {
        var saved = Locale.getDefault();

        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));

            var headers = exchange();

            assertThat(headers.status()).as("the response arrives").isNotNull();
            assertThat(headers.get("x-request-id")).hasToString("req-upper");
        } finally {
            Locale.setDefault(saved);
        }
    }

    /// The response headers, or empty headers (no status) when the stream ended without a response.
    private Http3Headers exchange() throws Exception {
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
            var response = new CompletableFuture<Http3Headers>();
            var stream = Http3.newRequestStream(quic, new HeadersReader(response)).sync().getNow();
            var request = new DefaultHttp3HeadersFrame();

            request.headers().method("GET").path("/").authority("127.0.0.1:" + port).scheme("https");
            stream.writeAndFlush(request).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);

            var outcome = response.get(10, TimeUnit.SECONDS);

            quic.close().sync();

            return outcome;
        } finally {
            channel.close().sync();
        }
    }

    private static final class HeadersReader extends Http3RequestStreamInboundHandler {
        private final CompletableFuture<Http3Headers> response;

        HeadersReader(CompletableFuture<Http3Headers> response) {
            this.response = response;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            response.complete(frame.headers());
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
            ReferenceCountUtil.release(frame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            response.complete(new DefaultHttp3HeadersFrame().headers());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            response.complete(new DefaultHttp3HeadersFrame().headers());
            ctx.fireChannelInactive();
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
