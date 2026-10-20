// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


public record InfrastructureConfig(Option<SshDeploymentConfig> ssh) {
    public static InfrastructureConfig infrastructureConfig() {
        return new InfrastructureConfig(Option.empty());
    }

    public static InfrastructureConfig infrastructureConfig(Option<SshDeploymentConfig> ssh) {
        return new InfrastructureConfig(ssh);
    }
}
