package org.pragmatica.http.routing;

import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.QueryParams;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.type.TypeToken;

import java.util.List;
import java.util.Map;

import io.netty.handler.codec.http.DefaultHttpHeadersFactory;
import io.netty.handler.codec.http.HttpHeaders;

/// Lightweight, Netty-free [RequestContext] used by router tests to exercise path-parameter
/// extraction (`matchPath`/`pathParam`) without standing up an HTTP server. The raw trailing path
/// segments are split here exactly as the production [RequestContext.RequestContextImpl#initPathParams]
/// does, so the spacer-skipping extraction contract can be validated at the interface level.
record TestRequestContext(Route<?> route, String requestPath) implements RequestContext {
    static TestRequestContext of(Route<?> route, String requestPath) {
        return new TestRequestContext(route, PathUtils.normalize(requestPath));
    }

    @Override
    public List<String> pathParams() {
        var remainder = requestPath.substring(route.path()
                                                   .length());
        var stripped = remainder.startsWith("/")
                       ? remainder.substring(1)
                       : remainder;
        if (stripped.isEmpty()) {
            return List.of();
        }
        var elements = stripped.split("/", 1024);
        return elements[elements.length - 1].isEmpty()
               ? List.of(elements)
                     .subList(0, elements.length - 1)
               : List.of(elements);
    }

    @Override
    public String path() {
        return requestPath;
    }

    @Override
    public String requestId() {
        return "req_test";
    }

    @Override
    public HttpMethod method() {
        return route.method();
    }

    @Override
    public Headers headers() {
        return Headers.headers(Map.of());
    }

    @Override
    public QueryParams queryParams() {
        return QueryParams.queryParams(Map.of());
    }

    @Override
    public byte[] body() {
        return new byte[0];
    }

    @Override
    public <T> Result<T> fromJson(TypeToken<T> literal) {
        return HttpStatus.NOT_FOUND.with("Body parsing unsupported in test context")
                                   .result();
    }

    @Override
    public HttpHeaders responseHeaders() {
        return DefaultHttpHeadersFactory.headersFactory()
                                        .newHeaders();
    }
}
