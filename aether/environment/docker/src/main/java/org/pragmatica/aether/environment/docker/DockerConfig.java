package org.pragmatica.aether.environment.docker;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;


/// Configuration for Docker-based compute provisioning.
/// Controls image name, network, port allocation, and Docker socket path.
public record DockerConfig(String imageName,
                           String networkName,
                           int managementPortBase,
                           int appPortBase,
                           int clusterPort,
                           String socketPath) {
    private static final String DEFAULT_IMAGE_NAME = "aether-node:local";

    private static final String DEFAULT_NETWORK_NAME = "aether-network";

    private static final int DEFAULT_MANAGEMENT_PORT_BASE = 5150;

    private static final int DEFAULT_APP_PORT_BASE = 8070;

    private static final int DEFAULT_CLUSTER_PORT = 6000;

    private static final String DEFAULT_SOCKET_PATH = "/var/run/docker.sock";

    public static Result<DockerConfig> dockerConfig(String imageName,
                                                    String networkName,
                                                    int managementPortBase,
                                                    int appPortBase,
                                                    int clusterPort,
                                                    String socketPath) {
        return success(new DockerConfig(imageName, networkName, managementPortBase, appPortBase, clusterPort, socketPath));
    }

    public static Result<DockerConfig> dockerConfig() {
        return dockerConfig(DEFAULT_IMAGE_NAME,
                            DEFAULT_NETWORK_NAME,
                            DEFAULT_MANAGEMENT_PORT_BASE,
                            DEFAULT_APP_PORT_BASE,
                            DEFAULT_CLUSTER_PORT,
                            DEFAULT_SOCKET_PATH);
    }
}
