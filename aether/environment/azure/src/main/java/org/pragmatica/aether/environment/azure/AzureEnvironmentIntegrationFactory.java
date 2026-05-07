// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.azure;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.azure.AzureConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.Map;

import static org.pragmatica.aether.environment.azure.AzureEnvironmentConfig.AzureLbConfig.azureLbConfig;
import static org.pragmatica.aether.environment.azure.AzureEnvironmentConfig.azureEnvironmentConfig;


public record AzureEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override public String providerName() {
        return "azure";
    }

    @Override public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config).flatMap(AzureEnvironmentIntegration::azureEnvironmentIntegration)
                                     .map(EnvironmentIntegration.class::cast);
    }

    private static Result<AzureEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        return validateCredentials(config.credentials()).flatMap(creds -> buildFromValidated(creds, config));
    }

    private static Result<Map<String, String>> validateCredentials(Map<String, String> creds) {
        var missing = new ArrayList<String>();
        if (blank(creds.get("tenant_id"))) {missing.add("AZURE_TENANT_ID");}
        if (blank(creds.get("client_id"))) {missing.add("AZURE_CLIENT_ID");}
        if (blank(creds.get("client_secret"))) {missing.add("AZURE_CLIENT_SECRET");}
        if (blank(creds.get("subscription_id"))) {missing.add("AZURE_SUBSCRIPTION_ID");}
        if (blank(creds.get("resource_group"))) {missing.add("AZURE_RESOURCE_GROUP");}
        if (blank(creds.get("location"))) {missing.add("AZURE_LOCATION");}
        if (!missing.isEmpty()) {return Result.failure(EnvironmentError.CredentialsMissing.credentialsMissing("azure",
                                                                                                              missing)
        .unwrap());}
        return Result.success(creds);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Result<AzureEnvironmentConfig> buildFromValidated(Map<String, String> creds, CloudConfig config) {
        var compute = config.compute();
        var azureConfig = AzureConfig.azureConfig(creds.get("tenant_id"),
                                                  creds.get("client_id"),
                                                  creds.get("client_secret"),
                                                  creds.get("subscription_id"),
                                                  creds.get("resource_group"),
                                                  creds.get("location"));
        return azureEnvironmentConfig(azureConfig,
                                      compute.getOrDefault("vm_size", ""),
                                      compute.getOrDefault("image", ""),
                                      compute.getOrDefault("admin_username", ""),
                                      compute.getOrDefault("ssh_public_key", ""),
                                      compute.getOrDefault("vnet_subnet_id", ""),
                                      compute.getOrDefault("user_data", "")).map(envConfig -> applyLoadBalancer(envConfig,
                                                                                                                config.loadBalancer()))
                                     .map(envConfig -> applyDiscovery(envConfig,
                                                                      config.discovery()))
                                     .map(envConfig -> applyCertificatePrefix(envConfig,
                                                                              config.security()));
    }

    private static AzureEnvironmentConfig applyLoadBalancer(AzureEnvironmentConfig envConfig,
                                                            Map<String, String> lbMap) {
        var lbName = lbMap.getOrDefault("load_balancer_name", "");
        var poolName = lbMap.getOrDefault("backend_pool_name", "");
        var vnetId = lbMap.getOrDefault("vnet_id", "");
        if (lbName.isEmpty() || poolName.isEmpty() || vnetId.isEmpty()) {return envConfig;}
        return azureLbConfig(lbName, poolName, vnetId).map(lb -> withLoadBalancer(envConfig, lb)).or(envConfig);
    }

    private static AzureEnvironmentConfig applyDiscovery(AzureEnvironmentConfig envConfig,
                                                         Map<String, String> discoveryMap) {
        var clusterName = discoveryMap.getOrDefault("cluster_name", "");
        var pollInterval = discoveryMap.getOrDefault("poll_interval_ms", "");
        var result = clusterName.isEmpty()
                    ? envConfig
                    : envConfig.withDiscovery(clusterName);
        return pollInterval.isEmpty()
              ? result
              : Result.lift(() -> Long.parseLong(pollInterval)).map(result::withDiscoveryPollInterval)
                           .or(result);
    }

    private static AzureEnvironmentConfig applyCertificatePrefix(AzureEnvironmentConfig envConfig,
                                                                 Map<String, String> securityMap) {
        var prefix = securityMap.getOrDefault("certificate_secret_prefix", "");
        return prefix.isEmpty()
              ? envConfig
              : envConfig.withCertificateSecretPrefix(prefix);
    }

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
                                          envConfig.discoveryPollIntervalMs(),
                                          envConfig.certificateSecretPrefix());
    }
}
