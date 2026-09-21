// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.List;
import java.util.stream.Collectors;

import org.pragmatica.aether.config.cluster.ClusterBootstrapConfig;
import org.pragmatica.aether.config.cluster.ClusterConfigError;
import org.pragmatica.aether.config.cluster.CommunityPlacement;
import org.pragmatica.aether.config.cluster.NodeRole;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterConfigValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.TopologyEntry;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// One worker lifecycle. Omitted policies keep the established source-w-0 identity;
/// their target follows the committed topology, including reactive scaling updates.
public interface CommunityPolicies {
    static Result<ClusterBootstrapConfig> normalize(ClusterBootstrapConfig config, ClusterConfigValue intent) {
        if (!config.communities().isEmpty()) return Result.success(config);

        return Result.allOf(intent.desiredTopology()
                                  .stream()
                                  .filter(entry -> entry.role()
                                                        .equals("worker"))
                                  .map(entry -> implicitCommunity(config, entry))).flatMap(policies -> withCommunities(config,
                                                                                                                       policies));
    }

    private static Result<CommunityPlacement> implicitCommunity(ClusterBootstrapConfig config, TopologyEntry entry) {
        return Option.option(config.sources().get(entry.sourceName()))
                     .filter(source -> source.roles()
                                             .containsKey(NodeRole.WORKER))
                     .toResult(new ClusterConfigError.ParseFailed("Worker topology requires a configured worker source: " + entry.sourceName()))
                     .flatMap(_ -> CommunityPlacement.communityPlacement(entry.sourceName() + "-w-0",
                                                                         entry.count(),
                                                                         List.of(new CommunityPlacement.Location(entry.sourceName(),
                                                                                                                 Option.none(),
                                                                                                                 0,
                                                                                                                 1))));
    }

    private static Result<ClusterBootstrapConfig> withCommunities(ClusterBootstrapConfig config,
                                                                  List<CommunityPlacement> policies) {
        if (policies.stream().map(CommunityPlacement::id).distinct().count() != policies.size()) {
            return new ClusterConfigError.ParseFailed("Duplicate worker capacity source in topology").result();
        }

        var communities = policies.stream()
                                  .collect(Collectors.toUnmodifiableMap(CommunityPlacement::id, policy -> policy));

        return Result.success(ClusterBootstrapConfig.clusterBootstrapConfig(config.configVersion(),
                                                                            config.cluster(),
                                                                            config.coreTopology(),
                                                                            config.sources(),
                                                                            config.runtimes(),
                                                                            config.infrastructure(),
                                                                            config.operations(),
                                                                            communities));
    }
}
