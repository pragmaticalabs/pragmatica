// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.pragmatica.aether.config.cluster.CommunityPlacement.Location;
import org.pragmatica.aether.config.cluster.CostClass;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.lang.Option;

import static org.pragmatica.lang.Option.option;


/// Pure source ranking for community capacity (rc6 f5c §2). NOT WIRED: nothing in production calls it; Lane B
/// replaces the reconciler's `locationOrder()` with it.
///
/// Filters, each dropping a candidate (order irrelevant): 1. the source is declared in `sources` (a binding
/// whose source is gone is dropped, never defaulted); 2. the source offers `role`; 4. `max_nodes` is not
/// reached. Filter 3 (health) is the caller's: `unavailable` locations never enter `candidates`.
///
/// Rank, lexicographic: 1. meets the deadline (a boolean, so with no deadline every candidate ties; a raw
/// lead-time key would reorder a docker+cloud community that sets no field); 2. `preference` ascending;
/// 3. cost rank ascending (`cost_class` only); 4. source name, then zone (`""` when unzoned) ascending, exactly today's
/// `CommunityPlacementReconciler.locationOrder()`.
///
/// Guarantees: deterministic for identical arguments (no clock, no map iteration; key 4 is a total order on
/// distinct locations: it relies on (source, zone) being unique within the candidates, which `CommunityPlacement`
/// enforces at parse time ("Duplicate source/zone in community"); `rank` does not re-check it, and a duplicated
/// pair would tie and keep input order) and the result is a subset of `candidates` (the only names it can emit).
public final class SourceRanking {
    private SourceRanking() {}

    private record Ranked(Location location, SourceProfile profile) {
        static Ranked ranked(Location location, SourceProfile profile) {
            return new Ranked(location, profile);
        }
    }

    /// `sourceNodeCount` absent means filter 4 is not applied (F7: Lane B passes none until Lane C supplies
    /// counts). Present, a source missing from the map has zero nodes.
    public static List<Location> rank(List<Location> candidates,
                                      Map<String, SourceProfile> sources,
                                      NodeRole role,
                                      SelectionRequest request,
                                      Option<Map<String, Integer>> sourceNodeCount) {
        return eligible(candidates, sources, role, sourceNodeCount).sorted(order(role, request))
                       .map(Ranked::location)
                       .toList();
    }

    private static Stream<Ranked> eligible(List<Location> candidates,
                                           Map<String, SourceProfile> sources,
                                           NodeRole role,
                                           Option<Map<String, Integer>> sourceNodeCount) {
        return candidates.stream()
                         .flatMap(location -> declared(location, sources).stream())
                         .filter(ranked -> offersRole(ranked, role))
                         .filter(ranked -> withinMaxNodes(ranked, sourceNodeCount));
    }

    private static Option<Ranked> declared(Location location, Map<String, SourceProfile> sources) {
        return option(sources.get(location.source())).map(profile -> Ranked.ranked(location, profile));
    }

    private static boolean offersRole(Ranked ranked, NodeRole role) {
        return ranked.profile()
                     .roles()
                     .containsKey(role);
    }

    private static boolean withinMaxNodes(Ranked ranked, Option<Map<String, Integer>> sourceNodeCount) {
        return ranked.profile()
                     .maxNodes()
                     .flatMap(max -> underCap(sourceNodeCount, ranked, max))
                     .or(true);
    }

    private static Option<Boolean> underCap(Option<Map<String, Integer>> sourceNodeCount, Ranked ranked, int max) {
        return sourceNodeCount.map(counts -> nodeCount(counts, ranked) < max);
    }

    private static int nodeCount(Map<String, Integer> counts, Ranked ranked) {
        return counts.getOrDefault(ranked.location().source(),
                                   0);
    }

    private static Comparator<Ranked> order(NodeRole role, SelectionRequest request) {
        return Comparator.<Ranked, Boolean> comparing(ranked -> meetsDeadline(ranked.profile(),
                                                                              role,
                                                                              request))
                         .reversed()
                         .thenComparingInt(ranked -> ranked.profile()
                                                           .preference())
                         .thenComparingInt(ranked -> costRank(ranked.profile().costClass()))
                         .thenComparing(ranked -> ranked.location()
                                                        .source())
                         .thenComparing(ranked -> ranked.location()
                                                        .zone()
                                                        .or(""));
    }

    private static boolean meetsDeadline(SourceProfile profile, NodeRole role, SelectionRequest request) {
        return request.deadlineMs()
                      .map(deadline -> request.evaluatedAtMs() + profile.effectiveLeadTime(role)
                                                                        .millis() <= deadline)
                      .or(true);
    }

    /// Reads `cost_class` only: `reserved` and `free` rank 0, `ondemand` 1, `spot` 2. `FREE` sharing rank 0 with
    /// `reserved` is the owner ruling Q4, know ea915292bf.
    private static int costRank(CostClass costClass) {
        return switch (costClass) {
            case RESERVED, FREE -> 0;
            case ONDEMAND -> 1;
            case SPOT -> 2;
        };
    }
}
