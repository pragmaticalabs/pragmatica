// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

import org.pragmatica.aether.config.cluster.FirewallRule;
import org.pragmatica.lang.Option;

import java.util.List;


public sealed interface FirewallPresets {
    int MGMT_PORT = 8080;
    int APP_HTTP_PORT = 8070;
    int CLUSTER_PORT = 7100;
    int SWIM_PORT = 7200;
    String ANY_CIDR = "0.0.0.0/0";
    String DEFAULT_INTERNAL_CIDR = "10.0.0.0/8";

    static List<FirewallRule> rulesFor(FirewallPreset preset, String adminCidr, String internalCidr) {
        return switch (preset) {
            case STANDARD -> List.of(rule(MGMT_PORT, "tcp", ANY_CIDR, "Management API"),
                                     rule(APP_HTTP_PORT, "tcp", ANY_CIDR, "App HTTP"),
                                     rule(CLUSTER_PORT, "tcp", ANY_CIDR, "Cluster (Rabia consensus)"),
                                     rule(SWIM_PORT, "udp", ANY_CIDR, "SWIM gossip"));
            case RESTRICTIVE -> List.of(rule(MGMT_PORT, "tcp", adminCidr, "Management API (admin only)"),
                                        rule(APP_HTTP_PORT, "tcp", adminCidr, "App HTTP (admin only)"),
                                        rule(CLUSTER_PORT, "tcp", internalCidr, "Cluster (internal)"),
                                        rule(SWIM_PORT, "udp", internalCidr, "SWIM gossip (internal)"));
            case OPEN, CUSTOM -> List.of();
        };
    }

    private static FirewallRule rule(int port, String protocol, String cidr, String description) {
        return FirewallRule.firewallRule(port, protocol, cidr, Option.some(description));
    }

    record unused() implements FirewallPresets {}
}
