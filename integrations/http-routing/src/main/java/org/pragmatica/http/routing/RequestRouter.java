package org.pragmatica.http.routing;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.pragmatica.http.HttpMethod;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.pragmatica.lang.Option.option;


public final class RequestRouter {
    private static final Logger log = LoggerFactory.getLogger(RequestRouter.class);

    // Store multiple routes per base path to handle routes with different spacers
    private final Map<HttpMethod, TreeMap<String, List<Route<?>>>> routes;

    private RequestRouter(Map<HttpMethod, TreeMap<String, List<Route<?>>>> routes) {
        this.routes = routes;
    }

    public static RequestRouter with(RouteSource... routes) {
        return with(Stream.of(routes));
    }

    public static RequestRouter with(Stream<RouteSource> routeStream) {
        var routes = new HashMap<HttpMethod, TreeMap<String, List<Route<?>>>>();

        routeStream.flatMap(RouteSource::routes)
                   .forEach(route -> routes.compute(route.method(),
                                                    (_, pathMap) -> collectRoutes(route, pathMap)));

        return new RequestRouter(routes);
    }

    private static TreeMap<String, List<Route<?>>> collectRoutes(Route<?> route,
                                                                 TreeMap<String, List<Route<?>>> pathMap) {
        var map = option(pathMap).or(TreeMap::new);

        map.computeIfAbsent(route.path(), _ -> new ArrayList<>()).add(route);

        return map;
    }

    @Contract
    public void print() {
        if (!log.isInfoEnabled()) {
            return;
        }

        routes.forEach((_, endpoints) -> endpoints.forEach((_, routeList) -> routeList.forEach(route -> log.info("{}",
                                                                                                                 route))));
    }

    public Option<Route<?>> findRoute(HttpMethod method, String inputPath) {
        var path = inputPath + "/";
        var methodRoutes = routes.get(method);

        if (methodRoutes == null) {
            return Option.empty();
        }
        // Walk back through candidate prefixes via descending headMap. floorEntry alone is
        // not sufficient: with sibling routes like `/api/streams/publish/`, `/api/streams/read/`
        // and `/api/streams/`, an input of `/api/streams/test1/` lands on `/api/streams/read/`
        // (alphabetically nearest) but fails isSameOrStartOfPath. We must keep walking to the
        // broader `/api/streams/` entry.
        for (var entry : methodRoutes.headMap(path, true).descendingMap().entrySet()) {
            if (isSameOrStartOfPath(path, entry.getKey())) {
                return selectBestRoute(entry.getValue(), inputPath);
            }
        }

        return Option.empty();
    }

    /// Select the best matching route from a list of routes with the same base path.
    /// This handles routes that differ only in their path arity or spacer parameters.
    ///
    /// Selection is arity-aware: the number of trailing path segments in the request (after the
    /// candidates' shared base path) is matched against each candidate's declared
    /// [Route#pathParamCount()]. A spacer-bearing route is preferred when its spacers are all
    /// present (the more specific match); otherwise the candidate whose arity equals the trailing
    /// segment count wins. This prevents a sibling parameter route from shadowing an exact
    /// collection route (and vice versa) merely because of registration order.
    private Option<Route<?>> selectBestRoute(List<Route<?>> candidates, String inputPath) {
        // A candidate whose handler needs more trailing segments than the request supplies cannot
        // serve it: dispatching anyway reaches the handler and dies at pathParam(), which surfaces
        // as "Unknown request path" instead of the ordinary no-match. Spacer routes carry their own
        // matching and are left to it.
        var viable = candidates.stream()
                               .filter(route -> !route.spacers().isEmpty()
                                                || route.pathParamCount() <= trailingSegmentCount(route.path(),
                                                                                                  inputPath))
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

    private Option<Route<?>> findMatchingSpacerRoute(List<Route<?>> candidates, String inputPath) {
        return Option.from(candidates.stream()
                                     .filter(route -> !route.spacers()
                                                            .isEmpty())
                                     .filter(route -> routeMatchesPath(route, inputPath))
                                     .findFirst());
    }

    /// Select the spacer-free candidate whose declared path arity equals the request's trailing
    /// segment count. Falls back to a no-arity (collection) route, then to the first candidate, so
    /// a match is always returned for a path that reached this point.
    private Option<Route<?>> findArityMatchingRoute(List<Route<?>> candidates, String inputPath) {
        var trailingSegments = trailingSegmentCount(candidates.getFirst().path(),
                                                    inputPath);

        return Option.from(candidates.stream()
                                     .filter(route -> route.spacers()
                                                           .isEmpty())
                                     .filter(route -> route.pathParamCount() == trailingSegments)
                                     .findFirst()).orElse(() -> findFallbackRoute(candidates));
    }

    private Option<Route<?>> findFallbackRoute(List<Route<?>> candidates) {
        var noSpacerRoute = candidates.stream().filter(route -> route.spacers()
                                                                     .isEmpty()).findFirst();

        return Option.some(noSpacerRoute.orElse(candidates.getFirst()));
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
    private boolean routeMatchesPath(Route<?> route, String inputPath) {
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

    private boolean isSameOrStartOfPath(String inputPath, String routePath) {
        return isExactMatch(inputPath, routePath) || isPrefixMatch(inputPath, routePath);
    }

    private static boolean isExactMatch(String inputPath, String routePath) {
        return inputPath.length() == routePath.length() && inputPath.equals(routePath);
    }

    private static boolean isPrefixMatch(String inputPath, String routePath) {
        return inputPath.length() > routePath.length()
               && inputPath.startsWith(routePath)
               && inputPath.charAt(routePath.length() - 1) == '/';
    }
}
