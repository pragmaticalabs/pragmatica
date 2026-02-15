package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Option;

import static org.pragmatica.aether.environment.hetzner.HetznerComputeProvider.hetznerComputeProvider;
import static org.pragmatica.aether.environment.hetzner.HetznerLoadBalancerProvider.hetznerLoadBalancerProvider;

/// Hetzner Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the Hetzner Cloud API.
/// Optionally provides load balancer management when configured.
public record HetznerEnvironmentIntegration(HetznerComputeProvider computeProvider,
                                            Option<LoadBalancerProvider> loadBalancerProvider) implements EnvironmentIntegration {

    /// Factory method for creating a HetznerEnvironmentIntegration from configuration.
    public static HetznerEnvironmentIntegration hetznerEnvironmentIntegration(HetznerEnvironmentConfig config) {
        var client = HetznerClient.hetznerClient(config.hetznerConfig());
        return hetznerEnvironmentIntegration(client, config);
    }

    /// Factory method for creating a HetznerEnvironmentIntegration with a custom client.
    public static HetznerEnvironmentIntegration hetznerEnvironmentIntegration(HetznerClient client,
                                                                               HetznerEnvironmentConfig config) {
        var compute = hetznerComputeProvider(client, config);
        Option<LoadBalancerProvider> lb = config.loadBalancer()
                                                .map(lbConfig -> createLbProvider(client, lbConfig));
        return new HetznerEnvironmentIntegration(compute, lb);
    }

    // --- Leaf: create load balancer provider from config ---

    private static LoadBalancerProvider createLbProvider(HetznerClient client,
                                                         HetznerEnvironmentConfig.HetznerLbConfig lbConfig) {
        return hetznerLoadBalancerProvider(client, lbConfig.loadBalancerId(), lbConfig.destinationPort());
    }

    @Override
    public Option<ComputeProvider> compute() {
        return Option.some(computeProvider);
    }

    @Override
    public Option<SecretsProvider> secrets() {
        return Option.empty();
    }

    @Override
    public Option<LoadBalancerProvider> loadBalancer() {
        return loadBalancerProvider;
    }
}
