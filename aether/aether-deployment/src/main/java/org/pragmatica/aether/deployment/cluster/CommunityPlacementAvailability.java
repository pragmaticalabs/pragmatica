// SPDX-License-Identifier: BUSL-1.1
package org.pragmatica.aether.deployment.cluster;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.lang.io.TimeSpan;


/// Durable refusal evidence changes soft distribution only; minima remain obligations everywhere.
public final class CommunityPlacementAvailability {
    private CommunityPlacementAvailability() {}

    public static Map<CommunityPlacement.Location, Integer> effectiveCounts(CommunityPlacement policy,
                                                                            Set<CommunityPlacement.Location> unavailable) {
        var eligible = policy.locations().stream().filter(location -> !unavailable.contains(location)).toList();

        if (eligible.isEmpty()) return policy.desiredCounts();

        int discretionary = policy.targetSize() - policy.locations()
                                                        .stream()
                                                        .mapToInt(CommunityPlacement.Location::minimum)
                                                        .sum();
        long weight = eligible.stream().mapToLong(CommunityPlacement.Location::weight).sum();
        var counts = new LinkedHashMap<CommunityPlacement.Location, Integer>();

        policy.locations().forEach(location -> counts.put(location, location.minimum()));
        eligible.forEach(location -> counts.computeIfPresent(location,
                                                             (_, minimum) -> minimum + (int)((long) discretionary * location.weight() / weight)));
        int remaining = policy.targetSize() - counts.values().stream().mapToInt(Integer::intValue).sum();

        eligible.stream()
                .sorted(Comparator.<CommunityPlacement.Location> comparingLong(location -> - ((long) discretionary * location.weight() % weight))
                                  .thenComparing(CommunityPlacement.Location::source)
                                  .thenComparing(location -> location.zone()
                                                                     .or("")))
                .limit(remaining)
                .forEach(location -> counts.computeIfPresent(location,
                                                             (_, count) -> count + 1));

        return Map.copyOf(counts);
    }

    public static String policyIdentity(CommunityPlacement policy) {
        return policy.id()
             + ":" + policy.targetSize()
             + ":" + policy.locations()
                           .stream()
                           .sorted(Comparator.comparing(CommunityPlacement.Location::source).thenComparing(location -> location.zone()
                                                                                                                               .or("")))
                           .map(location -> location.source()
                                                    .length()
                                           + ":" + location.source()
                                           + ":" + location.zone()
                                                           .or("")
                                                           .length()
                                           + ":" + location.zone()
                                                           .or("")
                                           + ":" + location.minimum()
                                           + ":" + location.weight())
                           .collect(Collectors.joining(";"));
    }

    public static TimeSpan retryDelay(int attempts) {
        return TimeSpan.timeSpan(Math.min(600,
                                          30L << Math.min(5, Math.max(0, attempts - 1)))).seconds();
    }
}
