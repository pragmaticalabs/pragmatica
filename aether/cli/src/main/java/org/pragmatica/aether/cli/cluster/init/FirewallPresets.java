// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import java.util.ArrayList;
import java.util.List;

import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.aether.config.cluster.PortMapping;
import org.pragmatica.lang.Option;


/// Firewall rule presets offered by `aether cluster init`.
///
/// Ports are read from [PortMapping#defaultPortMapping] rather than re-spelled here. Re-spelling is
/// exactly how they drifted: these constants said cluster=7100 / swim=7200 while the documented
/// defaults (and `PortMapping`) say 8090 / 8190. Wizard-generated configs stayed self-consistent —
/// `ClusterConfigGenerator` wrote `[operations.ports]` from these same constants — so nothing broke;
/// the defect was that the wizard disagreed with every other spelling of the same value.
///
/// **The management API is never opened to `0.0.0.0/0`.** It is the cluster's control plane, and the
/// documented cloud example sets `security_mode = "NONE"` — the pair is unauthenticated remote
/// control. When no admin CIDR is supplied the management rule is OMITTED rather than widened: an
/// absent CIDR means "I did not say who may reach this", which is never "everyone".
public sealed interface FirewallPresets {
    int SSH_PORT = 22;
    String ANY_CIDR = "0.0.0.0/0";
    String DEFAULT_INTERNAL_CIDR = "10.0.0.0/8";

    static List<FirewallRule> rulesFor(FirewallPreset preset, Option<String> adminCidr, String internalCidr) {
        var ports = PortMapping.defaultPortMapping();

        return switch (preset) {
            case STANDARD -> standardRules(ports, adminCidr);
            case RESTRICTIVE -> restrictiveRules(ports, adminCidr, internalCidr);
            case OPEN, CUSTOM -> List.of();
        };
    }

    /// Public app traffic, cluster mesh reachable, control plane scoped to the operator.
    ///
    /// Cluster and SWIM stay at `0.0.0.0/0`: with `[infrastructure.networking] type = "manual"` the
    /// nodes address each other by PUBLIC IP, so narrowing these to a private CIDR would stop the
    /// cluster forming. They carry authenticated consensus traffic, unlike the management API.
    private static List<FirewallRule> standardRules(PortMapping ports, Option<String> adminCidr) {
        var rules = new ArrayList<FirewallRule>();

        rules.add(rule(ports.appHttp(), "tcp", ANY_CIDR, "App HTTP"));
        // The cluster transport is QUIC — UDP. A tcp rule here left inbound QUIC dropped by the
        // deny-by-default firewall: 0/5 cores ever formed behind it (live-proven 2026-08-09).
        rules.add(rule(ports.cluster(), "udp", ANY_CIDR, "Cluster (Rabia consensus over QUIC)"));
        rules.add(rule(ports.swim(), "udp", ANY_CIDR, "SWIM gossip"));
        addAdminScoped(rules, ports, adminCidr);

        return List.copyOf(rules);
    }

    private static List<FirewallRule> restrictiveRules(PortMapping ports,
                                                       Option<String> adminCidr,
                                                       String internalCidr) {
        var rules = new ArrayList<FirewallRule>();

        rules.add(rule(ports.appHttp(), "tcp", internalCidr, "App HTTP (internal)"));
        rules.add(rule(ports.cluster(), "udp", internalCidr, "Cluster (internal, QUIC)"));
        rules.add(rule(ports.swim(), "udp", internalCidr, "SWIM gossip (internal)"));
        addAdminScoped(rules, ports, adminCidr);

        return List.copyOf(rules);
    }

    /// Management API and bootstrap SSH, both scoped to the operator network — or omitted entirely
    /// when no admin CIDR was given. Bootstrap reaches nodes on BOTH: it deploys the runtime over
    /// SSH and its readiness gate polls the management API on each node's public address, so a
    /// preset that omits them produces a config whose bootstrap fails on healthy nodes. Pre-flight
    /// warns about exactly that; widening to `0.0.0.0/0` to avoid the warning is the wrong trade.
    private static void addAdminScoped(List<FirewallRule> rules, PortMapping ports, Option<String> adminCidr) {
        adminCidr.onPresent(cidr -> {
            rules.add(rule(SSH_PORT, "tcp", cidr, "Bootstrap SSH (admin only)"));
            rules.add(rule(ports.management(), "tcp", cidr, "Management API (admin only)"));
        });
    }

    private static FirewallRule rule(int port, String protocol, String cidr, String description) {
        return FirewallRule.firewallRule(port, protocol, cidr, Option.some(description));
    }

    record unused() implements FirewallPresets {}
}
