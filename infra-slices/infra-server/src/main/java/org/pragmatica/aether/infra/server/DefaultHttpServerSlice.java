package org.pragmatica.aether.infra.server;

import org.pragmatica.http.CommonContentType;
import org.pragmatica.http.ContentCategory;
import org.pragmatica.http.ContentType;
import org.pragmatica.http.routing.CommonContentTypes;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.http.routing.HttpStatus;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.ProblemDetail;
import org.pragmatica.http.routing.RequestContextImpl;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.HttpServer;
import org.pragmatica.http.server.HttpServerConfig;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Default implementation of HttpServerSlice using pragmatica-lite's NettyHttpServer
 * and aether's http-routing module for route handling.
 */
final class DefaultHttpServerSlice implements HttpServerSlice {
    private static final Logger log = LoggerFactory.getLogger(DefaultHttpServerSlice.class);
    private static final ContentType PROBLEM_JSON_CONTENT_TYPE = ContentType.contentType("application/problem+json; charset=UTF-8",
                                                                                         ContentCategory.JSON);

    private final HttpServerSliceConfig config;
    private final RequestRouter router;
    private final JsonCodec jsonCodec;
    private final int routeCount;
    private final AtomicReference<HttpServer> serverRef = new AtomicReference<>();
    private final AtomicBoolean running = new AtomicBoolean(false);

    private DefaultHttpServerSlice(HttpServerSliceConfig config,
                                   RequestRouter router,
                                   JsonCodec jsonCodec,
                                   int routeCount) {
        this.config = config;
        this.router = router;
        this.jsonCodec = jsonCodec;
        this.routeCount = routeCount;
    }

    static DefaultHttpServerSlice defaultHttpServerSlice(HttpServerSliceConfig config, Stream<RouteSource> routes) {
        return defaultHttpServerSlice(config, routes, JsonCodecAdapter.defaultCodec());
    }

    static DefaultHttpServerSlice defaultHttpServerSlice(HttpServerSliceConfig config,
                                                         Stream<RouteSource> routes,
                                                         JsonCodec jsonCodec) {
        var routeList = routes.toList();
        var routeCount = (int) routeList.stream()
                                       .flatMap(RouteSource::routes)
                                       .count();
        var router = RequestRouter.with(routeList.stream());
        return new DefaultHttpServerSlice(config, router, jsonCodec, routeCount);
    }

    @Override
    public HttpServerSliceConfig config() {
        return config;
    }

    @Override
    public Option<Integer> boundPort() {
        return Option.option(serverRef.get())
                     .map(HttpServer::port);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int routeCount() {
        return routeCount;
    }

    @Override
    public Promise<Unit> start() {
        if (running.compareAndSet(false, true)) {
            log.info("Starting HTTP server '{}' on port {} with {} routes", config.name(), config.port(), routeCount);
            var serverConfig = HttpServerConfig.httpServerConfig(config.name(),
                                                                 config.port())
                                               .withMaxContentLength(config.maxContentLength());
            return HttpServer.httpServer(serverConfig, this::handleRequest)
                             .map(this::onServerStarted)
                             .onFailure(this::onStartFailed);
        }
        return Promise.success(Unit.unit());
    }

    private Unit onServerStarted(HttpServer server) {
        serverRef.set(server);
        router.print();
        log.info("HTTP server '{}' started on port {}", config.name(), server.port());
        return Unit.unit();
    }

    private void onStartFailed(org.pragmatica.lang.Cause cause) {
        running.set(false);
        log.error("Failed to start HTTP server '{}': {}", config.name(), cause.message());
    }

    @Override
    public Promise<Unit> stop() {
        if (running.compareAndSet(true, false)) {
            return Option.option(serverRef.getAndSet(null))
                         .fold(() -> Promise.success(Unit.unit()),
                               this::stopServer);
        }
        return Promise.success(Unit.unit());
    }

    private Promise<Unit> stopServer(HttpServer server) {
        log.info("Stopping HTTP server '{}' on port {}", config.name(), server.port());
        return server.stop()
                     .onSuccess(this::onServerStopped);
    }

    @SuppressWarnings("unused")
    private void onServerStopped(Unit unit) {
        log.info("HTTP server '{}' stopped", config.name());
    }

    /**
     * Handles incoming HTTP requests by routing them to appropriate handlers.
     */
    private void handleRequest(RequestContext request, ResponseWriter writer) {
        var method = toRoutingMethod(request.method());
        var path = request.path();
        router.findRoute(method, path)
              .onPresent(route -> invokeRoute(route, request, writer))
              .onEmpty(() -> handleNotFound(request, writer));
    }

    private void invokeRoute(Route< ?> route, RequestContext request, ResponseWriter writer) {
        var nettyRequest = createNettyRequest(request);
        var routingContext = RequestContextImpl.requestContext(nettyRequest, route, jsonCodec, request.requestId());
        route.handler()
             .handle(routingContext)
             .onSuccess(result -> writeSuccessResponse(result, route, writer))
             .onFailure(cause -> writeErrorResponse(cause,
                                                    request.path(),
                                                    request.requestId(),
                                                    writer));
    }

    private DefaultFullHttpRequest createNettyRequest(RequestContext request) {
        var method = io.netty.handler.codec.http.HttpMethod.valueOf(request.method()
                                                                           .name());
        var content = Unpooled.wrappedBuffer(request.body());
        var uri = buildUriWithQueryParams(request);
        var nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, method, uri, content);
        copyHeaders(request.headers()
                           .asMap(),
                    nettyRequest);
        return nettyRequest;
    }

    private void copyHeaders(Map<String, List<String>> headers, DefaultFullHttpRequest nettyRequest) {
        headers.forEach((name, values) -> addHeaderValues(name, values, nettyRequest));
    }

    private void addHeaderValues(String name, List<String> values, DefaultFullHttpRequest nettyRequest) {
        values.forEach(value -> nettyRequest.headers()
                                            .add(name, value));
    }

    private String buildUriWithQueryParams(RequestContext request) {
        var queryParams = request.queryParams();
        if (queryParams.asMap()
                       .isEmpty()) {
            return request.path();
        }
        var sb = new StringBuilder(request.path());
        sb.append('?');
        var first = true;
        for (var entry : queryParams.asMap()
                                    .entrySet()) {
            for (var value : entry.getValue()) {
                if (!first) {
                    sb.append('&');
                }
                first = false;
                sb.append(entry.getKey())
                  .append('=')
                  .append(value);
            }
        }
        return sb.toString();
    }

    private void writeSuccessResponse(Object result, Route< ?> route, ResponseWriter writer) {
        var contentType = route.contentType();
        var pragmaticaContentType = toPragmaticaContentType(contentType);
        Option.option(result)
              .onEmpty(() -> writeNoContent(writer, pragmaticaContentType))
              .onPresent(r -> writeResult(r, contentType, pragmaticaContentType, writer));
    }

    private void writeNoContent(ResponseWriter writer, org.pragmatica.http.ContentType contentType) {
        writer.write(org.pragmatica.http.HttpStatus.NO_CONTENT, new byte[0], contentType);
    }

    private void writeResult(Object result,
                             org.pragmatica.http.routing.ContentType routeContentType,
                             org.pragmatica.http.ContentType pragmaticaContentType,
                             ResponseWriter writer) {
        if (result instanceof HttpStatus httpStatus) {
            writeHttpStatusResult(httpStatus, pragmaticaContentType, writer);
        } else {
            writeBodyResult(result, routeContentType, pragmaticaContentType, writer);
        }
    }

    private void writeHttpStatusResult(HttpStatus httpStatus,
                                       org.pragmatica.http.ContentType contentType,
                                       ResponseWriter writer) {
        var status = toPragmaticaStatus(httpStatus.code());
        var body = httpStatus.message()
                             .getBytes(StandardCharsets.UTF_8);
        writer.write(status, body, contentType);
    }

    private void writeBodyResult(Object result,
                                 org.pragmatica.http.routing.ContentType routeContentType,
                                 org.pragmatica.http.ContentType pragmaticaContentType,
                                 ResponseWriter writer) {
        var body = serializeResult(result, routeContentType);
        writer.write(org.pragmatica.http.HttpStatus.OK, body, pragmaticaContentType);
    }

    private byte[] serializeResult(Object result, org.pragmatica.http.routing.ContentType contentType) {
        if (contentType.category() == org.pragmatica.http.routing.ContentCategory.JSON) {
            return serializeToBytes(result);
        }
        if (result instanceof String str) {
            return str.getBytes(StandardCharsets.UTF_8);
        }
        if (result instanceof byte[] bytes) {
            return bytes;
        }
        return serializeToBytes(result);
    }

    private void writeErrorResponse(org.pragmatica.lang.Cause cause,
                                    String path,
                                    String requestId,
                                    ResponseWriter writer) {
        var problemDetail = createProblemDetail(cause, path, requestId);
        var status = toPragmaticaStatus(problemDetail.status());
        var body = serializeToBytes(problemDetail);
        writer.write(status, body, PROBLEM_JSON_CONTENT_TYPE);
    }

    private ProblemDetail createProblemDetail(org.pragmatica.lang.Cause cause, String path, String requestId) {
        if (cause instanceof org.pragmatica.http.routing.HttpError httpError) {
            return ProblemDetail.fromHttpError(httpError, path, requestId);
        }
        return ProblemDetail.fromCause(cause, path, requestId);
    }

    private void handleNotFound(RequestContext request, ResponseWriter writer) {
        log.debug("No route found for {} {}", request.method(), request.path());
        var problemDetail = ProblemDetail.problemDetail(HttpStatus.NOT_FOUND,
                                                        "No route found for " + request.method() + " " + request.path(),
                                                        request.path(),
                                                        request.requestId());
        var body = serializeToBytes(problemDetail);
        writer.write(org.pragmatica.http.HttpStatus.NOT_FOUND, body, PROBLEM_JSON_CONTENT_TYPE);
    }

    private HttpMethod toRoutingMethod(org.pragmatica.http.HttpMethod method) {
        return switch (method) {
            case GET -> HttpMethod.GET;
            case POST -> HttpMethod.POST;
            case PUT -> HttpMethod.PUT;
            case DELETE -> HttpMethod.DELETE;
            case PATCH -> HttpMethod.PATCH;
            case HEAD -> HttpMethod.HEAD;
            case OPTIONS -> HttpMethod.OPTIONS;
            case TRACE -> HttpMethod.TRACE;
            case CONNECT -> HttpMethod.CONNECT;
        };
    }

    private org.pragmatica.http.ContentType toPragmaticaContentType(org.pragmatica.http.routing.ContentType contentType) {
        return switch (contentType.category()) {
            case JSON -> CommonContentType.APPLICATION_JSON;
            case HTML -> CommonContentType.TEXT_HTML;
            case PLAIN_TEXT -> CommonContentType.TEXT_PLAIN;
            case BINARY -> CommonContentType.APPLICATION_OCTET_STREAM;
        };
    }

    private byte[] serializeToBytes(Object value) {
        return jsonCodec.serialize(value)
                        .onFailure(cause -> log.debug("JSON serialization failed: {}",
                                                      cause.message()))
                        .fold(_ -> "{}".getBytes(StandardCharsets.UTF_8),
                              ByteBufUtil::getBytes);
    }

    private org.pragmatica.http.HttpStatus toPragmaticaStatus(int code) {
        return switch (code) {
            case 200 -> org.pragmatica.http.HttpStatus.OK;
            case 201 -> org.pragmatica.http.HttpStatus.CREATED;
            case 202 -> org.pragmatica.http.HttpStatus.ACCEPTED;
            case 204 -> org.pragmatica.http.HttpStatus.NO_CONTENT;
            case 301 -> org.pragmatica.http.HttpStatus.MOVED_PERMANENTLY;
            case 302 -> org.pragmatica.http.HttpStatus.FOUND;
            case 304 -> org.pragmatica.http.HttpStatus.NOT_MODIFIED;
            case 307 -> org.pragmatica.http.HttpStatus.TEMPORARY_REDIRECT;
            case 308 -> org.pragmatica.http.HttpStatus.PERMANENT_REDIRECT;
            case 400 -> org.pragmatica.http.HttpStatus.BAD_REQUEST;
            case 401 -> org.pragmatica.http.HttpStatus.UNAUTHORIZED;
            case 403 -> org.pragmatica.http.HttpStatus.FORBIDDEN;
            case 404 -> org.pragmatica.http.HttpStatus.NOT_FOUND;
            case 405 -> org.pragmatica.http.HttpStatus.METHOD_NOT_ALLOWED;
            case 409 -> org.pragmatica.http.HttpStatus.CONFLICT;
            case 422 -> org.pragmatica.http.HttpStatus.UNPROCESSABLE_ENTITY;
            case 429 -> org.pragmatica.http.HttpStatus.TOO_MANY_REQUESTS;
            case 501 -> org.pragmatica.http.HttpStatus.NOT_IMPLEMENTED;
            case 502 -> org.pragmatica.http.HttpStatus.BAD_GATEWAY;
            case 503 -> org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE;
            case 504 -> org.pragmatica.http.HttpStatus.GATEWAY_TIMEOUT;
            default -> org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR;
        };
    }
}
