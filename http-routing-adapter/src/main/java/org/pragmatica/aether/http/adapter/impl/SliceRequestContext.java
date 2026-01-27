package org.pragmatica.aether.http.adapter.impl;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.security.SecurityContext;
import org.pragmatica.http.routing.PathUtils;
import org.pragmatica.http.routing.RequestContext;
import org.pragmatica.http.routing.Route;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;

import static org.pragmatica.http.routing.Utils.lazy;
import static org.pragmatica.http.routing.Utils.value;

/**
 * RequestContext implementation that wraps HttpRequestContext.
 * <p>
 * Provides path parameter extraction, JSON deserialization, and other
 * RequestContext features without requiring a Netty FullHttpRequest.
 */
public final class SliceRequestContext implements RequestContext {
    private static final int PATH_PARAM_LIMIT = 1024;

    private final HttpRequestContext httpContext;
    private final Route<?> route;
    private final JsonMapper jsonMapper;
    private final ByteBuf bodyBuf;
    private final HttpHeaders responseHeaders;

    private Supplier<List<String>> pathParamsSupplier = lazy(() -> pathParamsSupplier = value(initPathParams()));
    private Supplier<Map<String, String>> headersSupplier = lazy(() -> headersSupplier = value(initRequestHeaders()));

    private SliceRequestContext(HttpRequestContext httpContext,
                                Route<?> route,
                                JsonMapper jsonMapper) {
        this.httpContext = httpContext;
        this.route = route;
        this.jsonMapper = jsonMapper;
        this.bodyBuf = Unpooled.wrappedBuffer(httpContext.body());
        this.responseHeaders = DefaultHttpHeadersFactory.headersFactory()
                                                        .withCombiningHeaders(true)
                                                        .newHeaders();
    }

    public static SliceRequestContext sliceRequestContext(HttpRequestContext httpContext,
                                                          Route<?> route,
                                                          JsonMapper jsonMapper) {
        return new SliceRequestContext(httpContext, route, jsonMapper);
    }

    /**
     * Access the original HttpRequestContext for security checks.
     */
    public HttpRequestContext original() {
        return httpContext;
    }

    /**
     * Convenience method to access security context.
     */
    public SecurityContext security() {
        return httpContext.security();
    }

    @Override
    public Route<?> route() {
        return route;
    }

    @Override
    public String requestId() {
        return httpContext.requestId();
    }

    @Override
    public ByteBuf body() {
        return bodyBuf;
    }

    @Override
    public String bodyAsString() {
        return new String(httpContext.body(), StandardCharsets.UTF_8);
    }

    @Override
    public <T> Result<T> fromJson(TypeToken<T> literal) {
        return jsonMapper.readBytes(httpContext.body(), literal);
    }

    @Override
    public List<String> pathParams() {
        return pathParamsSupplier.get();
    }

    @Override
    public Map<String, List<String>> queryParams() {
        return httpContext.queryParams();
    }

    @Override
    public Map<String, String> requestHeaders() {
        return headersSupplier.get();
    }

    @Override
    public HttpHeaders responseHeaders() {
        return responseHeaders;
    }

    private List<String> initPathParams() {
        var normalizedPath = PathUtils.normalize(httpContext.path());
        var routePath = route.path();
        if (normalizedPath.length() <= routePath.length()) {
            return List.of();
        }
        var remainder = normalizedPath.substring(routePath.length());
        var elements = remainder.split("/", PATH_PARAM_LIMIT);
        if (elements.length == 0) {
            return List.of();
        }
        // Remove trailing empty element if path ends with /
        if (elements[elements.length - 1].isEmpty()) {
            return List.of(elements)
                       .subList(0, elements.length - 1);
        }
        return List.of(elements);
    }

    private Map<String, String> initRequestHeaders() {
        var headers = new java.util.HashMap<String, String>();
        httpContext.headers()
                   .forEach((key, values) -> {
                       if (!values.isEmpty()) {
                           headers.put(key,
                                       values.getFirst());
                       }
                   });
        return Map.copyOf(headers);
    }
}
