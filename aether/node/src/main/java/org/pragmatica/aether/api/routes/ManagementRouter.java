package org.pragmatica.aether.api.routes;

import org.pragmatica.aether.management.route.ManagementRoute;
import org.pragmatica.aether.management.route.MatchedRoute;
import org.pragmatica.aether.management.route.RouteTarget;
import org.pragmatica.aether.slice.delegation.TaskGroup;
import org.pragmatica.aether.slice.delegation.TaskGroupAssignmentRegistry;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.http.routing.ContentType;
import org.pragmatica.http.routing.HttpMethod;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.JsonCodecAdapter;
import org.pragmatica.http.routing.PathUtils;
import org.pragmatica.http.routing.RequestRouter;
import org.pragmatica.http.routing.Route;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.http.server.RequestContext;
import org.pragmatica.http.server.ResponseWriter;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;


/// Router that bridges http-server RequestContext/ResponseWriter with http-routing Route DSL.
///
/// Adapts the pragmatica-lite http-server infrastructure to work with RouteSource-based routes.
///
/// Includes a thin disposition layer that consults the compile-time [ManagementRoute] registry
/// via [ManagementRoute#match] before dispatching. The match result is used for logging and
/// target-aware diagnostics: if the route's [RouteTarget] is a [RouteTarget.TaskGroupTarget],
/// [#dispatchLocallyIfOwner] consults the [TaskGroupAssignmentRegistry] and refuses to dispatch
/// locally when this node is not the current owner of the task group, returning a clearer
/// 503/SERVICE_UNAVAILABLE error instead of the legacy NOT_LEADER message.
public final class ManagementRouter {
    private static final Logger log = LoggerFactory.getLogger(ManagementRouter.class);

    private final RequestRouter requestRouter;
    private final JsonCodec jsonCodec;
    private final NodeId selfNodeId;
    private final Supplier<TaskGroupAssignmentRegistry> taskGroupAssignmentRegistrySupplier;

    private ManagementRouter(RequestRouter requestRouter,
                             JsonCodec jsonCodec,
                             NodeId selfNodeId,
                             Supplier<TaskGroupAssignmentRegistry> taskGroupAssignmentRegistrySupplier) {
        this.requestRouter = requestRouter;
        this.jsonCodec = jsonCodec;
        this.selfNodeId = selfNodeId;
        this.taskGroupAssignmentRegistrySupplier = taskGroupAssignmentRegistrySupplier;
    }

    public static ManagementRouter managementRouter(NodeId selfNodeId,
                                                    Supplier<TaskGroupAssignmentRegistry> taskGroupAssignmentRegistrySupplier,
                                                    RouteSource... sources) {
        return new ManagementRouter(RequestRouter.with(sources),
                                    JsonCodecAdapter.defaultCodec(),
                                    selfNodeId,
                                    taskGroupAssignmentRegistrySupplier);
    }

    public boolean handle(RequestContext ctx, ResponseWriter response) {
        return parseMethod(ctx.method().name()).flatMap(method -> dispatch(method, ctx, response)).or(false);
    }

    private Option<Boolean> dispatch(HttpMethod method, RequestContext ctx, ResponseWriter response) {
        var matchResult = ManagementRoute.match(method, ctx.path());
        return requestRouter.findRoute(method,
                                       ctx.path())
        .map(route -> {
                                          dispatchMatchedRoute(matchResult, route, ctx, response);
                                          return true;
                                      });
    }

    private void dispatchMatchedRoute(Result<MatchedRoute> matchResult,
                                      Route<?> route,
                                      RequestContext ctx,
                                      ResponseWriter response) {
        matchResult.onSuccess(matched -> logRouteDispatch(matched))
                             .onFailure(cause -> log.debug("No ManagementRoute match for {} {} — dispatching via RequestRouter fallback: {}",
                                                           ctx.method().name(),
                                                           ctx.path(),
                                                           cause.message()));
        if (matchResult.isSuccess() && matchResult.unwrap().route()
                                                         .target() instanceof RouteTarget.TaskGroupTarget tgt) {
            dispatchLocallyIfOwner(tgt.group(), route, ctx, response);
            return;
        }
        handleRoute(route, ctx, response);
    }

    private static void logRouteDispatch(MatchedRoute matched) {
        log.trace("ManagementRoute matched: {} target={}",
                  matched.route().name(),
                  matched.route().target());
    }

    private void dispatchLocallyIfOwner(TaskGroup group, Route<?> route, RequestContext ctx, ResponseWriter response) {
        var registry = taskGroupAssignmentRegistrySupplier.get();
        var ownerOpt = registry.ownerFor(group).onFailure(cause -> {
                                                              log.debug("Task group {} has no current owner for management route {} {}: {}",
                                                                        group,
                                                                        ctx.method().name(),
                                                                        ctx.path(),
                                                                        cause.message());
                                                              response.error(org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE,
                                                                             cause.message());
                                                          })
                                        .option();
        if (ownerOpt.isEmpty()) {return;}
        var owner = ownerOpt.unwrap();
        if (!owner.equals(selfNodeId)) {
            log.debug("Task group {} owned by {} (not self {}) — refusing local dispatch of {} {}",
                      group,
                      owner,
                      selfNodeId,
                      ctx.method().name(),
                      ctx.path());
            response.error(org.pragmatica.http.HttpStatus.SERVICE_UNAVAILABLE,
                           "Task group " + group + " is currently owned by " + owner + ", not this node");
            return;
        }
        log.trace("Local dispatch of task-group-targeted management route {} (owner {})", ctx.path(), group);
        handleRoute(route, ctx, response);
    }

    private void handleRoute(Route<?> route, RequestContext serverCtx, ResponseWriter response) {
        var routingCtx = adaptContext(serverCtx, route);
        route.handler().handle(routingCtx)
                     .onFailure(cause -> writeError(response, cause))
                     .onSuccess(value -> writeSuccess(value,
                                                      route.contentType(),
                                                      response));
    }

    private void writeError(ResponseWriter response, org.pragmatica.lang.Cause cause) {
        var status = resolveHttpStatus(cause);
        response.error(status, cause.message());
    }

    private static org.pragmatica.http.HttpStatus resolveHttpStatus(org.pragmatica.lang.Cause cause) {
        if (cause instanceof org.pragmatica.http.routing.HttpError httpError) {return findByCode(httpError.status()
                                                                                                                 .code());}
        return org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private static org.pragmatica.http.HttpStatus findByCode(int code) {
        for (var status : org.pragmatica.http.HttpStatus.values()) {if (status.code() == code) {return status;}}
        return org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR;
    }

    private void writeSuccess(Object value, ContentType contentType, ResponseWriter response) {
        if (value instanceof Option<?> opt && opt.isEmpty()) {
            response.noContent();
            return;
        }
        if (isTextContent(contentType)) {
            response.okText(value.toString());
            return;
        }
        if (value instanceof String json) {
            response.ok(json);
            return;
        }
        writeJson(value, response);
    }

    private void writeJson(Object value, ResponseWriter response) {
        jsonCodec.serialize(value).onFailure(_ -> response.error(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR,
                                                                 "Serialization failed"))
                           .onSuccess(byteBuf -> extractAndRelease(byteBuf, response));
    }

    private void extractAndRelease(io.netty.buffer.ByteBuf byteBuf, ResponseWriter response) {
        try {
            var bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            response.ok(new String(bytes, StandardCharsets.UTF_8));
        } finally {
            byteBuf.release();
        }
    }

    private org.pragmatica.http.routing.RequestContext adaptContext(RequestContext serverCtx, Route<?> route) {
        return ServerRequestContextAdapter.serverRequestContextAdapter(serverCtx, route, jsonCodec);
    }

    private static Option<HttpMethod> parseMethod(String method) {
        return Result.lift(org.pragmatica.lang.utils.Causes::fromThrowable,
                           () -> HttpMethod.valueOf(method.toUpperCase()))
        .option();
    }

    private static boolean isTextContent(ContentType contentType) {
        var headerText = contentType.headerText().toLowerCase();
        return headerText.startsWith("text/") || headerText.contains("plain");
    }

    private record ServerRequestContextAdapter(RequestContext serverCtx,
                                               Route<?> route,
                                               JsonCodec jsonCodec,
                                               ByteBuf bodyBuf,
                                               HttpHeaders responseHeaders,
                                               AtomicReference<List<String>> pathParamsRef) implements org.pragmatica.http.routing.RequestContext {
        static ServerRequestContextAdapter serverRequestContextAdapter(RequestContext serverCtx,
                                                                       Route<?> route,
                                                                       JsonCodec jsonCodec) {
            return new ServerRequestContextAdapter(serverCtx,
                                                   route,
                                                   jsonCodec,
                                                   Unpooled.wrappedBuffer(serverCtx.body()),
                                                   DefaultHttpHeadersFactory.headersFactory().withCombiningHeaders(true)
                                                                                           .newHeaders(),
                                                   new AtomicReference<>());
        }

        @Override public String requestPath() {
            return serverCtx.path();
        }

        @Override public String requestId() {
            return serverCtx.requestId();
        }

        @Override public ByteBuf body() {
            return bodyBuf;
        }

        @Override public String bodyAsString() {
            return serverCtx.bodyAsString();
        }

        @Override public <T> Result<T> fromJson(TypeToken<T> literal) {
            return jsonCodec.deserialize(bodyBuf, literal);
        }

        @Override public List<String> pathParams() {
            var params = pathParamsRef.get();
            if (params == null) {
                params = initPathParams();
                pathParamsRef.set(params);
            }
            return params;
        }

        @Override public Map<String, List<String>> queryParams() {
            return serverCtx.queryParams().asMap()
                                        .entrySet()
                                        .stream()
                                        .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override public Map<String, String> requestHeaders() {
            return serverCtx.headers().asMap()
                                    .entrySet()
                                    .stream()
                                    .collect(Collectors.toMap(Map.Entry::getKey,
                                                              entry -> entry.getValue().isEmpty()
                                                                      ? ""
                                                                      : entry.getValue().getFirst()));
        }

        @Override public HttpHeaders responseHeaders() {
            return responseHeaders;
        }

        private List<String> initPathParams() {
            var normalizedPath = PathUtils.normalize(serverCtx.path());
            var routePath = route.path();
            if (normalizedPath.length() <= routePath.length()) {return List.of();}
            var remainder = normalizedPath.substring(routePath.length());
            if (remainder.startsWith("/")) {remainder = remainder.substring(1);}
            if (remainder.isEmpty()) {return List.of();}
            var elements = remainder.split("/", 1024);
            if (elements[elements.length - 1].isEmpty()) {return List.of(elements).subList(0, elements.length - 1);}
            return List.of(elements);
        }
    }
}
