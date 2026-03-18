package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig.HetznerLbConfig.hetznerLbConfig;
import static org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig.hetznerEnvironmentConfig;

/// ServiceLoader factory for creating HetznerEnvironmentIntegration from generic CloudConfig.
public record HetznerEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "hetzner";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config)
            .flatMap(HetznerEnvironmentIntegration::hetznerEnvironmentIntegration)
            .map(EnvironmentIntegration.class::cast);
    }

    // --- Leaf: assemble HetznerEnvironmentConfig from generic maps ---
    private static Result<HetznerEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        var creds = config.credentials();
        var compute = config.compute();
        var hetznerConfig = HetznerConfig.hetznerConfig(creds.getOrDefault("api_token", ""));

        return hetznerEnvironmentConfig(
            hetznerConfig,
            compute.getOrDefault("server_type", ""),
            compute.getOrDefault("image", ""),
            compute.getOrDefault("region", ""),
            parseLongList(compute.getOrDefault("ssh_key_ids", "")),
            parseLongList(compute.getOrDefault("network_ids", "")),
            parseLongList(compute.getOrDefault("firewall_ids", "")),
            compute.getOrDefault("user_data", "")
        ).map(envConfig -> applyLoadBalancer(envConfig, config.loadBalancer()))
         .map(envConfig -> applyDiscovery(envConfig, config.discovery()));
    }

    // --- Leaf: apply optional load balancer config ---
    private static HetznerEnvironmentConfig applyLoadBalancer(HetznerEnvironmentConfig envConfig,
                                                               Map<String, String> lbMap) {
        var lbIdStr = lbMap.getOrDefault("load_balancer_id", "");
        var portStr = lbMap.getOrDefault("destination_port", "");

        if (lbIdStr.isEmpty() || portStr.isEmpty()) {
            return envConfig;
        }

        return hetznerLbConfig(Long.parseLong(lbIdStr), Integer.parseInt(portStr))
            .map(lb -> withLoadBalancer(envConfig, lb))
            .or(envConfig);
    }

    // --- Leaf: apply optional discovery config ---
    private static HetznerEnvironmentConfig applyDiscovery(HetznerEnvironmentConfig envConfig,
                                                            Map<String, String> discoveryMap) {
        var clusterName = discoveryMap.getOrDefault("cluster_name", "");
        var pollInterval = discoveryMap.getOrDefault("poll_interval_ms", "");

        var result = clusterName.isEmpty() ? envConfig : envConfig.withDiscovery(clusterName);

        return pollInterval.isEmpty()
               ? result
               : result.withDiscoveryPollInterval(Long.parseLong(pollInterval));
    }

    // --- Leaf: withLoadBalancer copy helper ---
    @SuppressWarnings("JBCT-VO-02") // Copy-with-change — direct constructor use in config builder
    private static HetznerEnvironmentConfig withLoadBalancer(HetznerEnvironmentConfig envConfig,
                                                              HetznerEnvironmentConfig.HetznerLbConfig lbConfig) {
        return new HetznerEnvironmentConfig(
            envConfig.hetznerConfig(),
            envConfig.serverType(),
            envConfig.image(),
            envConfig.region(),
            envConfig.sshKeyIds(),
            envConfig.networkIds(),
            envConfig.firewallIds(),
            envConfig.userData(),
            Option.some(lbConfig),
            envConfig.clusterName(),
            envConfig.selfServerId(),
            envConfig.discoveryPollIntervalMs()
        );
    }

    // --- Leaf: parse comma-separated long list ---
    private static List<Long> parseLongList(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .map(Long::parseLong)
                     .toList();
    }
}
