package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.net.tcp.security.CertificateProvider;

import java.util.ServiceLoader;

import static org.pragmatica.lang.Option.empty;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


/// Faceted SPI entry point for all deployment environment interactions.
///
/// Each facet is `Option<T>` — implementations return only the facets they support.
/// Local/Forge supports compute only. Cloud providers may support all facets.
///
/// Discovered via ServiceLoader. Use `EnvironmentIntegration.SPI` for the
/// ServiceLoader-discovered instance, or factory methods for programmatic construction.
public interface EnvironmentIntegration {
    Option<EnvironmentIntegration> SPI = Option.from(ServiceLoader.load(EnvironmentIntegration.class).findFirst());

    Option<ComputeProvider> compute();
    Option<SecretsProvider> secrets();
    Option<LoadBalancerProvider> loadBalancer();

    default Option<DiscoveryProvider> discovery() {
        return empty();
    }

    /// Certificate provider for mTLS and gossip key management.
    /// Cloud implementations fetch certificate material from their respective secrets/certificate services.
    default Option<CertificateProvider> certificateProvider() {
        return empty();
    }

    static EnvironmentIntegration withCompute(ComputeProvider compute) {
        return environmentIntegration(some(compute), empty(), empty(), empty(), empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer) {
        return environmentIntegration(compute, secrets, loadBalancer, empty(), empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DiscoveryProvider> discovery) {
        return environmentIntegration(compute, secrets, loadBalancer, discovery, empty());
    }

    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer,
                                                         Option<DiscoveryProvider> discovery,
                                                         Option<CertificateProvider> certificateProvider) {
        return FacetedEnvironment.facetedEnvironment(compute, secrets, loadBalancer, discovery, certificateProvider).unwrap();
    }

    record FacetedEnvironment(Option<ComputeProvider> compute,
                              Option<SecretsProvider> secrets,
                              Option<LoadBalancerProvider> loadBalancer,
                              Option<DiscoveryProvider> discovery,
                              Option<CertificateProvider> certificateProvider) implements EnvironmentIntegration {
        public static Result<FacetedEnvironment> facetedEnvironment(Option<ComputeProvider> compute,
                                                                    Option<SecretsProvider> secrets,
                                                                    Option<LoadBalancerProvider> loadBalancer,
                                                                    Option<DiscoveryProvider> discovery,
                                                                    Option<CertificateProvider> certificateProvider) {
            return success(new FacetedEnvironment(compute, secrets, loadBalancer, discovery, certificateProvider));
        }
    }
}
