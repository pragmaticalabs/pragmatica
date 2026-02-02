# Aether Demo Application + HTTP Router Implementation Plan

## Overview

Implement a realistic Order domain demo application with HTTP→Slice router to demonstrate Aether's distributed architecture and inter-slice communication.

**Components:**
1. HTTP Router - routes external HTTP requests to slice methods
2. Order Demo - 3 use cases + 2 shared services demonstrating inter-slice calls

---

## Part 1: HTTP→Slice Router

### Architecture

```
HTTP Request → HttpRouterHandler
  → RouteRegistry.findRoute(method, path)
  → RequestExtractor.extract(request, bindings)
  → RequestBuilder.buildRequest(params, route)
  → SliceDispatcher.dispatch(route, request)
     → InternalSlice.call() [local] OR SliceInvoker.invoke() [remote]
  → JsonMapper.toJson(response)
  → HTTP Response
```

### New Files to Create

#### 1. `node/src/main/java/org/pragmatica/aether/router/HttpRouterConfig.java`

```java
package org.pragmatica.aether.router;

import org.pragmatica.lang.io.TimeSpan;

public record HttpRouterConfig(
    int port,
    int maxContentLength,  // default 65536
    TimeSpan requestTimeout // default 30s
) {
    public static HttpRouterConfig defaultConfig(int port) {
        return new HttpRouterConfig(port, 65536, TimeSpan.timeSpan(30).seconds());
    }
}
```

#### 2. `node/src/main/java/org/pragmatica/aether/router/RouterError.java`

```java
package org.pragmatica.aether.router;

import io.netty.handler.codec.http.HttpResponseStatus;
import org.pragmatica.lang.Cause;

public sealed interface RouterError extends Cause {

    record RouteNotFound(String method, String path) implements RouterError {
        @Override
        public String message() {
            return "No route found for " + method + " " + path;
        }
    }

    record MethodNotAllowed(String method, String path) implements RouterError {
        @Override
        public String message() {
            return "Method " + method + " not allowed for " + path;
        }
    }

    record InvalidRequestBody(String details) implements RouterError {
        @Override
        public String message() {
            return "Invalid request body: " + details;
        }
    }

    record ParameterMissing(String param) implements RouterError {
        @Override
        public String message() {
            return "Required parameter missing: " + param;
        }
    }

    record SliceNotAvailable(String sliceId) implements RouterError {
        @Override
        public String message() {
            return "Slice not available: " + sliceId;
        }
    }

    record InvocationFailed(Cause cause) implements RouterError {
        @Override
        public String message() {
            return "Slice invocation failed: " + cause.message();
        }
    }

    record RequestTimeout(String method, String path) implements RouterError {
        @Override
        public String message() {
            return "Request timeout: " + method + " " + path;
        }
    }

    static HttpResponseStatus toHttpStatus(Cause cause) {
        return switch (cause) {
            case RouteNotFound _ -> HttpResponseStatus.NOT_FOUND;
            case MethodNotAllowed _ -> HttpResponseStatus.METHOD_NOT_ALLOWED;
            case InvalidRequestBody _ -> HttpResponseStatus.BAD_REQUEST;
            case ParameterMissing _ -> HttpResponseStatus.BAD_REQUEST;
            case SliceNotAvailable _ -> HttpResponseStatus.SERVICE_UNAVAILABLE;
            case InvocationFailed _ -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
            case RequestTimeout _ -> HttpResponseStatus.GATEWAY_TIMEOUT;
            default -> HttpResponseStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
```

#### 3. `node/src/main/java/org/pragmatica/aether/router/JsonMapper.java`

```java
package org.pragmatica.aether.router;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufInputStream;
import org.pragmatica.lang.Result;

public interface JsonMapper {
    <T> Result<T> fromJson(String json, Class<T> type);
    <T> Result<T> fromJson(ByteBuf buf, Class<T> type);
    Result<String> toJson(Object value);

    static JsonMapper jsonMapper() {
        return new JacksonJsonMapper();
    }
}

class JacksonJsonMapper implements JsonMapper {
    private final ObjectMapper mapper;

    JacksonJsonMapper() {
        this.mapper = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Override
    public <T> Result<T> fromJson(String json, Class<T> type) {
        return Result.lift(() -> mapper.readValue(json, type));
    }

    @Override
    public <T> Result<T> fromJson(ByteBuf buf, Class<T> type) {
        return Result.lift(() -> mapper.readValue(new ByteBufInputStream(buf), type));
    }

    @Override
    public Result<String> toJson(Object value) {
        return Result.lift(() -> mapper.writeValueAsString(value));
    }
}
```

#### 4. `node/src/main/java/org/pragmatica/aether/router/CompiledRoute.java`

```java
package org.pragmatica.aether.router;

import org.pragmatica.aether.routing.Binding;
import org.pragmatica.aether.routing.Route;
import org.pragmatica.aether.routing.RouteTarget;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record CompiledRoute(
    HttpMethod method,
    Pattern pathPattern,
    List<String> pathParamNames,
    RouteTarget target,
    List<Binding> bindings,
    Class<?> requestType,   // null if no Body binding
    Class<?> responseType
) {
    /**
     * Compile route pattern like "/orders/{orderId}" into regex.
     * Extracts path parameter names.
     */
    public static Result<CompiledRoute> compile(HttpMethod method, Route route,
                                                 Class<?> requestType, Class<?> responseType) {
        return Result.lift(() -> {
            var pathParamNames = new ArrayList<String>();
            var patternStr = route.pattern();

            // Replace {paramName} with named capture group
            var regex = new StringBuilder();
            var matcher = Pattern.compile("\\{([^}]+)}").matcher(patternStr);
            while (matcher.find()) {
                var paramName = matcher.group(1);
                pathParamNames.add(paramName);
                matcher.appendReplacement(regex, "(?<" + paramName + ">[^/]+)");
            }
            matcher.appendTail(regex);

            // Anchor pattern
            var pattern = Pattern.compile("^" + regex + "$");

            return new CompiledRoute(method, pattern, pathParamNames,
                                     route.target(), route.bindings(),
                                     requestType, responseType);
        });
    }

    /**
     * Match path and extract parameters.
     */
    public Option<Map<String, String>> match(HttpMethod reqMethod, String path) {
        if (this.method != reqMethod) {
            return Option.empty();
        }

        Matcher matcher = pathPattern.matcher(path);
        if (!matcher.matches()) {
            return Option.empty();
        }

        var params = new HashMap<String, String>();
        for (var name : pathParamNames) {
            params.put(name, matcher.group(name));
        }
        return Option.some(params);
    }
}

enum HttpMethod {
    GET, POST, PUT, DELETE, PATCH, HEAD, OPTIONS
}
```

#### 5. `node/src/main/java/org/pragmatica/aether/router/RouteMatch.java`

```java
package org.pragmatica.aether.router;

import java.util.Map;

public record RouteMatch(
    CompiledRoute route,
    Map<String, String> pathParams
) {}
```

#### 6. `node/src/main/java/org/pragmatica/aether/router/RouteRegistry.java`

```java
package org.pragmatica.aether.router;

import org.pragmatica.aether.routing.Route;
import org.pragmatica.aether.routing.RoutingSection;
import org.pragmatica.lang.Option;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public interface RouteRegistry {
    Option<RouteMatch> findRoute(HttpMethod method, String path);
    void updateRoutes(List<RoutingSection> httpSections, SliceTypeRegistry typeRegistry);
    List<CompiledRoute> allRoutes();

    static RouteRegistry routeRegistry() {
        return new RouteRegistryImpl();
    }
}

class RouteRegistryImpl implements RouteRegistry {
    private final List<CompiledRoute> routes = new CopyOnWriteArrayList<>();

    @Override
    public Option<RouteMatch> findRoute(HttpMethod method, String path) {
        for (var route : routes) {
            var match = route.match(method, path);
            if (match.isPresent()) {
                return match.map(params -> new RouteMatch(route, params));
            }
        }
        return Option.empty();
    }

    @Override
    public void updateRoutes(List<RoutingSection> httpSections, SliceTypeRegistry typeRegistry) {
        var newRoutes = new ArrayList<CompiledRoute>();

        for (var section : httpSections) {
            if (!"http".equals(section.protocol())) continue;

            for (var route : section.routes()) {
                // Parse method from pattern prefix (e.g., "GET:/api/orders")
                var parts = route.pattern().split(":", 2);
                var method = HttpMethod.valueOf(parts[0]);
                var path = parts[1];

                // Lookup types from registry
                var requestType = typeRegistry.getRequestType(route.target());
                var responseType = typeRegistry.getResponseType(route.target());

                var pathOnlyRoute = new Route(path, route.target(), route.bindings());
                CompiledRoute.compile(method, pathOnlyRoute, requestType, responseType)
                             .onSuccess(newRoutes::add);
            }
        }

        routes.clear();
        routes.addAll(newRoutes);
    }

    @Override
    public List<CompiledRoute> allRoutes() {
        return List.copyOf(routes);
    }
}
```

#### 7. `node/src/main/java/org/pragmatica/aether/router/SliceTypeRegistry.java`

```java
package org.pragmatica.aether.router;

import org.pragmatica.aether.routing.RouteTarget;
import org.pragmatica.aether.slice.SliceMethod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of slice method types for request/response serialization.
 */
public interface SliceTypeRegistry {
    void register(String sliceId, String methodName, Class<?> requestType, Class<?> responseType);
    Class<?> getRequestType(RouteTarget target);
    Class<?> getResponseType(RouteTarget target);

    static SliceTypeRegistry sliceTypeRegistry() {
        return new SliceTypeRegistryImpl();
    }
}

class SliceTypeRegistryImpl implements SliceTypeRegistry {
    private record TypePair(Class<?> requestType, Class<?> responseType) {}
    private final Map<String, TypePair> types = new ConcurrentHashMap<>();

    @Override
    public void register(String sliceId, String methodName, Class<?> requestType, Class<?> responseType) {
        types.put(sliceId + ":" + methodName, new TypePair(requestType, responseType));
    }

    @Override
    public Class<?> getRequestType(RouteTarget target) {
        var pair = types.get(target.sliceId() + ":" + target.methodName());
        return pair != null ? pair.requestType() : Object.class;
    }

    @Override
    public Class<?> getResponseType(RouteTarget target) {
        var pair = types.get(target.sliceId() + ":" + target.methodName());
        return pair != null ? pair.responseType() : Object.class;
    }
}
```

#### 8. `node/src/main/java/org/pragmatica/aether/router/RequestExtractor.java`

```java
package org.pragmatica.aether.router;

import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.QueryStringDecoder;
import org.pragmatica.aether.routing.Binding;
import org.pragmatica.aether.routing.BindingSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.HashMap;
import java.util.Map;

public interface RequestExtractor {
    Result<ExtractedParams> extract(FullHttpRequest request, RouteMatch match);

    static RequestExtractor requestExtractor(JsonMapper jsonMapper) {
        return new RequestExtractorImpl(jsonMapper);
    }
}

record ExtractedParams(
    Map<String, Object> params,
    Option<Object> body
) {}

class RequestExtractorImpl implements RequestExtractor {
    private final JsonMapper jsonMapper;

    RequestExtractorImpl(JsonMapper jsonMapper) {
        this.jsonMapper = jsonMapper;
    }

    @Override
    public Result<ExtractedParams> extract(FullHttpRequest request, RouteMatch match) {
        var params = new HashMap<String, Object>();
        Object body = null;

        var queryDecoder = new QueryStringDecoder(request.uri());

        for (var binding : match.route().bindings()) {
            var result = extractBinding(request, match, queryDecoder, binding);
            if (result.isFailure()) {
                return result.map(_ -> null);
            }

            result.onSuccess(value -> {
                if (binding.source() instanceof BindingSource.Body) {
                    // Body handled separately
                } else {
                    params.put(binding.param(), value);
                }
            });
        }

        // Extract body if Body binding present and request has content
        var hasBodyBinding = match.route().bindings().stream()
            .anyMatch(b -> b.source() instanceof BindingSource.Body);

        if (hasBodyBinding && request.content().readableBytes() > 0) {
            var bodyResult = jsonMapper.fromJson(request.content(), match.route().requestType());
            if (bodyResult.isFailure()) {
                return bodyResult.map(_ -> null);
            }
            body = bodyResult.unwrap();
        }

        return Result.success(new ExtractedParams(params, Option.option(body)));
    }

    private Result<Object> extractBinding(FullHttpRequest request, RouteMatch match,
                                          QueryStringDecoder queryDecoder, Binding binding) {
        return switch (binding.source()) {
            case BindingSource.PathVar(var name) -> {
                var value = match.pathParams().get(name);
                yield value != null
                    ? Result.success(value)
                    : new RouterError.ParameterMissing(name).result();
            }
            case BindingSource.QueryVar(var name) -> {
                var values = queryDecoder.parameters().get(name);
                yield values != null && !values.isEmpty()
                    ? Result.success(values.getFirst())
                    : Result.success(null); // Query params optional
            }
            case BindingSource.Header(var name) -> {
                var value = request.headers().get(name);
                yield Result.success(value);
            }
            case BindingSource.Body() -> Result.success(null); // Handled separately
            default -> Result.success(null);
        };
    }
}
```

#### 9. `node/src/main/java/org/pragmatica/aether/router/SliceResolver.java`

```java
package org.pragmatica.aether.router;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.routing.SliceSpec;
import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Maps slice IDs (from routes) to actual Artifacts.
 */
public interface SliceResolver {
    Option<Artifact> resolve(String sliceId);
    void updateMappings(List<SliceSpec> slices);

    static SliceResolver sliceResolver() {
        return new SliceResolverImpl();
    }
}

class SliceResolverImpl implements SliceResolver {
    private final Map<String, Artifact> mappings = new ConcurrentHashMap<>();

    @Override
    public Option<Artifact> resolve(String sliceId) {
        return Option.option(mappings.get(sliceId));
    }

    @Override
    public void updateMappings(List<SliceSpec> slices) {
        mappings.clear();
        for (var spec : slices) {
            // sliceId is artifactId part of the artifact
            var sliceId = spec.artifact().artifactId().value();
            mappings.put(sliceId, spec.artifact());
        }
    }
}
```

#### 10. `node/src/main/java/org/pragmatica/aether/router/SliceDispatcher.java`

```java
package org.pragmatica.aether.router;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.endpoint.EndpointRegistry;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.InternalSlice;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public interface SliceDispatcher {
    Promise<Object> dispatch(CompiledRoute route, Object request, SliceResolver resolver);
    void registerLocalSlice(Artifact artifact, InternalSlice slice);
    void unregisterLocalSlice(Artifact artifact);

    static SliceDispatcher sliceDispatcher(SliceInvoker invoker, NodeId self) {
        return new SliceDispatcherImpl(invoker, self);
    }
}

class SliceDispatcherImpl implements SliceDispatcher {
    private final SliceInvoker invoker;
    private final NodeId self;
    private final Map<Artifact, InternalSlice> localSlices = new ConcurrentHashMap<>();

    SliceDispatcherImpl(SliceInvoker invoker, NodeId self) {
        this.invoker = invoker;
        this.self = self;
    }

    @Override
    public Promise<Object> dispatch(CompiledRoute route, Object request, SliceResolver resolver) {
        var sliceId = route.target().sliceId();
        var methodName = route.target().methodName();

        return resolver.resolve(sliceId)
            .map(artifact -> dispatchToArtifact(artifact, methodName, request, route.responseType()))
            .or(() -> new RouterError.SliceNotAvailable(sliceId).promise());
    }

    private Promise<Object> dispatchToArtifact(Artifact artifact, String methodName,
                                                Object request, Class<?> responseType) {
        // Check if local first
        var localSlice = localSlices.get(artifact);
        if (localSlice != null) {
            return dispatchLocal(localSlice, methodName, request);
        }

        // Remote invocation
        return invoker.invoke(artifact,
                                     MethodName.methodName(methodName).unwrap(),
                                     request,
                                     responseType)
                      .map(r -> (Object) r);
    }

    private Promise<Object> dispatchLocal(InternalSlice slice, String methodName, Object request) {
        // For local dispatch, use InternalSlice.call() with serialization
        // This maintains consistency with remote calls
        return invoker.invokeLocal(slice, MethodName.methodName(methodName).unwrap(), request)
                      .map(r -> (Object) r);
    }

    @Override
    public void registerLocalSlice(Artifact artifact, InternalSlice slice) {
        localSlices.put(artifact, slice);
    }

    @Override
    public void unregisterLocalSlice(Artifact artifact) {
        localSlices.remove(artifact);
    }
}
```

#### 11. `node/src/main/java/org/pragmatica/aether/router/HttpRouterHandler.java`

```java
package org.pragmatica.aether.router;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.CharsetUtil;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.io.TimeSpan;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_LENGTH;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

public class HttpRouterHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private final RouteRegistry routeRegistry;
    private final RequestExtractor requestExtractor;
    private final SliceDispatcher dispatcher;
    private final SliceResolver sliceResolver;
    private final JsonMapper jsonMapper;
    private final TimeSpan timeout;

    public HttpRouterHandler(RouteRegistry routeRegistry,
                             RequestExtractor requestExtractor,
                             SliceDispatcher dispatcher,
                             SliceResolver sliceResolver,
                             JsonMapper jsonMapper,
                             TimeSpan timeout) {
        this.routeRegistry = routeRegistry;
        this.requestExtractor = requestExtractor;
        this.dispatcher = dispatcher;
        this.sliceResolver = sliceResolver;
        this.jsonMapper = jsonMapper;
        this.timeout = timeout;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        var method = HttpMethod.valueOf(request.method().name());
        var path = extractPath(request.uri());

        routeRegistry.findRoute(method, path)
            .fold(
                () -> sendError(ctx, new RouterError.RouteNotFound(method.name(), path)),
                match -> processRequest(ctx, request, match)
            );
    }

    private void processRequest(ChannelHandlerContext ctx,
                                FullHttpRequest request,
                                RouteMatch match) {
        requestExtractor.extract(request, match)
            .async()
            .flatMap(params -> buildRequest(params, match))
            .flatMap(reqObj -> dispatcher.dispatch(match.route(), reqObj, sliceResolver))
            .timeout(timeout)
            .flatMap(response -> jsonMapper.toJson(response).async())
            .onSuccess(json -> sendJson(ctx, HttpResponseStatus.OK, json))
            .onFailure(cause -> sendError(ctx, cause));
    }

    private org.pragmatica.lang.Promise<Object> buildRequest(ExtractedParams params, RouteMatch match) {
        // If body present, use body as request
        // Otherwise, for simple path param routes, pass the param directly
        return params.body()
            .map(org.pragmatica.lang.Promise::success)
            .or(() -> {
                // Single path param case - pass as string
                if (params.params().size() == 1) {
                    return org.pragmatica.lang.Promise.success(params.params().values().iterator().next());
                }
                // Multiple params - wrap in map
                return org.pragmatica.lang.Promise.success(params.params());
            });
    }

    private String extractPath(String uri) {
        var idx = uri.indexOf('?');
        return idx > 0 ? uri.substring(0, idx) : uri;
    }

    private void sendJson(ChannelHandlerContext ctx, HttpResponseStatus status, String json) {
        var buf = Unpooled.copiedBuffer(json, CharsetUtil.UTF_8);
        var response = new DefaultFullHttpResponse(HTTP_1_1, status, buf);
        response.headers().set(CONTENT_TYPE, "application/json");
        response.headers().setInt(CONTENT_LENGTH, buf.readableBytes());
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private void sendError(ChannelHandlerContext ctx, Cause cause) {
        var status = RouterError.toHttpStatus(cause);
        var errorJson = "{\"error\":\"" + escapeJson(cause.message()) + "\"}";
        sendJson(ctx, status, errorJson);
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r");
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        sendError(ctx, new RouterError.InvocationFailed(
            org.pragmatica.lang.utils.Causes.fromThrowable(cause)));
        ctx.close();
    }
}
```

#### 12. `node/src/main/java/org/pragmatica/aether/router/HttpRouter.java`

```java
package org.pragmatica.aether.router;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.routing.RoutingSection;
import org.pragmatica.aether.routing.SliceSpec;
import org.pragmatica.cluster.net.NodeId;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public interface HttpRouter {
    Promise<Unit> start();
    Promise<Unit> stop();
    void updateRoutes(List<RoutingSection> sections, List<SliceSpec> slices, SliceTypeRegistry typeRegistry);
    SliceDispatcher dispatcher();

    static HttpRouter httpRouter(HttpRouterConfig config, SliceInvoker invoker, NodeId self) {
        return new HttpRouterImpl(config, invoker, self);
    }
}

class HttpRouterImpl implements HttpRouter {
    private static final Logger log = LoggerFactory.getLogger(HttpRouterImpl.class);

    private final HttpRouterConfig config;
    private final RouteRegistry routeRegistry;
    private final RequestExtractor requestExtractor;
    private final SliceDispatcher dispatcher;
    private final SliceResolver sliceResolver;
    private final JsonMapper jsonMapper;

    private EventLoopGroup bossGroup;
    private EventLoopGroup workerGroup;
    private Channel serverChannel;

    HttpRouterImpl(HttpRouterConfig config, SliceInvoker invoker, NodeId self) {
        this.config = config;
        this.jsonMapper = JsonMapper.jsonMapper();
        this.routeRegistry = RouteRegistry.routeRegistry();
        this.requestExtractor = RequestExtractor.requestExtractor(jsonMapper);
        this.dispatcher = SliceDispatcher.sliceDispatcher(invoker, self);
        this.sliceResolver = SliceResolver.sliceResolver();
    }

    @Override
    public Promise<Unit> start() {
        var promise = Promise.<Unit>promise();

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        var bootstrap = new ServerBootstrap()
            .group(bossGroup, workerGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline()
                      .addLast(new HttpServerCodec())
                      .addLast(new HttpObjectAggregator(config.maxContentLength()))
                      .addLast(new HttpRouterHandler(
                          routeRegistry, requestExtractor, dispatcher,
                          sliceResolver, jsonMapper, config.requestTimeout()));
                }
            });

        bootstrap.bind(config.port()).addListener((ChannelFuture future) -> {
            if (future.isSuccess()) {
                serverChannel = future.channel();
                log.info("HTTP Router started on port {}", config.port());
                promise.succeed(Unit.unit());
            } else {
                promise.fail(org.pragmatica.lang.utils.Causes.fromThrowable(future.cause()));
            }
        });

        return promise;
    }

    @Override
    public Promise<Unit> stop() {
        var promise = Promise.<Unit>promise();

        if (serverChannel != null) {
            serverChannel.close().addListener(_ -> {
                bossGroup.shutdownGracefully();
                workerGroup.shutdownGracefully();
                log.info("HTTP Router stopped");
                promise.succeed(Unit.unit());
            });
        } else {
            promise.succeed(Unit.unit());
        }

        return promise;
    }

    @Override
    public void updateRoutes(List<RoutingSection> sections, List<SliceSpec> slices,
                             SliceTypeRegistry typeRegistry) {
        sliceResolver.updateMappings(slices);
        routeRegistry.updateRoutes(sections, typeRegistry);
        log.info("Updated routes: {} routes registered", routeRegistry.allRoutes().size());
    }

    @Override
    public SliceDispatcher dispatcher() {
        return dispatcher;
    }
}
```

### Files to Modify

#### `node/src/main/java/org/pragmatica/aether/node/AetherNodeConfig.java`

Add:
```java
int httpPort()  // 0 = disabled, >0 = HTTP router port
```

#### `node/src/main/java/org/pragmatica/aether/node/AetherNode.java`

Add:
```java
HttpRouter httpRouter();
```

In factory, add HTTP router creation:
```java
if (config.httpPort() > 0) {
    var httpRouter = HttpRouter.httpRouter(
        HttpRouterConfig.defaultConfig(config.httpPort()),
        sliceInvoker,
        self
    );
    // Start with node
    // Wire blueprint changes to updateRoutes()
}
```

#### `node/pom.xml`

Add Jackson dependency:
```xml
<dependency>
    <groupId>com.fasterxml.jackson.core</groupId>
    <artifactId>jackson-databind</artifactId>
    <version>2.17.0</version>
</dependency>
<dependency>
    <groupId>com.fasterxml.jackson.datatype</groupId>
    <artifactId>jackson-datatype-jdk8</artifactId>
    <version>2.17.0</version>
</dependency>
```

---

## Part 2: Order Demo Application

### Module Structure

Create new directory `demo-order/` at project root:

```
aetherx/
├── demo-order/
│   ├── pom.xml                      # Parent POM
│   ├── order-domain/
│   │   ├── pom.xml
│   │   └── src/main/java/org/pragmatica/aether/demo/order/domain/
│   │       ├── OrderId.java
│   │       ├── ProductId.java
│   │       ├── CustomerId.java
│   │       ├── Quantity.java
│   │       ├── Money.java
│   │       ├── Currency.java
│   │       ├── OrderStatus.java
│   │       └── OrderItem.java
│   ├── inventory-service/
│   │   ├── pom.xml
│   │   └── src/main/java/org/pragmatica/aether/demo/order/inventory/
│   │       ├── InventoryServiceSlice.java
│   │       ├── InventoryError.java
│   │       ├── CheckStockRequest.java
│   │       ├── StockAvailability.java
│   │       ├── ReserveStockRequest.java
│   │       ├── StockReservation.java
│   │       ├── ReleaseStockRequest.java
│   │       └── StockReleased.java
│   ├── pricing-service/
│   │   ├── pom.xml
│   │   └── src/main/java/org/pragmatica/aether/demo/order/pricing/
│   │       ├── PricingServiceSlice.java
│   │       ├── PricingError.java
│   │       ├── GetPriceRequest.java
│   │       ├── ProductPrice.java
│   │       ├── CalculateTotalRequest.java
│   │       └── OrderTotal.java
│   ├── place-order/
│   │   ├── pom.xml
│   │   └── src/main/java/org/pragmatica/aether/demo/order/usecase/placeorder/
│   │       ├── PlaceOrderSlice.java
│   │       ├── PlaceOrderError.java
│   │       ├── PlaceOrderRequest.java
│   │       ├── ValidPlaceOrderRequest.java
│   │       └── PlaceOrderResponse.java
│   ├── get-order-status/
│   │   ├── pom.xml
│   │   └── src/main/java/org/pragmatica/aether/demo/order/usecase/getorderstatus/
│   │       ├── GetOrderStatusSlice.java
│   │       ├── GetOrderStatusError.java
│   │       ├── GetOrderStatusRequest.java
│   │       ├── ValidGetOrderStatusRequest.java
│   │       └── GetOrderStatusResponse.java
│   └── cancel-order/
│       ├── pom.xml
│       └── src/main/java/org/pragmatica/aether/demo/order/usecase/cancelorder/
│           ├── CancelOrderSlice.java
│           ├── CancelOrderError.java
│           ├── CancelOrderRequest.java
│           ├── ValidCancelOrderRequest.java
│           └── CancelOrderResponse.java
```

### Parent POM (`demo-order/pom.xml`)

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
                             http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.pragmatica-lite.aether</groupId>
        <artifactId>aether</artifactId>
        <version>0.4.0</version>
    </parent>

    <groupId>org.pragmatica-lite.aether.demo</groupId>
    <artifactId>demo-order</artifactId>
    <version>0.1.0</version>
    <packaging>pom</packaging>
    <name>Aether Demo - Order Domain</name>

    <modules>
        <module>order-domain</module>
        <module>inventory-service</module>
        <module>pricing-service</module>
        <module>place-order</module>
        <module>get-order-status</module>
        <module>cancel-order</module>
    </modules>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.pragmatica-lite.aether.demo</groupId>
                <artifactId>order-domain</artifactId>
                <version>${project.version}</version>
            </dependency>
        </dependencies>
    </dependencyManagement>
</project>
```

### Value Objects (order-domain)

#### `OrderId.java`

```java
package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.UUID;
import java.util.regex.Pattern;

public record OrderId(String value) {
    private static final Pattern ORDER_ID_PATTERN = Pattern.compile("^ORD-[a-f0-9]{8}$");
    private static final Fn1<Cause, String> INVALID_ORDER_ID =
        Causes.forOneValue("Invalid order ID: %s");

    public static Result<OrderId> orderId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank)
                     .flatMap(Verify.ensureFn(INVALID_ORDER_ID, Verify.Is::matches, ORDER_ID_PATTERN))
                     .map(OrderId::new);
    }

    public static OrderId generate() {
        return new OrderId("ORD-" + UUID.randomUUID().toString().substring(0, 8));
    }
}
```

#### `ProductId.java`

```java
package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

public record ProductId(String value) {
    private static final Pattern PRODUCT_ID_PATTERN = Pattern.compile("^PROD-[A-Z0-9]{6}$");
    private static final Fn1<Cause, String> INVALID_PRODUCT_ID =
        Causes.forOneValue("Invalid product ID: %s");

    public static Result<ProductId> productId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank)
                     .flatMap(Verify.ensureFn(INVALID_PRODUCT_ID, Verify.Is::matches, PRODUCT_ID_PATTERN))
                     .map(ProductId::new);
    }
}
```

#### `CustomerId.java`

```java
package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

import java.util.regex.Pattern;

public record CustomerId(String value) {
    private static final Pattern CUSTOMER_ID_PATTERN = Pattern.compile("^CUST-[0-9]{8}$");
    private static final Fn1<Cause, String> INVALID_CUSTOMER_ID =
        Causes.forOneValue("Invalid customer ID: %s");

    public static Result<CustomerId> customerId(String raw) {
        return Verify.ensure(raw, Verify.Is::notBlank)
                     .flatMap(Verify.ensureFn(INVALID_CUSTOMER_ID, Verify.Is::matches, CUSTOMER_ID_PATTERN))
                     .map(CustomerId::new);
    }
}
```

#### `Quantity.java`

```java
package org.pragmatica.aether.demo.order.domain;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;
import org.pragmatica.lang.utils.Causes;

public record Quantity(int value) {
    private static final Cause INVALID_QUANTITY = Causes.cause("Quantity must be between 1 and 1000");

    public static Result<Quantity> quantity(int raw) {
        return Verify.ensure(raw, Verify.Is::between, 1, 1000)
                     .mapError(_ -> INVALID_QUANTITY)
                     .map(Quantity::new);
    }
}
```

#### `Money.java`

```java
package org.pragmatica.aether.demo.order.domain;

import java.math.BigDecimal;

public record Money(BigDecimal amount, Currency currency) {

    public static Money usd(BigDecimal amount) {
        return new Money(amount, Currency.USD);
    }

    public static Money usd(String amount) {
        return new Money(new BigDecimal(amount), Currency.USD);
    }

    public Money add(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot add different currencies");
        }
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money multiply(int quantity) {
        return new Money(this.amount.multiply(BigDecimal.valueOf(quantity)), this.currency);
    }

    public Money subtract(Money other) {
        if (!this.currency.equals(other.currency)) {
            throw new IllegalArgumentException("Cannot subtract different currencies");
        }
        return new Money(this.amount.subtract(other.amount), this.currency);
    }
}
```

#### `Currency.java`

```java
package org.pragmatica.aether.demo.order.domain;

public enum Currency {
    USD, EUR, GBP
}
```

#### `OrderStatus.java`

```java
package org.pragmatica.aether.demo.order.domain;

public enum OrderStatus {
    PENDING,
    CONFIRMED,
    PROCESSING,
    SHIPPED,
    DELIVERED,
    CANCELLED
}
```

### Service Slices

#### InventoryServiceSlice

**`inventory-service/src/main/java/org/pragmatica/aether/demo/order/inventory/InventoryServiceSlice.java`**

```java
package org.pragmatica.aether.demo.order.inventory;

import org.pragmatica.aether.demo.order.domain.ProductId;
import org.pragmatica.aether.demo.order.domain.Quantity;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record InventoryServiceSlice() implements Slice {

    // Mock stock data
    private static final Map<String, Integer> STOCK = new ConcurrentHashMap<>(Map.of(
        "PROD-ABC123", 100,
        "PROD-DEF456", 50,
        "PROD-GHI789", 25
    ));

    private static final Map<String, ReservedStock> RESERVATIONS = new ConcurrentHashMap<>();

    private record ReservedStock(String productId, int quantity) {}

    public static InventoryServiceSlice inventoryServiceSlice() {
        return new InventoryServiceSlice();
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("checkStock").unwrap(),
                this::checkStock,
                new TypeToken<StockAvailability>() {},
                new TypeToken<CheckStockRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("reserveStock").unwrap(),
                this::reserveStock,
                new TypeToken<StockReservation>() {},
                new TypeToken<ReserveStockRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("releaseStock").unwrap(),
                this::releaseStock,
                new TypeToken<StockReleased>() {},
                new TypeToken<ReleaseStockRequest>() {}
            )
        );
    }

    private Promise<StockAvailability> checkStock(CheckStockRequest request) {
        var productId = request.productId();
        var available = STOCK.getOrDefault(productId, 0);

        if (available == 0) {
            return new InventoryError.ProductNotFound(productId).promise();
        }

        var sufficient = available >= request.quantity();
        return Promise.success(new StockAvailability(productId, available, sufficient));
    }

    private Promise<StockReservation> reserveStock(ReserveStockRequest request) {
        var productId = request.productId();
        var available = STOCK.getOrDefault(productId, 0);
        var requested = request.quantity();

        if (available == 0) {
            return new InventoryError.ProductNotFound(productId).promise();
        }

        if (available < requested) {
            return new InventoryError.InsufficientStock(productId, requested, available).promise();
        }

        // Reserve atomically
        STOCK.compute(productId, (k, v) -> v - requested);

        var reservationId = "RES-" + UUID.randomUUID().toString().substring(0, 8);
        RESERVATIONS.put(reservationId, new ReservedStock(productId, requested));

        return Promise.success(new StockReservation(reservationId, productId, requested));
    }

    private Promise<StockReleased> releaseStock(ReleaseStockRequest request) {
        var reservation = RESERVATIONS.remove(request.reservationId());

        if (reservation == null) {
            return new InventoryError.ReservationNotFound(request.reservationId()).promise();
        }

        STOCK.compute(reservation.productId(), (k, v) -> v + reservation.quantity());

        return Promise.success(new StockReleased(request.reservationId()));
    }
}
```

**DTOs:**

```java
// CheckStockRequest.java
package org.pragmatica.aether.demo.order.inventory;

public record CheckStockRequest(String productId, int quantity) {}

// StockAvailability.java
package org.pragmatica.aether.demo.order.inventory;

public record StockAvailability(String productId, int available, boolean sufficient) {}

// ReserveStockRequest.java
package org.pragmatica.aether.demo.order.inventory;

public record ReserveStockRequest(String productId, int quantity, String orderId) {}

// StockReservation.java
package org.pragmatica.aether.demo.order.inventory;

public record StockReservation(String reservationId, String productId, int quantity) {}

// ReleaseStockRequest.java
package org.pragmatica.aether.demo.order.inventory;

public record ReleaseStockRequest(String reservationId) {}

// StockReleased.java
package org.pragmatica.aether.demo.order.inventory;

public record StockReleased(String reservationId) {}

// InventoryError.java
package org.pragmatica.aether.demo.order.inventory;

import org.pragmatica.lang.Cause;

public sealed interface InventoryError extends Cause {

    record ProductNotFound(String productId) implements InventoryError {
        @Override
        public String message() {
            return "Product not found: " + productId;
        }
    }

    record InsufficientStock(String productId, int requested, int available) implements InventoryError {
        @Override
        public String message() {
            return "Insufficient stock for " + productId + ": requested " + requested + ", available " + available;
        }
    }

    record ReservationNotFound(String reservationId) implements InventoryError {
        @Override
        public String message() {
            return "Reservation not found: " + reservationId;
        }
    }
}
```

#### PricingServiceSlice

**`pricing-service/src/main/java/org/pragmatica/aether/demo/order/pricing/PricingServiceSlice.java`**

```java
package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Currency;
import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

public record PricingServiceSlice() implements Slice {

    private static final Map<String, BigDecimal> PRICES = Map.of(
        "PROD-ABC123", new BigDecimal("29.99"),
        "PROD-DEF456", new BigDecimal("49.99"),
        "PROD-GHI789", new BigDecimal("99.99")
    );

    private static final Map<String, BigDecimal> DISCOUNTS = Map.of(
        "SAVE10", new BigDecimal("0.10"),
        "SAVE20", new BigDecimal("0.20")
    );

    public static PricingServiceSlice pricingServiceSlice() {
        return new PricingServiceSlice();
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("getPrice").unwrap(),
                this::getPrice,
                new TypeToken<ProductPrice>() {},
                new TypeToken<GetPriceRequest>() {}
            ),
            new SliceMethod<>(
                MethodName.methodName("calculateTotal").unwrap(),
                this::calculateTotal,
                new TypeToken<OrderTotal>() {},
                new TypeToken<CalculateTotalRequest>() {}
            )
        );
    }

    private Promise<ProductPrice> getPrice(GetPriceRequest request) {
        var price = PRICES.get(request.productId());

        if (price == null) {
            return new PricingError.PriceNotFound(request.productId()).promise();
        }

        return Promise.success(new ProductPrice(request.productId(), Money.usd(price)));
    }

    private Promise<OrderTotal> calculateTotal(CalculateTotalRequest request) {
        var subtotal = BigDecimal.ZERO;

        for (var item : request.items()) {
            var price = PRICES.get(item.productId());
            if (price == null) {
                return new PricingError.PriceNotFound(item.productId()).promise();
            }
            subtotal = subtotal.add(price.multiply(BigDecimal.valueOf(item.quantity())));
        }

        var discountRate = request.discountCode() != null ? DISCOUNTS.get(request.discountCode()) : null;
        var discount = discountRate != null ? subtotal.multiply(discountRate) : BigDecimal.ZERO;
        var total = subtotal.subtract(discount);

        return Promise.success(new OrderTotal(
            Money.usd(subtotal),
            Money.usd(discount),
            Money.usd(total)
        ));
    }
}
```

**DTOs:**

```java
// GetPriceRequest.java
package org.pragmatica.aether.demo.order.pricing;

public record GetPriceRequest(String productId) {}

// ProductPrice.java
package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Money;

public record ProductPrice(String productId, Money unitPrice) {}

// CalculateTotalRequest.java
package org.pragmatica.aether.demo.order.pricing;

import java.util.List;

public record CalculateTotalRequest(List<LineItem> items, String discountCode) {
    public record LineItem(String productId, int quantity) {}
}

// OrderTotal.java
package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.aether.demo.order.domain.Money;

public record OrderTotal(Money subtotal, Money discount, Money total) {}

// PricingError.java
package org.pragmatica.aether.demo.order.pricing;

import org.pragmatica.lang.Cause;

public sealed interface PricingError extends Cause {

    record PriceNotFound(String productId) implements PricingError {
        @Override
        public String message() {
            return "Price not found for product: " + productId;
        }
    }

    record DiscountCodeInvalid(String code) implements PricingError {
        @Override
        public String message() {
            return "Invalid discount code: " + code;
        }
    }
}
```

### Use Case Slices

#### PlaceOrderSlice

**`place-order/src/main/java/org/pragmatica/aether/demo/order/usecase/placeorder/PlaceOrderSlice.java`**

```java
package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;
import org.pragmatica.aether.demo.order.inventory.CheckStockRequest;
import org.pragmatica.aether.demo.order.inventory.ReserveStockRequest;
import org.pragmatica.aether.demo.order.inventory.StockAvailability;
import org.pragmatica.aether.demo.order.inventory.StockReservation;
import org.pragmatica.aether.demo.order.pricing.CalculateTotalRequest;
import org.pragmatica.aether.demo.order.pricing.OrderTotal;
import org.pragmatica.aether.invoke.SliceInvoker;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;

/**
 * PlaceOrder Use Case
 *
 * Flow:
 * 1. Validate request
 * 2. Check stock for all items (parallel, calls InventoryService)
 * 3. Calculate total price (calls PricingService)
 * 4. Reserve stock for all items (parallel, calls InventoryService)
 * 5. Return order confirmation
 */
public record PlaceOrderSlice(SliceInvoker invoker) implements Slice {

    private static final Artifact INVENTORY = Artifact.artifact("org.pragmatica-lite.aether.demo:inventory-service:0.1.0").unwrap();
    private static final Artifact PRICING = Artifact.artifact("org.pragmatica-lite.aether.demo:pricing-service:0.1.0").unwrap();

    public static PlaceOrderSlice placeOrderSlice(SliceInvoker invoker) {
        return new PlaceOrderSlice(invoker);
    }

    @Override
    public List<SliceMethod<?, ?>> methods() {
        return List.of(
            new SliceMethod<>(
                MethodName.methodName("placeOrder").unwrap(),
                this::execute,
                new TypeToken<PlaceOrderResponse>() {},
                new TypeToken<PlaceOrderRequest>() {}
            )
        );
    }

    private Promise<PlaceOrderResponse> execute(PlaceOrderRequest request) {
        return ValidPlaceOrderRequest.validPlaceOrderRequest(request)
                                     .async()
                                     .flatMap(this::checkAllStock)
                                     .flatMap(this::calculateTotal)
                                     .flatMap(this::reserveAllStock)
                                     .map(this::createOrder);
    }

    private Promise<ValidWithStockCheck> checkAllStock(ValidPlaceOrderRequest request) {
        var stockChecks = request.items().stream()
            .map(item -> invoker.invoke(
                INVENTORY,
                MethodName.methodName("checkStock").unwrap(),
                new CheckStockRequest(item.productId(), item.quantity()),
                StockAvailability.class
            ))
            .toList();

        return Promise.allOf(stockChecks)
                      .flatMap(results -> {
                          var allAvailable = results.stream()
                              .allMatch(r -> r.isSuccess() && r.unwrap().sufficient());

                          if (!allAvailable) {
                              return new PlaceOrderError.InventoryCheckFailed(
                                  Causes.cause("Some items not available")
                              ).promise();
                          }
                          return Promise.success(new ValidWithStockCheck(request));
                      });
    }

    private Promise<ValidWithPrice> calculateTotal(ValidWithStockCheck context) {
        var lineItems = context.request().items().stream()
            .map(item -> new CalculateTotalRequest.LineItem(item.productId(), item.quantity()))
            .toList();

        return invoker.invoke(
            PRICING,
            MethodName.methodName("calculateTotal").unwrap(),
            new CalculateTotalRequest(lineItems, context.request().discountCode()),
            OrderTotal.class
        ).map(total -> new ValidWithPrice(context.request(), total))
         .trace(cause -> new PlaceOrderError.PricingFailed(cause));
    }

    private Promise<ValidWithReservations> reserveAllStock(ValidWithPrice context) {
        var orderId = OrderId.generate();

        var reservations = context.request().items().stream()
            .map(item -> invoker.invoke(
                INVENTORY,
                MethodName.methodName("reserveStock").unwrap(),
                new ReserveStockRequest(item.productId(), item.quantity(), orderId.value()),
                StockReservation.class
            ))
            .toList();

        return Promise.allOf(reservations)
                      .flatMap(results -> {
                          var successful = new ArrayList<StockReservation>();
                          for (var r : results) {
                              if (r.isSuccess()) {
                                  successful.add(r.unwrap());
                              }
                          }

                          if (successful.size() != results.size()) {
                              return new PlaceOrderError.ReservationFailed(
                                  Causes.cause("Failed to reserve all items")
                              ).promise();
                          }

                          return Promise.success(new ValidWithReservations(
                              context.request(), context.total(), orderId, successful
                          ));
                      });
    }

    private PlaceOrderResponse createOrder(ValidWithReservations context) {
        return new PlaceOrderResponse(
            context.orderId(),
            OrderStatus.CONFIRMED,
            context.total().total(),
            context.reservations().stream().map(StockReservation::reservationId).toList()
        );
    }

    // Pipeline context records
    private record ValidWithStockCheck(ValidPlaceOrderRequest request) {}
    private record ValidWithPrice(ValidPlaceOrderRequest request, OrderTotal total) {}
    private record ValidWithReservations(
        ValidPlaceOrderRequest request,
        OrderTotal total,
        OrderId orderId,
        List<StockReservation> reservations
    ) {}
}
```

**DTOs:**

```java
// PlaceOrderRequest.java
package org.pragmatica.aether.demo.order.usecase.placeorder;

import java.util.List;

public record PlaceOrderRequest(
    String customerId,
    List<OrderItemRequest> items,
    String discountCode
) {
    public record OrderItemRequest(String productId, int quantity) {}
}

// ValidPlaceOrderRequest.java
package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.demo.order.domain.CustomerId;
import org.pragmatica.aether.demo.order.domain.ProductId;
import org.pragmatica.aether.demo.order.domain.Quantity;
import org.pragmatica.lang.Result;

import java.util.List;

public record ValidPlaceOrderRequest(
    CustomerId customerId,
    List<ValidOrderItem> items,
    String discountCode
) {
    public record ValidOrderItem(String productId, int quantity) {}

    public static Result<ValidPlaceOrderRequest> validPlaceOrderRequest(PlaceOrderRequest raw) {
        return Result.all(CustomerId.customerId(raw.customerId()),
                          validateItems(raw.items()))
                     .map((customerId, items) ->
                         new ValidPlaceOrderRequest(customerId, items, raw.discountCode()));
    }

    private static Result<List<ValidOrderItem>> validateItems(List<PlaceOrderRequest.OrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            return new PlaceOrderError.InvalidRequest("At least one item required").result();
        }

        var validated = items.stream()
            .map(item -> Result.all(ProductId.productId(item.productId()),
                                    Quantity.quantity(item.quantity()))
                               .map((pid, qty) -> new ValidOrderItem(pid.value(), qty.value())))
            .toList();

        return Result.allOf(validated);
    }
}

// PlaceOrderResponse.java
package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.aether.demo.order.domain.Money;
import org.pragmatica.aether.demo.order.domain.OrderId;
import org.pragmatica.aether.demo.order.domain.OrderStatus;

import java.util.List;

public record PlaceOrderResponse(
    OrderId orderId,
    OrderStatus status,
    Money total,
    List<String> reservationIds
) {}

// PlaceOrderError.java
package org.pragmatica.aether.demo.order.usecase.placeorder;

import org.pragmatica.lang.Cause;

public sealed interface PlaceOrderError extends Cause {

    record InvalidRequest(String details) implements PlaceOrderError {
        @Override
        public String message() {
            return "Invalid order request: " + details;
        }
    }

    record InventoryCheckFailed(Cause cause) implements PlaceOrderError {
        @Override
        public String message() {
            return "Inventory check failed: " + cause.message();
        }
    }

    record PricingFailed(Cause cause) implements PlaceOrderError {
        @Override
        public String message() {
            return "Pricing calculation failed: " + cause.message();
        }
    }

    record ReservationFailed(Cause cause) implements PlaceOrderError {
        @Override
        public String message() {
            return "Stock reservation failed: " + cause.message();
        }
    }
}
```

#### GetOrderStatusSlice and CancelOrderSlice

Follow similar pattern - simpler implementations:
- **GetOrderStatusSlice**: Validate → Lookup mock store → Return status (no inter-slice calls)
- **CancelOrderSlice**: Validate → Lookup → Check cancellable → Release stock (calls InventoryService) → Update status

### Module POM Pattern

Each slice module POM:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project>
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.pragmatica-lite.aether.demo</groupId>
        <artifactId>demo-order</artifactId>
        <version>0.1.0</version>
    </parent>

    <artifactId>inventory-service</artifactId>

    <dependencies>
        <dependency>
            <groupId>org.pragmatica-lite.aether</groupId>
            <artifactId>slice-api</artifactId>
        </dependency>
        <dependency>
            <groupId>org.pragmatica-lite</groupId>
            <artifactId>core</artifactId>
        </dependency>
        <dependency>
            <groupId>org.pragmatica-lite.aether.demo</groupId>
            <artifactId>order-domain</artifactId>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-jar-plugin</artifactId>
                <configuration>
                    <archive>
                        <manifestEntries>
                            <Slice-Artifact>${project.groupId}:${project.artifactId}:${project.version}</Slice-Artifact>
                            <Slice-Class>org.pragmatica.aether.demo.order.inventory.InventoryServiceSlice</Slice-Class>
                        </manifestEntries>
                    </archive>
                </configuration>
            </plugin>
        </plugins>
    </build>
</project>
```

---

## Part 3: Blueprint

**`demo-order/blueprint.yaml`** or in-code:

```
# demo-order:1.0.0

[slices]
org.pragmatica-lite.aether.demo:inventory-service:0.1.0 instances=1
org.pragmatica-lite.aether.demo:pricing-service:0.1.0 instances=1
org.pragmatica-lite.aether.demo:place-order:0.1.0 instances=2
org.pragmatica-lite.aether.demo:get-order-status:0.1.0 instances=2
org.pragmatica-lite.aether.demo:cancel-order:0.1.0 instances=1

[routing:http]
POST:/api/orders => place-order:placeOrder(body)
GET:/api/orders/{orderId} => get-order-status:getOrderStatus(orderId)
DELETE:/api/orders/{orderId} => cancel-order:cancelOrder(orderId)
```

---

## Implementation Order

1. **HTTP Router** (Part 1) - enables external access
2. **order-domain** - shared value objects
3. **inventory-service** - first service slice
4. **pricing-service** - second service slice
5. **place-order** - main use case with inter-slice calls
6. **get-order-status** - simple lookup
7. **cancel-order** - calls inventory for stock release
8. **Integration test** - end-to-end

---

## Mock Data Summary

| Products | Stock | Price |
|----------|-------|-------|
| PROD-ABC123 | 100 | $29.99 |
| PROD-DEF456 | 50 | $49.99 |
| PROD-GHI789 | 25 | $99.99 |

| Discount Codes | Rate |
|----------------|------|
| SAVE10 | 10% |
| SAVE20 | 20% |

---

## Reference Files

- `slice-api/src/main/java/org/pragmatica/aether/slice/Slice.java` - Slice interface
- `slice-api/src/main/java/org/pragmatica/aether/slice/SliceMethod.java` - Method registration
- `node/src/main/java/org/pragmatica/aether/invoke/SliceInvoker.java` - Inter-slice calls
- `node/src/main/java/org/pragmatica/aether/api/ManagementServer.java` - Netty HTTP pattern
- `slice/src/main/java/org/pragmatica/aether/routing/Route.java` - Route definition
- `slice/src/main/java/org/pragmatica/aether/routing/BindingSource.java` - Binding types
- `example-slice/pom.xml` - Module POM pattern
