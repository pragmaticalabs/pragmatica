package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.environment.hetzner.HetznerComputeProvider.hetznerComputeProvider;

/// Hetzner Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the Hetzner Cloud API.
/// Secrets management is not yet supported.
public record HetznerEnvironmentIntegration(HetznerComputeProvider computeProvider) implements EnvironmentIntegration {

    /// Factory method for creating a HetznerEnvironmentIntegration from configuration.
    public static HetznerEnvironmentIntegration hetznerEnvironmentIntegration(HetznerEnvironmentConfig config) {
        var client = HetznerClient.hetznerClient(config.hetznerConfig());
        return new HetznerEnvironmentIntegration(hetznerComputeProvider(client, config));
    }

    /// Factory method for creating a HetznerEnvironmentIntegration with a custom client.
    public static HetznerEnvironmentIntegration hetznerEnvironmentIntegration(HetznerClient client,
                                                                               HetznerEnvironmentConfig config) {
        return new HetznerEnvironmentIntegration(hetznerComputeProvider(client, config));
    }

    @Override
    public Option<ComputeProvider> compute() {
        return Option.some(computeProvider);
    }

    @Override
    public Option<SecretsProvider> secrets() {
        return Option.empty();
    }
}
