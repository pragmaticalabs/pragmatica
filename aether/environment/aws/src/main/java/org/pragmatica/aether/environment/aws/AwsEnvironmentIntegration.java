package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.CachingSecretsProvider;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.aws.AwsClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.security.CertificateProvider;

import static org.pragmatica.aether.environment.aws.AwsCertificateProvider.awsCertificateProvider;
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
public record AwsEnvironmentIntegration(AwsComputeProvider computeProvider,
                                        Option<LoadBalancerProvider> loadBalancerProvider,
                                        Option<DiscoveryProvider> discoveryProvider,
                                        Option<SecretsProvider> secretsProvider,
                                        Option<CertificateProvider> certProvider) implements EnvironmentIntegration {
    public static Result<AwsEnvironmentIntegration> awsEnvironmentIntegration(AwsEnvironmentConfig config) {
        var client = AwsClient.awsClient(config.awsConfig());
        return awsEnvironmentIntegration(client, config);
    }

    public static Result<AwsEnvironmentIntegration> awsEnvironmentIntegration(AwsClient client,
                                                                              AwsEnvironmentConfig config) {
        var compute = awsComputeProvider(client, config);
        var lbProvider = resolveLbProvider(client, config);
        var discovery = resolveDiscoveryProvider(client, config);
        var secrets = resolveSecretsProvider(client);
        var certProv = resolveCertificateProvider(client, config);
        return Result.all(compute, lbProvider)
                         .map((cp, lb) -> new AwsEnvironmentIntegration(cp, lb, discovery, secrets, certProv));
    }

    private static Result<Option<LoadBalancerProvider>> resolveLbProvider(AwsClient client,
                                                                          AwsEnvironmentConfig config) {
        return config.loadBalancer().fold(() -> success(Option.empty()), lbConfig -> toLbOption(client, lbConfig));
    }

    private static Result<Option<LoadBalancerProvider>> toLbOption(AwsClient client,
                                                                   AwsEnvironmentConfig.AwsLbConfig lbConfig) {
        return awsLoadBalancerProvider(client, lbConfig.targetGroupArn()).map(AwsEnvironmentIntegration::wrapInSome);
    }

    private static Option<LoadBalancerProvider> wrapInSome(AwsLoadBalancerProvider provider) {
        return some(provider);
    }

    private static Option<DiscoveryProvider> resolveDiscoveryProvider(AwsClient client, AwsEnvironmentConfig config) {
        return config.clusterName().map(name -> awsDiscoveryProvider(client, config));
    }

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

    @Override public Option<CertificateProvider> certificateProvider() {
        return certProvider;
    }

    private static Option<CertificateProvider> resolveCertificateProvider(AwsClient client,
                                                                          AwsEnvironmentConfig config) {
        return config.certificateSecretPrefix()
                     .flatMap(prefix -> awsCertificateProvider(client, prefix)
                                            .map(CertificateProvider.class::cast)
                                            .option());
    }
}
