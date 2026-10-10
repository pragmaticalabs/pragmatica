// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.handler.RouteIdentity;
import org.pragmatica.aether.slice.topology.SliceTopology;
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
/// "The same route" is answered by [RouteIdentity], the one definition the runtime route extractor, the committed-route-table
/// announcer and this validator share: method, normalized prefix and shape. `/orders/{id}` and `/orders/{n}` are one route,
/// so are `/files/x{a}` and `/files/x{b}` (the prefix is the template up to its first `{`), and trailing slashes are not
/// significant; `/orders` against `/orders/export`, or `/orders/{id}` against `/orders/{id}/items`, are different routes and
/// are admitted, and the longer prefix then wins for the paths it covers, as designed. Two versions of the SAME artifact (a
/// rolling redeploy) never collide with each other.
///
/// A versioned route's manifest path is composed `{apiPrefix}/v{N}{template}`, which is its mounted path in PATH mode. In HEADER
/// mode the runtime mounts it at `{apiPrefix}{template}` and tells versions apart by a request header, so `versionInHeader`
/// removes the `/v{N}` segment before the identity is taken: `/api/v1/orders` of one slice and `/api/v2/orders` of another are
/// then one route, as they are at runtime.
///
/// `admitted` are the topologies of the blueprint being published; `stored` those of every other stored blueprint. A collision is
/// refused only if it involves an admitted slice, so a collision that already exists between two stored blueprints does not
/// block an unrelated publish. A collision between two slices of the blueprint being published is a malformed request
/// ([ExpanderError.RoutePrefixCollisions]); one with a slice of an already-stored blueprint is a conflict with current state
/// ([ExpanderError.RoutePrefixConflictsWithStored]), which names that blueprint and slice and takes precedence.
@SuppressWarnings("JBCT-UTIL-02")
public sealed interface RoutePrefixCollisionValidator {
    /// The slices of one ALREADY-STORED blueprint.
    record StoredSlices(String blueprint, List<SliceTopology> topologies) {
        public static StoredSlices storedSlices(String blueprint, List<SliceTopology> topologies) {
            return new StoredSlices(blueprint, List.copyOf(topologies));
        }
    }

    /// One slice claiming a route: its coordinate, the template it declares, and the stored blueprint it belongs to (empty for the
    /// blueprint being published).
    record Claimant(String coordinate, String template, String storedBlueprint) {
        boolean isStored() {
            return ! storedBlueprint.isEmpty();
        }
    }

    static Result<Unit> validate(List<SliceTopology> admitted, List<StoredSlices> stored, boolean versionInHeader) {
        var admittedBases = admitted.stream().map(RoutePrefixCollisionValidator::baseOf).toList();
        var claims = new LinkedHashMap<RouteIdentity, TreeMap<String, Claimant>>();

        admitted.forEach(topology -> claimRoutes(claims, topology, "", versionInHeader));
        stored.forEach(slices -> slices.topologies()
                                       .forEach(topology -> claimRoutes(claims,
                                                                        topology,
                                                                        slices.blueprint(),
                                                                        versionInHeader)));
        var internal = new ArrayList<ExpanderError.RouteCollision>();
        var conflicts = new ArrayList<ExpanderError.RouteCollision>();

        claims.forEach((identity, byBase) -> classify(identity, byBase, admittedBases, internal, conflicts));
        if (!conflicts.isEmpty()) {
            return ExpanderError.RoutePrefixConflictsWithStored.routePrefixConflictsWithStored(conflicts).result();
        }

        return internal.isEmpty()
               ? unitResult()
               : ExpanderError.RoutePrefixCollisions.routePrefixCollisions(internal).result();
    }

    private static void claimRoutes(Map<RouteIdentity, TreeMap<String, Claimant>> claims,
                                    SliceTopology topology,
                                    String storedBlueprint,
                                    boolean versionInHeader) {
        var base = baseOf(topology);

        topology.routes()
                .forEach(route -> claims.computeIfAbsent(identityOf(route, versionInHeader),
                                                         _ -> new TreeMap<>())
                                        .putIfAbsent(base,
                                                     new Claimant(topology.artifact(),
                                                                  route.path(),
                                                                  storedBlueprint)));
    }

    private static void classify(RouteIdentity identity,
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

        if (ours.isEmpty() || byBase.size() < 2) {
            return;
        }

        if (!theirs.isEmpty()) {
            var ours0 = ours.getFirst();
            var theirs0 = theirs.getFirst();

            conflicts.add(ExpanderError.RouteCollision.conflictWithStored(identity.method(),
                                                                          ours0.template(),
                                                                          theirs0.template(),
                                                                          ours0.coordinate(),
                                                                          theirs0.coordinate(),
                                                                          theirs0.storedBlueprint()));
        } else if (ours.size() > 1) {
            internal.add(ExpanderError.RouteCollision.routeCollision(identity.method(),
                                                                     ours.get(0).template(),
                                                                     ours.get(1).template(),
                                                                     ours.get(0).coordinate(),
                                                                     ours.get(1).coordinate()));
        }
    }

    /// The identity a route has at runtime, from its manifest path.
    static RouteIdentity identityOf(SliceTopology.Route route, boolean versionInHeader) {
        return RouteIdentity.ofTemplate(route.method(),
                                        versionInHeader && route.version() > 0
                                        ? withoutVersionSegment(route.path(), route.version())
                                        : route.path());
    }

    /// `/api/v2/orders` with version 2 becomes `/api/orders`: the first `/v2` segment is the mount's own.
    static String withoutVersionSegment(String path, int version) {
        var marker = "/v" + version;
        var from = 0;

        while (true) {
            var index = path.indexOf(marker, from);

            if (index < 0) {
                return path;
            }

            var end = index + marker.length();

            if (end == path.length() || path.charAt(end) == '/') {
                return path.substring(0, index) + path.substring(end);
            }

            from = end;
        }
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
