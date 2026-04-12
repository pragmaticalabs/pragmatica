package org.pragmatica.aether.cli.cluster;

import org.pragmatica.aether.config.cluster.SourceProfile;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.ComputeProvider;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.lang.Result;

import java.util.HashMap;
import java.util.Map;

import static org.pragmatica.aether.cli.cluster.ClusterBootstrapOrchestrator.BootstrapError;


/// Resolves compute providers from source profile configuration via SPI. Section 11.
@SuppressWarnings({"JBCT-SEQ-01", "JBCT-UTIL-02"}) public final class ProviderResolver {
    private static final BootstrapError.ProvisionFailed NO_PROVIDER =
        new BootstrapError.ProvisionFailed("cloud", "No cloud provider specified in source profile");

    private static final BootstrapError.ProvisionFailed NO_COMPUTE =
        new BootstrapError.ProvisionFailed("cloud", "Provider does not support compute operations");

    private ProviderResolver() {}

    /// Resolve a ComputeProvider for a cloud source profile via the EnvironmentIntegrationFactory SPI.
    public static Result<ComputeProvider> resolveCloudCompute(SourceProfile source) {
        return source.provider()
                     .toResult(NO_PROVIDER)
                     .flatMap(provider -> lookupAndCreateCloud(provider.value(), source))
                     .flatMap(ProviderResolver::extractCompute);
    }

    /// Resolve a ComputeProvider for a docker source profile via the EnvironmentIntegrationFactory SPI.
    public static Result<ComputeProvider> resolveDockerCompute() {
        return lookupFactory("docker")
                   .flatMap(factory -> factory.create(dockerCloudConfig()))
                   .flatMap(ProviderResolver::extractCompute);
    }

    private static Result<EnvironmentIntegration> lookupAndCreateCloud(String providerName, SourceProfile source) {
        return lookupFactory(providerName)
                   .flatMap(factory -> factory.create(buildCloudConfig(providerName, source)));
    }

    private static Result<EnvironmentIntegrationFactory> lookupFactory(String providerName) {
        return EnvironmentIntegrationFactory.forProvider(providerName)
                   .toResult(factoryNotFound(providerName));
    }

    private static Result<ComputeProvider> extractCompute(EnvironmentIntegration integration) {
        return integration.compute()
                          .toResult(NO_COMPUTE);
    }

    private static CloudConfig buildCloudConfig(String providerName, SourceProfile source) {
        var credentials = new HashMap<String, String>();
        source.credentials().onPresent(c -> credentials.put("credentials_file", c));
        var compute = new HashMap<String, String>();
        source.region().onPresent(r -> compute.put("region", r));
        source.zone().onPresent(z -> compute.put("zone", z));
        return new CloudConfig(providerName,
                               Map.copyOf(credentials),
                               Map.copyOf(compute),
                               Map.of(),
                               Map.of(),
                               Map.of(),
                               Map.of());
    }

    private static CloudConfig dockerCloudConfig() {
        return new CloudConfig("docker", Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    private static BootstrapError.ProvisionFailed factoryNotFound(String providerName) {
        return new BootstrapError.ProvisionFailed(providerName,
                                                   "No EnvironmentIntegrationFactory found for provider '" + providerName + "'");
    }
}
