package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.CachingSecretsProvider;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.DiscoveryProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.LoadBalancerProvider;
import org.pragmatica.aether.environment.SecretsProvider;
import org.pragmatica.cloud.azure.AzureClient;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.net.tcp.security.CertificateProvider;

import static org.pragmatica.aether.environment.azure.AzureCertificateProvider.azureCertificateProvider;
import static org.pragmatica.aether.environment.azure.AzureComputeProvider.azureComputeProvider;
import static org.pragmatica.aether.environment.azure.AzureDiscoveryProvider.azureDiscoveryProvider;
import static org.pragmatica.aether.environment.azure.AzureLoadBalancerProvider.azureLoadBalancerProvider;
import static org.pragmatica.aether.environment.azure.AzureSecretsProvider.azureSecretsProvider;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Azure Cloud implementation of the EnvironmentIntegration SPI.
/// Provides compute capabilities backed by the Azure Cloud API.
/// Optionally provides load balancer management and tag-based discovery when configured.
/// Provides Azure Key Vault secrets resolution.
public record AzureEnvironmentIntegration(AzureComputeProvider computeProvider,
                                          Option<LoadBalancerProvider> loadBalancerProvider,
                                          Option<DiscoveryProvider> discoveryProvider,
                                          Option<SecretsProvider> secretsProvider,
                                          Option<CertificateProvider> certProvider) implements EnvironmentIntegration {
    public static Result<AzureEnvironmentIntegration> azureEnvironmentIntegration(AzureEnvironmentConfig config) {
        var client = AzureClient.azureClient(config.azureConfig());
        return azureEnvironmentIntegration(client, config);
    }

    public static Result<AzureEnvironmentIntegration> azureEnvironmentIntegration(AzureClient client,
                                                                                  AzureEnvironmentConfig config) {
        var compute = azureComputeProvider(client, config);
        var lbProvider = resolveLbProvider(client, config);
        var discovery = resolveDiscoveryProvider(client, config);
        var secrets = resolveSecretsProvider(client);
        var certProv = resolveCertificateProvider(client, config);
        return Result.all(compute, lbProvider)
                         .map((cp, lb) -> new AzureEnvironmentIntegration(cp, lb, discovery, secrets, certProv));
    }

    private static Result<Option<LoadBalancerProvider>> resolveLbProvider(AzureClient client,
                                                                          AzureEnvironmentConfig config) {
        return config.loadBalancer().fold(() -> success(Option.empty()), lbConfig -> toLbOption(client, lbConfig));
    }

    private static Result<Option<LoadBalancerProvider>> toLbOption(AzureClient client,
                                                                   AzureEnvironmentConfig.AzureLbConfig lbConfig) {
        return azureLoadBalancerProvider(client,
                                         lbConfig.loadBalancerName(),
                                         lbConfig.backendPoolName(),
                                         lbConfig.vnetId()).map(AzureEnvironmentIntegration::wrapInSome);
    }

    private static Option<LoadBalancerProvider> wrapInSome(AzureLoadBalancerProvider provider) {
        return some(provider);
    }

    private static Option<DiscoveryProvider> resolveDiscoveryProvider(AzureClient client,
                                                                      AzureEnvironmentConfig config) {
        return config.clusterName().map(name -> azureDiscoveryProvider(client, config));
    }

    private static Option<SecretsProvider> resolveSecretsProvider(AzureClient client) {
        return azureSecretsProvider(client).map(AzureEnvironmentIntegration::wrapSecretInSome).or(Option.empty());
    }

    private static Option<SecretsProvider> wrapSecretInSome(AzureSecretsProvider provider) {
        return some(CachingSecretsProvider.cachingSecretsProvider(provider,
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

    private static Option<CertificateProvider> resolveCertificateProvider(AzureClient client,
                                                                          AzureEnvironmentConfig config) {
        return config.certificateSecretPrefix()
                     .flatMap(prefix -> azureCertificateProvider(client, prefix)
                                            .map(CertificateProvider.class::cast)
                                            .option());
    }
}
