// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Comparator;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Stable coordination identity with independently selected capacity locations.
public record CommunityPlacement(String id, int targetSize, List<Location> locations) {
    public record Location(String source, Option<String> zone, int minimum, int weight) {}

    public CommunityPlacement {
        locations = List.copyOf(locations);
    }

    /// Minimums are hard reservations; remaining slots use deterministic largest remainders.
    public Map<Location, Integer> desiredCounts() {
        var counts = new LinkedHashMap<Location, Integer>();
        var available = targetSize - locations.stream().mapToInt(Location::minimum).sum();
        var totalWeight = locations.stream().mapToLong(Location::weight).sum();

        for (var location : locations) {
            counts.put(location,
                       location.minimum() + (int)((long) available * location.weight() / totalWeight));
        }

        var remaining = targetSize - counts.values().stream().mapToInt(Integer::intValue).sum();

        locations.stream()
                 .sorted(Comparator.<Location> comparingLong(location -> - ((long) available * location.weight() % totalWeight))
                                   .thenComparing(Location::source)
                                   .thenComparing(location -> location.zone()
                                                                      .or("")))
                 .limit(remaining)
                 .forEach(location -> counts.computeIfPresent(location,
                                                              (_, count) -> count + 1));

        return Map.copyOf(counts);
    }

    public static Result<CommunityPlacement> communityPlacement(String id, int targetSize, List<Location> locations) {
        if (id.isBlank() || targetSize < 0 || locations.isEmpty()) {
            return new ClusterConfigError.ParseFailed("Community requires an identity, non-negative target_size and placement locations").result();
        }

        if (locations.stream()
                     .anyMatch(location -> location.source()
                                                   .isBlank() || location.minimum() < 0 || location.weight() < 1)) {
            return new ClusterConfigError.ParseFailed("Community placement requires source, non-negative minimum and positive weight").result();
        }

        if (locations.stream().mapToLong(Location::minimum).sum() > targetSize) {
            return new ClusterConfigError.ParseFailed("Community placement minimums exceed target_size: " + id).result();
        }

        if (locations.stream().map(location -> location.source() + "/" + location.zone()
                                                                                 .or("")).distinct().count() != locations.size()) {
            return new ClusterConfigError.ParseFailed("Duplicate source/zone in community: " + id).result();
        }

        if (locations.stream()
                     .anyMatch(location -> location.zone()
                                                   .isEmpty() && locations.stream()
                                                                          .filter(other -> other.source()
                                                                                                .equals(location.source()))
                                                                          .count() > 1)) {
            return new ClusterConfigError.ParseFailed("Unzoned placement overlaps another location for the same source: " + id).result();
        }

        return Result.success(new CommunityPlacement(id, targetSize, locations));
    }
}
