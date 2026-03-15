package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Result.success;

/// Validates that all publisher topics in a blueprint have at least one subscriber.
///
/// This is a deploy-time validation: if any publisher config section has no matching
/// subscriber config section across the entire blueprint, deployment is rejected.
@SuppressWarnings("JBCT-UTIL-02")
public sealed interface PubSubValidator {
    /// Validate that all publisher topics have at least one subscriber.
    ///
    /// @param topologies list of slice topologies from the blueprint
    ///
    /// @return success with the input topologies if valid, or failure with orphan topic details
    static Result<List<SliceTopology>> validate(List<SliceTopology> topologies) {
        var orphans = findOrphanPublishers(topologies);
        if (orphans.isEmpty()) {
            return success(topologies);
        }
        return ExpanderError.OrphanPublishers.orphanPublishers(orphans)
                            .result();
    }

    private static List<String> findOrphanPublishers(List<SliceTopology> topologies) {
        var subscribedConfigs = collectSubscriberConfigs(topologies);
        return topologies.stream()
                         .flatMap(topology -> topology.publishes()
                                                      .stream()
                                                      .map(SliceTopology.TopicPub::config))
                         .filter(config -> !subscribedConfigs.contains(config))
                         .distinct()
                         .toList();
    }

    private static Set<String> collectSubscriberConfigs(List<SliceTopology> topologies) {
        return topologies.stream()
                         .flatMap(topology -> topology.subscribes()
                                                      .stream()
                                                      .map(SliceTopology.TopicSub::config))
                         .collect(Collectors.toUnmodifiableSet());
    }

    record unused() implements PubSubValidator {}
}
