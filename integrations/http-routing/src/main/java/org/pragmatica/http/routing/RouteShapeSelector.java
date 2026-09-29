package org.pragmatica.http.routing;

import java.util.Arrays;
import java.util.List;

import org.pragmatica.lang.Option;

/// The ONE rule that picks, among routes sharing a base path, the route a request path is served by (#1678).
/// [RequestRouter] dispatches through it, and a node authorizing a request for a route it does not host selects
/// through it too, so the route that is authorized and the route that serves cannot be different siblings.
///
/// Selection is arity-aware: the number of trailing path segments in the request (after the candidates' shared
/// base path) is matched against each candidate's declared [RouteShape#pathParamCount()]. A spacer-bearing route is
/// preferred when its spacers are all present (the more specific match); otherwise the candidate whose arity equals
/// the trailing segment count wins. This prevents a sibling parameter route from shadowing an exact collection route
/// (and vice versa) merely because of registration order.
public final class RouteShapeSelector {
    private RouteShapeSelector() {}

    public static <T extends RouteShape> Option<T> select(List<T> candidates, String inputPath) {
        // A candidate whose handler needs more trailing segments than the request supplies cannot
        // serve it: dispatching anyway reaches the handler and dies at pathParam(), which surfaces
        // as "Unknown request path" instead of the ordinary no-match. Arity counts spacers too, so
        // spacer routes are held to it as well (#764) — and additionally to their spacers being
        // present, so neither the single-candidate shortcut nor the fallback below can hand back a
        // spacer route the path does not name.
        // #1101: a route that declares parameters consumes exactly that many trailing segments — an
        // over-length path is a MISS, never a dispatch of the first N (that dispatch, paired with a
        // weaker prefix authorisation, was a privilege escalation). An arity-0 route keeps its prefix
        // tolerance: `StaticFileRouteSource` registers `route(GET, urlPrefix, …)` and consumes the
        // remainder itself, and an arity-0 handler binds nothing positional.
        var viable = candidates.stream()
                               .filter(route -> arityAdmits(route,
                                                            trailingSegmentCount(route.path(),
                                                                                 inputPath)))
                               .filter(route -> route.spacers()
                                                     .isEmpty() || routeMatchesPath(route, inputPath))
                               .toList();

        if (viable.isEmpty()) {
            return Option.empty();
        }

        if (viable.size() == 1) {
            return Option.some(viable.getFirst());
        }

        var spacerMatch = findMatchingSpacerRoute(viable, inputPath);

        return spacerMatch.isPresent()
               ? spacerMatch
               : findArityMatchingRoute(viable, inputPath);
    }

    private static boolean arityAdmits(RouteShape route, int trailing) {
        return route.pathParamCount() == 0 || route.pathParamCount() == trailing;
    }

    private static <T extends RouteShape> Option<T> findMatchingSpacerRoute(List<T> candidates, String inputPath) {
        return Option.from(candidates.stream()
                                     .filter(route -> !route.spacers()
                                                            .isEmpty())
                                     .filter(route -> routeMatchesPath(route, inputPath))
                                     .findFirst());
    }

    /// Select the spacer-free candidate whose declared path arity equals the request's trailing
    /// segment count, falling back to the first spacer-free candidate. Every spacer route that
    /// reaches this point already matched its spacers and was preferred above, so with no
    /// spacer-free candidate left there is nothing that can serve the path — a miss, not the first
    /// registered route.
    private static <T extends RouteShape> Option<T> findArityMatchingRoute(List<T> candidates, String inputPath) {
        var trailingSegments = trailingSegmentCount(candidates.getFirst()
                                                              .path(),
                                                    inputPath);

        return Option.from(candidates.stream()
                                     .filter(route -> route.spacers()
                                                           .isEmpty())
                                     .filter(route -> route.pathParamCount() == trailingSegments)
                                     .findFirst()).orElse(() -> findFallbackRoute(candidates));
    }

    private static <T extends RouteShape> Option<T> findFallbackRoute(List<T> candidates) {
        return Option.from(candidates.stream().filter(route -> route.spacers()
                                                                    .isEmpty()).findFirst());
    }

    /// Count the trailing path segments of `inputPath` beyond the candidates' shared `basePath`.
    /// `basePath` always carries a trailing slash; `inputPath` may or may not. An empty remainder
    /// (the request is exactly the base path) yields `0`.
    private static int trailingSegmentCount(String basePath, String inputPath) {
        var normalizedInput = inputPath.endsWith("/")
                              ? inputPath
                              : inputPath + "/";

        if (normalizedInput.length() <= basePath.length()) {
            return 0;
        }

        var remainder = normalizedInput.substring(basePath.length());
        var trimmed = remainder.startsWith("/")
                      ? remainder.substring(1)
                      : remainder;
        var stripped = trimmed.endsWith("/")
                       ? trimmed.substring(0, trimmed.length() - 1)
                       : trimmed;

        return stripped.isEmpty()
               ? 0
               : (int) stripped.chars()
                               .filter(c -> c == '/')
                               .count() + 1;
    }

    /// Check if a route matches the input path by verifying all spacers are present.
    private static boolean routeMatchesPath(RouteShape route, String inputPath) {
        var basePath = route.path();

        if (inputPath.length() <= basePath.length()) {
            return route.spacers()
                        .isEmpty();
        }

        var pathElements = extractPathElements(inputPath, basePath);

        return allSpacersPresent(route.spacers(), pathElements);
    }

    private static String[] extractPathElements(String inputPath, String basePath) {
        var remainder = inputPath.substring(basePath.length());

        return remainder.startsWith("/")
               ? remainder.substring(1)
                          .split("/")
               : remainder.split("/");
    }

    private static boolean allSpacersPresent(List<String> spacers, String[] pathElements) {
        return spacers.stream()
                      .allMatch(spacer -> Arrays.stream(pathElements).anyMatch(spacer::equals));
    }
}
