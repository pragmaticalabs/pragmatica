package org.pragmatica.aether.environment;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

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
    Option<EnvironmentIntegration> SPI = Option.from(ServiceLoader.load(EnvironmentIntegration.class)
                                                                  .findFirst());

    Option<ComputeProvider> compute();

    Option<SecretsProvider> secrets();

    Option<LoadBalancerProvider> loadBalancer();

    /// Create an EnvironmentIntegration with compute support only.
    static EnvironmentIntegration withCompute(ComputeProvider compute) {
        return environmentIntegration(some(compute), empty(), empty());
    }

    /// Create an EnvironmentIntegration with all specified facets.
    static EnvironmentIntegration environmentIntegration(Option<ComputeProvider> compute,
                                                         Option<SecretsProvider> secrets,
                                                         Option<LoadBalancerProvider> loadBalancer) {
        return FacetedEnvironment.facetedEnvironment(compute, secrets, loadBalancer)
                                 .unwrap();
    }

    record FacetedEnvironment(Option<ComputeProvider> compute,
                              Option<SecretsProvider> secrets,
                              Option<LoadBalancerProvider> loadBalancer) implements EnvironmentIntegration {
        public static Result<FacetedEnvironment> facetedEnvironment(Option<ComputeProvider> compute,
                                                                    Option<SecretsProvider> secrets,
                                                                    Option<LoadBalancerProvider> loadBalancer) {
            return success(new FacetedEnvironment(compute, secrets, loadBalancer));
        }
    }
}
