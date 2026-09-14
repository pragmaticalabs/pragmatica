// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config.cluster;

/// `[operations.auto_heal]` as the cluster TOML declares it: `enabled` only. The eight tunables that
/// used to sit beside it (retry interval, startup cooldown, stale-observation TTL, QUIC miss
/// threshold, provisioning timeout, provision stability window, decommissioned retention, SWIM
/// hints TTL) parsed into this record and reached no node — the runtime builds its
/// `AutoHealConfig` from `[cluster] max_nodes` and `[timeouts.scaling] auto_heal_startup_cooldown`
/// in the NODE config — so `ClusterBootstrapConfigParser` now refuses them (PF-26, #675) instead
/// of parsing and discarding. `enabled = false` is refused by PF-25 for the same reason.
public record AutoHealSpec(boolean enabled) {
    public static AutoHealSpec autoHealSpec(boolean enabled) {
        return new AutoHealSpec(enabled);
    }

    public static AutoHealSpec defaultAutoHealSpec() {
        return new AutoHealSpec(true);
    }
}
