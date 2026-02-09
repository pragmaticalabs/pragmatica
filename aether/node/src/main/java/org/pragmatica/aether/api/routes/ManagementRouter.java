package org.pragmatica.aether.api.routes;

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
import java.util.stream.Collectors;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;

/// Router that bridges http-server RequestContext/ResponseWriter with http-routing Route DSL.
///
/// Adapts the pragmatica-lite http-server infrastructure to work with RouteSource-based routes.
public final class ManagementRouter {
    private final RequestRouter requestRouter;
    private final JsonCodec jsonCodec;

    private ManagementRouter(RequestRouter requestRouter, JsonCodec jsonCodec) {
        this.requestRouter = requestRouter;
        this.jsonCodec = jsonCodec;
    }

    public static ManagementRouter managementRouter(RouteSource... sources) {
        return new ManagementRouter(RequestRouter.with(sources), JsonCodecAdapter.defaultCodec());
    }

    /// Try to handle the request using route-based routing.
    ///
    /// @param ctx      the request context
    /// @param response the response writer
    /// @return true if a matching route was found and handled, false otherwise
    public boolean handle(RequestContext ctx, ResponseWriter response) {
        return parseMethod(ctx.method()
                              .name()).flatMap(method -> requestRouter.findRoute(method,
                                                                                 ctx.path()))
                          .map(route -> {
                              handleRoute(route, ctx, response);
                              return true;
                          })
                          .or(false);
    }

    private void handleRoute(Route<?> route, RequestContext serverCtx, ResponseWriter response) {
        var routingCtx = adaptContext(serverCtx, route);
        route.handler()
             .handle(routingCtx)
             .onFailure(cause -> writeError(response, cause))
             .onSuccess(value -> writeSuccess(value,
                                              route.contentType(),
                                              response));
    }

    private void writeError(ResponseWriter response, org.pragmatica.lang.Cause cause) {
        response.error(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR, cause.message());
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
        writeJson(value, response);
    }

    private void writeJson(Object value, ResponseWriter response) {
        jsonCodec.serialize(value)
                 .onFailure(_ -> response.error(org.pragmatica.http.HttpStatus.INTERNAL_SERVER_ERROR,
                                                "Serialization failed"))
                 .onSuccess(byteBuf -> extractAndRelease(byteBuf, response));
    }

    private void extractAndRelease(io.netty.buffer.ByteBuf byteBuf, ResponseWriter response) {
        try{
            var bytes = new byte[byteBuf.readableBytes()];
            byteBuf.readBytes(bytes);
            response.ok(new String(bytes, StandardCharsets.UTF_8));
        } finally{
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
        var headerText = contentType.headerText()
                                    .toLowerCase();
        return headerText.startsWith("text/") || headerText.contains("plain");
    }

    /// Adapter that wraps http-server RequestContext as http-routing RequestContext.
    private record ServerRequestContextAdapter(RequestContext serverCtx,
                                               Route<?> route,
                                               JsonCodec jsonCodec,
                                               ByteBuf bodyBuf,
                                               HttpHeaders responseHeaders,
                                               AtomicReference<List<String>> pathParamsRef)
    implements org.pragmatica.http.routing.RequestContext {
        static ServerRequestContextAdapter serverRequestContextAdapter(RequestContext serverCtx,
                                                                       Route<?> route,
                                                                       JsonCodec jsonCodec) {
            return new ServerRequestContextAdapter(serverCtx,
                                                   route,
                                                   jsonCodec,
                                                   Unpooled.wrappedBuffer(serverCtx.body()),
                                                   DefaultHttpHeadersFactory.headersFactory()
                                                                            .withCombiningHeaders(true)
                                                                            .newHeaders(),
                                                   new AtomicReference<>());
        }

        @Override
        public String requestPath() {
            return serverCtx.path();
        }

        @Override
        public String requestId() {
            return serverCtx.requestId();
        }

        @Override
        public ByteBuf body() {
            return bodyBuf;
        }

        @Override
        public String bodyAsString() {
            return serverCtx.bodyAsString();
        }

        @Override
        public <T> Result<T> fromJson(TypeToken<T> literal) {
            return jsonCodec.deserialize(bodyBuf, literal);
        }

        @Override
        public List<String> pathParams() {
            var params = pathParamsRef.get();
            if (params == null) {
                params = initPathParams();
                pathParamsRef.set(params);
            }
            return params;
        }

        @Override
        public Map<String, List<String>> queryParams() {
            return serverCtx.queryParams()
                            .asMap()
                            .entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        }

        @Override
        public Map<String, String> requestHeaders() {
            return serverCtx.headers()
                            .asMap()
                            .entrySet()
                            .stream()
                            .collect(Collectors.toMap(Map.Entry::getKey,
                                                      entry -> entry.getValue()
                                                                    .isEmpty()
                                                               ? ""
                                                               : entry.getValue()
                                                                      .getFirst()));
        }

        @Override
        public HttpHeaders responseHeaders() {
            return responseHeaders;
        }

        private List<String> initPathParams() {
            var normalizedPath = PathUtils.normalize(serverCtx.path());
            var routePath = route.path();
            if (normalizedPath.length() <= routePath.length()) {
                return List.of();
            }
            var remainder = normalizedPath.substring(routePath.length());
            // Strip leading slash before splitting
            if (remainder.startsWith("/")) {
                remainder = remainder.substring(1);
            }
            if (remainder.isEmpty()) {
                return List.of();
            }
            var elements = remainder.split("/", 1024);
            // Remove trailing empty element if path ends with /
            if (elements[elements.length - 1].isEmpty()) {
                return List.of(elements)
                           .subList(0, elements.length - 1);
            }
            return List.of(elements);
        }
    }
}
