package org.pragmatica.aether.environment.docker;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.docker.DockerConfig.dockerConfig;
import static org.pragmatica.aether.environment.docker.DockerEnvironmentIntegration.dockerEnvironmentIntegration;

/// ServiceLoader factory for creating DockerEnvironmentIntegration from generic CloudConfig.
public record DockerEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override public String providerName() {
        return "docker";
    }

    @Override public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildDockerConfig(config)
                   .flatMap(DockerEnvironmentIntegration::dockerEnvironmentIntegration)
                   .map(EnvironmentIntegration.class::cast);
    }

    // --- Leaf: assemble DockerConfig from generic cloud config maps ---
    private static Result<DockerConfig> buildDockerConfig(CloudConfig config) {
        var compute = config.compute();
        return dockerConfig(
            compute.getOrDefault("image_name", "aether-node:local"),
            compute.getOrDefault("network_name", "aether-network"),
            parseIntOrDefault(compute.getOrDefault("management_port_base", ""), 5150),
            parseIntOrDefault(compute.getOrDefault("app_port_base", ""), 8070),
            parseIntOrDefault(compute.getOrDefault("cluster_port", ""), 6000),
            compute.getOrDefault("socket_path", "/var/run/docker.sock"));
    }

    // --- Leaf: parse integer with fallback default ---
    private static int parseIntOrDefault(String value, int defaultValue) {
        if (value.isEmpty()) {
            return defaultValue;
        }
        return Result.lift(() -> Integer.parseInt(value)).or(defaultValue);
    }
}
