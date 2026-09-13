// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.environment.SourceName;
import org.pragmatica.config.toml.TomlDocument;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// `replacementCeiling` (#1049) is the hard per-source ceiling on how long an auto-heal replacement
/// provisioned from this source may stay in-flight while its provider still reports it existing or
/// booting — past it the leader stops waiting and re-dispatches. Absent → [#DEFAULT_REPLACEMENT_CEILING].
/// Read at runtime by `ClusterTopologyManagerRecord.replacementCeiling` from the persisted cluster
/// config, through the same cloud-source lookup that resolves a replacement's zones and instance type.
public record SourceProfile(SourceName name,
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
                            Option<TomlDocument> nodeConfig,
                            Option<TimeSpan> replacementCeiling) {
    /// Ten minutes, per the owner ruling on #1049. Sized against measured cloud mint-to-membership of
    /// 50.3–63.3s (Hetzner JVM runtime, n=6), roughly ten times that, and above the 5-minute
    /// infrastructure-readiness bound a cloud provision may itself spend before it resolves
    /// (`ReadinessPolicy.cloudDefault`) plus a container runtime's image pull on a fresh VM. It is the
    /// fallback for a provider that cannot report instance state, so it must outlast a slow-but-healthy
    /// boot rather than race it — the 45s timer this replaces is the defect #1049 recorded.
    public static final TimeSpan DEFAULT_REPLACEMENT_CEILING = timeSpan(10).minutes();

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

    /// The in-flight ceiling for an auto-heal replacement provisioned from this source: the configured
    /// `replacement_ceiling`, else [#DEFAULT_REPLACEMENT_CEILING].
    public TimeSpan effectiveReplacementCeiling() {
        return replacementCeiling.or(DEFAULT_REPLACEMENT_CEILING);
    }

    public static SourceProfile sourceProfile(SourceName name,
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
                                              Option<TomlDocument> nodeConfig,
                                              Option<TimeSpan> replacementCeiling) {
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
                                 nodeConfig,
                                 replacementCeiling);
    }

    public static SourceProfile sourceProfile(SourceName name,
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
        return sourceProfile(name,
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
                             nodeConfig,
                             Option.empty());
    }

    public static SourceProfile sourceProfile(SourceName name,
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

    public static SourceProfile sourceProfile(SourceName name,
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
