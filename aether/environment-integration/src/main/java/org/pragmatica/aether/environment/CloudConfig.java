// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.environment;

import org.pragmatica.lang.Result;

import java.util.Map;

import static org.pragmatica.lang.Result.success;


public record CloudConfig(String provider,
                          Map<String, String> credentials,
                          Map<String, String> compute,
                          Map<String, String> loadBalancer,
                          Map<String, String> discovery,
                          Map<String, String> secrets,
                          Map<String, String> security) {
    public static Result<CloudConfig> cloudConfig(String provider,
                                                  Map<String, String> credentials,
                                                  Map<String, String> compute) {
        return success(new CloudConfig(provider,
                                       Map.copyOf(credentials),
                                       Map.copyOf(compute),
                                       Map.of(),
                                       Map.of(),
                                       Map.of(),
                                       Map.of()));
    }

    @SuppressWarnings("JBCT-VO-02") public CloudConfig withLoadBalancer(Map<String, String> loadBalancer) {
        return new CloudConfig(provider, credentials, compute, Map.copyOf(loadBalancer), discovery, secrets, security);
    }

    @SuppressWarnings("JBCT-VO-02") public CloudConfig withDiscovery(Map<String, String> discovery) {
        return new CloudConfig(provider, credentials, compute, loadBalancer, Map.copyOf(discovery), secrets, security);
    }

    @SuppressWarnings("JBCT-VO-02") public CloudConfig withSecrets(Map<String, String> secrets) {
        return new CloudConfig(provider, credentials, compute, loadBalancer, discovery, Map.copyOf(secrets), security);
    }

    @SuppressWarnings("JBCT-VO-02") public CloudConfig withSecurity(Map<String, String> security) {
        return new CloudConfig(provider, credentials, compute, loadBalancer, discovery, secrets, Map.copyOf(security));
    }
}
