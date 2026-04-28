// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.docker;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


public record DockerConfig(String imageName,
                           String networkName,
                           int managementPortBase,
                           int appPortBase,
                           int clusterPort,
                           String socketPath,
                           String apiKey,
                           String dockerGid) {
    private static final String DEFAULT_IMAGE_NAME = "aether-node:local";

    private static final String DEFAULT_NETWORK_NAME = "aether-network";

    private static final int DEFAULT_MANAGEMENT_PORT_BASE = 5150;

    private static final int DEFAULT_APP_PORT_BASE = 8070;

    private static final int DEFAULT_CLUSTER_PORT = 6000;

    private static final String DEFAULT_SOCKET_PATH = "/var/run/docker.sock";

    private static final String DEFAULT_API_KEY = "";

    private static final String DEFAULT_DOCKER_GID = "";

    public static Result<DockerConfig> dockerConfig(String imageName,
                                                    String networkName,
                                                    int managementPortBase,
                                                    int appPortBase,
                                                    int clusterPort,
                                                    String socketPath,
                                                    String apiKey,
                                                    String dockerGid) {
        return success(new DockerConfig(imageName,
                                        networkName,
                                        managementPortBase,
                                        appPortBase,
                                        clusterPort,
                                        socketPath,
                                        apiKey,
                                        dockerGid));
    }

    public static Result<DockerConfig> dockerConfig() {
        return dockerConfig(DEFAULT_IMAGE_NAME,
                            DEFAULT_NETWORK_NAME,
                            DEFAULT_MANAGEMENT_PORT_BASE,
                            DEFAULT_APP_PORT_BASE,
                            DEFAULT_CLUSTER_PORT,
                            DEFAULT_SOCKET_PATH,
                            DEFAULT_API_KEY,
                            DEFAULT_DOCKER_GID);
    }
}
