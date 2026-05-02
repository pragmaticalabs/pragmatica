// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.docker.DockerConfig.dockerConfig;
import static org.pragmatica.aether.environment.docker.DockerEnvironmentIntegration.dockerEnvironmentIntegration;


public record DockerEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override public String providerName() {
        return "docker";
    }

    @Override public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildDockerConfig(config).flatMap(DockerEnvironmentIntegration::dockerEnvironmentIntegration)
                                .map(EnvironmentIntegration.class::cast);
    }

    private static Result<DockerConfig> buildDockerConfig(CloudConfig config) {
        var compute = config.compute();
        var envNetwork = System.getenv("AETHER_DOCKER_NETWORK");
        var networkName = envNetwork != null && !envNetwork.isBlank()
                         ? envNetwork
                         : compute.getOrDefault("network_name", "aether-network");
        return dockerConfig(compute.getOrDefault("image_name", "aether-node:local"),
                            networkName,
                            parseIntOrDefault(compute.getOrDefault("management_port_base", ""), 5150),
                            parseIntOrDefault(compute.getOrDefault("app_port_base", ""), 8070),
                            parseIntOrDefault(compute.getOrDefault("cluster_port", ""), 6000),
                            compute.getOrDefault("socket_path", "/var/run/docker.sock"),
                            compute.getOrDefault("api_key", ""),
                            compute.getOrDefault("docker_gid", ""),
                            parseBoolOrDefault(compute.getOrDefault("expose_host_ports", ""), false));
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value.isEmpty()) {return defaultValue;}
        return Result.lift(() -> Integer.parseInt(value)).or(defaultValue);
    }

    private static boolean parseBoolOrDefault(String value, boolean defaultValue) {
        if (value.isEmpty()) {return defaultValue;}
        return Boolean.parseBoolean(value);
    }
}
