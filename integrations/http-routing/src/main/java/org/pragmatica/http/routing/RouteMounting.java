package org.pragmatica.http.routing;

import java.util.stream.Stream;

/// Shared route-composition seam for the deploy-either-way #198 versioning (§7).
///
/// The generated `{Slice}Routes` exposes un-versioned route paths plus per-route version metadata;
/// composition into the wire-facing path is deferred to registration time so the SAME compiled slice
/// serves path mode OR header mode based on a deploy-time [RouteMountMode]. This utility performs that
/// composition ONCE, and the result is fed to every consumer (the local request router AND the wire
/// route-table extractor) so they agree on the exposed paths.
///
///   - [RouteMountMode.Mode#PATH]: each versioned route → `{apiPrefix}/v{N}/{path}`
///     (via [Route#mountInPathMode]). Byte-identical to the pre-#198-C3b baked form.
///   - [RouteMountMode.Mode#HEADER]: each versioned route → bare `{apiPrefix}/{path}`
///     (via [Route#mountInHeaderMode]); versions share a path and are version-dispatched.
///
/// Unversioned routes pass through unchanged in either mode.
public final class RouteMounting {
    private RouteMounting() {}

    /// Compose a slice's routes for the given detection mode (#198 §7), preserving the source's
    /// [SliceVersionRegistry] so version-aware consumers (the header-mode dispatcher) keep access to
    /// `requireVersionHeader`/`defaultVersion`/per-version metadata.
    ///
    /// @param source the generated route source (un-versioned paths + version metadata)
    /// @param mode   the deploy-time detection mode
    /// @return a route source whose paths are mounted per the mode, with the registry preserved
    public static RouteSource compose(RouteSource source, RouteMountMode mode) {
        return new MountedRouteSource(source, mode);
    }

    private record MountedRouteSource(RouteSource source, RouteMountMode mode) implements RouteSource {
        @Override
        public Stream<Route<?>> routes() {
            var apiPrefix = source.versionRegistry().apiPrefix();

            return mode.isHeaderMode()
                   ? source.routes().map(route -> Route.mountInHeaderMode(route, apiPrefix))
                   : source.routes().map(route -> Route.mountInPathMode(route, apiPrefix));
        }

        @Override
        public SliceVersionRegistry versionRegistry() {
            return source.versionRegistry();
        }
    }
}
