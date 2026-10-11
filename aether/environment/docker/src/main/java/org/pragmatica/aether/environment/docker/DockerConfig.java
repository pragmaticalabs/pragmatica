// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import java.util.Map;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record DockerConfig(String imageName,
                           String networkName,
                           int managementPortBase,
                           int appPortBase,
                           int clusterPort,
                           String socketPath,
                           String apiKey,
                           String dockerGid,
                           boolean exposeHostPorts,
                           Map<String, String> backupEnv) {
    /// `backupEnv` (#1968): the leader's EFFECTIVE `[backup]` as `AETHER_BACKUP_*` variables, whatever its source (TOML or
    /// environment). A Docker replacement has no node TOML, so this is what carries the backup to it; empty falls back to the
    /// provisioning host's own `AETHER_BACKUP_*` environment.
    public DockerConfig {
        backupEnv = Map.copyOf(backupEnv);
    }

    private static final String DEFAULT_IMAGE_NAME = "aether-node:local";
    private static final String DEFAULT_NETWORK_NAME = "aether-network";
    private static final int DEFAULT_MANAGEMENT_PORT_BASE = 5150;
    private static final int DEFAULT_APP_PORT_BASE = 8070;
    private static final int DEFAULT_CLUSTER_PORT = 6000;
    private static final String DEFAULT_SOCKET_PATH = "/var/run/docker.sock";
    private static final String DEFAULT_API_KEY = "";
    private static final String DEFAULT_DOCKER_GID = "";
    private static final boolean DEFAULT_EXPOSE_HOST_PORTS = false;

    public static Result<DockerConfig> dockerConfig(String imageName,
                                                    String networkName,
                                                    int managementPortBase,
                                                    int appPortBase,
                                                    int clusterPort,
                                                    String socketPath,
                                                    String apiKey,
                                                    String dockerGid) {
        return dockerConfig(imageName,
                            networkName,
                            managementPortBase,
                            appPortBase,
                            clusterPort,
                            socketPath,
                            apiKey,
                            dockerGid,
                            DEFAULT_EXPOSE_HOST_PORTS);
    }

    public static Result<DockerConfig> dockerConfig(String imageName,
                                                    String networkName,
                                                    int managementPortBase,
                                                    int appPortBase,
                                                    int clusterPort,
                                                    String socketPath,
                                                    String apiKey,
                                                    String dockerGid,
                                                    boolean exposeHostPorts) {
        return success(new DockerConfig(imageName,
                                        networkName,
                                        managementPortBase,
                                        appPortBase,
                                        clusterPort,
                                        socketPath,
                                        apiKey,
                                        dockerGid,
                                        exposeHostPorts,
                                        Map.of()));
    }

    public DockerConfig withBackupEnv(Map<String, String> backupEnv) {
        return new DockerConfig(imageName,
                                networkName,
                                managementPortBase,
                                appPortBase,
                                clusterPort,
                                socketPath,
                                apiKey,
                                dockerGid,
                                exposeHostPorts,
                                backupEnv);
    }

    public static Result<DockerConfig> dockerConfig() {
        return dockerConfig(DEFAULT_IMAGE_NAME,
                            DEFAULT_NETWORK_NAME,
                            DEFAULT_MANAGEMENT_PORT_BASE,
                            DEFAULT_APP_PORT_BASE,
                            DEFAULT_CLUSTER_PORT,
                            DEFAULT_SOCKET_PATH,
                            DEFAULT_API_KEY,
                            DEFAULT_DOCKER_GID,
                            DEFAULT_EXPOSE_HOST_PORTS);
    }
}
