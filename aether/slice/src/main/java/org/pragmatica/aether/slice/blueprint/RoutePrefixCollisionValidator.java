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
    /// The slices of one ALREADY-STORED blueprint.
    record StoredSlices(String blueprint, List<SliceTopology> topologies) {
        public static StoredSlices storedSlices(String blueprint, List<SliceTopology> topologies) {
            return new StoredSlices(blueprint, List.copyOf(topologies));
        }
    }

    /// One slice claiming a route: its coordinate, and the stored blueprint it belongs to (empty for the blueprint being
    /// published).
    record Claimant(String coordinate, String storedBlueprint) {
        boolean isStored() {
            return ! storedBlueprint.isEmpty();
        }
    }

    /// A collision between two slices of the blueprint being published is a malformed request ([ExpanderError.RoutePrefixCollisions]);
    /// one with a slice of an already-stored blueprint is a conflict with current state
    /// ([ExpanderError.RoutePrefixConflictsWithStored]), which names that blueprint and slice and takes precedence.
    static Result<Unit> validate(List<SliceTopology> admitted, List<StoredSlices> stored) {
        var admittedBases = admitted.stream().map(RoutePrefixCollisionValidator::baseOf).toList();
        var claims = new TreeMap<String, TreeMap<String, Claimant>>();

        admitted.forEach(topology -> claimRoutes(claims, topology, ""));
        stored.forEach(slices -> slices.topologies()
                                       .forEach(topology -> claimRoutes(claims,
                                                                        topology,
                                                                        slices.blueprint())));
        var internal = new ArrayList<ExpanderError.RouteCollision>();
        var conflicts = new ArrayList<ExpanderError.RouteCollision>();

        claims.forEach((key, byBase) -> classify(key, byBase, admittedBases, internal, conflicts));
        if (!conflicts.isEmpty()) {
            return ExpanderError.RoutePrefixConflictsWithStored.routePrefixConflictsWithStored(conflicts).result();
        }

        return internal.isEmpty()
               ? unitResult()
               : ExpanderError.RoutePrefixCollisions.routePrefixCollisions(internal).result();
    }

    private static void claimRoutes(Map<String, TreeMap<String, Claimant>> claims,
                                    SliceTopology topology,
                                    String storedBlueprint) {
        var base = baseOf(topology);

        topology.routes()
                .forEach(route -> claims.computeIfAbsent(keyOf(route),
                                                         _ -> new TreeMap<>())
                                        .putIfAbsent(base,
                                                     new Claimant(topology.artifact(),
                                                                  storedBlueprint)));
    }

    private static void classify(String key,
                                 TreeMap<String, Claimant> byBase,
                                 List<String> admittedBases,
                                 List<ExpanderError.RouteCollision> internal,
                                 List<ExpanderError.RouteCollision> conflicts) {
        var ours = byBase.entrySet()
                         .stream()
                         .filter(entry -> !entry.getValue()
                                                .isStored() && admittedBases.contains(entry.getKey()))
                         .map(Map.Entry::getValue)
                         .toList();
        var theirs = byBase.values().stream().filter(Claimant::isStored).toList();
        var separator = key.indexOf(' ');
        var method = key.substring(0, separator);
        var path = key.substring(separator + 1);

        if (ours.isEmpty() || byBase.size() < 2) {
            return;
        }

        if (!theirs.isEmpty()) {
            conflicts.add(ExpanderError.RouteCollision.conflictWithStored(method,
                                                                          path,
                                                                          ours.getFirst().coordinate(),
                                                                          theirs.getFirst().coordinate(),
                                                                          theirs.getFirst().storedBlueprint()));
        } else if (ours.size() > 1) {
            internal.add(ExpanderError.RouteCollision.routeCollision(method,
                                                                     path,
                                                                     ours.get(0).coordinate(),
                                                                     ours.get(1).coordinate()));
        }
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
