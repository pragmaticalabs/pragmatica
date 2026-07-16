package org.pragmatica.http.routing;

import java.util.stream.Stream;

public interface RouteSource {
    Stream<Route<?>> routes();

    /// The API version registry for this route source (#198 §6.4).
    /// Unversioned sources (the default) expose [SliceVersionRegistry#UNVERSIONED]; a generated
    /// versioned `{Slice}Routes` overrides this to expose the slice's `apiPrefix`, declared version
    /// set, default version, header-mode flag, and per-version deprecation/sunset metadata.
    ///
    /// @return the version registry, or [SliceVersionRegistry#UNVERSIONED] when unversioned
    default SliceVersionRegistry versionRegistry() {
        return SliceVersionRegistry.UNVERSIONED;
    }

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
