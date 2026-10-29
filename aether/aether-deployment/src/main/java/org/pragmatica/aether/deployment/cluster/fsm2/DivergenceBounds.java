// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

/// The transition timeouts `T` of the goal/state divergence clock (spec R-6.1.1), in milliseconds, one per expected next step of a pair.
/// They are configuration, not constants (P3-Q12, R-3.4.10): the interpreter derives them from `[timeouts.deployment]` where a key
/// exists (`loading`, `deactivating`, `unloading`), and the defaults below are the existing config defaults.
///
/// - `load`: LOADING and an absent row heading to LOADED or ACTIVE, 2 min.
/// - `activate`: LOADED or ACTIVATING heading to ACTIVE, 90 s. The `activating` config key (60 s) is the activate **step** bound, not
///   this progress window, so it stays at the `SliceState.ACTIVATING` value (R-6.1.4, Q11).
/// - `deactivate`: ACTIVE or DEACTIVATING heading to LOADED, 30 s.
/// - `unload`: heading to UNLOADED, 2 min.
public record DivergenceBounds(long load, long activate, long deactivate, long unload) {
    public static final DivergenceBounds DEFAULT = new DivergenceBounds(120_000L, 90_000L, 30_000L, 120_000L);

    public static DivergenceBounds divergenceBounds(long load, long activate, long deactivate, long unload) {
        return new DivergenceBounds(load, activate, deactivate, unload);
    }
}
