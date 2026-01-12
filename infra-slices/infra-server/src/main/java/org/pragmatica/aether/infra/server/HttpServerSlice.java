package org.pragmatica.aether.infra.server;

import org.pragmatica.aether.slice.Slice;
import org.pragmatica.aether.slice.SliceMethod;
import org.pragmatica.http.routing.JsonCodec;
import org.pragmatica.http.routing.RouteSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import java.util.List;
import java.util.stream.Stream;

/**
 * HTTP server infrastructure slice providing inbound HTTP operations.
 * <p>
 * Wraps pragmatica-lite's NettyHttpServer with Slice lifecycle management
 * and integrates with the http-routing module for route handling.
 * <p>
 * Example usage:
 * <pre>{@code
 * var routes = Stream.of(
 *     Route.get("/health")
 *          .withoutParameters()
 *          .toJson(() -> Map.of("status", "healthy")),
 *     Route.get("/users/{id}")
 *          .withPath(PathParameter.STRING)
 *          .toJson(userId -> userService.findById(userId))
 * );
 *
 * var server = HttpServerSlice.httpServerSlice(
 *     HttpServerSliceConfig.httpServerSliceConfig(8080).unwrap(),
 *     routes
 * );
 *
 * server.start().await();
 * }</pre>
 */
public interface HttpServerSlice extends Slice {
    /**
     * Returns the current server configuration.
     *
     * @return Server configuration
     */
    HttpServerSliceConfig config();

    /**
     * Returns the port the server is bound to.
     * Only valid after server is started.
     *
     * @return Bound port, or empty if not started
     */
    Option<Integer> boundPort();

    /**
     * Checks if the server is currently running.
     *
     * @return true if the server is running
     */
    boolean isRunning();

    /**
     * Returns the number of registered routes.
     *
     * @return Route count
     */
    int routeCount();

    /**
     * Factory method for creating an HTTP server slice with routes.
     *
     * @param config Configuration for the server
     * @param routes Stream of routes to register
     * @return HttpServerSlice instance
     */
    static HttpServerSlice httpServerSlice(HttpServerSliceConfig config, Stream<RouteSource> routes) {
        return DefaultHttpServerSlice.defaultHttpServerSlice(config, routes);
    }

    /**
     * Factory method for creating an HTTP server slice with routes and custom JSON codec.
     *
     * @param config    Configuration for the server
     * @param routes    Stream of routes to register
     * @param jsonCodec Custom JSON codec for request/response serialization
     * @return HttpServerSlice instance
     */
    static HttpServerSlice httpServerSlice(HttpServerSliceConfig config,
                                           Stream<RouteSource> routes,
                                           JsonCodec jsonCodec) {
        return DefaultHttpServerSlice.defaultHttpServerSlice(config, routes, jsonCodec);
    }

    /**
     * Factory method for creating an HTTP server slice with vararg routes.
     *
     * @param config Configuration for the server
     * @param routes Routes to register
     * @return HttpServerSlice instance
     */
    static HttpServerSlice httpServerSlice(HttpServerSliceConfig config, RouteSource... routes) {
        return DefaultHttpServerSlice.defaultHttpServerSlice(config, Stream.of(routes));
    }

    /**
     * Factory method with Result-based configuration.
     *
     * @param configResult Result containing configuration
     * @param routes       Stream of routes to register
     * @return Result containing HttpServerSlice or configuration error
     */
    static Result<HttpServerSlice> httpServerSlice(Result<HttpServerSliceConfig> configResult,
                                                   Stream<RouteSource> routes) {
        return configResult.map(config -> httpServerSlice(config, routes));
    }

    @Override
    default List<SliceMethod< ?, ?>> methods() {
        return List.of();
    }
}
