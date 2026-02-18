package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.hetzner.HetznerComputeProvider.hetznerComputeProvider;
import static org.pragmatica.aether.environment.hetzner.HetznerLoadBalancerProvider.hetznerLoadBalancerProvider;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Hetzner Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the Hetzner Cloud API.
/// Optionally provides load balancer management when configured.
public record HetznerEnvironmentIntegration(HetznerComputeProvider computeProvider,
                                            Option<LoadBalancerProvider> loadBalancerProvider) implements EnvironmentIntegration {
    /// Factory method for creating a HetznerEnvironmentIntegration from configuration.
    public static Result<HetznerEnvironmentIntegration> hetznerEnvironmentIntegration(HetznerEnvironmentConfig config) {
        var client = HetznerClient.hetznerClient(config.hetznerConfig());
        return hetznerEnvironmentIntegration(client, config);
    }

    /// Factory method for creating a HetznerEnvironmentIntegration with a custom client.
    public static Result<HetznerEnvironmentIntegration> hetznerEnvironmentIntegration(HetznerClient client,
                                                                                      HetznerEnvironmentConfig config) {
        var compute = hetznerComputeProvider(client, config);
        var lbProvider = resolveLbProvider(client, config);
        return Result.all(compute, lbProvider)
                     .map(HetznerEnvironmentIntegration::new);
    }

    // --- Leaf: resolve optional load balancer provider ---
    private static Result<Option<LoadBalancerProvider>> resolveLbProvider(HetznerClient client,
                                                                          HetznerEnvironmentConfig config) {
        return config.loadBalancer()
                     .fold(() -> success(Option.empty()),
                           lbConfig -> toLbOption(client, lbConfig));
    }

    // --- Leaf: create optional LB provider from config ---
    private static Result<Option<LoadBalancerProvider>> toLbOption(HetznerClient client,
                                                                   HetznerEnvironmentConfig.HetznerLbConfig lbConfig) {
        return hetznerLoadBalancerProvider(client, lbConfig.loadBalancerId(), lbConfig.destinationPort())
        .map(HetznerEnvironmentIntegration::wrapInSome);
    }

    // --- Leaf: wrap a LoadBalancerProvider in Option.some ---
    private static Option<LoadBalancerProvider> wrapInSome(HetznerLoadBalancerProvider provider) {
        return some(provider);
    }

    @Override
    public Option<ComputeProvider> compute() {
        return some(computeProvider);
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
