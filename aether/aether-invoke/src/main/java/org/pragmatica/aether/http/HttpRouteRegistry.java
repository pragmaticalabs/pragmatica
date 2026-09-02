// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.http;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

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
                    var security = route.security();
                    var ref = routesByMethod.computeIfAbsent(method, _ -> new AtomicReference<>(new TreeMap<>()));

                    ref.updateAndGet(current -> addNodeToRoute(current, method, prefix, nodeId, security));
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
                    if (!remaining.isEmpty()) {
                        updated.put(entry.getKey(),
                                    RouteInfo.routeInfo(route.httpMethod(),
                                                        route.pathPrefix(),
                                                        Set.copyOf(remaining),
                                                        route.security()));
                    }
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
                    if (!remaining.isEmpty()) {
                        updated.put(entry.getKey(),
                                    RouteInfo.routeInfo(route.httpMethod(),
                                                        route.pathPrefix(),
                                                        Set.copyOf(remaining),
                                                        route.security()));
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
