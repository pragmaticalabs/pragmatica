package org.pragmatica.aether.api;

import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.http.Headers;
import org.pragmatica.http.HttpMethod;
import org.pragmatica.http.QueryParams;
import org.pragmatica.http.server.RequestContext;


/// Adapts an HttpRequestContext (from forwarded binary protocol) to the
/// http-server RequestContext interface used by ManagementRouter.
record ForwardedRequestContext(HttpRequestContext source,
                               HttpMethod httpMethod,
                               Headers headers,
                               QueryParams queryParams) implements RequestContext {
    static ForwardedRequestContext forwardedRequestContext(HttpRequestContext source) {
        return new ForwardedRequestContext(source,
                                           HttpMethod.valueOf(source.method().toUpperCase()),
                                           Headers.headers(source.headers()),
                                           QueryParams.queryParams(source.queryParams()));
    }

    @Override public String requestId() {
        return source.requestId();
    }

    @Override public HttpMethod method() {
        return httpMethod;
    }

    @Override public String path() {
        return source.path();
    }

    @Override public Headers headers() {
        return headers;
    }

    @Override public QueryParams queryParams() {
        return queryParams;
    }

    @Override public byte[] body() {
        return source.body();
    }
}
