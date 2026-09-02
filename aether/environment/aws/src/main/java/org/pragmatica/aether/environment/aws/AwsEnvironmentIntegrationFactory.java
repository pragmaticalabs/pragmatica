// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Verify;

import static org.pragmatica.aether.environment.aws.AwsEnvironmentConfig.AwsLbConfig.awsLbConfig;
import static org.pragmatica.aether.environment.aws.AwsEnvironmentConfig.awsEnvironmentConfig;
import static org.pragmatica.lang.Option.option;


public record AwsEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "aws";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config).flatMap(AwsEnvironmentIntegration::awsEnvironmentIntegration)
                                     .map(EnvironmentIntegration.class::cast);
    }

    private static Result<AwsEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        return validateCredentials(config.credentials()).flatMap(creds -> buildFromValidated(creds, config));
    }

    private static Result<Map<String, String>> validateCredentials(Map<String, String> creds) {
        var missing = new ArrayList<String>();

        if (blank(creds.get("access_key_id"))) {
            missing.add("AWS_ACCESS_KEY_ID");
        }

        if (blank(creds.get("secret_access_key"))) {
            missing.add("AWS_SECRET_ACCESS_KEY");
        }

        if (blank(creds.get("region"))) {
            missing.add("AWS_REGION");
        }

        if (!missing.isEmpty()) {
            return Result.failure(EnvironmentError.CredentialsMissing.credentialsMissing("aws", missing).unwrap());
        }

        return Result.success(creds);
    }

    private static boolean blank(String value) {
        return ! Verify.Is.present(value);
    }

    private static Result<AwsEnvironmentConfig> buildFromValidated(Map<String, String> creds, CloudConfig config) {
        var compute = config.compute();
        var awsConfig = AwsConfig.awsConfig(creds.get("access_key_id"),
                                            creds.get("secret_access_key"),
                                            creds.get("region"));

        return awsEnvironmentConfig(awsConfig,
                                    compute.getOrDefault("ami_id", ""),
                                    compute.getOrDefault("instance_type", ""),
                                    option(compute.get("key_name")),
                                    parseStringList(compute.getOrDefault("security_group_ids", "")),
                                    compute.getOrDefault("subnet_id", ""),
                                    compute.getOrDefault("user_data", "")).map(envConfig -> applyLoadBalancer(envConfig,
                                                                                                              config.loadBalancer()))
                                   .map(envConfig -> applyDiscovery(envConfig,
                                                                    config.discovery()))
                                   .map(envConfig -> applyCertificatePrefix(envConfig,
                                                                            config.security()));
    }

    private static AwsEnvironmentConfig applyLoadBalancer(AwsEnvironmentConfig envConfig, Map<String, String> lbMap) {
        var targetGroupArn = lbMap.getOrDefault("target_group_arn", "");

        if (targetGroupArn.isEmpty()) {
            return envConfig;
        }

        return awsLbConfig(targetGroupArn).map(lb -> withLoadBalancer(envConfig, lb))
                          .or(envConfig);
    }

    private static AwsEnvironmentConfig applyDiscovery(AwsEnvironmentConfig envConfig,
                                                       Map<String, String> discoveryMap) {
        var clusterName = ClusterName.maybeClusterName(discoveryMap.get("cluster_name"));
        var pollInterval = discoveryMap.getOrDefault("poll_interval_ms", "");
        var result = clusterName.map(envConfig::withDiscovery).or(envConfig);

        return pollInterval.isEmpty()
               ? result
               : Result.lift(() -> Long.parseLong(pollInterval))
                       .map(result::withDiscoveryPollInterval)
                       .or(result);
    }

    private static AwsEnvironmentConfig applyCertificatePrefix(AwsEnvironmentConfig envConfig,
                                                               Map<String, String> securityMap) {
        var prefix = securityMap.getOrDefault("certificate_secret_prefix", "");

        return prefix.isEmpty()
               ? envConfig
               : envConfig.withCertificateSecretPrefix(prefix);
    }

    private static AwsEnvironmentConfig withLoadBalancer(AwsEnvironmentConfig envConfig,
                                                         AwsEnvironmentConfig.AwsLbConfig lbConfig) {
        return new AwsEnvironmentConfig(envConfig.awsConfig(),
                                        envConfig.amiId(),
                                        envConfig.instanceType(),
                                        envConfig.keyName(),
                                        envConfig.securityGroupIds(),
                                        envConfig.subnetId(),
                                        envConfig.userData(),
                                        Option.some(lbConfig),
                                        envConfig.clusterName(),
                                        envConfig.discoveryPollIntervalMs(),
                                        envConfig.certificateSecretPrefix());
    }

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
