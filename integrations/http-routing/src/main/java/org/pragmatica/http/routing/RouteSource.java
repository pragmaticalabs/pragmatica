package org.pragmatica.http.routing;

import java.util.stream.Stream;

public interface RouteSource {
    Stream<Route<?>> routes();

    default RouteSource withPrefix(String prefix) {
        return () -> routes().map(route -> (Route<?>) route.withPrefix(prefix));
    }

    static RouteSource routeSource(RouteSource... routes) {
        return () -> Stream.of(routes)
                           .flatMap(RouteSource::routes);
    }

    @Deprecated(forRemoval = true)
    static RouteSource of(RouteSource... routes) {
        return routeSource(routes);
    }
}
