package org.pragmatica.aether.http.adapter;

import org.pragmatica.aether.http.adapter.impl.SliceRouterImpl;
import org.pragmatica.aether.http.handler.HttpRequestContext;
import org.pragmatica.aether.http.handler.HttpResponseData;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.json.JsonMapper;
import org.pragmatica.lang.Promise;

/**
 * Request router that processes HTTP requests using http-routing routes.
 * <p>
 * Bridges http-handler-api types (HttpRequestContext, HttpResponseData) with
 * http-routing DSL (Route, Handler, RequestContext).
 * <p>
 * Usage:
 * <pre>{@code
 * var router = SliceRouter.sliceRouter(
 *     Route.in("/api").serve(
 *         Route.get("/users/{id}")
 *              .withPath(STRING)
 *              .toJson(id -> userService.findById(id))
 *     ),
 *     ErrorMapper.defaultMapper(),
 *     JsonMapper.defaultJsonMapper()
 * );
 *
 * router.handle(httpRequestContext)
 *       .onSuccess(response -> sendResponse(response))
 *       .onFailure(cause -> log.error("Failed", cause));
 * }</pre>
 */
public interface SliceRouter {
    /**
     * Process an HTTP request and produce a response.
     *
     * @param request the HTTP request context
     * @return promise of HTTP response data
     */
    Promise<HttpResponseData> handle(HttpRequestContext request);

    /**
     * Create a SliceRouter with the given routes, error mapper, and JSON mapper.
     *
     * @param routes      route definitions using http-routing DSL
     * @param errorMapper mapper for converting errors to HTTP errors
     * @param jsonMapper  JSON mapper for serialization/deserialization
     * @return configured SliceRouter instance
     */
    static SliceRouter sliceRouter(RouteSource routes,
                                   ErrorMapper errorMapper,
                                   JsonMapper jsonMapper) {
        return SliceRouterImpl.sliceRouterImpl(routes, errorMapper, jsonMapper);
    }
}
