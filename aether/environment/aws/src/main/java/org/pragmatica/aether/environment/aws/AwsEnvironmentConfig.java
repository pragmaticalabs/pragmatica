// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.aws;

import java.util.List;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.cloud.aws.AwsConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public record AwsEnvironmentConfig(AwsConfig awsConfig,
                                   String amiId,
                                   String instanceType,
                                   Option<String> keyName,
                                   List<String> securityGroupIds,
                                   String subnetId,
                                   String userData,
                                   Option<AwsLbConfig> loadBalancer,
                                   Option<ClusterName> clusterName,
                                   long discoveryPollIntervalMs,
                                   Option<String> certificateSecretPrefix) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    public record AwsLbConfig(String targetGroupArn) {
        public static Result<AwsLbConfig> awsLbConfig(String targetGroupArn) {
            return success(new AwsLbConfig(targetGroupArn));
        }
    }

    public static Result<AwsEnvironmentConfig> awsEnvironmentConfig(AwsConfig awsConfig,
                                                                    String amiId,
                                                                    String instanceType,
                                                                    Option<String> keyName,
                                                                    List<String> securityGroupIds,
                                                                    String subnetId,
                                                                    String userData) {
        return success(new AwsEnvironmentConfig(awsConfig,
                                                amiId,
                                                instanceType,
                                                keyName,
                                                List.copyOf(securityGroupIds),
                                                subnetId,
                                                userData,
                                                Option.empty(),
                                                Option.empty(),
                                                DEFAULT_POLL_INTERVAL_MS,
                                                Option.empty()));
    }

    public static Result<AwsEnvironmentConfig> awsEnvironmentConfig(AwsConfig awsConfig,
                                                                    String amiId,
                                                                    String instanceType,
                                                                    Option<String> keyName,
                                                                    List<String> securityGroupIds,
                                                                    String subnetId,
                                                                    String userData,
                                                                    AwsLbConfig loadBalancer) {
        return success(new AwsEnvironmentConfig(awsConfig,
                                                amiId,
                                                instanceType,
                                                keyName,
                                                List.copyOf(securityGroupIds),
                                                subnetId,
                                                userData,
                                                some(loadBalancer),
                                                Option.empty(),
                                                DEFAULT_POLL_INTERVAL_MS,
                                                Option.empty()));
    }

    public AwsEnvironmentConfig withDiscovery(ClusterName clusterLabel) {
        return new AwsEnvironmentConfig(awsConfig,
                                        amiId,
                                        instanceType,
                                        keyName,
                                        securityGroupIds,
                                        subnetId,
                                        userData,
                                        loadBalancer,
                                        some(clusterLabel),
                                        discoveryPollIntervalMs,
                                        certificateSecretPrefix);
    }

    public AwsEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new AwsEnvironmentConfig(awsConfig,
                                        amiId,
                                        instanceType,
                                        keyName,
                                        securityGroupIds,
                                        subnetId,
                                        userData,
                                        loadBalancer,
                                        clusterName,
                                        intervalMs,
                                        certificateSecretPrefix);
    }

    public AwsEnvironmentConfig withCertificateSecretPrefix(String prefix) {
        return new AwsEnvironmentConfig(awsConfig,
                                        amiId,
                                        instanceType,
                                        keyName,
                                        securityGroupIds,
                                        subnetId,
                                        userData,
                                        loadBalancer,
                                        clusterName,
                                        discoveryPollIntervalMs,
                                        some(prefix));
    }
}
