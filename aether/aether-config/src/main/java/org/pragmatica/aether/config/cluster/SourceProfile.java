// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Map;

import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;


public record SourceProfile(String name,
                            SourceType type,
                            Option<CloudProviderName> provider,
                            Option<String> credentials,
                            Option<String> region,
                            Option<String> zone,
                            List<String> zones,
                            Option<String> user,
                            Option<String> key,
                            Option<Integer> sshPort,
                            LoadBalancerMode loadBalancer,
                            List<String> loadBalancerIps,
                            Option<String> loadBalancerEndpoint,
                            Map<String, String> databases,
                            Map<NodeRole, RoleSubTable> roles,
                            List<FirewallRule> firewallRules,
                            Option<TomlDocument> nodeConfig) {
    public SourceProfile {
        zones = List.copyOf(zones);
        loadBalancerIps = List.copyOf(loadBalancerIps);
        databases = Map.copyOf(databases);
        roles = Map.copyOf(roles);
        firewallRules = List.copyOf(firewallRules);
    }

    /// Ordered list of zones to attempt when provisioning nodes of this source. Returns
    /// the explicit `zones` array when present; otherwise the single `zone` wrapped in a
    /// list; otherwise an empty list (caller falls back to the provider's default region
    /// with a single attempt and no zone placement hint). Cross-zone landing is fine —
    /// the bootstrap rotates through this list on capacity exhaustion.
    public List<String> effectiveZones() {
        if (!zones.isEmpty()) {
            return zones;
        }

        return zone.map(single -> List.of(single))
                   .or(List.of());
    }

    public static SourceProfile sourceProfile(String name,
                                              SourceType type,
                                              Option<CloudProviderName> provider,
                                              Option<String> credentials,
                                              Option<String> region,
                                              Option<String> zone,
                                              List<String> zones,
                                              Option<String> user,
                                              Option<String> key,
                                              Option<Integer> sshPort,
                                              LoadBalancerMode loadBalancer,
                                              List<String> loadBalancerIps,
                                              Option<String> loadBalancerEndpoint,
                                              Map<String, String> databases,
                                              Map<NodeRole, RoleSubTable> roles,
                                              List<FirewallRule> firewallRules,
                                              Option<TomlDocument> nodeConfig) {
        return new SourceProfile(name,
                                 type,
                                 provider,
                                 credentials,
                                 region,
                                 zone,
                                 zones,
                                 user,
                                 key,
                                 sshPort,
                                 loadBalancer,
                                 loadBalancerIps,
                                 loadBalancerEndpoint,
                                 databases,
                                 roles,
                                 firewallRules,
                                 nodeConfig);
    }

    public static SourceProfile sourceProfile(String name,
                                              SourceType type,
                                              Option<CloudProviderName> provider,
                                              Option<String> credentials,
                                              Option<String> region,
                                              Option<String> zone,
                                              Option<String> user,
                                              Option<String> key,
                                              Option<Integer> sshPort,
                                              LoadBalancerMode loadBalancer,
                                              List<String> loadBalancerIps,
                                              Option<String> loadBalancerEndpoint,
                                              Map<String, String> databases,
                                              Map<NodeRole, RoleSubTable> roles,
                                              List<FirewallRule> firewallRules,
                                              Option<TomlDocument> nodeConfig) {
        return sourceProfile(name,
                             type,
                             provider,
                             credentials,
                             region,
                             zone,
                             List.of(),
                             user,
                             key,
                             sshPort,
                             loadBalancer,
                             loadBalancerIps,
                             loadBalancerEndpoint,
                             databases,
                             roles,
                             firewallRules,
                             nodeConfig);
    }

    public static SourceProfile sourceProfile(String name,
                                              SourceType type,
                                              Option<CloudProviderName> provider,
                                              Option<String> credentials,
                                              Option<String> region,
                                              Option<String> zone,
                                              Option<String> user,
                                              Option<String> key,
                                              Option<Integer> sshPort,
                                              LoadBalancerMode loadBalancer,
                                              List<String> loadBalancerIps,
                                              Option<String> loadBalancerEndpoint,
                                              Map<String, String> databases,
                                              Map<NodeRole, RoleSubTable> roles,
                                              List<FirewallRule> firewallRules) {
        return sourceProfile(name,
                             type,
                             provider,
                             credentials,
                             region,
                             zone,
                             user,
                             key,
                             sshPort,
                             loadBalancer,
                             loadBalancerIps,
                             loadBalancerEndpoint,
                             databases,
                             roles,
                             firewallRules,
                             Option.empty());
    }
}
