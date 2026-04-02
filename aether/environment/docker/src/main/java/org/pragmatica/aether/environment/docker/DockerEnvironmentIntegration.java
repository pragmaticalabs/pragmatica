package org.pragmatica.aether.environment.docker;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;

/// Docker implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities only, backed by Docker CLI.
/// Secrets, load balancing, and discovery are not applicable in a local Docker environment.
public record DockerEnvironmentIntegration( DockerComputeProvider computeProvider) implements EnvironmentIntegration {
    /// Factory method for creating a DockerEnvironmentIntegration from configuration.
    public static Result<DockerEnvironmentIntegration> dockerEnvironmentIntegration(DockerConfig config) {
        return dockerEnvironmentIntegration(ProcessCommandRunner.processCommandRunner(), config);
    }

    /// Factory method for creating a DockerEnvironmentIntegration with a custom command runner.
    public static Result<DockerEnvironmentIntegration> dockerEnvironmentIntegration(DockerCommandRunner runner,
                                                                                    DockerConfig config) {
        return DockerComputeProvider.dockerComputeProvider(runner, config)
                                    .map(DockerEnvironmentIntegration::new);
    }

    @Override public Option<ComputeProvider> compute() {
        return some(computeProvider);
    }

    @Override public Option<SecretsProvider> secrets() {
        return empty();
    }

    @Override public Option<LoadBalancerProvider> loadBalancer() {
        return empty();
    }

    @Override public Option<DiscoveryProvider> discovery() {
        return empty();
    }
}
