package org.pragmatica.http.routing;

import java.util.ArrayList;
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

    /// #1678: the sibling selection lives in [RouteShapeSelector], the one rule a node that does not host the
    /// route authorizes by as well.
    private Option<Route<?>> selectBestRoute(List<Route<?>> candidates, String inputPath) {
        return RouteShapeSelector.select(candidates, inputPath);
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
