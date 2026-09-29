// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.List;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.http.handler.security.SecurityPolicy;
import org.pragmatica.aether.slice.kvstore.AetherKey.HttpNodeRouteKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.HttpNodeRouteValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.GenerationSnapshotSource;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Verify;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public interface HttpRouteRegistry {
    Option<RouteInfo> findRoute(String httpMethod, String path);
    List<RouteInfo> allRoutes();

    @SuppressWarnings("JBCT-RET-01")
    void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut);

    @SuppressWarnings("JBCT-RET-01")
    void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove);

    @SuppressWarnings("JBCT-RET-01")
    void evictNode(NodeId nodeId);

    long staleFenceObservationCount();

    /// One node's policies for a route: `enforced` is what that node published as enforced, `declared` the
    /// slice-declared policy it was derived from (#1659).
    record NodeRouteSecurity(String enforced, String declared) {
        public static NodeRouteSecurity nodeRouteSecurity(String enforced, String declared) {
            return new NodeRouteSecurity(enforced, declared);
        }
    }

    /// Who published a route entry: a node, for one artifact it serves (#1659, v1670 F3). One node can serve the
    /// same route from two artifacts (two versions during a rollout); keyed by node alone, the later put overwrote
    /// the other and removing either artifact's key dropped the node from every route it still served.
    record RouteSource(NodeId nodeId, String artifact) {
        public static RouteSource routeSource(NodeId nodeId, String artifact) {
            return new RouteSource(nodeId, artifact);
        }
    }

    /// A route and the policies EACH source serving it published (#1659). Before, the route kept whichever policy
    /// was registered FIRST for as long as any node stayed registered, so an override republished afterwards never
    /// reached it, and a node that did not host the route authorized requests against the stale policy -- fail
    /// OPEN. Now a source's re-put replaces its own entry, its key's removal drops that entry, a node's departure
    /// drops all of its entries, and the route reports the STRONGEST policy across them: while they disagree (a
    /// republish still in flight or failed) the route is as strict as its strictest source, and it relaxes once
    /// every source has republished.
    record RouteInfo(String httpMethod, String pathPrefix, Map<RouteSource, NodeRouteSecurity> securityBySource) {
        public RouteInfo {
            securityBySource = Map.copyOf(securityBySource);
        }

        public static RouteInfo routeInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes, String security) {
            return new RouteInfo(httpMethod, pathPrefix, uniform(nodes, security));
        }

        public static RouteInfo routeInfo(String httpMethod, String pathPrefix, Set<NodeId> nodes) {
            return routeInfo(httpMethod, pathPrefix, nodes, "PUBLIC");
        }

        public Set<NodeId> nodes() {
            return securityBySource.keySet()
                                   .stream()
                                   .map(RouteSource::nodeId)
                                   .collect(Collectors.toUnmodifiableSet());
        }

        /// The strongest policy any serving source enforces.
        public String security() {
            return strongest(securityBySource.values().stream().map(NodeRouteSecurity::enforced).toList());
        }

        /// The strongest slice-declared policy among the serving sources (they agree unless versions differ).
        public String declaredSecurity() {
            return strongest(securityBySource.values().stream().map(NodeRouteSecurity::declared).toList());
        }

        RouteInfo withSource(RouteSource source, NodeRouteSecurity security) {
            var updated = new HashMap<>(securityBySource);

            updated.put(source, security);

            return new RouteInfo(httpMethod, pathPrefix, updated);
        }

        RouteInfo withoutSource(RouteSource source) {
            var updated = new HashMap<>(securityBySource);

            updated.remove(source);

            return new RouteInfo(httpMethod, pathPrefix, updated);
        }

        RouteInfo withoutNode(NodeId nodeId) {
            var updated = new HashMap<>(securityBySource);

            updated.keySet().removeIf(source -> source.nodeId()
                                                      .equals(nodeId));

            return new RouteInfo(httpMethod, pathPrefix, updated);
        }

        private static Map<RouteSource, NodeRouteSecurity> uniform(Set<NodeId> nodes, String security) {
            return nodes.stream()
                        .collect(Collectors.toMap(node -> RouteSource.routeSource(node, ""),
                                                  _ -> NodeRouteSecurity.nodeRouteSecurity(security, security)));
        }

        /// Strongest by [SecurityPolicy#strength], with one adjustment: `UNSPECIFIED` (strength -1, "inherit the
        /// global mode") ranks just ABOVE `PUBLIC`, never below it -- the global mode is at least public, so an
        /// explicit `PUBLIC` must not outrank a node that inherits a stricter global policy. An empty set is
        /// `UNSPECIFIED`.
        ///
        /// A TOTAL order (v1670 F4): equal strengths (`API_KEY` and `BEARER_TOKEN`, `ROLE:a` and `ROLE:b`) are broken
        /// by the policy's canonical string. The values arrive in `Map.copyOf` iteration order, which is seeded per
        /// JVM, so without the tie-break two ingresses could resolve one route to different policies.
        private static String strongest(List<String> policies) {
            return Option.from(policies.stream()
                                       .max(Comparator.comparingInt(RouteInfo::rank).thenComparing(Comparator.naturalOrder()))).or("UNSPECIFIED");
        }

        private static int rank(String policy) {
            return switch (SecurityPolicy.fromString(policy)) {
                case SecurityPolicy.Public _ -> 0;
                case SecurityPolicy.Unspecified _ -> 1;
                case SecurityPolicy other -> other.strength() + 2;
            };
        }

        public String routeIdentity() {
            return httpMethod + ":" + pathPrefix;
        }
    }

    long STALE_FENCE_TERM_THRESHOLD = 5L;

    static HttpRouteRegistry httpRouteRegistry() {
        return httpRouteRegistry(GenerationSnapshotSource.noop());
    }

    static HttpRouteRegistry httpRouteRegistry(GenerationSnapshotSource snapshotSource) {
        record httpRouteRegistry(Map<String, AtomicReference<TreeMap<String, RouteInfo>>> routesByMethod,
                                 GenerationSnapshotSource snapshotSource,
                                 AtomicLong staleFenceCounter) implements HttpRouteRegistry {
            private static final Logger log = LoggerFactory.getLogger(httpRouteRegistry.class);

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onNodeRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> valuePut) {
                var key = valuePut.cause().key();
                var value = valuePut.cause().value();
                var nodeId = key.nodeId();

                if (isStaleFence(nodeId,
                                 key.artifact().asString(),
                                 value)) {
                    return;
                }

                for (var route : value.routes()) {
                    if (!route.isRoutable()) {
                        continue;
                    }

                    var method = route.httpMethod();
                    var prefix = route.pathPrefix();
                    var security = NodeRouteSecurity.nodeRouteSecurity(route.security(), route.declaredSecurity());
                    var source = RouteSource.routeSource(nodeId,
                                                         key.artifact().asString());
                    var ref = routesByMethod.computeIfAbsent(method, _ -> new AtomicReference<>(new TreeMap<>()));

                    ref.updateAndGet(current -> addSourceToRoute(current, method, prefix, source, security));
                    log.debug("HttpRouteRegistry: Registered compound route {} {} node={}", method, prefix, nodeId);
                }
            }

            @Override
            public long staleFenceObservationCount() {
                return staleFenceCounter.get();
            }

            private boolean isStaleFence(NodeId nodeId, String artifact, NodeRoutesValue value) {
                var valueTerm = value.observedCoreEpoch().rabiaTerm();
                var observedTerm = snapshotSource.observedEpochRabiaTerm();

                if (observedTerm - valueTerm > STALE_FENCE_TERM_THRESHOLD) {
                    staleFenceCounter.incrementAndGet();
                    log.warn("Stale route update for {}/{}: value.rabiaTerm={} observed.rabiaTerm={} — REJECTED (hard fence)",
                             nodeId,
                             artifact,
                             valueTerm,
                             observedTerm);

                    return true;
                }

                return false;
            }

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void onNodeRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> valueRemove) {
                var key = valueRemove.cause().key();
                var source = RouteSource.routeSource(key.nodeId(),
                                                     key.artifact().asString());

                routesByMethod.values()
                              .forEach(ref -> ref.updateAndGet(current -> removeSourceFromAllRoutes(current, source)));
            }

            /// #1659 (v1670 F3): only THIS artifact's entries go; the node keeps serving routes from its others.
            private TreeMap<String, RouteInfo> removeSourceFromAllRoutes(TreeMap<String, RouteInfo> current,
                                                                         RouteSource source) {
                var updated = new TreeMap<String, RouteInfo>();

                for (var entry : current.entrySet()) {
                    var remaining = entry.getValue().withoutSource(source);

                    if (!remaining.nodes().isEmpty()) {
                        updated.put(entry.getKey(), remaining);
                    }
                }

                return updated;
            }

            /// #1659: the source's own entry is REPLACED, so a republish carrying a changed policy reaches the route.
            private TreeMap<String, RouteInfo> addSourceToRoute(TreeMap<String, RouteInfo> current,
                                                                String method,
                                                                String prefix,
                                                                RouteSource source,
                                                                NodeRouteSecurity security) {
                var updated = new TreeMap<>(current);
                var existing = Option.option(updated.get(prefix)).or(() -> new RouteInfo(method, prefix, Map.of()));

                updated.put(prefix, existing.withSource(source, security));

                return updated;
            }

            @Override
            public Option<RouteInfo> findRoute(String httpMethod, String path) {
                return Option.option(routesByMethod.get(httpMethod.toUpperCase()))
                             .map(AtomicReference::get)
                             .filter(routes -> !routes.isEmpty())
                             .flatMap(routes -> findMatchingRoute(routes, path));
            }

            private Option<RouteInfo> findMatchingRoute(TreeMap<String, RouteInfo> routes, String path) {
                var normalizedPath = normalizePath(path);

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

            @Override
            @SuppressWarnings("JBCT-RET-01")
            public void evictNode(NodeId nodeId) {
                var totalAffected = new int[]{0};

                routesByMethod.values().forEach(ref -> totalAffected[0] += evictNodeFromMethodRoutes(ref, nodeId));
                log.info("Evicted node {} from route cache, {} routes affected", nodeId, totalAffected[0]);
            }

            private int evictNodeFromMethodRoutes(AtomicReference<TreeMap<String, RouteInfo>> ref, NodeId nodeId) {
                var affected = new int[]{0};

                ref.updateAndGet(current -> buildEvictedMap(current, nodeId, affected));

                return affected[0];
            }

            /// #1659: a departed node's entries are dropped with it, so its last published policy cannot pin a route
            /// it no longer serves (a stricter one would otherwise hold the route strict forever).
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
                    var remaining = route.withoutNode(nodeId);

                    if (!remaining.nodes().isEmpty()) {
                        updated.put(entry.getKey(), remaining);
                    }
                }

                return updated;
            }

            private String normalizePath(String path) {
                if (!Verify.Is.present(path)) {
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

            private boolean isSameOrStartOfPath(String inputPath, String routePath) {
                return (inputPath.length() == routePath.length() && inputPath.equals(routePath)) || (inputPath.length() > routePath.length()
                                                                                                     && inputPath.startsWith(routePath)
                                                                                                     && inputPath.charAt(routePath.length() - 1) == '/');
            }
        }

        return new httpRouteRegistry(new ConcurrentHashMap<>(), snapshotSource, new AtomicLong());
    }
}
