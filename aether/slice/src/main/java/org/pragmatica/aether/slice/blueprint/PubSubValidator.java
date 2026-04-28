// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.blueprint;

import org.pragmatica.aether.slice.topology.SliceTopology;
import org.pragmatica.lang.Result;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.pragmatica.lang.Result.success;


@SuppressWarnings("JBCT-UTIL-02") public sealed interface PubSubValidator {
    static Result<List<SliceTopology>> validate(List<SliceTopology> topologies) {
        var orphans = findOrphanPublishers(topologies);
        if (orphans.isEmpty()) {return success(topologies);}
        return ExpanderError.OrphanPublishers.orphanPublishers(orphans).result();
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
