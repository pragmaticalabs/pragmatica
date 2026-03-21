package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.azure.AzureConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.aether.environment.azure.AzureEnvironmentConfig.AzureLbConfig.azureLbConfig;
import static org.pragmatica.aether.environment.azure.AzureEnvironmentConfig.azureEnvironmentConfig;

/// ServiceLoader factory for creating AzureEnvironmentIntegration from generic CloudConfig.
public record AzureEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "azure";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config).flatMap(AzureEnvironmentIntegration::azureEnvironmentIntegration)
                                     .map(EnvironmentIntegration.class::cast);
    }

    // --- Leaf: assemble AzureEnvironmentConfig from generic maps ---
    private static Result<AzureEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        var creds = config.credentials();
        var compute = config.compute();
        var azureConfig = AzureConfig.azureConfig(creds.getOrDefault("tenant_id", ""),
                                                  creds.getOrDefault("client_id", ""),
                                                  creds.getOrDefault("client_secret", ""),
                                                  creds.getOrDefault("subscription_id", ""),
                                                  creds.getOrDefault("resource_group", ""),
                                                  creds.getOrDefault("location", ""));
        return azureEnvironmentConfig(azureConfig,
                                      compute.getOrDefault("vm_size", ""),
                                      compute.getOrDefault("image", ""),
                                      compute.getOrDefault("admin_username", ""),
                                      compute.getOrDefault("ssh_public_key", ""),
                                      compute.getOrDefault("vnet_subnet_id", ""),
                                      compute.getOrDefault("user_data", "")).map(envConfig -> applyLoadBalancer(envConfig,
                                                                                                                config.loadBalancer()))
                                     .map(envConfig -> applyDiscovery(envConfig,
                                                                      config.discovery()));
    }

    // --- Leaf: apply optional load balancer config ---
    private static AzureEnvironmentConfig applyLoadBalancer(AzureEnvironmentConfig envConfig,
                                                            Map<String, String> lbMap) {
        var lbName = lbMap.getOrDefault("load_balancer_name", "");
        var poolName = lbMap.getOrDefault("backend_pool_name", "");
        var vnetId = lbMap.getOrDefault("vnet_id", "");
        if (lbName.isEmpty() || poolName.isEmpty() || vnetId.isEmpty()) {
            return envConfig;
        }
        return azureLbConfig(lbName, poolName, vnetId).map(lb -> withLoadBalancer(envConfig, lb))
                            .or(envConfig);
    }

    // --- Leaf: apply optional discovery config ---
    private static AzureEnvironmentConfig applyDiscovery(AzureEnvironmentConfig envConfig,
                                                         Map<String, String> discoveryMap) {
        var clusterName = discoveryMap.getOrDefault("cluster_name", "");
        var pollInterval = discoveryMap.getOrDefault("poll_interval_ms", "");
        var result = clusterName.isEmpty()
                     ? envConfig
                     : envConfig.withDiscovery(clusterName);
        return pollInterval.isEmpty()
               ? result
               : result.withDiscoveryPollInterval(Long.parseLong(pollInterval));
    }

    // --- Leaf: withLoadBalancer copy helper ---
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change — direct constructor use in config builder
    private static AzureEnvironmentConfig withLoadBalancer(AzureEnvironmentConfig envConfig,
                                                           AzureEnvironmentConfig.AzureLbConfig lbConfig) {
        return new AzureEnvironmentConfig(envConfig.azureConfig(),
                                          envConfig.vmSize(),
                                          envConfig.image(),
                                          envConfig.adminUsername(),
                                          envConfig.sshPublicKey(),
                                          envConfig.vnetSubnetId(),
                                          envConfig.userData(),
                                          Option.some(lbConfig),
                                          envConfig.clusterName(),
                                          envConfig.selfVmName(),
                                          envConfig.discoveryPollIntervalMs());
    }
}
