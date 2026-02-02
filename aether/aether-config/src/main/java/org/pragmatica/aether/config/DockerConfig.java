package org.pragmatica.aether.config;
/**
 * Docker-specific configuration.
 *
 * @param network Docker network name
 * @param image   Docker image to use for nodes
 */
public record DockerConfig(String network,
                           String image) {
    public static final String DEFAULT_NETWORK = "aether-network";
    public static final String DEFAULT_IMAGE = "ghcr.io/siy/aether-node:latest";

    public static DockerConfig defaults() {
        return new DockerConfig(DEFAULT_NETWORK, DEFAULT_IMAGE);
    }

    public DockerConfig withNetwork(String network) {
        return new DockerConfig(network, image);
    }

    public DockerConfig withImage(String image) {
        return new DockerConfig(network, image);
    }
}
