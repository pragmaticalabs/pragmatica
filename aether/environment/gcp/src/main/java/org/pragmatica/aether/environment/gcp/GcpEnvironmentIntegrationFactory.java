package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.gcp.GcpConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.aether.environment.gcp.GcpEnvironmentConfig.GcpNegConfig.gcpNegConfig;
import static org.pragmatica.aether.environment.gcp.GcpEnvironmentConfig.gcpEnvironmentConfig;

/// ServiceLoader factory for creating GcpEnvironmentIntegration from generic CloudConfig.
public record GcpEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "gcp";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config)
            .flatMap(GcpEnvironmentIntegration::gcpEnvironmentIntegration)
            .map(EnvironmentIntegration.class::cast);
    }

    // --- Leaf: assemble GcpEnvironmentConfig from generic maps ---
    private static Result<GcpEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        var creds = config.credentials();
        var compute = config.compute();
        var gcpConfig = GcpConfig.gcpConfig(
            creds.getOrDefault("project_id", ""),
            creds.getOrDefault("zone", ""),
            creds.getOrDefault("service_account_email", ""),
            creds.getOrDefault("private_key_pem", "")
        );

        return gcpEnvironmentConfig(
            gcpConfig,
            compute.getOrDefault("machine_type", ""),
            compute.getOrDefault("source_image", ""),
            compute.getOrDefault("network", ""),
            compute.getOrDefault("subnetwork", ""),
            compute.getOrDefault("user_data", "")
        ).map(envConfig -> applyLoadBalancer(envConfig, config.loadBalancer()))
         .map(envConfig -> applyDiscovery(envConfig, config.discovery()));
    }

    // --- Leaf: apply optional load balancer (NEG) config ---
    private static GcpEnvironmentConfig applyLoadBalancer(GcpEnvironmentConfig envConfig,
                                                           Map<String, String> lbMap) {
        var negName = lbMap.getOrDefault("neg_name", "");
        var portStr = lbMap.getOrDefault("port", "");

        if (negName.isEmpty() || portStr.isEmpty()) {
            return envConfig;
        }

        return gcpNegConfig(negName, Integer.parseInt(portStr))
            .map(neg -> withNetworkEndpointGroup(envConfig, neg))
            .or(envConfig);
    }

    // --- Leaf: apply optional discovery config ---
    private static GcpEnvironmentConfig applyDiscovery(GcpEnvironmentConfig envConfig,
                                                        Map<String, String> discoveryMap) {
        var clusterName = discoveryMap.getOrDefault("cluster_name", "");
        var pollInterval = discoveryMap.getOrDefault("poll_interval_ms", "");

        var result = clusterName.isEmpty() ? envConfig : envConfig.withDiscovery(clusterName);

        return pollInterval.isEmpty()
               ? result
               : result.withDiscoveryPollInterval(Long.parseLong(pollInterval));
    }

    // --- Leaf: withNetworkEndpointGroup copy helper ---
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change — direct constructor use in config builder
    private static GcpEnvironmentConfig withNetworkEndpointGroup(GcpEnvironmentConfig envConfig,
                                                                  GcpEnvironmentConfig.GcpNegConfig negConfig) {
        return new GcpEnvironmentConfig(
            envConfig.gcpConfig(),
            envConfig.machineType(),
            envConfig.sourceImage(),
            envConfig.network(),
            envConfig.subnetwork(),
            envConfig.userData(),
            Option.some(negConfig),
            envConfig.clusterName(),
            envConfig.selfInstanceName(),
            envConfig.discoveryPollIntervalMs()
        );
    }
}
