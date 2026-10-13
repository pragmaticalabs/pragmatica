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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;
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

import static org.pragmatica.lang.Option.option;
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
        return config.tls()
                     .map(tls -> TlsContextFactory.createServer(tls).map(Option::some))
                     .or(Result.success(Option.<SslContext> empty()))
                     .fold(cause -> tlsBuildFailed(config, cause, ownsGroups, bossGroup, workerGroup),
                           sslContext -> bindConfigured(config, handler, bossGroup, workerGroup, ownsGroups, sslContext));
    }

    /// A TLS configuration that fails to build refuses startup: serving plain HTTP on a port the operator
    /// configured for TLS would be fail-open.
    private static Promise<HttpServer> tlsBuildFailed(HttpServerConfig config,
                                                      Cause cause,
                                                      boolean ownsGroups,
                                                      EventLoopGroup bossGroup,
                                                      EventLoopGroup workerGroup) {
        var failure = new HttpServerError.TlsFailed(config.name(), config.port(), cause);

        log.error("HTTP server '{}' will not start: {}", config.name(), failure.message());

        return releaseGroupsOnBindFailure(ownsGroups, bossGroup, workerGroup).fold(_ -> failure.promise());
    }

    private static Promise<HttpServer> bindConfigured(HttpServerConfig config,
                                                      BiConsumer<HttpRequest, ResponseWriter> handler,
                                                      EventLoopGroup bossGroup,
                                                      EventLoopGroup workerGroup,
                                                      boolean ownsGroups,
                                                      Option<SslContext> sslContext) {
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
            var boundPort = ((InetSocketAddress) future.channel().localAddress()).getPort();

            log.info("{} server '{}' started on port {}", protocol, config.name(), boundPort);
            promise.succeed(new NettyHttpServer(boundPort,
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
            // #2184: one router per channel installs the protocol handler for the requested endpoint only
            pipeline.addLast(new WebSocketUpgradeRouter(wsEndpoints, config.maxContentLength()));
            // Create a new handler instance per channel (not @Sharable)
            pipeline.addLast(new HttpRequestHandler(handler, wsEndpoints));
        }
    }

    /// #2184: routes a WebSocket upgrade request by path. Netty installs a single handshake handler bound to the
    /// path of the first `WebSocketServerProtocolHandler` in a pipeline, so one static handler per endpoint
    /// never handshakes the second and later endpoints. This router, per channel, installs exactly one protocol
    /// handler for the endpoint the request names and passes the request into its handshake. An upgrade to a
    /// path with no endpoint is refused with 404 and the connection is closed. Any other message passes through
    /// untouched.
    ///
    /// Once handed over, the handshake either completes or fails with the connection closed: the endpoint is
    /// matched on the raw path, as Netty's handshake path check is, and a version Netty cannot handshake is
    /// answered with 426 here, before anything is installed, so a client may retry on the same connection.
    private static class WebSocketUpgradeRouter extends ChannelInboundHandlerAdapter {
        private static final Logger log = LoggerFactory.getLogger(WebSocketUpgradeRouter.class);

        private final Map<String, WebSocketEndpoint> wsEndpoints;
        private final int maxMessageLength;

        WebSocketUpgradeRouter(Map<String, WebSocketEndpoint> wsEndpoints, int maxMessageLength) {
            this.wsEndpoints = wsEndpoints;
            this.maxMessageLength = maxMessageLength;
        }

        @Contract
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request && isWebSocketUpgrade(request)) {
                route(ctx, request);
            } else {
                ctx.fireChannelRead(msg);
            }
        }

        private void route(ChannelHandlerContext ctx, FullHttpRequest request) {
            var path = rawPath(request.uri());

            option(wsEndpoints.get(path)).onPresent(endpoint -> accept(ctx, request, endpoint))
                  .onEmpty(() -> refuse(ctx, request, path));
        }

        private void accept(ChannelHandlerContext ctx, FullHttpRequest request, WebSocketEndpoint endpoint) {
            if (isSupportedVersion(request)) {
                handOver(ctx, request, endpoint);
            } else {
                refuseVersion(ctx, request);
            }
        }

        /// The handshake handler releases the request and fires `HandshakeComplete`, which
        /// [HttpRequestHandler] turns into the endpoint's session and `Open`. The frame aggregator sits after the
        /// protocol handler, so a fragmented message reaches the handler whole; one longer than the configured
        /// content length closes the connection.
        private void handOver(ChannelHandlerContext ctx, FullHttpRequest request, WebSocketEndpoint endpoint) {
            ctx.pipeline().addAfter(ctx.name(), "ws-frame-aggregator", new WebSocketFrameAggregator(maxMessageLength));
            ctx.pipeline()
               .addAfter(ctx.name(),
                         "ws-protocol",
                         new WebSocketServerProtocolHandler(protocolConfig(endpoint.path())));
            ctx.fireChannelRead(request);
        }

        private static boolean isSupportedVersion(FullHttpRequest request) {
            return option(new WebSocketServerHandshakerFactory(request.uri(), null, false).newHandshaker(request)).isPresent();
        }

        /// RFC 6455 4.4: 426 names the supported version and keeps the connection, so the client may retry.
        private static void refuseVersion(ChannelHandlerContext ctx, FullHttpRequest request) {
            request.release();
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        }

        /// `checkStartsWith` lets a query string (`/ws/events?token=...`) through the handshake handler's own
        /// path check; the endpoint itself was already selected by exact path.
        private static WebSocketServerProtocolConfig protocolConfig(String path) {
            return WebSocketServerProtocolConfig.newBuilder()
                                                .websocketPath(path)
                                                .checkStartsWith(true)
                                                .build();
        }

        private static void refuse(ChannelHandlerContext ctx, FullHttpRequest request, String path) {
            log.debug("Refusing WebSocket upgrade to {}: no endpoint registered", path);
            request.release();
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);

            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }

        private static boolean isWebSocketUpgrade(FullHttpRequest request) {
            return request.headers()
                          .contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
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
            // WebSocket upgrades never reach here: WebSocketUpgradeRouter consumes them
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
                             }
                             });
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete handshake) {
                endpointOf(handshake).onPresent(endpoint -> open(ctx, endpoint));
            }

            super.userEventTriggered(ctx, evt);
        }

        /// #2184: the endpoint is resolved from the handshake itself, so the session exists only once the
        /// handshake has completed, and `Open` is always the first message the handler sees.
        private Option<WebSocketEndpoint> endpointOf(WebSocketServerProtocolHandler.HandshakeComplete handshake) {
            return option(wsEndpoints.get(rawPath(handshake.requestUri())));
        }

        private static void open(ChannelHandlerContext ctx, WebSocketEndpoint endpoint) {
            var state = new WebSocketState(endpoint.handler().get(),
                                           new NettyWebSocketSession(ctx.channel()));

            ctx.channel().attr(WS_STATE).set(Option.some(state));
            state.handler.handle(state.session, new WebSocketMessage.Open());
        }

        /// #2184: `Close` is delivered exactly once, when an opened channel goes inactive (Netty fires that event
        /// once per channel). That covers a client close frame (the protocol handler answers it and closes the
        /// channel), a server-side [WebSocketSession#close()], and a connection dropped without any close frame.
        @Contract
        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            option(ctx.channel().attr(WS_STATE).get()).flatMap(opt -> opt)
                  .onPresent(state -> state.handler.handle(state.session,
                                                           new WebSocketMessage.Close()));
            ctx.fireChannelInactive();
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

    /// #2184: the request path exactly as sent, without the query. Netty's handshake compares the raw URI, so
    /// endpoints are matched in the same form: `/ws/%61` is not `/ws/a`.
    private static String rawPath(String uri) {
        var query = uri.indexOf('?');

        return query < 0
               ? uri
               : uri.substring(0, query);
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
