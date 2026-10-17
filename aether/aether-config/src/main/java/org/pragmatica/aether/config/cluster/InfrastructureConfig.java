// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
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
