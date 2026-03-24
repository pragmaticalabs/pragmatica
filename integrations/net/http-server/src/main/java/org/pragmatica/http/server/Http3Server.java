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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioDatagramChannel;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.http3.Http3ServerConnectionHandler;
import io.netty.handler.codec.quic.InsecureQuicTokenHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.util.ReferenceCountUtil;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.utility.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/// HTTP/3 server implementation using Netty QUIC transport.
///
/// Binds a UDP socket and handles HTTP/3 requests over QUIC,
/// converting them to the same [RequestContext]/[ResponseWriter]
/// interface used by the HTTP/1.1 server.
final class Http3Server {
    private static final Logger log = LoggerFactory.getLogger(Http3Server.class);

    private final int port;
    private final Option<EventLoopGroup> workerGroup;
    private final Option<Channel> serverChannel;
    private final boolean ownsGroup;

    private Http3Server(int port,
                        Option<EventLoopGroup> workerGroup,
                        Option<Channel> serverChannel,
                        boolean ownsGroup) {
        this.port = port;
        this.workerGroup = workerGroup;
        this.serverChannel = serverChannel;
        this.ownsGroup = ownsGroup;
    }

    int port() {
        return port;
    }

    Promise<Unit> stop() {
        return Promise.promise(this::initiateShutdown);
    }

    private void initiateShutdown(Promise<Unit> promise) {
        log.info("Stopping HTTP/3 server on port {}", port);
        serverChannel.onPresent(channel -> channel.close()
                                                   .addListener(_ -> cleanupAndComplete(promise)))
                     .onEmpty(() -> cleanupAndComplete(promise));
    }

    private void cleanupAndComplete(Promise<Unit> promise) {
        if (!ownsGroup) {
            promise.succeed(unit());
            return;
        }
        workerGroup.map(EventLoopGroup::shutdownGracefully)
                   .onPresent(f -> f.addListener(_ -> promise.succeed(unit())))
                   .onEmpty(() -> promise.succeed(unit()));
    }

    /// Create and start an HTTP/3 server with its own event loop group.
    static Promise<Http3Server> create(HttpServerConfig config,
                                       QuicSslContext quicSslContext,
                                       BiConsumer<RequestContext, ResponseWriter> handler) {
        var group = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        return bind(config, quicSslContext, handler, group, true);
    }

    /// Create and start an HTTP/3 server using an externally managed event loop group.
    static Promise<Http3Server> createShared(HttpServerConfig config,
                                             QuicSslContext quicSslContext,
                                             BiConsumer<RequestContext, ResponseWriter> handler,
                                             EventLoopGroup workerGroup) {
        return bind(config, quicSslContext, handler, workerGroup, false);
    }

    @SuppressWarnings("JBCT-UTIL-01")
    private static Promise<Http3Server> bind(HttpServerConfig config,
                                             QuicSslContext quicSslContext,
                                             BiConsumer<RequestContext, ResponseWriter> handler,
                                             EventLoopGroup group,
                                             boolean ownsGroup) {
        var maxContentLength = config.maxContentLength();
        var codec = Http3.newQuicServerCodecBuilder()
                         .sslContext(quicSslContext)
                         .maxIdleTimeout(5000, TimeUnit.MILLISECONDS)
                         .initialMaxData(10_000_000)
                         .initialMaxStreamDataBidirectionalLocal(1_000_000)
                         .initialMaxStreamDataBidirectionalRemote(1_000_000)
                         .initialMaxStreamsBidirectional(100)
                         .tokenHandler(InsecureQuicTokenHandler.INSTANCE)
                         .handler(new QuicConnectionInitializer(handler, maxContentLength))
                         .build();
        var bootstrap = new Bootstrap()
            .group(group)
            .channel(NioDatagramChannel.class)
            .handler(codec);
        return Promise.promise(promise -> bootstrap.bind(new InetSocketAddress(config.port()))
                                                   .addListener(future -> onBind(config,
                                                                                 promise,
                                                                                 future,
                                                                                 group,
                                                                                 ownsGroup)));
    }

    @SuppressWarnings("JBCT-PAT-01") // Netty future callback
    private static void onBind(HttpServerConfig config,
                               Promise<Http3Server> promise,
                               io.netty.util.concurrent.Future<? super Void> future,
                               EventLoopGroup group,
                               boolean ownsGroup) {
        if (future.isSuccess()) {
            var channel = ((io.netty.channel.ChannelFuture) future).channel();
            log.info("HTTP/3 QUIC server '{}' started on port {}", config.name(), config.port());
            promise.succeed(new Http3Server(config.port(),
                                            Option.option(group),
                                            Option.option(channel),
                                            ownsGroup));
        } else {
            if (ownsGroup) {
                group.shutdownGracefully();
            }
            promise.fail(new HttpServerError.BindFailed(config.port(), future.cause()));
        }
    }

    /// Initializer for each new QUIC connection.
    /// Adds [Http3ServerConnectionHandler] which manages HTTP/3 streams.
    private static class QuicConnectionInitializer extends ChannelInitializer<QuicChannel> {
        private final BiConsumer<RequestContext, ResponseWriter> handler;
        private final int maxContentLength;

        QuicConnectionInitializer(BiConsumer<RequestContext, ResponseWriter> handler,
                                  int maxContentLength) {
            this.handler = handler;
            this.maxContentLength = maxContentLength;
        }

        @Override
        protected void initChannel(QuicChannel ch) {
            ch.pipeline().addLast(new Http3ServerConnectionHandler(
                new Http3StreamInitializer(handler, maxContentLength)));
        }
    }

    /// Initializer for each new HTTP/3 request stream.
    /// Creates a fresh [Http3RequestHandler] per stream (no shared state).
    private static class Http3StreamInitializer extends ChannelInitializer<QuicStreamChannel> {
        private final BiConsumer<RequestContext, ResponseWriter> handler;
        private final int maxContentLength;

        Http3StreamInitializer(BiConsumer<RequestContext, ResponseWriter> handler,
                               int maxContentLength) {
            this.handler = handler;
            this.maxContentLength = maxContentLength;
        }

        @Override
        protected void initChannel(QuicStreamChannel ch) {
            ch.pipeline().addLast(new Http3RequestHandler(handler, maxContentLength));
        }
    }

    /// Per-stream HTTP/3 request handler.
    ///
    /// Accumulates headers and data frames, then dispatches
    /// to the shared request handler when input closes.
    /// Each stream gets its own instance — no @Sharable needed.
    private static class Http3RequestHandler extends Http3RequestStreamInboundHandler {
        private static final Logger log = LoggerFactory.getLogger(Http3RequestHandler.class);

        private final BiConsumer<RequestContext, ResponseWriter> handler;
        private final int maxContentLength;
        private Http3HeadersFrame headersFrame;
        private ByteBuf bodyData;

        Http3RequestHandler(BiConsumer<RequestContext, ResponseWriter> handler,
                            int maxContentLength) {
            this.handler = handler;
            this.maxContentLength = maxContentLength;
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
                return;
            }
            dispatch(ctx);
        }

        @SuppressWarnings({"JBCT-PAT-01", "JBCT-EX-01"}) // Netty handler boundary: must catch exceptions to send error response
        private void dispatch(ChannelHandlerContext ctx) {
            var requestId = IdGenerator.generate("req");
            var requestContext = buildRequestContext(requestId);
            var responseWriter = new Http3ResponseWriter(ctx, requestId);
            try {
                handler.accept(requestContext, responseWriter);
            } catch (Exception e) {
                log.error("Error handling HTTP/3 request {}", requestId, e);
                responseWriter.error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            }
        }

        private void appendData(ByteBuf content) {
            if (bodyData == null) {
                bodyData = Unpooled.buffer(Math.min(content.readableBytes(), maxContentLength));
            }
            var bytesToRead = Math.min(content.readableBytes(), maxContentLength - bodyData.readableBytes());
            if (bytesToRead > 0) {
                bodyData.writeBytes(content, bytesToRead);
            }
        }

        private RequestContext buildRequestContext(String requestId) {
            var h3Headers = headersFrame.headers();
            var methodStr = Option.option(h3Headers.method()).map(CharSequence::toString).or("GET");
            var pathStr = Option.option(h3Headers.path()).map(CharSequence::toString).or("/");
            var method = HttpMethod.httpMethod(methodStr).or(HttpMethod.GET);
            var pathAndQuery = pathStr.split("\\?", 2);
            var path = pathAndQuery[0];
            var queryParams = parseQueryParams(pathAndQuery.length > 1 ? pathAndQuery[1] : "");
            var headerMap = extractHeaders(h3Headers);
            var body = extractBody();
            return new Http3RequestContext(requestId, method, path, Headers.headers(headerMap), queryParams, body);
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

        private static Map<String, List<String>> extractHeaders(Http3Headers h3Headers) {
            var headerMap = new HashMap<String, List<String>>();
            for (var entry : h3Headers) {
                headerMap.computeIfAbsent(entry.getKey().toString().toLowerCase(),
                                          _ -> new ArrayList<>())
                         .add(entry.getValue().toString());
            }
            return headerMap;
        }

        private static QueryParams parseQueryParams(String queryString) {
            if (queryString.isEmpty()) {
                return QueryParams.empty();
            }
            var params = new HashMap<String, List<String>>();
            for (var param : queryString.split("&")) {
                var kv = param.split("=", 2);
                if (kv.length == 2) {
                    params.computeIfAbsent(kv[0], _ -> new ArrayList<>()).add(kv[1]);
                } else if (kv.length == 1) {
                    params.computeIfAbsent(kv[0], _ -> new ArrayList<>()).add("");
                }
            }
            return QueryParams.queryParams(params);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in HTTP/3 handler", cause);
            ctx.close();
        }
    }

    private record Http3RequestContext(String requestId,
                                       HttpMethod method,
                                       String path,
                                       Headers headers,
                                       QueryParams queryParams,
                                       byte[] body) implements RequestContext {}

    /// HTTP/3 response writer that sends response as HTTP/3 frames.
    private static class Http3ResponseWriter implements ResponseWriter {
        private final ChannelHandlerContext ctx;
        private final String requestId;
        // Thread-safe: each Http3ResponseWriter is created per-stream and accessed only from the Netty event loop thread
        private final Map<String, String> pendingHeaders = new HashMap<>();
        private final AtomicBoolean written = new AtomicBoolean(false);

        Http3ResponseWriter(ChannelHandlerContext ctx, String requestId) {
            this.ctx = ctx;
            this.requestId = requestId;
        }

        @Override
        public ResponseWriter header(String name, String value) {
            pendingHeaders.put(name, value);
            return this;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            if (!written.compareAndSet(false, true)) {
                return;
            }
            var responseHeaders = new io.netty.handler.codec.http3.DefaultHttp3Headers();
            responseHeaders.status(String.valueOf(status.code()));
            responseHeaders.set("content-type", contentType.headerText());
            responseHeaders.setInt("content-length", body.length);
            responseHeaders.set("x-request-id", requestId);
            pendingHeaders.forEach(responseHeaders::set);
            ctx.write(new DefaultHttp3HeadersFrame(responseHeaders));
            if (body.length > 0) {
                ctx.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body)))
                   .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                ctx.writeAndFlush(Unpooled.EMPTY_BUFFER)
                   .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
        }
    }
}
