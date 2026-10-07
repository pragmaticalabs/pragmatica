// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;

import static org.pragmatica.lang.Result.unitResult;


/// #1206: refuses, at blueprint admission, two slices of different artifacts that declare the same HTTP route.
///
/// The runtime keeps a deterministic tie-break for a collision it meets (`HttpRoutePublisher`: longest prefix, then the lexically
/// smaller artifact coordinate), which since #884 makes the whole cluster agree. That made a collision CONSISTENT, not
/// harmless: the losing slice activates, reports healthy, and serves nothing. Admission is where the intended set of slices is
/// visible before anything activates, and where a refusal is the same on every node. A publisher-level refusal would depend on
/// local activation order.
///
/// A route is identified by its method and its path template with path parameters normalized (`/orders/{id}` and `/orders/{n}`
/// are one route), trailing slashes ignored. Only IDENTICAL routes collide: `/orders` against `/orders/export`, or
/// `/orders/{id}` against `/orders/{id}/items`, are different routes and are admitted; the longer prefix then wins for the
/// paths it covers, as designed. Two versions of the SAME artifact (a rolling redeploy) never collide with each other.
///
/// `admitted` are the topologies of the blueprint being published; `existing` those of every other stored blueprint. A
/// collision is refused only if it involves an admitted slice, so a collision that already exists between two stored
/// blueprints (published before this check, or invisible at their admission) does not block an unrelated publish.
@SuppressWarnings("JBCT-UTIL-02")
public sealed interface RoutePrefixCollisionValidator {
    static Result<Unit> validate(List<SliceTopology> admitted, List<SliceTopology> existing) {
        var admittedBases = admitted.stream().map(RoutePrefixCollisionValidator::baseOf).toList();
        var claims = new TreeMap<String, TreeMap<String, String>>();

        Stream.concat(admitted.stream(), existing.stream()).forEach(topology -> claimRoutes(claims, topology));
        var collisions = new ArrayList<ExpanderError.RouteCollision>();

        claims.forEach((key, byBase) -> collisionOf(key, byBase, admittedBases).onPresent(collisions::add));

        return collisions.isEmpty()
               ? unitResult()
               : ExpanderError.RoutePrefixCollisions.routePrefixCollisions(collisions).result();
    }

    private static void claimRoutes(Map<String, TreeMap<String, String>> claims, SliceTopology topology) {
        var base = baseOf(topology);

        topology.routes()
                .forEach(route -> claims.computeIfAbsent(keyOf(route),
                                                         _ -> new TreeMap<>())
                                        .putIfAbsent(base,
                                                     topology.artifact()));
    }

    private static Option<ExpanderError.RouteCollision> collisionOf(String key,
                                                                    TreeMap<String, String> byBase,
                                                                    List<String> admittedBases) {
        if (byBase.size() < 2 || byBase.keySet().stream().noneMatch(admittedBases::contains)) {
            return Option.none();
        }

        var separator = key.indexOf(' ');
        var coordinates = List.copyOf(byBase.values());

        return Option.some(ExpanderError.RouteCollision.routeCollision(key.substring(0, separator),
                                                                       key.substring(separator + 1),
                                                                       coordinates.get(0),
                                                                       coordinates.get(1)));
    }

    /// `METHOD /normalized/path`.
    static String keyOf(SliceTopology.Route route) {
        return route.method()
                    .toUpperCase() + " " + normalizePath(route.path());
    }

    /// Path parameters (`{id}`, `{userId}`) are one segment shape; trailing slashes are not significant.
    static String normalizePath(String path) {
        var normalized = Stream.of(path.split("/"))
                               .filter(segment -> !segment.isEmpty())
                               .map(segment -> segment.startsWith("{") && segment.endsWith("}")
                                               ? "{}"
                                               : segment)
                               .toList();

        return "/" + String.join("/", normalized);
    }

    /// The artifact without its version: a rolling redeploy publishes two versions of one slice, which is not a collision.
    private static String baseOf(SliceTopology topology) {
        return Artifact.artifact(topology.artifact())
                       .map(artifact -> artifact.base()
                                                .asString())
                       .or(topology.artifact());
    }

    record unused() implements RoutePrefixCollisionValidator {}
}
