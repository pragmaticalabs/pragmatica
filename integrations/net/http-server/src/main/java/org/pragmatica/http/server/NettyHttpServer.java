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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.pragmatica.http.*;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpRequest;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.tcp.TlsContextFactory;
import org.pragmatica.utility.IdGenerator;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.stream.ChunkedWriteHandler;
import io.netty.util.AttributeKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Promise.resolved;


/// Netty-based HTTP server implementation.
final class NettyHttpServer implements HttpServer {
    private static final Logger log = LoggerFactory.getLogger(NettyHttpServer.class);

    private final int port;
    private final Option<EventLoopGroup> bossGroup;
    private final Option<EventLoopGroup> workerGroup;
    private final Option<Channel> serverChannel;
    private final boolean ownsGroups;

    private NettyHttpServer(int port,
                            Option<EventLoopGroup> bossGroup,
                            Option<EventLoopGroup> workerGroup,
                            Option<Channel> serverChannel,
                            boolean ownsGroups) {
        this.port = port;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.serverChannel = serverChannel;
        this.ownsGroups = ownsGroups;
    }

    @Override
    public int port() {
        return port;
    }

    /// #1612: every step is a DEPENDENT continuation of the one before it. The server channel close is
    /// bounded; the owned groups are then shut down with no quiet period (see [ServerShutdown]) and awaited,
    /// bounded. The returned promise resolves only when both have settled, with the first failure among them
    /// (a timed-out close or termination), typed. Shared groups belong to their owner and are left running.
    ///
    /// Before #1612 this already waited for termination (the listener sat on the future `shutdownGracefully()`
    /// returns, which is the termination future), but nothing was bounded, so a wedged loop hung `stop()`
    /// forever; the default 2 s quiet period delayed every stop; and a failed termination still reported
    /// success.
    @Override
    public Promise<Unit> stop() {
        log.info("Stopping HTTP server on port {}", port);

        return serverChannel.map(ServerShutdown::closed)
                            .or(Promise.unitPromise())
                            .fold(this::shutdownGroupsThen);
    }

    /// Package-private for the #1612 stop tests: the owned groups, to wedge a loop or confirm termination.
    Option<EventLoopGroup> bossGroup() {
        return bossGroup;
    }

    Option<EventLoopGroup> workerGroup() {
        return workerGroup;
    }

    Option<Channel> serverChannel() {
        return serverChannel;
    }

    private Promise<Unit> shutdownGroupsThen(Result<Unit> closeOutcome) {
        return shutdownOwnedGroups().fold(groupsOutcome -> resolved(closeOutcome.flatMap(_ -> groupsOutcome)))
                                  .onSuccessRun(() -> log.info("HTTP server on port {} stopped", port))
                                  .onFailure(cause -> log.warn("HTTP server on port {} did not stop cleanly: {}",
                                                               port,
                                                               cause.message()));
    }

    private Promise<Unit> shutdownOwnedGroups() {
        return ownsGroups
               ? shutdownGroups(bossGroup, workerGroup)
               : Promise.unitPromise();
    }

    /// Both shutdowns are requested before either is awaited; the outcome is the first failure.
    private static Promise<Unit> shutdownGroups(Option<EventLoopGroup> bossGroup, Option<EventLoopGroup> workerGroup) {
        var bossTerminated = terminatedIfPresent(bossGroup);
        var workerTerminated = terminatedIfPresent(workerGroup);

        return bossTerminated.fold(bossOutcome -> ServerShutdown.firstFailureOf(bossOutcome, workerTerminated));
    }

    private static Promise<Unit> terminatedIfPresent(Option<EventLoopGroup> group) {
        return group.map(ServerShutdown::terminated)
                    .or(Promise.unitPromise());
    }

    static Promise<HttpServer> create(HttpServerConfig config, BiConsumer<HttpRequest, ResponseWriter> handler) {
        return createOwning(config,
                            handler,
                            new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory()),
                            new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory()));
    }

    static Promise<HttpServer> createShared(HttpServerConfig config,
                                            BiConsumer<HttpRequest, ResponseWriter> handler,
                                            EventLoopGroup bossGroup,
                                            EventLoopGroup workerGroup) {
        return bind(config, handler, bossGroup, workerGroup, false);
    }

    /// A server that OWNS `bossGroup` and `workerGroup`: its stop, or a failed bind, terminates them.
    /// Package-private so the #1612 tests can hold the groups and check termination at the moment a failed
    /// create reports.
    static Promise<HttpServer> createOwning(HttpServerConfig config,
                                            BiConsumer<HttpRequest, ResponseWriter> handler,
                                            EventLoopGroup bossGroup,
                                            EventLoopGroup workerGroup) {
        return bind(config, handler, bossGroup, workerGroup, true);
    }

    private static Promise<HttpServer> bind(HttpServerConfig config,
                                            BiConsumer<HttpRequest, ResponseWriter> handler,
                                            EventLoopGroup bossGroup,
                                            EventLoopGroup workerGroup,
                                            boolean ownsGroups) {
        var sslContext = config.tls().await().flatMap(TlsContextFactory::create).option();
        var socketOptions = config.socketOptions();
        var bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                                             .channel(NioServerSocketChannel.class)
                                             .childHandler(new HttpServerInitializer(config, handler, sslContext))
                                             .option(ChannelOption.SO_BACKLOG,
                                                     socketOptions.soBacklog())
                                             .childOption(ChannelOption.SO_KEEPALIVE,
                                                          socketOptions.soKeepalive());

        return Promise.promise(promise -> bootstrap.bind(config.port())
                                                   .addListener((ChannelFuture future) -> onBind(config,
                                                                                                 promise,
                                                                                                 future,
                                                                                                 sslContext,
                                                                                                 bossGroup,
                                                                                                 workerGroup,
                                                                                                 ownsGroups)));
    }

    private static void onBind(HttpServerConfig config,
                               Promise<HttpServer> promise,
                               ChannelFuture future,
                               Option<SslContext> sslContext,
                               EventLoopGroup bossGroup,
                               EventLoopGroup workerGroup,
                               boolean ownsGroups) {
        if (future.isSuccess()) {
            var protocol = sslContext.map(_ -> "HTTPS").or("HTTP");

            log.info("{} server '{}' started on port {}", protocol, config.name(), config.port());
            promise.succeed(new NettyHttpServer(config.port(),
                                                Option.option(bossGroup),
                                                Option.option(workerGroup),
                                                Option.option(future.channel()),
                                                ownsGroups));
        } else {
            // #1612: the groups this create made are terminated (bounded) BEFORE the failure is reported, so a
            // caller that retries sees no event-loop threads left over from this attempt. The bind failure is
            // what the create reports; a termination failure is only logged.
            var bindFailed = new HttpServerError.BindFailed(config.port(), future.cause());

            releaseGroupsOnBindFailure(ownsGroups, bossGroup, workerGroup).onResultRun(() -> promise.fail(bindFailed));
        }
    }

    private static Promise<Unit> releaseGroupsOnBindFailure(boolean ownsGroups,
                                                            EventLoopGroup bossGroup,
                                                            EventLoopGroup workerGroup) {
        return ownsGroups
               ? shutdownGroups(some(bossGroup), some(workerGroup)).onFailure(cause -> log.warn("HTTP server event loops did not terminate after a failed bind: {}",
                                                                                                cause.message()))
               : Promise.unitPromise();
    }

    private static class HttpServerInitializer extends ChannelInitializer<SocketChannel> {
        private final HttpServerConfig config;
        private final BiConsumer<HttpRequest, ResponseWriter> handler;
        private final Option<SslContext> sslContext;
        private final Map<String, WebSocketEndpoint> wsEndpoints;

        HttpServerInitializer(HttpServerConfig config,
                              BiConsumer<HttpRequest, ResponseWriter> handler,
                              Option<SslContext> sslContext) {
            this.config = config;
            this.handler = handler;
            this.sslContext = sslContext;
            this.wsEndpoints = new HashMap<>();
            for (var endpoint : config.webSocketEndpoints()) {
                wsEndpoints.put(endpoint.path(), endpoint);
            }
        }

        @Override
        protected void initChannel(SocketChannel ch) {
            var pipeline = ch.pipeline();

            sslContext.onPresent(ctx -> pipeline.addLast(ctx.newHandler(ch.alloc())));
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(config.maxContentLength()));
            if (config.chunkedWriteEnabled()) {
                pipeline.addLast(new ChunkedWriteHandler());
            }
            // Add WebSocket handlers for each endpoint
            for (var endpoint : config.webSocketEndpoints()) {
                pipeline.addLast(new WebSocketServerProtocolHandler(endpoint.path(), null, true));
            }
            // Create a new handler instance per channel (not @Sharable)
            pipeline.addLast(new HttpRequestHandler(handler, wsEndpoints));
        }
    }

    /// Per-channel HTTP request handler.
    /// NOT @Sharable - each channel gets its own instance to maintain WebSocket state safely.
    private static class HttpRequestHandler extends SimpleChannelInboundHandler<Object> {
        private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);
        private static final AttributeKey<Option<WebSocketState>> WS_STATE = AttributeKey.valueOf("wsState");

        private final BiConsumer<HttpRequest, ResponseWriter> handler;
        private final Map<String, WebSocketEndpoint> wsEndpoints;

        HttpRequestHandler(BiConsumer<HttpRequest, ResponseWriter> handler,
                           Map<String, WebSocketEndpoint> wsEndpoints) {
            this.handler = handler;
            this.wsEndpoints = wsEndpoints;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                handleHttpRequest(ctx, request);
            } else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
            // Check if this is a WebSocket upgrade
            var path = new QueryStringDecoder(request.uri()).path();
            var wsEndpoint = Option.option(wsEndpoints.get(path));

            if (wsEndpoint.isPresent() && isWebSocketUpgrade(request)) {
                // WebSocket upgrade will be handled by WebSocketServerProtocolHandler
                // Store handler and session in channel attribute for later use
                wsEndpoint.onPresent(endpoint -> {
                    var wsHandler = endpoint.handler()
                                            .get();
                    var wsSession = new NettyWebSocketSession(ctx.channel());

                    ctx.channel()
                       .attr(WS_STATE)
                       .set(Option.some(new WebSocketState(wsHandler, wsSession)));
                    ctx.fireChannelRead(request.retain());
                });

                return;
            }
            // Regular HTTP request - generate request ID
            var requestId = IdGenerator.generate("req");
            var requestContext = createRequestContext(requestId, request);
            var responseWriter = new NettyResponseWriter(ctx, requestId, HttpUtil.isKeepAlive(request));

            try {
                handler.accept(requestContext, responseWriter);
            } catch (Exception e) {
                log.error("Error handling request {}", requestId, e);
                responseWriter.error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            }
        }

        private boolean isWebSocketUpgrade(FullHttpRequest request) {
            return request.headers()
                          .contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            Option.option(ctx.channel().attr(WS_STATE).get())
                  .flatMap(opt -> opt)
                  .onPresent(state -> {
                                 if (frame instanceof TextWebSocketFrame textFrame) {
                                 state.handler.handle(state.session,
                                                      new WebSocketMessage.Text(textFrame.text()));
                             } else if (frame instanceof BinaryWebSocketFrame binaryFrame) {
                                 var bytes = new byte[binaryFrame.content()
                                                                 .readableBytes()];

                                 binaryFrame.content()
                                            .readBytes(bytes);
                                 state.handler.handle(state.session,
                                                      new WebSocketMessage.Binary(bytes));
                             } else if (frame instanceof CloseWebSocketFrame) {
                                 state.handler.handle(state.session,
                                                      new WebSocketMessage.Close());
                                 ctx.channel()
                                    .attr(WS_STATE)
                                    .set(Option.none());
                             }
                             });
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                Option.option(ctx.channel().attr(WS_STATE).get())
                      .flatMap(opt -> opt)
                      .onPresent(state -> state.handler.handle(state.session,
                                                               new WebSocketMessage.Open()));
            }

            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("Error in HTTP handler", cause);
            ctx.close();
        }

        private HttpRequest createRequestContext(String requestId, FullHttpRequest request) {
            var decoder = new QueryStringDecoder(request.uri());
            var path = decoder.path();
            // Netty validates the HTTP method before we receive it, so this will always succeed
            var method = HttpMethod.httpMethod(request.method().name()).fold(_ -> HttpMethod.GET, v -> v);
            // Parse headers
            var headerMap = new HashMap<String, List<String>>();

            for (var entry : request.headers()) {
                headerMap.computeIfAbsent(entry.getKey().toLowerCase(),
                                          _ -> new java.util.ArrayList<>())
                         .add(entry.getValue());
            }

            var headers = Headers.headers(headerMap);
            // Parse query params
            var queryParams = QueryParams.queryParams(decoder.parameters());
            // Read body
            var content = request.content();
            var body = new byte[content.readableBytes()];

            content.readBytes(body);

            return new NettyRequestContext(requestId, method, path, headers, queryParams, body);
        }
    }

    /// WebSocket state stored per channel.
    private record WebSocketState(WebSocketHandler handler, NettyWebSocketSession session) {}

    private record NettyRequestContext(String requestId,
                                       HttpMethod method,
                                       String path,
                                       Headers headers,
                                       QueryParams queryParams,
                                       byte[] body) implements HttpRequest {}

    private static class NettyResponseWriter implements ResponseWriter {
        private final ChannelHandlerContext ctx;
        private final String requestId;
        private final boolean keepAlive;
        private final io.netty.handler.codec.http.HttpHeaders responseHeaders;

        private final java.util.concurrent.atomic.AtomicBoolean written = new java.util.concurrent.atomic.AtomicBoolean(false);

        NettyResponseWriter(ChannelHandlerContext ctx, String requestId, boolean keepAlive) {
            this.ctx = ctx;
            this.requestId = requestId;
            this.keepAlive = keepAlive;
            this.responseHeaders = new DefaultHttpHeaders();
        }

        @Override
        public ResponseWriter header(String name, String value) {
            responseHeaders.set(name, value);

            return this;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            if (!written.compareAndSet(false, true)) {
                return;
            }

            var nettyStatus = HttpResponseStatus.valueOf(status.code());
            var content = Unpooled.wrappedBuffer(body);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, content);

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType.headerText());
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers().set(X_REQUEST_ID, requestId);
            if (keepAlive) {
                response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
            }

            response.headers().add(responseHeaders);
            var future = ctx.writeAndFlush(response);

            if (!keepAlive) {
                future.addListener(ChannelFutureListener.CLOSE);
            }
        }
    }

    private static class NettyWebSocketSession implements WebSocketSession {
        private final Channel channel;
        private final String id;

        NettyWebSocketSession(Channel channel) {
            this.channel = channel;
            this.id = IdGenerator.generate("ws");
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void send(String text) {
            if (channel.isActive()) {
                channel.writeAndFlush(new TextWebSocketFrame(text));
            }
        }

        @Override
        public void send(byte[] binary) {
            if (channel.isActive()) {
                channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(binary)));
            }
        }

        @Override
        public void close() {
            if (channel.isActive()) {
                channel.writeAndFlush(new CloseWebSocketFrame()).addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public boolean isOpen() {
            return channel.isActive();
        }
    }
}
