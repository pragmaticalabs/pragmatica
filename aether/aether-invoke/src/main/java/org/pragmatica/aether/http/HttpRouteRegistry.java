package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpRouteValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageReceiver;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Passive KV-Store watcher that maintains a local cache of HTTP route definitions.
 *
 * <p>Key responsibilities:
 * <ul>
 *   <li>Watch HTTP route key events (ValuePut/ValueRemove)</li>
 *   <li>Maintain TreeMap per HTTP method for prefix-based route matching</li>
 *   <li>Provide route discovery for incoming HTTP requests</li>
 * </ul>
 *
 * <p>Uses TreeMap with floor-entry lookup for efficient prefix matching.
 * Same algorithm as RequestRouter in http-routing module.
 */
public interface HttpRouteRegistry {
    @MessageReceiver
    void onValuePut(ValuePut<AetherKey, AetherValue> valuePut);

    @MessageReceiver
    void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove);

    /**
     * Find route for HTTP method and path.
     *
     * <p>Uses TreeMap floor-entry lookup to find the longest matching prefix.
     *
     * @param httpMethod HTTP method (GET, POST, etc.)
     * @param path request path (e.g., "/users/123")
     * @return matching route info, or empty if no route matches
     */
    Option<RouteInfo> findRoute(String httpMethod, String path);

    /**
     * Get all registered routes (for monitoring/debugging and router building).
     */
    List<RouteInfo> allRoutes();

    /**
     * Route information with set of nodes that can handle the route.
     *
     * @param httpMethod HTTP method
     * @param pathPrefix path prefix that matched
     * @param nodes set of node IDs that have this route available
     */
    record RouteInfo(String httpMethod,
                     String pathPrefix,
                     Set<NodeId> nodes) {
        public static RouteInfo routeInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes) {
            return new RouteInfo(httpMethod, pathPrefix, nodes);
        }

        public HttpRouteKey toKey() {
            return HttpRouteKey.httpRouteKey(httpMethod, pathPrefix);
        }
    }

    /**
     * Create a new HTTP route registry.
     */
    static HttpRouteRegistry httpRouteRegistry() {
        record httpRouteRegistry(Map<String, AtomicReference<TreeMap<String, RouteInfo>>> routesByMethod) implements HttpRouteRegistry {
            private static final Logger log = LoggerFactory.getLogger(httpRouteRegistry.class);

            @Override
            public void onValuePut(ValuePut<AetherKey, AetherValue> valuePut) {
                var key = valuePut.cause()
                                  .key();
                var value = valuePut.cause()
                                    .value();
                log.debug("HttpRouteRegistry.onValuePut received: keyType={}, valueType={}",
                          key.getClass()
                             .getSimpleName(),
                          value.getClass()
                               .getSimpleName());
                if (key instanceof HttpRouteKey httpRouteKey && value instanceof HttpRouteValue httpRouteValue) {
                    log.info("HttpRouteRegistry: Processing HttpRouteKey {} {}",
                             httpRouteKey.httpMethod(),
                             httpRouteKey.pathPrefix());
                    var routeInfo = RouteInfo.routeInfo(httpRouteKey.httpMethod(),
                                                        httpRouteKey.pathPrefix(),
                                                        httpRouteValue.nodes());
                    var ref = routesByMethod.computeIfAbsent(httpRouteKey.httpMethod(),
                                                             _ -> new AtomicReference<>(new TreeMap<>()));
                    // Atomic copy-on-write: copy current map, add entry, swap
                    ref.updateAndGet(current -> {
                                         var updated = new TreeMap<>(current);
                                         updated.put(httpRouteKey.pathPrefix(), routeInfo);
                                         return updated;
                                     });
                    log.info("HttpRouteRegistry: Registered route {} {} -> {} nodes",
                             httpRouteKey.httpMethod(),
                             httpRouteKey.pathPrefix(),
                             httpRouteValue.nodes()
                                           .size());
                }
            }

            @Override
            public void onValueRemove(ValueRemove<AetherKey, AetherValue> valueRemove) {
                var key = valueRemove.cause()
                                     .key();
                if (key instanceof HttpRouteKey httpRouteKey) {
                    Option.option(routesByMethod.get(httpRouteKey.httpMethod()))
                          .onPresent(ref -> {
                                         // Atomic copy-on-write: copy current map, remove entry, swap
                    var removed = ref.getAndUpdate(current -> {
                                                       if (current.containsKey(httpRouteKey.pathPrefix())) {
                                                           var updated = new TreeMap<>(current);
                                                           updated.remove(httpRouteKey.pathPrefix());
                                                           return updated;
                                                       }
                                                       return current;
                                                   });
                                         if (removed.containsKey(httpRouteKey.pathPrefix())) {
                                             log.debug("Unregistered HTTP route: {} {}",
                                                       httpRouteKey.httpMethod(),
                                                       httpRouteKey.pathPrefix());
                                         }
                                     });
                }
            }

            @Override
            public Option<RouteInfo> findRoute(String httpMethod, String path) {
                return Option.option(routesByMethod.get(httpMethod.toUpperCase()))
                             .map(AtomicReference::get)
                             .filter(routes -> !routes.isEmpty())
                             .flatMap(routes -> findMatchingRoute(routes, path));
            }

            private Option<RouteInfo> findMatchingRoute(TreeMap<String, RouteInfo> routes, String path) {
                // Normalize path for matching (ensure trailing slash)
                var normalizedPath = normalizePath(path);
                // Use floor-entry to find longest matching prefix
                return Option.option(routes.floorEntry(normalizedPath))
                             .filter(entry -> isSameOrStartOfPath(normalizedPath,
                                                                  entry.getKey()))
                             .map(Map.Entry::getValue);
            }

            @Override
            public List<RouteInfo> allRoutes() {
                return routesByMethod.values()
                                     .stream()
                                     .map(AtomicReference::get)
                                     .flatMap(map -> map.values()
                                                        .stream())
                                     .toList();
            }

            private String normalizePath(String path) {
                if (path == null || path.isBlank()) {
                    return "/";
                }
                var normalized = path.strip();
                if (!normalized.startsWith("/")) {
                    normalized = "/" + normalized;
                }
                if (!normalized.endsWith("/")) {
                    normalized = normalized + "/";
                }
                return normalized;
            }

            /**
             * Check if inputPath matches routePath (same or starts with).
             * Same algorithm as RequestRouter.
             */
            private boolean isSameOrStartOfPath(String inputPath, String routePath) {
                return (inputPath.length() == routePath.length() && inputPath.equals(routePath)) || (inputPath.length() > routePath.length() && inputPath.startsWith(routePath) && inputPath.charAt(routePath.length() - 1) == '/');
            }
        }
        return new httpRouteRegistry(new ConcurrentHashMap<>());
    }
}
