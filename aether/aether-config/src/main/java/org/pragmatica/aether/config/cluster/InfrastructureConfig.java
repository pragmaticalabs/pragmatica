package org.pragmatica.aether.config.cluster;

/// Infrastructure configuration. S6.1 REQ-6.1.1
public record InfrastructureConfig(NetworkingType networkingType) {
    public static InfrastructureConfig infrastructureConfig(NetworkingType networkingType) {
        return new InfrastructureConfig(networkingType);
    }
}
