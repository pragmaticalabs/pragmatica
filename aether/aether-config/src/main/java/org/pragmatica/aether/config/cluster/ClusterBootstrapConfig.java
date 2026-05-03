// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;

import java.util.List;
import java.util.Map;


public record ClusterBootstrapConfig(String configVersion,
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

    public ClusterBootstrapConfig withClusterName(String newName) {
        return new ClusterBootstrapConfig(configVersion,
                                          cluster.withName(newName),
                                          coreTopology,
                                          sources,
                                          runtimes,
                                          infrastructure,
                                          operations);
    }

    public int derivedCoreCount() {
        return sources.values().stream()
                             .flatMap(s -> Option.option(s.roles().get(NodeRole.CORE)).stream())
                             .mapToInt(ClusterBootstrapConfig::roleSize)
                             .sum();
    }

    private static int roleSize(RoleSubTable role) {
        return role.count().or(0) + role.hosts().map(List::size)
                                              .or(0);
    }
}
