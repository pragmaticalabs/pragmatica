package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.CachingSecretsProvider;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.gcp.GcpClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.security.CertificateProvider;

import static org.pragmatica.aether.environment.gcp.GcpCertificateProvider.gcpCertificateProvider;
import static org.pragmatica.aether.environment.gcp.GcpComputeProvider.gcpComputeProvider;
import static org.pragmatica.aether.environment.gcp.GcpDiscoveryProvider.gcpDiscoveryProvider;
import static org.pragmatica.aether.environment.gcp.GcpLoadBalancerProvider.gcpLoadBalancerProvider;
import static org.pragmatica.aether.environment.gcp.GcpSecretsProvider.gcpSecretsProvider;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// GCP Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the GCP Compute Engine API.
/// Optionally provides NEG-based load balancing and label-based discovery when configured.
/// Always provides GCP Secret Manager-based secrets resolution.
public record GcpEnvironmentIntegration(GcpComputeProvider computeProvider,
                                        Option<LoadBalancerProvider> loadBalancerProvider,
                                        Option<DiscoveryProvider> discoveryProvider,
                                        Option<SecretsProvider> secretsProvider,
                                        Option<CertificateProvider> certProvider) implements EnvironmentIntegration {
    public static Result<GcpEnvironmentIntegration> gcpEnvironmentIntegration(GcpEnvironmentConfig config) {
        var client = GcpClient.gcpClient(config.gcpConfig());
        return gcpEnvironmentIntegration(client, config);
    }

    public static Result<GcpEnvironmentIntegration> gcpEnvironmentIntegration(GcpClient client,
                                                                              GcpEnvironmentConfig config) {
        var compute = gcpComputeProvider(client, config);
        var lbProvider = resolveLbProvider(client, config);
        var discovery = resolveDiscoveryProvider(client, config);
        var secrets = resolveSecretsProvider(client);
        var certProv = resolveCertificateProvider(client, config);
        return Result.all(compute, lbProvider)
                         .map((cp, lb) -> new GcpEnvironmentIntegration(cp, lb, discovery, secrets, certProv));
    }

    private static Result<Option<LoadBalancerProvider>> resolveLbProvider(GcpClient client,
                                                                          GcpEnvironmentConfig config) {
        return config.networkEndpointGroup()
                                          .fold(() -> success(Option.empty()),
                                                negConfig -> toNegOption(client, negConfig));
    }

    private static Result<Option<LoadBalancerProvider>> toNegOption(GcpClient client,
                                                                    GcpEnvironmentConfig.GcpNegConfig negConfig) {
        return gcpLoadBalancerProvider(client, negConfig.negName(), negConfig.port()).map(GcpEnvironmentIntegration::wrapInSome);
    }

    private static Option<LoadBalancerProvider> wrapInSome(GcpLoadBalancerProvider provider) {
        return some(provider);
    }

    private static Option<DiscoveryProvider> resolveDiscoveryProvider(GcpClient client, GcpEnvironmentConfig config) {
        return config.clusterName().map(name -> gcpDiscoveryProvider(client, config));
    }

    private static Option<SecretsProvider> resolveSecretsProvider(GcpClient client) {
        return some(CachingSecretsProvider.cachingSecretsProvider(gcpSecretsProvider(client),
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

    private static Option<CertificateProvider> resolveCertificateProvider(GcpClient client,
                                                                          GcpEnvironmentConfig config) {
        return config.certificateSecretPrefix()
                     .flatMap(prefix -> gcpCertificateProvider(client, prefix)
                                            .map(CertificateProvider.class::cast)
                                            .option());
    }
}
