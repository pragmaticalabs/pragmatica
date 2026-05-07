// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.gcp;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.gcp.GcpConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.Map;

import static org.pragmatica.aether.environment.gcp.GcpEnvironmentConfig.GcpNegConfig.gcpNegConfig;
import static org.pragmatica.aether.environment.gcp.GcpEnvironmentConfig.gcpEnvironmentConfig;


public record GcpEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override public String providerName() {
        return "gcp";
    }

    @Override public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config).flatMap(GcpEnvironmentIntegration::gcpEnvironmentIntegration)
                                     .map(EnvironmentIntegration.class::cast);
    }

    private static Result<GcpEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        return validateCredentials(config.credentials()).flatMap(creds -> buildFromValidated(creds, config));
    }

    private static Result<Map<String, String>> validateCredentials(Map<String, String> creds) {
        var missing = new ArrayList<String>();
        if (blank(creds.get("project_id"))) {missing.add("GCP_PROJECT_ID");}
        if (blank(creds.get("service_account_email"))) {missing.add("GCP_SERVICE_ACCOUNT_EMAIL");}
        if (blank(creds.get("private_key_pem"))) {missing.add("GCP_PRIVATE_KEY_PEM");}
        if (blank(creds.get("zone"))) {missing.add("GCP_ZONE");}
        if (!missing.isEmpty()) {return Result.failure(EnvironmentError.CredentialsMissing.credentialsMissing("gcp",
                                                                                                              missing)
        .unwrap());}
        return Result.success(creds);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Result<GcpEnvironmentConfig> buildFromValidated(Map<String, String> creds, CloudConfig config) {
        var compute = config.compute();
        var gcpConfig = GcpConfig.gcpConfig(creds.get("project_id"),
                                            creds.get("zone"),
                                            creds.get("service_account_email"),
                                            creds.get("private_key_pem"));
        return gcpEnvironmentConfig(gcpConfig,
                                    compute.getOrDefault("machine_type", ""),
                                    compute.getOrDefault("source_image", ""),
                                    compute.getOrDefault("network", ""),
                                    compute.getOrDefault("subnetwork", ""),
                                    compute.getOrDefault("user_data", "")).map(envConfig -> applyLoadBalancer(envConfig,
                                                                                                              config.loadBalancer()))
                                   .map(envConfig -> applyDiscovery(envConfig,
                                                                    config.discovery()))
                                   .map(envConfig -> applyCertificatePrefix(envConfig,
                                                                            config.security()));
    }

    private static GcpEnvironmentConfig applyLoadBalancer(GcpEnvironmentConfig envConfig, Map<String, String> lbMap) {
        var negName = lbMap.getOrDefault("neg_name", "");
        var portStr = lbMap.getOrDefault("port", "");
        if (negName.isEmpty() || portStr.isEmpty()) {return envConfig;}
        return Result.lift(() -> Integer.parseInt(portStr)).flatMap(port -> gcpNegConfig(negName, port))
                          .map(neg -> withNetworkEndpointGroup(envConfig, neg))
                          .or(envConfig);
    }

    private static GcpEnvironmentConfig applyDiscovery(GcpEnvironmentConfig envConfig,
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

    private static GcpEnvironmentConfig applyCertificatePrefix(GcpEnvironmentConfig envConfig,
                                                               Map<String, String> securityMap) {
        var prefix = securityMap.getOrDefault("certificate_secret_prefix", "");
        return prefix.isEmpty()
              ? envConfig
              : envConfig.withCertificateSecretPrefix(prefix);
    }

    private static GcpEnvironmentConfig withNetworkEndpointGroup(GcpEnvironmentConfig envConfig,
                                                                 GcpEnvironmentConfig.GcpNegConfig negConfig) {
        return new GcpEnvironmentConfig(envConfig.gcpConfig(),
                                        envConfig.machineType(),
                                        envConfig.sourceImage(),
                                        envConfig.network(),
                                        envConfig.subnetwork(),
                                        envConfig.userData(),
                                        Option.some(negConfig),
                                        envConfig.clusterName(),
                                        envConfig.selfInstanceName(),
                                        envConfig.discoveryPollIntervalMs(),
                                        envConfig.certificateSecretPrefix());
    }
}
