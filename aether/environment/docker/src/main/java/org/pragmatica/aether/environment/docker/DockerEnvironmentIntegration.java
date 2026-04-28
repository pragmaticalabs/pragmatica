// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.FloatingIpProvider;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.NoopFloatingIpProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.NoopFloatingIpProvider.noopFloatingIpProvider;
import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;


public record DockerEnvironmentIntegration(DockerComputeProvider computeProvider) implements EnvironmentIntegration {
    public static Result<DockerEnvironmentIntegration> dockerEnvironmentIntegration(DockerConfig config) {
        return dockerEnvironmentIntegration(ProcessCommandRunner.processCommandRunner(), config);
    }

    public static Result<DockerEnvironmentIntegration> dockerEnvironmentIntegration(DockerCommandRunner runner,
                                                                                    DockerConfig config) {
        return DockerComputeProvider.dockerComputeProvider(runner, config).map(DockerEnvironmentIntegration::new);
    }

    @Override public Option<ComputeProvider> compute() {
        return some(computeProvider);
    }

    @Override public Option<SecretsProvider> secrets() {
        return empty();
    }

    @Override public Option<LoadBalancerProvider> loadBalancer() {
        return empty();
    }

    @Override public Option<DiscoveryProvider> discovery() {
        return empty();
    }

    @Override public Option<FloatingIpProvider> floatingIp() {
        return some(noopFloatingIpProvider());
    }
}
