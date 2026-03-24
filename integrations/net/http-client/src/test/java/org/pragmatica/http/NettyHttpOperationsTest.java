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

import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class NettyHttpOperationsTest {
    private static EventLoopGroup serverGroup;
    private static Channel serverChannel;
    private static int serverPort;
    private static NettyHttpOperations client;

    @BeforeAll
    static void startServer() throws Exception {
        serverGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        var bootstrap = new ServerBootstrap()
            .group(serverGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                      .addLast(new HttpServerCodec())
                      .addLast(new HttpObjectAggregator(1024 * 1024))
                      .addLast(new TestHttpHandler());
                }
            });
        serverChannel = bootstrap.bind(0).sync().channel();
        serverPort = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        client = NettyHttpOperations.nettyHttpOperations();
    }

    @AfterAll
    static void stopServer() throws Exception {
        client.close().await();
        serverChannel.close().sync();
        serverGroup.shutdownGracefully().sync();
    }

    @Nested
    class FactoryMethods {
        @Test
        void nettyHttpOperations_createsInstance_withDefaults() {
            var ops = NettyHttpOperations.nettyHttpOperations();
            assertThat(ops).isNotNull();
            ops.close().await();
        }

        @Test
        void nettyHttpOperations_createsInstance_withSharedEventLoop() {
            var eventLoop = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
            var ops = NettyHttpOperations.nettyHttpOperations(eventLoop);
            assertThat(ops).isNotNull();
            ops.close().await();
            eventLoop.shutdownGracefully().syncUninterruptibly();
        }
    }

    @Nested
    class Http1RoundTrip {
        @Test
        void send_returnsResponse_forGetRequest() {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + serverPort + "/hello"))
                                     .GET()
                                     .build();

            var result = client.sendString(request).await();

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(httpResult -> assertGetHelloResponse(httpResult));
        }

        @Test
        void sendString_returnsStringBody_forGetRequest() {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + serverPort + "/hello"))
                                     .GET()
                                     .build();

            var result = client.sendString(request).await();

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(httpResult -> assertThat(httpResult.body()).isEqualTo("Hello, World!"));
        }

        @Test
        void sendBytes_returnsByteArrayBody_forGetRequest() {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + serverPort + "/hello"))
                                     .GET()
                                     .build();

            var result = client.sendBytes(request).await();

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(httpResult -> assertThat(httpResult.body()).isEqualTo("Hello, World!".getBytes(StandardCharsets.UTF_8)));
        }

        @Test
        void send_returnsResponse_forPostWithBody() {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + serverPort + "/echo"))
                                     .POST(HttpRequest.BodyPublishers.ofString("test payload"))
                                     .build();

            var result = client.sendString(request).await();

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(httpResult -> assertPostEchoResponse(httpResult));
        }

        @Test
        void send_returnsHeaders_fromResponse() {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:" + serverPort + "/hello"))
                                     .GET()
                                     .build();

            var result = client.sendString(request).await();

            result.onFailure(cause -> assertThat(cause).isNull())
                  .onSuccess(httpResult -> assertThat(httpResult.header("x-test-header").isPresent()).isTrue());
        }

        private static void assertGetHelloResponse(HttpResult<String> httpResult) {
            assertThat(httpResult.statusCode()).isEqualTo(200);
            assertThat(httpResult.body()).isEqualTo("Hello, World!");
        }

        private static void assertPostEchoResponse(HttpResult<String> httpResult) {
            assertThat(httpResult.statusCode()).isEqualTo(200);
            assertThat(httpResult.body()).isEqualTo("test payload");
        }
    }

    @Nested
    class ErrorHandling {
        @Test
        void send_failsWithConnectionError_whenServerUnreachable() {
            var request = HttpRequest.newBuilder()
                                     .uri(URI.create("http://localhost:1/unreachable"))
                                     .GET()
                                     .build();

            var result = client.sendString(request).await();

            result.onSuccess(_ -> assertThat(true).as("Expected failure").isFalse())
                  .onFailure(cause -> assertThat(cause).isInstanceOf(HttpError.class));
        }
    }

    /// Test HTTP server handler: /hello returns "Hello, World!", /echo returns the request body.
    private static class TestHttpHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            var path = request.uri();
            byte[] body;
            HttpResponseStatus status;

            if (path.startsWith("/echo")) {
                body = new byte[request.content().readableBytes()];
                request.content().readBytes(body);
                status = HttpResponseStatus.OK;
            } else if (path.startsWith("/hello")) {
                body = "Hello, World!".getBytes(StandardCharsets.UTF_8);
                status = HttpResponseStatus.OK;
            } else {
                body = "Not Found".getBytes(StandardCharsets.UTF_8);
                status = HttpResponseStatus.NOT_FOUND;
            }

            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.wrappedBuffer(body));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/plain");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set("x-test-header", "test-value");
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            ctx.close();
        }
    }
}
