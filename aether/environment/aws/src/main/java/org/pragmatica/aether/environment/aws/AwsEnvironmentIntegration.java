package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.CachingSecretsProvider;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.aether.environment.aws.AwsComputeProvider.awsComputeProvider;
import static org.pragmatica.aether.environment.aws.AwsDiscoveryProvider.awsDiscoveryProvider;
import static org.pragmatica.aether.environment.aws.AwsLoadBalancerProvider.awsLoadBalancerProvider;
import static org.pragmatica.aether.environment.aws.AwsSecretsProvider.awsSecretsProvider;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;

/// AWS Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the AWS EC2 API.
/// Optionally provides ELBv2 load balancer management and tag-based discovery when configured.
/// Always provides AWS Secrets Manager-based secrets resolution.
public record AwsEnvironmentIntegration( AwsComputeProvider computeProvider,
                                         Option<LoadBalancerProvider> loadBalancerProvider,
                                         Option<DiscoveryProvider> discoveryProvider,
                                         Option<SecretsProvider> secretsProvider) implements EnvironmentIntegration {
    /// Factory method for creating an AwsEnvironmentIntegration from configuration.
    public static Result<AwsEnvironmentIntegration> awsEnvironmentIntegration(AwsEnvironmentConfig config) {
        var client = AwsClient.awsClient(config.awsConfig());
        return awsEnvironmentIntegration(client, config);
    }

    /// Factory method for creating an AwsEnvironmentIntegration with a custom client.
    public static Result<AwsEnvironmentIntegration> awsEnvironmentIntegration(AwsClient client,
                                                                              AwsEnvironmentConfig config) {
        var compute = awsComputeProvider(client, config);
        var lbProvider = resolveLbProvider(client, config);
        var discovery = resolveDiscoveryProvider(client, config);
        var secrets = resolveSecretsProvider(client);
        return Result.all(compute, lbProvider)
        .map((cp, lb) -> new AwsEnvironmentIntegration(cp, lb, discovery, secrets));
    }

    // --- Leaf: resolve optional load balancer provider ---
    private static Result<Option<LoadBalancerProvider>> resolveLbProvider(AwsClient client,
                                                                          AwsEnvironmentConfig config) {
        return config.loadBalancer().fold(() -> success(Option.empty()), lbConfig -> toLbOption(client, lbConfig));
    }

    // --- Leaf: create optional LB provider from config ---
    private static Result<Option<LoadBalancerProvider>> toLbOption(AwsClient client,
                                                                   AwsEnvironmentConfig.AwsLbConfig lbConfig) {
        return awsLoadBalancerProvider(client, lbConfig.targetGroupArn()).map(AwsEnvironmentIntegration::wrapInSome);
    }

    // --- Leaf: wrap a LoadBalancerProvider in Option.some ---
    private static Option<LoadBalancerProvider> wrapInSome(AwsLoadBalancerProvider provider) {
        return some(provider);
    }

    // --- Leaf: resolve optional discovery provider based on clusterName ---
    private static Option<DiscoveryProvider> resolveDiscoveryProvider(AwsClient client,
                                                                      AwsEnvironmentConfig config) {
        return config.clusterName().map(name -> awsDiscoveryProvider(client, config));
    }

    // --- Leaf: resolve secrets provider (AWS Secrets Manager with TTL cache) ---
    private static Option<SecretsProvider> resolveSecretsProvider(AwsClient client) {
        return some(CachingSecretsProvider.cachingSecretsProvider(awsSecretsProvider(client).unwrap(),
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
