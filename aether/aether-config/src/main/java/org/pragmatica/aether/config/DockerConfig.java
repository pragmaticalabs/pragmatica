package org.pragmatica.aether.config;

import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Result.success;

/// Docker-specific configuration.
///
/// @param network Docker network name
/// @param image   Docker image to use for nodes
public record DockerConfig(String network,
                           String image) {
    public static final String DEFAULT_NETWORK = "aether-network";
    public static final String DEFAULT_IMAGE = "ghcr.io/siy/aether-node:latest";

    /// Factory method following JBCT naming convention.
    public static Result<DockerConfig> dockerConfig(String network, String image) {
        return success(new DockerConfig(network, image));
    }

    /// Default Docker configuration.
    public static DockerConfig dockerConfig() {
        return dockerConfig(DEFAULT_NETWORK, DEFAULT_IMAGE).unwrap();
    }

    public DockerConfig withNetwork(String network) {
        return dockerConfig(network, image).unwrap();
    }

    public DockerConfig withImage(String image) {
        return dockerConfig(network, image).unwrap();
    }
}
