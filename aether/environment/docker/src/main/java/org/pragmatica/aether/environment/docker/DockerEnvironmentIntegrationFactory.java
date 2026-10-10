// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.Map;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.docker.DockerConfig.dockerConfig;
import static org.pragmatica.aether.environment.docker.DockerEnvironmentIntegration.dockerEnvironmentIntegration;


public record DockerEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "docker";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildDockerConfig(config).flatMap(DockerEnvironmentIntegration::dockerEnvironmentIntegration)
                                .map(EnvironmentIntegration.class::cast);
    }

    static Result<DockerConfig> buildDockerConfig(CloudConfig config) {
        var compute = config.compute();
        var envNetwork = System.getenv("AETHER_DOCKER_NETWORK");
        var networkName = envNetwork != null && !envNetwork.isBlank()
                          ? envNetwork
                          : compute.getOrDefault("network_name", "aether-network");
        var refusal = Option.option(compute.get(org.pragmatica.aether.environment.ClusterIdentityEnv.BACKUP_PATH))
                            .filter(path -> !path.isBlank())
                            .flatMap(path -> org.pragmatica.aether.environment.BackupPathRule.refusal(path, true));

        if (refusal.isPresent()) {
            return org.pragmatica.aether.environment.EnvironmentError.operationNotSupported("[backup] path " + refusal.unwrap())
                                                                     .result();
        }

        return dockerConfig(compute.getOrDefault("image_name", "aether-node:local"),
                            networkName,
                            resolvePortBase(compute, "management_port_base", "AETHER_MGMT_PORT_BASE", 5150),
                            resolvePortBase(compute, "app_port_base", "AETHER_APP_PORT_BASE", 8070),
                            parseIntOrDefault(compute.getOrDefault("cluster_port", ""), 6000),
                            compute.getOrDefault("socket_path", "/var/run/docker.sock"),
                            compute.getOrDefault("api_key", ""),
                            compute.getOrDefault("docker_gid", ""),
                            parseBoolOrDefault(compute.getOrDefault("expose_host_ports", ""), false)).map(docker -> docker.withBackupEnv(backupEnvOf(compute))
                                                                                                                          .withClusterName(clusterNameOf(compute)));
    }

    /// The cluster the provider is scoped to: `cluster_name` of the compute map (the CLI hands it in), else the host's
    /// `AETHER_CLUSTER_NAME` (a node always has it); blank when neither is set.
    private static String clusterNameOf(Map<String, String> compute) {
        return Option.option(compute.get("cluster_name"))
                     .filter(name -> !name.isBlank())
                     .orElse(() -> Option.option(System.getenv("AETHER_CLUSTER_NAME")).filter(name -> !name.isBlank()))
                     .or("");
    }

    /// The leader's effective `[backup]`, handed in as `AETHER_BACKUP_*` entries of the compute map (`Main` writes them).
    private static Map<String, String> backupEnvOf(Map<String, String> compute) {
        return compute.entrySet()
                      .stream()
                      .filter(entry -> org.pragmatica.aether.environment.ClusterIdentityEnv.BACKUP_VARS.contains(entry.getKey()))
                      .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }

    /// Resolve a port-base setting with the following precedence:
    /// 1. Direct env var (highest — survives even when the TOML field is absent
    ///    or contains an unresolved `${env:...}` literal).
    /// 2. The numeric value in the compute config map.
    /// 3. Numeric `defaultValue`.
    ///
    /// This lets per-cluster docker-compose files set `AETHER_MGMT_PORT_BASE=5150`
    /// (cluster A) vs. `5160` (cluster B) without baking the value into the image's
    /// `aether.toml`, and without forcing every caller to rewrite the TOML overlay.
    private static int resolvePortBase(Map<String, String> compute, String tomlKey, String envVar, int defaultValue) {
        var envValue = System.getenv(envVar);

        if (envValue != null && !envValue.isBlank()) {
            return parseIntOrDefault(envValue, defaultValue);
        }

        return parseIntOrDefault(compute.getOrDefault(tomlKey, ""), defaultValue);
    }

    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value.isEmpty()) {
            return defaultValue;
        }

        return Result.lift(() -> Integer.parseInt(value)).or(defaultValue);
    }

    private static boolean parseBoolOrDefault(String value, boolean defaultValue) {
        if (value.isEmpty()) {
            return defaultValue;
        }

        return Boolean.parseBoolean(value);
    }
}
