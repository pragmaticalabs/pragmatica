// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


public record ClusterBootstrapConfig(ClusterIdentity cluster,
                                     CoreTopology coreTopology,
                                     Map<String, SourceProfile> sources,
                                     Map<String, RuntimeProfile> runtimes,
                                     InfrastructureConfig infrastructure,
                                     OperationsConfig operations,
                                     Map<String, CommunityPlacement> communities) {
    public ClusterBootstrapConfig {
        communities = Map.copyOf(communities);
        sources = Map.copyOf(sources);
        runtimes = Map.copyOf(runtimes);
    }

    public static ClusterBootstrapConfig clusterBootstrapConfig(ClusterIdentity cluster,
                                                                CoreTopology coreTopology,
                                                                Map<String, SourceProfile> sources,
                                                                Map<String, RuntimeProfile> runtimes,
                                                                InfrastructureConfig infrastructure,
                                                                OperationsConfig operations,
                                                                Map<String, CommunityPlacement> communities) {
        return new ClusterBootstrapConfig(cluster,
                                          coreTopology,
                                          sources,
                                          runtimes,
                                          infrastructure,
                                          operations,
                                          communities);
    }

    public Result<ClusterBootstrapConfig> withClusterName(String newName) {
        return cluster.withName(newName)
                      .map(updated -> new ClusterBootstrapConfig(updated,
                                                                 coreTopology,
                                                                 sources,
                                                                 runtimes,
                                                                 infrastructure,
                                                                 operations,
                                                                 communities));
    }

    public int derivedCoreCount() {
        return sources.values()
                      .stream()
                      .flatMap(s -> Option.option(s.roles().get(NodeRole.CORE)).stream())
                      .mapToInt(ClusterBootstrapConfig::roleSize)
                      .sum();
    }

    /// #1543 — the ids of the INITIAL cores this bootstrap provisions, `<source>-core-<index>`, the same
    /// minting `BootstrapPhaseProvision` uses. Rendered as `cluster.genesis_voters` so a CLI-bootstrapped
    /// cluster forms exactly one epoch-0 configuration. Empty — render nothing — when a core-bearing
    /// source is DOCKER or FORGE: their node ids are not minted by this scheme, so naming them would
    /// make genesis wait for ids that never announce.
    public List<String> initialCoreIds() {
        var coreSources = sources.entrySet()
                                 .stream()
                                 .sorted(Map.Entry.comparingByKey())
                                 .filter(entry -> Option.option(entry.getValue().roles().get(NodeRole.CORE)).isPresent())
                                 .toList();
        var unnameable = coreSources.stream()
                                    .anyMatch(entry -> entry.getValue()
                                                            .type() == SourceType.DOCKER || entry.getValue()
                                                                                                 .type() == SourceType.FORGE);

        return unnameable
               ? List.of()
               : coreSources.stream()
                            .flatMap(entry -> coreIds(SourceName.sourceNameOrDefault(entry.getKey()),
                                                      entry.getValue().roles().get(NodeRole.CORE)))
                            .toList();
    }

    private static Stream<String> coreIds(SourceName sourceName, RoleSubTable core) {
        return IntStream.range(0, roleSize(core)).mapToObj(index -> sourceName.value() + "-core-" + index);
    }

    private static int roleSize(RoleSubTable role) {
        return role.count()
                   .or(0) + role.hosts()
                                .map(List::size)
                                .or(0);
    }
}
