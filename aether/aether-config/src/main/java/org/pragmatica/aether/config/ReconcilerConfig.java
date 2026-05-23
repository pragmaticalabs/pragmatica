// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;

import org.pragmatica.lang.io.TimeSpan;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;


/// Phase 4 PR-D — `[reconciler]` section of `aether.toml`.
///
/// `enabled` — global on/off switch for the reconciler. When `false` the
/// `LifecycleReconcilerRecord` is constructed but its periodic tick is not scheduled;
/// effectively the reconciler is dormant. Default: `true`.
///
/// `tickInterval` — periodic tick cadence. Spec §7 caps at `[5s, 60s]` — sub-5s overlaps
/// the SWIM probe cycle and produces noise; >60s makes operator-perceived recovery
/// sluggish. Default: 10s.
///
/// `rules` — per-rule enable + enforce toggles. See `ReconcilerRulesConfig`. Default:
/// every rule enabled, every rule `enforce=false` (Phase 4 dry-run).
///
/// `recentDecisionsCapacity` — ring buffer size for the per-rule `recentDecisions`
/// surfaced by `GET /api/nodes/lifecycle/reconciler`. Default: 50 per rule.
public record ReconcilerConfig(boolean enabled,
                               TimeSpan tickInterval,
                               ReconcilerRulesConfig rules,
                               int recentDecisionsCapacity) {
    public static final TimeSpan DEFAULT_TICK_INTERVAL = timeSpan(10).seconds();
    public static final int DEFAULT_RECENT_DECISIONS_CAPACITY = 50;

    public static ReconcilerConfig defaults() {
        return new ReconcilerConfig(true,
                                    DEFAULT_TICK_INTERVAL,
                                    ReconcilerRulesConfig.dryRunDefaults(),
                                    DEFAULT_RECENT_DECISIONS_CAPACITY);
    }
}
