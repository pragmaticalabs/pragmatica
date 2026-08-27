// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Map;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.aether.config.ConfigKeyLive;


/// `configVersion` is #693: parsed from the TOML file, but no downstream code reads this accessor — the
/// live, KV-store-backed `ClusterConfigValue.configVersion()` (a different, unrelated record used by
/// `ClusterConfigRoutes`/`ClusterTopologyManagerRecord` for the applied/desired topology's own version
/// fencing) is the one every real consumer actually reads. `@ConfigKeyLive`-suppressed rather than
/// deleted: #693 owns the fix, not #519's dead-surface guard.
public record ClusterBootstrapConfig(@ConfigKeyLive("#693: parsed but never read — ClusterConfigValue.configVersion() is the live, unrelated accessor every consumer actually reads") String configVersion,
                                     ClusterIdentity cluster,
                                     CoreTopology coreTopology,
                                     Map<String, SourceProfile> sources,
                                     Map<String, RuntimeProfile> runtimes,
                                     InfrastructureConfig infrastructure,
                                     OperationsConfig operations) {
    public ClusterBootstrapConfig {
        sources = Map.copyOf(sources);
        runtimes = Map.copyOf(runtimes);
    }

    public static ClusterBootstrapConfig clusterBootstrapConfig(String configVersion,
                                                                ClusterIdentity cluster,
                                                                CoreTopology coreTopology,
                                                                Map<String, SourceProfile> sources,
                                                                Map<String, RuntimeProfile> runtimes,
                                                                InfrastructureConfig infrastructure,
                                                                OperationsConfig operations) {
        return new ClusterBootstrapConfig(configVersion,
                                          cluster,
                                          coreTopology,
                                          sources,
                                          runtimes,
                                          infrastructure,
                                          operations);
    }

    public Result<ClusterBootstrapConfig> withClusterName(String newName) {
        return cluster.withName(newName)
                      .map(updated -> new ClusterBootstrapConfig(configVersion,
                                                                 updated,
                                                                 coreTopology,
                                                                 sources,
                                                                 runtimes,
                                                                 infrastructure,
                                                                 operations));
    }

    public int derivedCoreCount() {
        return sources.values()
                      .stream()
                      .flatMap(s -> Option.option(s.roles().get(NodeRole.CORE)).stream())
                      .mapToInt(ClusterBootstrapConfig::roleSize)
                      .sum();
    }

    private static int roleSize(RoleSubTable role) {
        return role.count()
                   .or(0) + role.hosts()
                                .map(List::size)
                                .or(0);
    }
}
