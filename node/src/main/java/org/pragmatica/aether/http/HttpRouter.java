package org.pragmatica.aether.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.util.CharsetUtil;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.routing.RoutingSection;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.serialization.Deserializer;
import org.pragmatica.net.serialization.Serializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Unit.unit;

/**
 * HTTP router that handles incoming requests and dispatches them to slices.
 */
public interface HttpRouter {

    /**
     * Start the HTTP router.
     */
    Promise<Unit> start();

    /**
     * Stop the HTTP router.
     */
    Promise<Unit> stop();

    /**
     * Create an HTTP router.
     */
    static HttpRouter httpRouter(
            RouterConfig config,
            List<RoutingSection> routingSections,
            SliceInvoker invoker,
            SliceDispatcher.ArtifactResolver artifactResolver,
            Serializer serializer,
            Deserializer deserializer
    ) {
        return new HttpRouterImpl(config, routingSections, invoker, artifactResolver, serializer, deserializer);
    }
}

class HttpRouterImpl implements HttpRouter {

    private static final Logger log = LoggerFactory.getLogger(HttpRouterImpl.class);

    private final RouterConfig config;
    private final RouteMatcher routeMatcher;
    private final BindingResolver bindingResolver;
    private final SliceDispatcher dispatcher;
    private final ResponseWriter responseWriter;
    private final NioEventLoopGroup bossGroup;
    private final NioEventLoopGroup workerGroup;
    private Channel serverChannel;

    HttpRouterImpl(
            RouterConfig config,
            List<RoutingSection> routingSections,
            SliceInvoker invoker,
            SliceDispatcher.ArtifactResolver artifactResolver,
            Serializer serializer,
            Deserializer deserializer
    ) {
        this.config = config;
        this.routeMatcher = RouteMatcher.routeMatcher(routingSections);
        this.bindingResolver = BindingResolver.bindingResolver(deserializer);
        this.dispatcher = SliceDispatcher.sliceDispatcher(invoker, artifactResolver);
        this.responseWriter = ResponseWriter.responseWriter(serializer);
        this.bossGroup = new NioEventLoopGroup(1);
        this.workerGroup = new NioEventLoopGroup();
    }

    @Override
    public Promise<Unit> start() {
        return Promise.promise(promise -> {
            var bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ChannelPipeline p = ch.pipeline();
                            p.addLast(new HttpServerCodec());
                            p.addLast(new HttpObjectAggregator(config.maxContentLength()));
                            p.addLast(new HttpRequestHandler(
                                    routeMatcher, bindingResolver, dispatcher, responseWriter
                            ));
                        }
                    });

            bootstrap.bind(config.port()).addListener(future -> {
                if (future.isSuccess()) {
                    serverChannel = ((io.netty.channel.ChannelFuture) future).channel();
                    log.info("HTTP router started on port {}", config.port());
                    promise.succeed(unit());
                } else {
                    log.error("Failed to start HTTP router on port {}", config.port(), future.cause());
                    promise.fail(Causes.fromThrowable(future.cause()));
                }
            });
        });
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.promise(promise -> {
            if (serverChannel != null) {
                serverChannel.close().addListener(f -> {
                    bossGroup.shutdownGracefully();
                    workerGroup.shutdownGracefully();
                    log.info("HTTP router stopped");
                    promise.succeed(unit());
                });
            } else {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
                promise.succeed(unit());
            }
        });
    }
}

class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final RouteMatcher routeMatcher;
    private final BindingResolver bindingResolver;
    private final SliceDispatcher dispatcher;
    private final ResponseWriter responseWriter;

    HttpRequestHandler(
            RouteMatcher routeMatcher,
            BindingResolver bindingResolver,
            SliceDispatcher dispatcher,
            ResponseWriter responseWriter
    ) {
        this.routeMatcher = routeMatcher;
        this.bindingResolver = bindingResolver;
        this.dispatcher = dispatcher;
        this.responseWriter = responseWriter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult().isSuccess()) {
            responseWriter.writeError(ctx, HttpResponseStatus.BAD_REQUEST,
                    Causes.cause("Invalid HTTP request"));
            return;
        }

        var method = HttpMethod.fromString(request.method().name());
        var decoder = new QueryStringDecoder(request.uri());
        var path = decoder.path();

        log.debug("Received {} {}", method, path);

        // Match route
        routeMatcher.match(method, path).fold(
                () -> {
                    responseWriter.writeError(ctx, HttpResponseStatus.NOT_FOUND,
                            new HttpRouterError.RouteNotFound(path));
                    return null;
                },
                match -> {
                    handleMatchedRoute(ctx, method, path, decoder, request, match);
                    return null;
                }
        );
    }

    private void handleMatchedRoute(ChannelHandlerContext ctx, HttpMethod method, String path,
                                     QueryStringDecoder decoder, FullHttpRequest request, MatchResult match) {
        var route = match.route();

        // Build request context
        var queryParams = decoder.parameters();
        var headers = new HashMap<String, String>();
        request.headers().forEach(entry -> headers.put(entry.getKey().toLowerCase(), entry.getValue()));
        var body = new byte[request.content().readableBytes()];
        request.content().readBytes(body);

        var context = new RequestContext(method, path, match.pathVariables(), queryParams, headers, body);

        // Resolve bindings
        var bindingsResult = bindingResolver.resolve(route.bindings(), context);
        if (bindingsResult.isFailure()) {
            bindingsResult.onFailure(cause ->
                    responseWriter.writeError(ctx, HttpResponseStatus.BAD_REQUEST, cause)
            );
            return;
        }

        // Dispatch to slice
        bindingsResult.onSuccess(resolvedParams -> {
            dispatcher.dispatch(route, resolvedParams)
                    .onSuccess(result -> responseWriter.writeSuccess(ctx, result))
                    .onFailure(cause -> {
                        var status = determineErrorStatus(cause);
                        responseWriter.writeError(ctx, status, cause);
                    });
        });
    }

    private HttpResponseStatus determineErrorStatus(org.pragmatica.lang.Cause cause) {
        if (cause instanceof HttpRouterError.SliceNotFound) {
            return HttpResponseStatus.NOT_FOUND;
        } else if (cause instanceof HttpRouterError.BindingFailed) {
            return HttpResponseStatus.BAD_REQUEST;
        } else if (cause instanceof HttpRouterError.DeserializationFailed) {
            return HttpResponseStatus.BAD_REQUEST;
        } else {
            return HttpResponseStatus.INTERNAL_SERVER_ERROR;
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("Error handling HTTP request", cause);
        responseWriter.writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR,
                Causes.fromThrowable(cause));
    }
}
