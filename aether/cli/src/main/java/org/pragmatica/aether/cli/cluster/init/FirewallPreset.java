// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.cli.cluster.init;

/// Firewall preset selection for the wizard.
///
/// - STANDARD: all 4 ports open from any source. Reasonable default for non-public clusters.
/// - RESTRICTIVE: management/app from admin CIDR; cluster/SWIM from internal CIDR.
/// - OPEN: no rules emitted; cloud provider security group / firewall left wide open.
///   Loud warning surfaced.
/// - CUSTOM: operator supplies rules one-by-one; no preset applied.
public enum FirewallPreset {
    STANDARD,
    RESTRICTIVE,
    OPEN,
    CUSTOM
}
