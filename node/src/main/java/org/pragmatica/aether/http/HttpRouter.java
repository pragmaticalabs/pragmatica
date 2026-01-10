package org.pragmatica.aether.http;

import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.net.tcp.TlsContextFactory;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.HashMap;
import java.util.concurrent.atomic.AtomicReference;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.ssl.SslContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Unit.unit;

/**
 * HTTP router that handles incoming requests and dispatches them to slices.
 * Routes are dynamically registered via RouteRegistry when slices activate.
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
     * Create an HTTP router with dynamic route registry.
     */
    static HttpRouter httpRouter(RouterConfig config,
                                 RouteRegistry routeRegistry,
                                 SliceInvoker invoker,
                                 SliceDispatcher.ArtifactResolver artifactResolver,
                                 Serializer serializer,
                                 Deserializer deserializer) {
        return new HttpRouterImpl(config, routeRegistry, invoker, artifactResolver, serializer, deserializer);
    }
}

class HttpRouterImpl implements HttpRouter {
    private static final Logger log = LoggerFactory.getLogger(HttpRouterImpl.class);

    private final RouterConfig config;
    private final RouteRegistry routeRegistry;
    private final BindingResolver bindingResolver;
    private final SliceDispatcher dispatcher;
    private final ResponseWriter responseWriter;
    private final MultiThreadIoEventLoopGroup bossGroup;
    private final MultiThreadIoEventLoopGroup workerGroup;
    private final Option<SslContext> sslContext;
    private final AtomicReference<Channel> serverChannel = new AtomicReference<>();

    HttpRouterImpl(RouterConfig config,
                   RouteRegistry routeRegistry,
                   SliceInvoker invoker,
                   SliceDispatcher.ArtifactResolver artifactResolver,
                   Serializer serializer,
                   Deserializer deserializer) {
        this.config = config;
        this.routeRegistry = routeRegistry;
        this.bindingResolver = BindingResolver.bindingResolver(deserializer);
        this.dispatcher = SliceDispatcher.sliceDispatcher(invoker, artifactResolver);
        this.responseWriter = ResponseWriter.responseWriter(serializer);
        this.bossGroup = new MultiThreadIoEventLoopGroup(1, NioIoHandler.newFactory());
        this.workerGroup = new MultiThreadIoEventLoopGroup(NioIoHandler.newFactory());
        this.sslContext = config.tls()
                                .map(TlsContextFactory::create)
                                .flatMap(Result::option);
    }

    @Override
    public Promise<Unit> start() {
        return Promise.promise(promise -> {
                                   var bootstrap = new ServerBootstrap().group(bossGroup, workerGroup)
                                                                        .channel(NioServerSocketChannel.class)
                                                                        .childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) {
                                                                                          ChannelPipeline p = ch.pipeline();
                                                                                          sslContext.onPresent(ctx -> p.addLast(ctx.newHandler(ch.alloc())));
                                                                                          p.addLast(new HttpServerCodec());
                                                                                          p.addLast(new HttpObjectAggregator(config.maxContentLength()));
                                                                                          p.addLast(new HttpRequestHandler(routeRegistry,
                                                                                                                           bindingResolver,
                                                                                                                           dispatcher,
                                                                                                                           responseWriter));
                                                                                      }
        });
                                   bootstrap.bind(config.port())
                                            .addListener(future -> {
                                                             if (future.isSuccess()) {
                                                                 serverChannel.set(((io.netty.channel.ChannelFuture) future).channel());
                                                                 var protocol = sslContext.isPresent()
                                                                                ? "HTTPS"
                                                                                : "HTTP";
                                                                 log.info("{} router started on port {}",
                                                                          protocol,
                                                                          config.port());
                                                                 promise.succeed(unit());
                                                             } else {
                                                                 log.error("Failed to start HTTP router on port {}",
                                                                           config.port(),
                                                                           future.cause());
                                                                 promise.fail(Causes.fromThrowable(future.cause()));
                                                             }
                                                         });
                               });
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.promise(promise -> {
                                   var channel = serverChannel.get();
                                   if (channel != null) {
                                       channel.close()
                                              .addListener(_ -> shutdownEventLoops(promise));
                                   } else {
                                       shutdownEventLoops(promise);
                                   }
                               });
    }

    private void shutdownEventLoops(Promise<Unit> promise) {
        var bossFuture = bossGroup.shutdownGracefully();
        var workerFuture = workerGroup.shutdownGracefully();
        bossFuture.addListener(_ -> workerFuture.addListener(_ -> {
                                                                 log.info("HTTP router stopped");
                                                                 promise.succeed(unit());
                                                             }));
    }
}

class HttpRequestHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
    private static final Logger log = LoggerFactory.getLogger(HttpRequestHandler.class);

    private final RouteRegistry routeRegistry;
    private final BindingResolver bindingResolver;
    private final SliceDispatcher dispatcher;
    private final ResponseWriter responseWriter;

    HttpRequestHandler(RouteRegistry routeRegistry,
                       BindingResolver bindingResolver,
                       SliceDispatcher dispatcher,
                       ResponseWriter responseWriter) {
        this.routeRegistry = routeRegistry;
        this.bindingResolver = bindingResolver;
        this.dispatcher = dispatcher;
        this.responseWriter = responseWriter;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        if (!request.decoderResult()
                    .isSuccess()) {
            responseWriter.writeError(ctx, HttpResponseStatus.BAD_REQUEST, Causes.cause("Invalid HTTP request"));
            return;
        }
        var decoder = new QueryStringDecoder(request.uri());
        var path = decoder.path();
        HttpMethod.fromString(request.method()
                                     .name())
                  .onEmpty(() -> responseWriter.writeError(ctx,
                                                           HttpResponseStatus.METHOD_NOT_ALLOWED,
                                                           Causes.cause("Unsupported HTTP method: " + request.method()
                                                                                                             .name())))
                  .onPresent(method -> {
                                 log.debug("Received {} {}", method, path);
                                 routeRegistry.match(method, path)
                                              .onEmpty(() -> responseWriter.writeError(ctx,
                                                                                       HttpResponseStatus.NOT_FOUND,
                                                                                       new HttpRouterError.RouteNotFound(path)))
                                              .onPresent(match -> handleMatchedRoute(ctx,
                                                                                     method,
                                                                                     path,
                                                                                     decoder,
                                                                                     request,
                                                                                     match));
                             });
    }

    private void handleMatchedRoute(ChannelHandlerContext ctx,
                                    HttpMethod method,
                                    String path,
                                    QueryStringDecoder decoder,
                                    FullHttpRequest request,
                                    MatchResult match) {
        var route = match.route();
        // Build request context
        var queryParams = decoder.parameters();
        var headers = new HashMap<String, String>();
        request.headers()
               .forEach(entry -> headers.put(entry.getKey()
                                                  .toLowerCase(),
                                             entry.getValue()));
        var body = new byte[request.content()
                                   .readableBytes()];
        request.content()
               .readBytes(body);
        var context = new RequestContext(method, path, match.pathVariables(), queryParams, headers, body);
        // Resolve bindings
        var bindingsResult = bindingResolver.resolve(route.bindings(), context);
        if (bindingsResult.isFailure()) {
            bindingsResult.onFailure(cause -> responseWriter.writeError(ctx, HttpResponseStatus.BAD_REQUEST, cause));
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
        responseWriter.writeError(ctx, HttpResponseStatus.INTERNAL_SERVER_ERROR, Causes.fromThrowable(cause));
    }
}
