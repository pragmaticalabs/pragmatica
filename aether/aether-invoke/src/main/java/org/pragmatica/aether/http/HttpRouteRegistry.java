package org.pragmatica.aether.http;

import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/// Passive KV-Store watcher that maintains a local cache of HTTP route definitions.
///
///
/// The KV store holds flat per-node keys (`HttpNodeRouteKey(method, prefix, nodeId)`).
/// This registry reconstructs `Map<(method+prefix), Set<NodeId>>` in memory from those flat keys.
///
///
/// Key responsibilities:
///
///   - Watch HTTP node route key events (ValuePut/ValueRemove)
///   - Maintain TreeMap per HTTP method for prefix-based route matching
///   - Reconstruct node sets from flat per-node keys
///   - Provide route discovery for incoming HTTP requests
///
///
///
/// Uses TreeMap with floor-entry lookup for efficient prefix matching.
/// Same algorithm as RequestRouter in http-routing module.
public interface HttpRouteRegistry {
    Option<RouteInfo> findRoute(String httpMethod, String path);
    List<RouteInfo> allRoutes();
    @SuppressWarnings("JBCT-RET-01") void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);
    @SuppressWarnings("JBCT-RET-01") void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove);
    @SuppressWarnings("JBCT-RET-01") void evictNode(NodeId nodeId);

    record RouteInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes, String security) {
        public static RouteInfo routeInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes, String security) {
            return new RouteInfo(httpMethod, pathPrefix, nodes, security);
        }

        public static RouteInfo routeInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes) {
            return new RouteInfo(httpMethod, pathPrefix, nodes, "PUBLIC");
        }

        public String routeIdentity() {
            return httpMethod + ":" + pathPrefix;
        }
    }

    static HttpRouteRegistry httpRouteRegistry() {
        record httpRouteRegistry(Map<String, AtomicReference<TreeMap<String, RouteInfo>>> routesByMethod) implements HttpRouteRegistry {
            private static final Logger log = LoggerFactory.getLogger(httpRouteRegistry.class);

            @Override@SuppressWarnings("JBCT-RET-01") public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();
                var nodeId = key.nodeId();
                for (var route : value.routes()) {
                    if (!route.isRoutable()) {continue;}
                    var method = route.httpMethod();
                    var prefix = route.pathPrefix();
                    var security = route.security();
                    var ref = routesByMethod.computeIfAbsent(method, _ -> new AtomicReference<>(new TreeMap<>()));
                    ref.updateAndGet(current -> addNodeToRoute(current, method, prefix, nodeId, security));
                    log.debug("HttpRouteRegistry: Registered compound route {} {} node={}", method, prefix, nodeId);
                }
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
                var key = valueRemove.cause().key();
                var nodeId = key.nodeId();
                routesByMethod.values()
                                     .forEach(ref -> ref.updateAndGet(current -> removeNodeFromAllRoutes(current, nodeId)));
            }

            private TreeMap<String, RouteInfo> removeNodeFromAllRoutes(TreeMap<String, RouteInfo> current,
                                                                       NodeId nodeId) {
                var updated = new TreeMap<String, RouteInfo>();
                for (var entry : current.entrySet()) {
                    var route = entry.getValue();
                    if (!route.nodes().contains(nodeId)) {
                        updated.put(entry.getKey(), route);
                        continue;
                    }
                    var remaining = new HashSet<>(route.nodes());
                    remaining.remove(nodeId);
                    if (!remaining.isEmpty()) {updated.put(entry.getKey(),
                                                           RouteInfo.routeInfo(route.httpMethod(),
                                                                               route.pathPrefix(),
                                                                               Set.copyOf(remaining),
                                                                               route.security()));}
                }
                return updated;
            }

            private TreeMap<String, RouteInfo> addNodeToRoute(TreeMap<String, RouteInfo> current,
                                                              String method,
                                                              String prefix,
                                                              NodeId nodeId,
                                                              String security) {
                var updated = new TreeMap<>(current);
                var existing = updated.get(prefix);
                var nodes = (existing != null)
                           ? new HashSet<>(existing.nodes())
                           : new HashSet<NodeId>();
                var effectiveSecurity = (existing != null)
                                       ? existing.security()
                                       : security;
                nodes.add(nodeId);
                updated.put(prefix,
                            RouteInfo.routeInfo(method, prefix, Set.copyOf(nodes), effectiveSecurity));
                return updated;
            }

            @Override public Option<RouteInfo> findRoute(String httpMethod, String path) {
                return Option.option(routesByMethod.get(httpMethod.toUpperCase())).map(AtomicReference::get)
                                    .filter(routes -> !routes.isEmpty())
                                    .flatMap(routes -> findMatchingRoute(routes, path));
            }

            private Option<RouteInfo> findMatchingRoute(TreeMap<String, RouteInfo> routes, String path) {
                var normalizedPath = normalizePath(path);
                return Option.option(routes.floorEntry(normalizedPath)).filter(entry -> isSameOrStartOfPath(normalizedPath,
                                                                                                            entry.getKey()))
                                    .map(Map.Entry::getValue);
            }

            @Override public List<RouteInfo> allRoutes() {
                return routesByMethod.values().stream()
                                            .map(AtomicReference::get)
                                            .flatMap(map -> map.values().stream())
                                            .toList();
            }

            @Override@SuppressWarnings("JBCT-RET-01") public void evictNode(NodeId nodeId) {
                var totalAffected = new int[]{0};
                routesByMethod.values().forEach(ref -> totalAffected[0] += evictNodeFromMethodRoutes(ref, nodeId));
                log.info("Evicted node {} from route cache, {} routes affected", nodeId, totalAffected[0]);
            }

            private int evictNodeFromMethodRoutes(AtomicReference<TreeMap<String, RouteInfo>> ref, NodeId nodeId) {
                var affected = new int[]{0};
                ref.updateAndGet(current -> buildEvictedMap(current, nodeId, affected));
                return affected[0];
            }

            private TreeMap<String, RouteInfo> buildEvictedMap(TreeMap<String, RouteInfo> current,
                                                               NodeId nodeId,
                                                               int[] affected) {
                var updated = new TreeMap<String, RouteInfo>();
                for (var entry : current.entrySet()) {
                    var route = entry.getValue();
                    if (!route.nodes().contains(nodeId)) {
                        updated.put(entry.getKey(), route);
                        continue;
                    }
                    affected[0]++;
                    var remaining = new HashSet<>(route.nodes());
                    remaining.remove(nodeId);
                    if (!remaining.isEmpty()) {updated.put(entry.getKey(),
                                                           RouteInfo.routeInfo(route.httpMethod(),
                                                                               route.pathPrefix(),
                                                                               Set.copyOf(remaining),
                                                                               route.security()));}
                }
                return updated;
            }

            private String normalizePath(String path) {
                if (path == null || path.isBlank()) {return "/";}
                var normalized = path.strip();
                if (!normalized.startsWith("/")) {normalized = "/" + normalized;}
                if (!normalized.endsWith("/")) {normalized = normalized + "/";}
                return normalized;
            }

            private boolean isSameOrStartOfPath(String inputPath, String routePath) {
                return (inputPath.length() == routePath.length() && inputPath.equals(routePath)) || (inputPath.length() > routePath.length() && inputPath.startsWith(routePath) && inputPath.charAt(routePath.length() - 1) == '/');
            }
        }
        return new httpRouteRegistry(new ConcurrentHashMap<>());
    }
}
