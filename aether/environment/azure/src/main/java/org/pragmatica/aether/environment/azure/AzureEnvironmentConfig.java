// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.ClusterName;
import org.pragmatica.cloud.azure.AzureConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.Result.success;


public record AzureEnvironmentConfig(AzureConfig azureConfig,
                                     String vmSize,
                                     String image,
                                     String adminUsername,
                                     String sshPublicKey,
                                     String vnetSubnetId,
                                     String userData,
                                     Option<AzureLbConfig> loadBalancer,
                                     Option<ClusterName> clusterName,
                                     Option<String> selfVmName,
                                     long discoveryPollIntervalMs,
                                     Option<String> certificateSecretPrefix) {
    private static final long DEFAULT_POLL_INTERVAL_MS = 30_000L;

    public record AzureLbConfig(String loadBalancerName, String backendPoolName, String vnetId) {
        public static Result<AzureLbConfig> azureLbConfig(String loadBalancerName,
                                                          String backendPoolName,
                                                          String vnetId) {
            return success(new AzureLbConfig(loadBalancerName, backendPoolName, vnetId));
        }
    }

    public static Result<AzureEnvironmentConfig> azureEnvironmentConfig(AzureConfig azureConfig,
                                                                        String vmSize,
                                                                        String image,
                                                                        String adminUsername,
                                                                        String sshPublicKey,
                                                                        String vnetSubnetId,
                                                                        String userData) {
        return success(new AzureEnvironmentConfig(azureConfig,
                                                  vmSize,
                                                  image,
                                                  adminUsername,
                                                  sshPublicKey,
                                                  vnetSubnetId,
                                                  userData,
                                                  Option.empty(),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  DEFAULT_POLL_INTERVAL_MS,
                                                  Option.empty()));
    }

    public static Result<AzureEnvironmentConfig> azureEnvironmentConfig(AzureConfig azureConfig,
                                                                        String vmSize,
                                                                        String image,
                                                                        String adminUsername,
                                                                        String sshPublicKey,
                                                                        String vnetSubnetId,
                                                                        String userData,
                                                                        AzureLbConfig loadBalancer) {
        return success(new AzureEnvironmentConfig(azureConfig,
                                                  vmSize,
                                                  image,
                                                  adminUsername,
                                                  sshPublicKey,
                                                  vnetSubnetId,
                                                  userData,
                                                  some(loadBalancer),
                                                  Option.empty(),
                                                  Option.empty(),
                                                  DEFAULT_POLL_INTERVAL_MS,
                                                  Option.empty()));
    }

    public AzureEnvironmentConfig withDiscovery(ClusterName clusterLabel) {
        return new AzureEnvironmentConfig(azureConfig,
                                          vmSize,
                                          image,
                                          adminUsername,
                                          sshPublicKey,
                                          vnetSubnetId,
                                          userData,
                                          loadBalancer,
                                          some(clusterLabel),
                                          selfVmName,
                                          discoveryPollIntervalMs,
                                          certificateSecretPrefix);
    }

    public AzureEnvironmentConfig withSelfVmName(String vmName) {
        return new AzureEnvironmentConfig(azureConfig,
                                          vmSize,
                                          image,
                                          adminUsername,
                                          sshPublicKey,
                                          vnetSubnetId,
                                          userData,
                                          loadBalancer,
                                          clusterName,
                                          some(vmName),
                                          discoveryPollIntervalMs,
                                          certificateSecretPrefix);
    }

    public AzureEnvironmentConfig withDiscoveryPollInterval(long intervalMs) {
        return new AzureEnvironmentConfig(azureConfig,
                                          vmSize,
                                          image,
                                          adminUsername,
                                          sshPublicKey,
                                          vnetSubnetId,
                                          userData,
                                          loadBalancer,
                                          clusterName,
                                          selfVmName,
                                          intervalMs,
                                          certificateSecretPrefix);
    }

    public AzureEnvironmentConfig withCertificateSecretPrefix(String prefix) {
        return new AzureEnvironmentConfig(azureConfig,
                                          vmSize,
                                          image,
                                          adminUsername,
                                          sshPublicKey,
                                          vnetSubnetId,
                                          userData,
                                          loadBalancer,
                                          clusterName,
                                          selfVmName,
                                          discoveryPollIntervalMs,
                                          some(prefix));
    }
}
