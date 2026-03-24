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

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandler;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.AsyncCloseable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Unit.unit;

/// Netty-based HTTP client supporting HTTP/1.1 (TCP) and HTTP/3 (QUIC).
///
/// Creates a new connection per request. Connection pooling is not yet implemented.
/// Implements [AsyncCloseable] for event loop shutdown.
public final class NettyHttpOperations implements HttpOperations, AsyncCloseable {
    private static final Logger log = LoggerFactory.getLogger(NettyHttpOperations.class);
    private static final int MAX_CONTENT_LENGTH = 10 * 1024 * 1024;
    private static final int DEFAULT_HTTP_PORT = 80;
    private static final int DEFAULT_HTTPS_PORT = 443;

    private final EventLoopGroup eventLoopGroup;
    private final Option<QuicSslContext> quicSslContext;
    private final boolean ownsEventLoop;

    private NettyHttpOperations(EventLoopGroup eventLoopGroup,
                                Option<QuicSslContext> quicSslContext,
                                boolean ownsEventLoop) {
        this.eventLoopGroup = eventLoopGroup;
        this.quicSslContext = quicSslContext;
        this.ownsEventLoop = ownsEventLoop;
    }

    /// Creates NettyHttpOperations for HTTP/1.1 only (TCP) with its own event loop.
    ///
    /// @return NettyHttpOperations instance
    public static NettyHttpOperations nettyHttpOperations() {
        return new NettyHttpOperations(defaultEventLoop(), none(), true);
    }

    /// Creates NettyHttpOperations with HTTP/3 QUIC support.
    ///
    /// @param quicSslContext QUIC SSL context for HTTP/3 connections
    ///
    /// @return NettyHttpOperations instance with HTTP/3 capability
    public static NettyHttpOperations nettyHttpOperations(QuicSslContext quicSslContext) {
        return new NettyHttpOperations(defaultEventLoop(), option(quicSslContext), true);
    }

    /// Creates NettyHttpOperations with a shared event loop (HTTP/1.1 only).
    ///
    /// @param eventLoop Shared event loop group
    ///
    /// @return NettyHttpOperations instance using the shared event loop
    public static NettyHttpOperations nettyHttpOperations(EventLoopGroup eventLoop) {
        return new NettyHttpOperations(eventLoop, none(), false);
    }

    /// Creates NettyHttpOperations with HTTP/3 QUIC support and shared event loop.
    ///
    /// @param quicSslContext QUIC SSL context for HTTP/3 connections
    /// @param eventLoop Shared event loop group
    ///
    /// @return NettyHttpOperations instance with HTTP/3 and shared event loop
    public static NettyHttpOperations nettyHttpOperations(QuicSslContext quicSslContext, EventLoopGroup eventLoop) {
        return new NettyHttpOperations(eventLoop, option(quicSslContext), false);
    }

    @Override
    public <T> Promise<HttpResult<T>> send(HttpRequest request, BodyHandler<T> handler) {
        var uri = request.uri();
        var scheme = option(uri.getScheme()).or("http");

        if ("https".equalsIgnoreCase(scheme) && quicSslContext.isPresent()) {
            return sendHttp3(request, handler, uri);
        }
        return sendHttp1(request, handler, uri);
    }

    @Override
    public Promise<Unit> close() {
        if (!ownsEventLoop) {
            return Promise.success(unit());
        }
        return Promise.promise(promise -> eventLoopGroup.shutdownGracefully()
                                                        .addListener(_ -> promise.succeed(unit())));
    }

    // --- HTTP/1.1 ---

    private <T> Promise<HttpResult<T>> sendHttp1(HttpRequest request, BodyHandler<T> handler, URI uri) {
        return Promise.promise(promise -> initiateHttp1Request(request, handler, uri, promise));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty bootstrap + channel future callback
    private <T> void initiateHttp1Request(HttpRequest request,
                                          BodyHandler<T> handler,
                                          URI uri,
                                          Promise<HttpResult<T>> promise) {
        var host = uri.getHost();
        var port = resolvePort(uri, DEFAULT_HTTP_PORT);
        var path = resolvePath(uri);

        var bootstrap = new Bootstrap().group(eventLoopGroup)
                                       .channel(NioSocketChannel.class)
                                       .handler(new Http1ChannelInitializer<>(promise, handler));

        bootstrap.connect(new InetSocketAddress(host, port))
                 .addListener(future -> handleHttp1Connect(future, request, host, path, promise));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback
    private static <T> void handleHttp1Connect(io.netty.util.concurrent.Future<? super Void> future,
                                               HttpRequest request,
                                               String host,
                                               String path,
                                               Promise<HttpResult<T>> promise) {
        if (!future.isSuccess()) {
            promise.fail(HttpError.fromException(future.cause()));
            return;
        }
        var channel = ((io.netty.channel.ChannelFuture) future).channel();
        writeHttp1Request(channel, request, host, path);
    }

    private static void writeHttp1Request(Channel channel, HttpRequest request, String host, String path) {
        var method = HttpMethod.valueOf(request.method());
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, path, requestBody(request));

        nettyRequest.headers().set("Host", host);
        request.headers().map().forEach((name, values) -> values.forEach(v -> nettyRequest.headers().add(name, v)));
        nettyRequest.headers().set("Content-Length", nettyRequest.content().readableBytes());

        channel.writeAndFlush(nettyRequest);
    }

    // --- HTTP/3 ---

    private <T> Promise<HttpResult<T>> sendHttp3(HttpRequest request, BodyHandler<T> handler, URI uri) {
        return Promise.promise(promise -> initiateHttp3Request(request, handler, uri, promise));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty QUIC bootstrap
    private <T> void initiateHttp3Request(HttpRequest request,
                                          BodyHandler<T> handler,
                                          URI uri,
                                          Promise<HttpResult<T>> promise) {
        quicSslContext.onPresent(ssl -> connectQuic(request, handler, uri, ssl, promise))
                      .onEmpty(() -> promise.fail(HttpError.ConnectionFailed.connectionFailed("No QUIC SSL context for HTTP/3")));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty QUIC bootstrap chain
    private <T> void connectQuic(HttpRequest request,
                                 BodyHandler<T> handler,
                                 URI uri,
                                 QuicSslContext ssl,
                                 Promise<HttpResult<T>> promise) {
        var host = uri.getHost();
        var port = resolvePort(uri, DEFAULT_HTTPS_PORT);

        var codec = Http3.newQuicClientCodecBuilder()
                         .sslContext(ssl)
                         .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                         .initialMaxData(10_000_000)
                         .initialMaxStreamDataBidirectionalLocal(1_000_000)
                         .initialMaxStreamDataBidirectionalRemote(1_000_000)
                         .initialMaxStreamsBidirectional(100)
                         .build();

        var bootstrap = new Bootstrap().group(eventLoopGroup)
                                       .channel(NioDatagramChannel.class)
                                       .handler(codec);

        bootstrap.bind(0)
                 .addListener(future -> handleQuicBind(future, request, handler, host, port, promise));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback chain
    private static <T> void handleQuicBind(io.netty.util.concurrent.Future<? super Void> future,
                                           HttpRequest request,
                                           BodyHandler<T> handler,
                                           String host,
                                           int port,
                                           Promise<HttpResult<T>> promise) {
        if (!future.isSuccess()) {
            promise.fail(HttpError.fromException(future.cause()));
            return;
        }
        var datagramChannel = ((io.netty.channel.ChannelFuture) future).channel();
        connectQuicChannel(datagramChannel, request, handler, host, port, promise);
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty QUIC channel bootstrap
    private static <T> void connectQuicChannel(Channel datagramChannel,
                                               HttpRequest request,
                                               BodyHandler<T> handler,
                                               String host,
                                               int port,
                                               Promise<HttpResult<T>> promise) {
        QuicChannel.newBootstrap(datagramChannel)
                   .handler(new Http3ClientConnectionHandler())
                   .remoteAddress(new InetSocketAddress(host, port))
                   .connect()
                   .addListener(future -> handleQuicConnect(future, request, handler, promise));
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"}) // Netty future callback
    private static <T> void handleQuicConnect(io.netty.util.concurrent.Future<?> future,
                                              HttpRequest request,
                                              BodyHandler<T> handler,
                                              Promise<HttpResult<T>> promise) {
        if (!future.isSuccess()) {
            promise.fail(HttpError.fromException(future.cause()));
            return;
        }
        var quicChannel = (QuicChannel) future.getNow();
        openHttp3Stream(quicChannel, request, handler, promise);
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty HTTP/3 stream creation
    private static <T> void openHttp3Stream(QuicChannel quicChannel,
                                            HttpRequest request,
                                            BodyHandler<T> handler,
                                            Promise<HttpResult<T>> promise) {
        Http3.newRequestStream(quicChannel, new Http3ResponseHandler<>(promise, handler))
             .addListener(future -> handleStreamCreated(future, request));
    }

    @SuppressWarnings({"JBCT-PAT-01", "unchecked"}) // Netty future callback
    private static <T> void handleStreamCreated(io.netty.util.concurrent.Future<?> future,
                                                HttpRequest request) {
        if (!future.isSuccess()) {
            log.error("Failed to create HTTP/3 request stream", future.cause());
            return;
        }
        var streamChannel = (QuicStreamChannel) future.getNow();
        writeHttp3Request(streamChannel, request);
    }

    private static void writeHttp3Request(QuicStreamChannel streamChannel, HttpRequest request) {
        var uri = request.uri();
        var headers = new DefaultHttp3Headers();

        headers.method(request.method());
        headers.path(resolvePath(uri));
        headers.authority(uri.getHost());
        headers.scheme(option(uri.getScheme()).or("https"));

        request.headers().map().forEach((name, values) -> values.forEach(v -> headers.set(name, v)));

        streamChannel.write(new DefaultHttp3HeadersFrame(headers));

        var body = requestBody(request);
        if (body.isReadable()) {
            streamChannel.write(new DefaultHttp3DataFrame(body));
        } else {
            body.release();
        }
        streamChannel.writeAndFlush(Unpooled.EMPTY_BUFFER)
                     .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
    }

    // --- Shared Helpers ---

    private static EventLoopGroup defaultEventLoop() {
        return new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
    }

    private static int resolvePort(URI uri, int defaultPort) {
        return uri.getPort() > 0 ? uri.getPort() : defaultPort;
    }

    private static String resolvePath(URI uri) {
        var path = option(uri.getRawPath()).or("/");
        var query = option(uri.getRawQuery());
        return query.map(q -> path + "?" + q).or(path);
    }

    private static ByteBuf requestBody(HttpRequest request) {
        return request.bodyPublisher()
                      .map(NettyHttpOperations::extractBodyBytes)
                      .map(Unpooled::wrappedBuffer)
                      .orElse(Unpooled.EMPTY_BUFFER);
    }

    private static byte[] extractBodyBytes(HttpRequest.BodyPublisher publisher) {
        var subscriber = java.net.http.HttpResponse.BodySubscribers.ofByteArray();
        publisher.subscribe(new BodyPublisherToSubscriber(subscriber));
        return subscriber.getBody().toCompletableFuture().join();
    }

    @SuppressWarnings("unchecked")
    static <T> T convertResponseBody(byte[] bytes, BodyHandler<T> handler, int statusCode, HttpHeaders headers) {
        var responseInfo = new ResponseInfoAdapter(statusCode, headers);
        var subscriber = handler.apply(responseInfo);
        subscriber.onSubscribe(new ImmediateSubscription<>(subscriber, bytes));
        return subscriber.getBody().toCompletableFuture().join();
    }

    static HttpHeaders buildHttpHeaders(Map<String, List<String>> headerMap) {
        return HttpHeaders.of(headerMap, (_, _) -> true);
    }

    // --- Channel Handlers ---

    /// HTTP/1.1 channel initializer adding codec and aggregator.
    private static class Http1ChannelInitializer<T> extends ChannelInitializer<SocketChannel> {
        private final Promise<HttpResult<T>> promise;
        private final BodyHandler<T> handler;

        Http1ChannelInitializer(Promise<HttpResult<T>> promise, BodyHandler<T> handler) {
            this.promise = promise;
            this.handler = handler;
        }

        @Override
        protected void initChannel(SocketChannel ch) {
            ch.pipeline()
              .addLast(new HttpClientCodec())
              .addLast(new HttpObjectAggregator(MAX_CONTENT_LENGTH))
              .addLast(new Http1ResponseHandler<>(promise, handler));
        }
    }

    /// HTTP/1.1 response handler converting Netty response to HttpResult.
    private static class Http1ResponseHandler<T> extends SimpleChannelInboundHandler<FullHttpResponse> {
        private final Promise<HttpResult<T>> promise;
        private final BodyHandler<T> handler;

        Http1ResponseHandler(Promise<HttpResult<T>> promise, BodyHandler<T> handler) {
            this.promise = promise;
            this.handler = handler;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
            var bytes = extractBytes(response.content());
            var statusCode = response.status().code();
            var headers = buildHttpHeaders(extractResponseHeaders(response));
            var body = convertResponseBody(bytes, handler, statusCode, headers);
            promise.succeed(new HttpResult<>(statusCode, headers, body));
            ctx.close();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            promise.fail(HttpError.fromException(cause));
            ctx.close();
        }

        private static byte[] extractBytes(ByteBuf content) {
            var bytes = new byte[content.readableBytes()];
            content.readBytes(bytes);
            return bytes;
        }

        private static Map<String, List<String>> extractResponseHeaders(FullHttpResponse response) {
            var headerMap = new HashMap<String, List<String>>();

            for (var entry : response.headers()) {
                headerMap.computeIfAbsent(entry.getKey().toLowerCase(), _ -> new ArrayList<>())
                         .add(entry.getValue());
            }
            return headerMap;
        }
    }

    /// HTTP/3 response handler accumulating headers and data frames.
    private static class Http3ResponseHandler<T> extends Http3RequestStreamInboundHandler {
        private final Promise<HttpResult<T>> promise;
        private final BodyHandler<T> handler;
        private Http3HeadersFrame headersFrame;
        private ByteBuf bodyData;

        Http3ResponseHandler(Promise<HttpResult<T>> promise, BodyHandler<T> handler) {
            this.promise = promise;
            this.handler = handler;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
            headersFrame = frame;
        }

        @Override
        protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
            appendData(dataFrame.content());
            ReferenceCountUtil.release(dataFrame);
        }

        @Override
        protected void channelInputClosed(ChannelHandlerContext ctx) {
            if (headersFrame == null) {
                promise.fail(HttpError.InvalidResponse.invalidResponse("No headers received"));
                return;
            }
            completeResponse(ctx);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            promise.fail(HttpError.fromException(cause));
            ctx.close();
        }

        private void completeResponse(ChannelHandlerContext ctx) {
            var h3Headers = headersFrame.headers();
            var statusCode = parseStatusCode(h3Headers);
            var headers = buildHttpHeaders(extractHttp3Headers(h3Headers));
            var bytes = extractBody();
            var body = convertResponseBody(bytes, handler, statusCode, headers);
            promise.succeed(new HttpResult<>(statusCode, headers, body));
            ctx.close();
        }

        private static int parseStatusCode(io.netty.handler.codec.http3.Http3Headers headers) {
            var status = option(headers.status()).map(CharSequence::toString).or("200");
            return Integer.parseInt(status);
        }

        private static Map<String, List<String>> extractHttp3Headers(io.netty.handler.codec.http3.Http3Headers h3Headers) {
            var headerMap = new HashMap<String, List<String>>();

            for (var entry : h3Headers) {
                var key = entry.getKey().toString().toLowerCase();
                if (!key.startsWith(":")) {
                    headerMap.computeIfAbsent(key, _ -> new ArrayList<>())
                             .add(entry.getValue().toString());
                }
            }
            return headerMap;
        }

        private void appendData(ByteBuf content) {
            if (bodyData == null) {
                bodyData = Unpooled.buffer(content.readableBytes());
            }
            bodyData.writeBytes(content);
        }

        private byte[] extractBody() {
            if (bodyData == null || !bodyData.isReadable()) {
                return new byte[0];
            }
            var bytes = new byte[bodyData.readableBytes()];
            bodyData.readBytes(bytes);
            bodyData.release();
            return bytes;
        }
    }

    /// Minimal ResponseInfo adapter for BodyHandler.apply().
    private record ResponseInfoAdapter(int statusCode, HttpHeaders headers)
        implements java.net.http.HttpResponse.ResponseInfo {

        @Override
        public java.net.http.HttpClient.Version version() {
            return java.net.http.HttpClient.Version.HTTP_1_1;
        }
    }

    /// Subscription that delivers bytes immediately upon request.
    private static class ImmediateSubscription<T> implements java.util.concurrent.Flow.Subscription {
        private final java.net.http.HttpResponse.BodySubscriber<T> subscriber;
        private final byte[] bytes;

        ImmediateSubscription(java.net.http.HttpResponse.BodySubscriber<T> subscriber, byte[] bytes) {
            this.subscriber = subscriber;
            this.bytes = bytes;
        }

        @Override
        public void request(long n) {
            subscriber.onNext(List.of(java.nio.ByteBuffer.wrap(bytes)));
            subscriber.onComplete();
        }

        @Override
        public void cancel() {
            // No-op
        }
    }

    /// Adapter bridging JDK BodyPublisher to Flow.Subscriber for body extraction.
    private static class BodyPublisherToSubscriber implements java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer> {
        private final java.net.http.HttpResponse.BodySubscriber<byte[]> subscriber;

        BodyPublisherToSubscriber(java.net.http.HttpResponse.BodySubscriber<byte[]> subscriber) {
            this.subscriber = subscriber;
        }

        @Override
        public void onSubscribe(java.util.concurrent.Flow.Subscription subscription) {
            subscriber.onSubscribe(subscription);
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(java.nio.ByteBuffer item) {
            subscriber.onNext(List.of(item));
        }

        @Override
        public void onError(Throwable throwable) {
            subscriber.onError(throwable);
        }

        @Override
        public void onComplete() {
            subscriber.onComplete();
        }
    }
}
