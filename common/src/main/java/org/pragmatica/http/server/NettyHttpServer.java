package org.pragmatica.http.server;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.websocket.WebSocketEndpoint;
import org.pragmatica.http.websocket.WebSocketHandler;
import org.pragmatica.http.websocket.WebSocketMessage;
import org.pragmatica.http.websocket.WebSocketSession;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.net.SocketOptions;
import org.pragmatica.net.TlsContextFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BiConsumer;

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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * Netty-based HTTP server implementation.
 */
final class NettyHttpServer implements HttpServer {
    private static final Logger LOG = LoggerFactory.getLogger(NettyHttpServer.class);

    private final int port;
    private final EventLoopGroup bossGroup;
    private final EventLoopGroup workerGroup;
    private final Channel serverChannel;

    private NettyHttpServer(int port, EventLoopGroup bossGroup, EventLoopGroup workerGroup, Channel serverChannel) {
        this.port = port;
        this.bossGroup = bossGroup;
        this.workerGroup = workerGroup;
        this.serverChannel = serverChannel;
    }

    @Override
    public int port() {
        return port;
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.promise(promise -> {
                                   LOG.info("Stopping HTTP server on port {}", port);
                                   if (serverChannel != null) {
                                   serverChannel.close()
                                                .addListener(_ -> {
                                   cleanup();
                                   promise.succeed(unit());
                               });
                               }else {
                                   cleanup();
                                   promise.succeed(unit());
                               }
                               });
    }

    private void cleanup() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
    }

    static Promise<HttpServer> create(HttpServerConfig config, BiConsumer<RequestContext, ResponseWriter> handler) {
        // Handle TLS
        var sslContextResult = config.tls()
                                     .map(TlsContextFactory::create)
                                     .fold(() -> null,
                                           r -> r);
        if (sslContextResult != null && sslContextResult.isFailure()) {
            return sslContextResult.<HttpServer> map(_ -> null)
                   .async();
        }
        var sslContext = sslContextResult != null
                         ? sslContextResult.fold(_ -> null, ctx -> ctx)
                         : null;
        var bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        var workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        var socketOptions = config.socketOptions();
        var bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                        .channel(NioServerSocketChannel.class)
                        .childHandler(new HttpServerInitializer(config, handler, sslContext))
                        .option(ChannelOption.SO_BACKLOG,
                                socketOptions.soBacklog())
                        .childOption(ChannelOption.SO_KEEPALIVE,
                                     socketOptions.soKeepalive());
        return Promise.promise(promise -> {
                                   bootstrap.bind(config.port())
                                            .addListener((ChannelFutureListener) future -> {
                                                             if (future.isSuccess()) {
                                                             var protocol = sslContext != null
                                                                            ? "HTTPS"
                                                                            : "HTTP";
                                                             LOG.info("{} server '{}' started on port {}",
                                                                      protocol,
                                                                      config.name(),
                                                                      config.port());
                                                             promise.succeed(new NettyHttpServer(config.port(),
                                                                                                 bossGroup,
                                                                                                 workerGroup,
                                                                                                 future.channel()));
                                                         }else {
                                                             bossGroup.shutdownGracefully();
                                                             workerGroup.shutdownGracefully();
                                                             promise.fail(new HttpServerError.BindFailed(config.port(),
                                                                                                         future.cause()));
                                                         }
                                                         });
                               });
    }

    private static class HttpServerInitializer extends ChannelInitializer<SocketChannel> {
        private final HttpServerConfig config;
        private final BiConsumer<RequestContext, ResponseWriter> handler;
        private final SslContext sslContext;

        HttpServerInitializer(HttpServerConfig config,
                              BiConsumer<RequestContext, ResponseWriter> handler,
                              SslContext sslContext) {
            this.config = config;
            this.handler = handler;
            this.sslContext = sslContext;
        }

        @Override
        protected void initChannel(SocketChannel ch) {
            var pipeline = ch.pipeline();
            if (sslContext != null) {
                pipeline.addLast(sslContext.newHandler(ch.alloc()));
            }
            pipeline.addLast(new HttpServerCodec());
            pipeline.addLast(new HttpObjectAggregator(config.maxContentLength()));
            if (config.chunkedWriteEnabled()) {
                pipeline.addLast(new ChunkedWriteHandler());
            }
            // Add WebSocket handlers for each endpoint
            for (var endpoint : config.webSocketEndpoints()) {
                pipeline.addLast(new WebSocketServerProtocolHandler(endpoint.path(), null, true));
            }
            pipeline.addLast(new HttpRequestHandler(handler, config.webSocketEndpoints()));
        }
    }

    @ChannelHandler.Sharable
    private static class HttpRequestHandler extends SimpleChannelInboundHandler<Object> {
        private static final Logger LOG = LoggerFactory.getLogger(HttpRequestHandler.class);

        private final BiConsumer<RequestContext, ResponseWriter> handler;
        private final Map<String, WebSocketEndpoint> wsEndpoints;
        private WebSocketHandler currentWsHandler;
        private NettyWebSocketSession currentWsSession;

        HttpRequestHandler(BiConsumer<RequestContext, ResponseWriter> handler, List<WebSocketEndpoint> endpoints) {
            this.handler = handler;
            this.wsEndpoints = new HashMap<>();
            for (var endpoint : endpoints) {
                wsEndpoints.put(endpoint.path(), endpoint);
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof FullHttpRequest request) {
                handleHttpRequest(ctx, request);
            }else if (msg instanceof WebSocketFrame frame) {
                handleWebSocketFrame(ctx, frame);
            }
        }

        private void handleHttpRequest(ChannelHandlerContext ctx, FullHttpRequest request) {
            // Check if this is a WebSocket upgrade
            var path = new QueryStringDecoder(request.uri()).path();
            var wsEndpoint = wsEndpoints.get(path);
            if (wsEndpoint != null && isWebSocketUpgrade(request)) {
                // WebSocket upgrade will be handled by WebSocketServerProtocolHandler
                // Just set up the handler for later
                currentWsHandler = wsEndpoint.handler()
                                             .get();
                currentWsSession = new NettyWebSocketSession(ctx.channel());
                ctx.fireChannelRead(request.retain());
                return;
            }
            // Regular HTTP request
            var requestContext = createRequestContext(request);
            var responseWriter = new NettyResponseWriter(ctx);
            try{
                handler.accept(requestContext, responseWriter);
            } catch (Exception e) {
                LOG.error("Error handling request", e);
                responseWriter.error(HttpStatus.INTERNAL_SERVER_ERROR, "Internal Server Error");
            }
        }

        private boolean isWebSocketUpgrade(FullHttpRequest request) {
            return request.headers()
                          .contains(HttpHeaderNames.UPGRADE, HttpHeaderValues.WEBSOCKET, true);
        }

        private void handleWebSocketFrame(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (currentWsHandler == null) {
                return;
            }
            if (currentWsSession == null) {
                currentWsSession = new NettyWebSocketSession(ctx.channel());
            }
            if (frame instanceof TextWebSocketFrame textFrame) {
                currentWsHandler.handle(currentWsSession,
                                        new WebSocketMessage.Text(textFrame.text()));
            }else if (frame instanceof BinaryWebSocketFrame binaryFrame) {
                var bytes = new byte[binaryFrame.content()
                                                .readableBytes()];
                binaryFrame.content()
                           .readBytes(bytes);
                currentWsHandler.handle(currentWsSession, new WebSocketMessage.Binary(bytes));
            }else if (frame instanceof CloseWebSocketFrame) {
                currentWsHandler.handle(currentWsSession, new WebSocketMessage.Close());
                currentWsHandler = null;
                currentWsSession = null;
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
                if (currentWsHandler != null && currentWsSession != null) {
                    currentWsHandler.handle(currentWsSession, new WebSocketMessage.Open());
                }
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            LOG.error("Error in HTTP handler", cause);
            ctx.close();
        }

        private RequestContext createRequestContext(FullHttpRequest request) {
            var decoder = new QueryStringDecoder(request.uri());
            var path = decoder.path();
            var method = HttpMethod.from(request.method()
                                                .name());
            // Parse headers
            var headerMap = new HashMap<String, List<String>>();
            for (var entry : request.headers()) {
                headerMap.computeIfAbsent(entry.getKey()
                                               .toLowerCase(),
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
            return new NettyRequestContext(method, path, headers, queryParams, body);
        }
    }

    private record NettyRequestContext(
    HttpMethod method,
    String path,
    Headers headers,
    QueryParams queryParams,
    byte[] body) implements RequestContext {}

    private static class NettyResponseWriter implements ResponseWriter {
        private final ChannelHandlerContext ctx;
        private final io.netty.handler.codec.http.HttpHeaders responseHeaders;
        private boolean written = false;

        NettyResponseWriter(ChannelHandlerContext ctx) {
            this.ctx = ctx;
            this.responseHeaders = new DefaultHttpHeaders();
        }

        @Override
        public ResponseWriter header(String name, String value) {
            responseHeaders.set(name, value);
            return this;
        }

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            if (written) {
                return;
            }
            written = true;
            var nettyStatus = HttpResponseStatus.valueOf(status.code());
            var content = Unpooled.wrappedBuffer(body);
            var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, nettyStatus, content);
            response.headers()
                    .set(HttpHeaderNames.CONTENT_TYPE,
                         contentType.headerText());
            response.headers()
                    .set(HttpHeaderNames.CONTENT_LENGTH, body.length);
            response.headers()
                    .add(responseHeaders);
            ctx.writeAndFlush(response)
               .addListener(ChannelFutureListener.CLOSE);
        }
    }

    private static class NettyWebSocketSession implements WebSocketSession {
        private final Channel channel;
        private final String id;

        NettyWebSocketSession(Channel channel) {
            this.channel = channel;
            this.id = UUID.randomUUID()
                          .toString();
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
                channel.writeAndFlush(new CloseWebSocketFrame())
                       .addListener(ChannelFutureListener.CLOSE);
            }
        }

        @Override
        public boolean isOpen() {
            return channel.isActive();
        }
    }
}
