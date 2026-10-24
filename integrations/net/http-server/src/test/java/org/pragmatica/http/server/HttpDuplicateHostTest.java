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
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.http.HttpRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.http.websocket.WebSocketEndpoint.webSocketEndpoint;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #2386: RFC 9112 §3.2 — a request with more than one `Host` field line is answered 400 and never dispatched. Netty's
/// decoder keeps every line, so the server checks. Sent both through a real HTTP client (Netty's, which writes each
/// added line as given) and through a raw socket, in case a client library merges the lines.
class HttpDuplicateHostTest {
    private final Recorder recorder = new Recorder();
    private HttpServer server;

    @BeforeEach
    void start() {
        var config = HttpServerConfig.httpServerConfig("duplicate-host", 0)
                                     .withWebSocket(webSocketEndpoint("/ws", () -> (_, _) -> {}));

        server = HttpServer.httpServer(config, recorder)
                           .await(timeSpan(10).seconds())
                           .unwrap();
    }

    @AfterEach
    void stop() {
        server.stop().await(timeSpan(10).seconds());
    }

    @Test
    void singleHost_isServed() throws IOException {
        assertThat(rawStatus("GET / HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n")).isEqualTo(200);
        assertThat(recorder.requests()).hasSize(1);
    }

    @Test
    void twoHostLines_areRefused400_andNeverDispatched() throws IOException {
        assertRefused("GET / HTTP/1.1\r\nHost: localhost\r\nHost: evil.example\r\nConnection: close\r\n\r\n");
    }

    /// The duplicate-Host request is not dispatched, and the connection is closed after the 400 even when the client
    /// asked to keep it.
    @Test
    void twoHostLines_closeTheConnection() throws IOException {
        assertRefused("GET / HTTP/1.1\r\nHost: localhost\r\nHost: evil.example\r\n\r\n");
    }

    /// Field names are case-insensitive: `Host` and `host` are two lines of the same field.
    @Test
    void twoHostLines_differingInCase_areRefused400() throws IOException {
        assertRefused("GET / HTTP/1.1\r\nHost: localhost\r\nhOST: evil.example\r\nConnection: close\r\n\r\n");
    }

    /// The rule is about field lines, not values: the same value twice is refused too.
    @Test
    void twoHostLines_withTheSameValue_areRefused400() throws IOException {
        assertRefused("GET / HTTP/1.1\r\nHost: localhost\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    /// A WebSocket upgrade is a request message too; it is refused before the handshake, never upgraded.
    @Test
    void upgradeWithTwoHostLines_isRefused400_notUpgraded() throws IOException {
        var raw = "GET /ws HTTP/1.1\r\nHost: localhost\r\nHost: evil.example\r\nUpgrade: websocket\r\n"
                  + "Connection: Upgrade\r\nSec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                  + "Sec-WebSocket-Version: 13\r\n\r\n";

        assertRefused(raw);
    }

    /// The control for the upgrade case: the same upgrade with one `Host` is switched (101).
    @Test
    void upgradeWithOneHost_isUpgraded() throws IOException {
        var raw = "GET /ws HTTP/1.1\r\nHost: localhost\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                  + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";

        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var head = new String(socket.getInputStream().readNBytes(12), StandardCharsets.US_ASCII);

            assertThat(head).isEqualTo("HTTP/1.1 101");
        }
    }

    @Test
    void realClient_singleHost_isServed() throws Exception {
        assertThat(clientStatus("localhost")).isEqualTo(200);
        assertThat(recorder.requests()).hasSize(1);
    }

    @Test
    void realClient_twoHostLines_areRefused400_andNeverDispatched() throws Exception {
        assertThat(clientStatus("localhost", "evil.example")).isEqualTo(400);
        assertThat(recorder.requests()).isEmpty();
    }

    private void assertRefused(String raw) throws IOException {
        assertThat(rawStatus(raw)).isEqualTo(400);
        assertThat(recorder.requests()).as("a request with more than one Host line must not be dispatched")
                                       .isEmpty();
    }

    private int rawStatus(String raw) throws IOException {
        try (var socket = new Socket("127.0.0.1", server.port())) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(raw.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();

            var response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.US_ASCII);

            return Integer.parseInt(response.substring(9, 12));
        }
    }

    /// Sends `GET /` with one `Host` line per value, alternating the name's case, through Netty's HTTP client codec.
    private int clientStatus(String... hosts) throws Exception {
        var group = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());

        try {
            var status = new CompletableFuture<Integer>();
            var channel = new Bootstrap().group(group)
                                         .channel(NioSocketChannel.class)
                                         .handler(new ChannelInitializer<SocketChannel>() {
                                             @Override
                                             protected void initChannel(SocketChannel ch) {
                                                 ch.pipeline()
                                                   .addLast(new HttpClientCodec(),
                                                            new HttpObjectAggregator(65_536),
                                                            new StatusReader(status));
                                             }
                                         })
                                         .connect("127.0.0.1", server.port())
                                         .sync()
                                         .channel();

            try {
                var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");

                for (int i = 0; i < hosts.length; i++) {
                    request.headers().add(i % 2 == 0 ? "Host" : "host", hosts[i]);
                }
                channel.writeAndFlush(request).sync();

                return status.get(10, TimeUnit.SECONDS);
            } finally {
                channel.close().sync();
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    private static final class StatusReader extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final CompletableFuture<Integer> status;

        StatusReader(CompletableFuture<Integer> status) {
            this.status = status;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            status.complete(response.status().code());
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            status.completeExceptionally(cause);
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
    }
}
