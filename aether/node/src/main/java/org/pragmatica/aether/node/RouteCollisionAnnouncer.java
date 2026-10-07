// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.node;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.pragmatica.aether.api.OperationalEvent;
import org.pragmatica.aether.slice.kvstore.AetherKey.NodeRoutesKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeRoutesValue;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Unit;


/// #1206 (owner rule: an operator-facing condition emits an event on its transition, and a recovery event): announces two
/// artifacts serving the same HTTP route, and the route having one claimant again.
///
/// Blueprint admission refuses an identical-route collision it can see (`RoutePrefixCollisionValidator`). What it cannot see
/// still happens: a slice jar that was unavailable at admission, two publishes racing, a slice deployed by another path. Then
/// the runtime tie-break (`HttpRoutePublisher`: the lexically smaller coordinate serves) keeps the whole cluster consistent,
/// and the other slice reports healthy and serves nothing. This is the operator's signal for that case.
///
/// Derived from the COMMITTED route table, like [StreamIsrAnnouncer]: every node applies the same Puts and Removes and so
/// derives the same event, and the cluster-events aggregator publishes only on the owner of the events partition. A route is
/// claimed by an artifact BASE (group and artifact id) when at least one node publishes an ACTIVE entry of it, so two versions of
/// one slice during a rolling redeploy never collide. The state is rebuilt from the notifications themselves (a node's entry
/// replaces that node's previous contribution), so a replayed notification changes nothing and a collision that already
/// existed at boot is simply known, not announced. The condition is "two or more bases claim the route", so a commit that keeps
/// it on the same side announces nothing: the committed table is the dedupe.
///
/// Every event carries a deterministic `eventId`: the route, its claimants and the newest `registeredAt` among the claiming
/// entries, so a collision that clears and later comes back (a republish stamps new entries) is a different event, while the
/// same transition seen by two nodes is one.
public interface RouteCollisionAnnouncer {
    @Contract
    void onRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> put);

    @Contract
    void onRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> remove);

    static RouteCollisionAnnouncer routeCollisionAnnouncer(Consumer<OperationalEvent> sink) {
        return new Tracker(sink);
    }

    /// A route as the router keys it: method, normalized prefix and shape.
    record RouteIdentity(String method, String prefix, int arity, List<String> spacers) {}

    final class Tracker implements RouteCollisionAnnouncer {
        private final Consumer<OperationalEvent> sink;
        /// What each (node, artifact) entry currently contributes.
        private final Map<NodeRoutesKey, Map<RouteIdentity, Long>> contributions = new HashMap<>();

        private Tracker(Consumer<OperationalEvent> sink) {
            this.sink = sink;
        }

        @Override
        public synchronized void onRoutesPut(ValuePut<NodeRoutesKey, NodeRoutesValue> put) {
            var key = put.cause().key();
            var next = put.cause()
                          .value()
                          .routes()
                          .stream()
                          .filter(entry -> "ACTIVE".equals(entry.state()))
                          .collect(Collectors.toMap(entry -> new RouteIdentity(entry.httpMethod().toUpperCase(),
                                                                               entry.pathPrefix(),
                                                                               entry.pathArity(),
                                                                               entry.spacers()),
                                                    NodeRoutesValue.RouteEntry::registeredAt,
                                                    Math::max));

            replace(key, next);
        }

        @Override
        public synchronized void onRoutesRemove(ValueRemove<NodeRoutesKey, NodeRoutesValue> remove) {
            replace(remove.cause().key(),
                    Map.of());
        }

        private Unit replace(NodeRoutesKey key, Map<RouteIdentity, Long> next) {
            var previous = contributions.getOrDefault(key, Map.of());
            var affected = new HashSet<>(previous.keySet());

            affected.addAll(next.keySet());
            var before = claimantsOf(affected);

            if (next.isEmpty()) {
                contributions.remove(key);
            } else {
                contributions.put(key, Map.copyOf(next));
            }

            var after = claimantsOf(affected);

            affected.forEach(route -> announce(route, before.get(route), after.get(route)));

            return Unit.unit();
        }

        /// Who claims each of `routes` now: the artifact bases, and the newest `registeredAt` among the claiming entries.
        private Map<RouteIdentity, Claim> claimantsOf(Set<RouteIdentity> routes) {
            var claims = new HashMap<RouteIdentity, Claim>();

            contributions.forEach((key, identities) -> identities.forEach((route, registeredAt) -> {
                if (routes.contains(route)) {
                    claims.merge(route,
                                 new Claim(new TreeSet<>(Set.of(key.artifact().base().asString())),
                                           registeredAt),
                                 Claim::merged);
                }
            }));

            return claims;
        }

        private Unit announce(RouteIdentity route, Claim before, Claim after) {
            var wasCollision = before != null && before.bases().size() > 1;
            var isCollision = after != null && after.bases().size() > 1;

            if (wasCollision == isCollision) {
                return Unit.unit();
            }

            sink.accept(isCollision
                        ? OperationalEvent.RoutePrefixCollision.routePrefixCollision(route.method(),
                                                                                     route.prefix(),
                                                                                     List.copyOf(after.bases()),
                                                                                     eventId("route-collision",
                                                                                             route,
                                                                                             after))
                        : OperationalEvent.RoutePrefixCollisionCleared.routePrefixCollisionCleared(route.method(),
                                                                                                   route.prefix(),
                                                                                                   List.copyOf(before.bases()),
                                                                                                   eventId("route-collision-cleared",
                                                                                                           route,
                                                                                                           before)));

            return Unit.unit();
        }

        private static String eventId(String kind, RouteIdentity route, Claim claim) {
            return kind
                 + ":" + route.method()
                 + ":" + route.prefix()
                 + ":" + route.arity()
                 + ":" + String.join("+", route.spacers())
                 + ":" + String.join(",", claim.bases())
                 + ":" + claim.newestRegisteredAt();
        }

        private record Claim(TreeSet<String> bases, long newestRegisteredAt) {
            Claim merged(Claim other) {
                var all = new TreeSet<>(bases);

                all.addAll(other.bases());

                return new Claim(all, Math.max(newestRegisteredAt, other.newestRegisteredAt));
            }
        }
    }
}
