// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

import org.pragmatica.lang.Option;


/// Firewall rule definition. S5.1.8
public record FirewallRule(int port, String protocol, String sourceCidr, Option<String> description) {
    public static FirewallRule firewallRule(int port, String protocol, String sourceCidr, Option<String> description) {
        return new FirewallRule(port, protocol, sourceCidr, description);
    }
}
