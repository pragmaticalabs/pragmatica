package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.CachingSecretsProvider;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvSecretsProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.cloud.hetzner.HetznerClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.hetzner.HetznerComputeProvider.hetznerComputeProvider;
import static org.pragmatica.aether.environment.hetzner.HetznerDiscoveryProvider.hetznerDiscoveryProvider;
import static org.pragmatica.aether.environment.hetzner.HetznerLoadBalancerProvider.hetznerLoadBalancerProvider;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// Hetzner Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the Hetzner Cloud API.
/// Optionally provides load balancer management and label-based discovery when configured.
/// Always provides environment-variable-based secrets resolution.
public record HetznerEnvironmentIntegration( HetznerComputeProvider computeProvider,
                                             Option<LoadBalancerProvider> loadBalancerProvider,
                                             Option<DiscoveryProvider> discoveryProvider,
                                             Option<SecretsProvider> secretsProvider) implements EnvironmentIntegration {
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
        var discovery = resolveDiscoveryProvider(client, config);
        var secrets = resolveSecretsProvider();
        return Result.all(compute, lbProvider)
        .map((cp, lb) -> new HetznerEnvironmentIntegration(cp, lb, discovery, secrets));
    }

    // --- Leaf: resolve optional load balancer provider ---
    private static Result<Option<LoadBalancerProvider>> resolveLbProvider(HetznerClient client,
                                                                          HetznerEnvironmentConfig config) {
        return config.loadBalancer().fold(() -> success(Option.empty()), lbConfig -> toLbOption(client, lbConfig));
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

    // --- Leaf: resolve optional discovery provider based on clusterName ---
    private static Option<DiscoveryProvider> resolveDiscoveryProvider(HetznerClient client,
                                                                      HetznerEnvironmentConfig config) {
        return config.clusterName().map(name -> hetznerDiscoveryProvider(client, config));
    }

    // --- Leaf: resolve secrets provider (env vars with TTL cache) ---
    private static Option<SecretsProvider> resolveSecretsProvider() {
        return some(CachingSecretsProvider.cachingSecretsProvider(EnvSecretsProvider.envSecretsProvider(),
                                                                  TimeSpan.timeSpan(5).minutes()));
    }

    @Override public Option<ComputeProvider> compute() {
        return some(computeProvider);
    }

    @Override public Option<SecretsProvider> secrets() {
        return secretsProvider;
    }

    @Override public Option<LoadBalancerProvider> loadBalancer() {
        return loadBalancerProvider;
    }

    @Override public Option<DiscoveryProvider> discovery() {
        return discoveryProvider;
    }
}
