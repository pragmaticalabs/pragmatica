// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment.hetzner;

import org.pragmatica.aether.environment.CloudConfig;
import org.pragmatica.aether.environment.EnvironmentError;
import org.pragmatica.aether.environment.EnvironmentIntegration;
import org.pragmatica.aether.environment.EnvironmentIntegrationFactory;
import org.pragmatica.cloud.hetzner.HetznerConfig;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig.HetznerLbConfig.hetznerLbConfig;
import static org.pragmatica.aether.environment.hetzner.HetznerEnvironmentConfig.hetznerEnvironmentConfig;


public record HetznerEnvironmentIntegrationFactory() implements EnvironmentIntegrationFactory {
    @Override
    public String providerName() {
        return "hetzner";
    }

    @Override
    public Result<EnvironmentIntegration> create(CloudConfig config) {
        return buildEnvironmentConfig(config).flatMap(HetznerEnvironmentIntegration::hetznerEnvironmentIntegration)
                                     .map(EnvironmentIntegration.class::cast);
    }

    // #442: no hardcoded instance-type default. An absent server_type stays empty ("unset") and is
    // resolved from the ProvisionSpec's per-role instance type — or fails loud — at provision time.
    private static final String DEFAULT_IMAGE = "ubuntu-22.04";

    private static Result<HetznerEnvironmentConfig> buildEnvironmentConfig(CloudConfig config) {
        return validateCredentials(config.credentials()).flatMap(creds -> buildFromValidated(creds, config));
    }

    private static Result<Map<String, String>> validateCredentials(Map<String, String> creds) {
        var missing = new ArrayList<String>();

        if (blank(creds.get("api_token"))) {
            missing.add("HCLOUD_TOKEN");
        }

        if (!missing.isEmpty()) {
            return Result.failure(EnvironmentError.CredentialsMissing.credentialsMissing("hetzner", missing).unwrap());
        }

        return Result.success(creds);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static Result<HetznerEnvironmentConfig> buildFromValidated(Map<String, String> creds, CloudConfig config) {
        var compute = config.compute();
        var hetznerConfig = HetznerConfig.hetznerConfig(creds.get("api_token"));

        return hetznerEnvironmentConfig(hetznerConfig,
                                        nonBlank(compute.get("server_type"),
                                                 ""),
                                        nonBlank(compute.get("image"),
                                                 DEFAULT_IMAGE),
                                        compute.getOrDefault("region", ""),
                                        parseLongList(compute.getOrDefault("ssh_key_ids", "")),
                                        parseLongList(compute.getOrDefault("network_ids", "")),
                                        parseLongList(compute.getOrDefault("firewall_ids", "")),
                                        compute.getOrDefault("user_data", "")).map(envConfig -> applyLoadBalancer(envConfig,
                                                                                                                  config.loadBalancer()))
                                       .map(envConfig -> applyDiscovery(envConfig,
                                                                        config.discovery()));
    }

    private static HetznerEnvironmentConfig applyLoadBalancer(HetznerEnvironmentConfig envConfig,
                                                              Map<String, String> lbMap) {
        var lbIdStr = lbMap.getOrDefault("load_balancer_id", "");
        var portStr = lbMap.getOrDefault("destination_port", "");

        if (lbIdStr.isEmpty() || portStr.isEmpty()) {
            return envConfig;
        }

        return hetznerLbConfig(Long.parseLong(lbIdStr),
                               Integer.parseInt(portStr)).map(lb -> withLoadBalancer(envConfig, lb))
                              .or(envConfig);
    }

    private static HetznerEnvironmentConfig applyDiscovery(HetznerEnvironmentConfig envConfig,
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

    private static HetznerEnvironmentConfig withLoadBalancer(HetznerEnvironmentConfig envConfig,
                                                             HetznerEnvironmentConfig.HetznerLbConfig lbConfig) {
        return new HetznerEnvironmentConfig(envConfig.hetznerConfig(),
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
                                            envConfig.discoveryPollIntervalMs());
    }

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

    private static String nonBlank(String value, String fallback) {
        return value == null || value.isBlank()
               ? fallback
               : value;
    }
}
