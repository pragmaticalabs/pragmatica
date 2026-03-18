package org.pragmatica.aether.environment.aws;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.aws.AwsEnvironmentConfig.AwsLbConfig.awsLbConfig;
import static org.pragmatica.aether.environment.aws.AwsEnvironmentConfig.awsEnvironmentConfig;
import static org.pragmatica.lang.Option.option;

/// ServiceLoader factory for creating AwsEnvironmentIntegration from generic CloudConfig.
public record AwsEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "aws";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config)
            .flatMap(AwsEnvironmentIntegration::awsEnvironmentIntegration)
            .map(EnvironmentIntegration.class::cast);
    }

    // --- Leaf: assemble AwsEnvironmentConfig from generic maps ---
    private static Result<AwsEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        var creds = config.credentials();
        var compute = config.compute();
        var awsConfig = AwsConfig.awsConfig(
            creds.getOrDefault("access_key_id", ""),
            creds.getOrDefault("secret_access_key", ""),
            creds.getOrDefault("region", "")
        );

        return awsEnvironmentConfig(
            awsConfig,
            compute.getOrDefault("ami_id", ""),
            compute.getOrDefault("instance_type", ""),
            option(compute.get("key_name")),
            parseStringList(compute.getOrDefault("security_group_ids", "")),
            compute.getOrDefault("subnet_id", ""),
            compute.getOrDefault("user_data", "")
        ).map(envConfig -> applyLoadBalancer(envConfig, config.loadBalancer()))
         .map(envConfig -> applyDiscovery(envConfig, config.discovery()));
    }

    // --- Leaf: apply optional load balancer config ---
    private static AwsEnvironmentConfig applyLoadBalancer(AwsEnvironmentConfig envConfig,
                                                           Map<String, String> lbMap) {
        var targetGroupArn = lbMap.getOrDefault("target_group_arn", "");

        if (targetGroupArn.isEmpty()) {
            return envConfig;
        }

        return awsLbConfig(targetGroupArn)
            .map(lb -> withLoadBalancer(envConfig, lb))
            .or(envConfig);
    }

    // --- Leaf: apply optional discovery config ---
    private static AwsEnvironmentConfig applyDiscovery(AwsEnvironmentConfig envConfig,
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
    private static AwsEnvironmentConfig withLoadBalancer(AwsEnvironmentConfig envConfig,
                                                          AwsEnvironmentConfig.AwsLbConfig lbConfig) {
        return new AwsEnvironmentConfig(
            envConfig.awsConfig(),
            envConfig.amiId(),
            envConfig.instanceType(),
            envConfig.keyName(),
            envConfig.securityGroupIds(),
            envConfig.subnetId(),
            envConfig.userData(),
            Option.some(lbConfig),
            envConfig.clusterName(),
            envConfig.discoveryPollIntervalMs()
        );
    }

    // --- Leaf: parse comma-separated string list ---
    private static List<String> parseStringList(String value) {
        if (value.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(value.split(","))
                     .map(String::trim)
                     .filter(s -> !s.isEmpty())
                     .toList();
    }
}
