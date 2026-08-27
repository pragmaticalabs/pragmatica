// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;
import org.pragmatica.aether.config.ConfigKeyLive;


/// `networkingType` is #693: parsed from `[infra.networking].type` (defaulting to `MANUAL`) by
/// `ClusterBootstrapConfigParser.parseInfrastructure`, but no downstream code reads this accessor.
/// `@ConfigKeyLive`-suppressed rather than deleted: #693 owns the fix, not #519's dead-surface guard.
public record InfrastructureConfig(@ConfigKeyLive("#693: parsed but never read downstream") NetworkingType networkingType,
                                   Option<SshDeploymentConfig> ssh) {
    public static InfrastructureConfig infrastructureConfig(NetworkingType networkingType) {
        return new InfrastructureConfig(networkingType, Option.empty());
    }

    public static InfrastructureConfig infrastructureConfig(NetworkingType networkingType,
                                                            Option<SshDeploymentConfig> ssh) {
        return new InfrastructureConfig(networkingType, ssh);
    }
}
