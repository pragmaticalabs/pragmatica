// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.resource.ResourceVersion;
import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings("JBCT-UTIL-02") public sealed interface PubSubValidator {
    static Result<List<SliceTopology>> validate(List<SliceTopology> topologies) {
        var addressFailures = validateTopicAddresses(topologies);
        if (!addressFailures.isEmpty()) {
            return ExpanderError.InvalidTopicAddresses.invalidTopicAddresses(addressFailures).result();
        }
        var orphans = findOrphanPublishers(topologies);
        if (orphans.isEmpty()) {return success(topologies);}
        return ExpanderError.OrphanPublishers.orphanPublishers(orphans).result();
    }

    /// Validate every declared topic config's namespace/topic/version grammar and reject the
    /// reserved `system` namespace (and any `system.*` prefix) for app topics. Mirrors
    /// `StreamResourceValidator`'s reserved-namespace gate. Aggregates all diagnostics instead of
    /// short-circuiting at the first one.
    ///
    ///  - Explicitly-namespaced configs (`namespace:topic:version`) are parsed as a [ResourceAddress]
    ///    via [ResourceAddress#validateAppNamespace] on the namespace plus full-address grammar.
    ///  - Bare configs are validated only for kebab-case topic-name grammar; their namespace is
    ///    derived at deploy time from the blueprint coordinates and cannot be `system`.
    private static List<String> validateTopicAddresses(List<SliceTopology> topologies) {
        var diagnostics = new ArrayList<String>();
        declaredTopicConfigs(topologies).forEach(config -> validateTopicConfig(config)
                .onFailure(cause -> diagnostics.add("'" + config + "': " + cause.message())));
        return List.copyOf(diagnostics);
    }

    private static Result<ResourceAddress> validateTopicConfig(String config) {
        if (config != null && config.contains(":")) {
            return ResourceAddress.resourceAddress(config)
                               .flatMap(address -> ResourceAddress.validateAppNamespace(address.namespace()).map(_ -> address));
        }
        return ResourceAddress.resourceAddress(ResourceAddress.DEFAULT_NAMESPACE, config, ResourceVersion.defaultVersion());
    }

    private static Set<String> declaredTopicConfigs(List<SliceTopology> topologies) {
        return topologies.stream()
                         .flatMap(topology -> Stream.concat(topology.publishes().stream().map(SliceTopology.TopicPub::config),
                                                            topology.subscribes().stream().map(SliceTopology.TopicSub::config)))
                         .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private static List<String> findOrphanPublishers(List<SliceTopology> topologies) {
        var subscribedConfigs = collectSubscriberConfigs(topologies);
        return topologies.stream().flatMap(topology -> topology.publishes().stream()
                                                                         .map(SliceTopology.TopicPub::config))
                                .filter(config -> !subscribedConfigs.contains(config))
                                .distinct()
                                .toList();
    }

    private static Set<String> collectSubscriberConfigs(List<SliceTopology> topologies) {
        return topologies.stream().flatMap(topology -> topology.subscribes().stream()
                                                                          .map(SliceTopology.TopicSub::config))
                                .collect(Collectors.toUnmodifiableSet());
    }

    record unused() implements PubSubValidator{}
}
