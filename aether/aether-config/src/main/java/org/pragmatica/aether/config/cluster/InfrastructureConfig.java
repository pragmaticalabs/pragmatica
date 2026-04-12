package org.pragmatica.aether.config.cluster;

public record InfrastructureConfig(NetworkingType networkingType) {
    public static InfrastructureConfig infrastructureConfig(NetworkingType networkingType) {
        return new InfrastructureConfig(networkingType);
    }
}
