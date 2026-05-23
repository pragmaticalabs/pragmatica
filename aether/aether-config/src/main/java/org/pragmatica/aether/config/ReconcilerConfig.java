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
/// `normalPhaseWarmup` — grace period the reconciler honours immediately after the
/// cluster phase transitions to `NORMAL`. During warmup the periodic tick still runs
/// (SWIM-since tracker is updated and the snapshot is built) but rules are NOT
/// evaluated. Required because SWIM gossip can briefly flap during cluster formation
/// or recovery, and a freshly-entered `NORMAL` window can therefore contain peers that
/// the `OnDutyFaulty` / `JoiningTimeout` rules would otherwise force-decommission
/// despite being on the recovery path. The warmup also resets the SWIM-since tracker
/// on every phase transition so stale `Faulty`-since stamps from the prior NORMAL
/// window cannot trigger immediate firing on re-entry. Default: 60s — comfortably
/// exceeds `SWIM_FAULTY_DECLARATION × 3` (30s) so any rule that depends on a sustained
/// Faulty signal genuinely sees a sustained Faulty signal post-warmup.
///
/// `rules` — per-rule enable + enforce toggles. See `ReconcilerRulesConfig`. Default
/// (RC1, post-grace-period-fix): five rules `enforce=true`, two rules audit-only
/// forever (`joiningStuckAlert`, `stoppedZombie`). The `normalPhaseWarmup` field above
/// makes this safe — false positives observed in remote validation #3 stemmed from
/// the reconciler firing within the first 30s of NORMAL on still-stabilizing peers.
/// Operators can flip individual rules back to audit-only via
/// `[reconciler.rules.<rule>] enforce=false` in `aether.toml`.
///
/// `recentDecisionsCapacity` — ring buffer size for the per-rule `recentDecisions`
/// surfaced by `GET /api/nodes/lifecycle/reconciler`. Default: 50 per rule.
public record ReconcilerConfig(boolean enabled,
                               TimeSpan tickInterval,
                               TimeSpan normalPhaseWarmup,
                               ReconcilerRulesConfig rules,
                               int recentDecisionsCapacity) {
    public static final TimeSpan DEFAULT_TICK_INTERVAL = timeSpan(10).seconds();
    public static final TimeSpan DEFAULT_NORMAL_PHASE_WARMUP = timeSpan(60).seconds();
    public static final int DEFAULT_RECENT_DECISIONS_CAPACITY = 50;

    /// Backward-compat 4-arg constructor preserves earlier call sites that pre-date
    /// the `normalPhaseWarmup` field. Threads `DEFAULT_NORMAL_PHASE_WARMUP` (60s).
    public ReconcilerConfig(boolean enabled,
                            TimeSpan tickInterval,
                            ReconcilerRulesConfig rules,
                            int recentDecisionsCapacity) {
        this(enabled, tickInterval, DEFAULT_NORMAL_PHASE_WARMUP, rules, recentDecisionsCapacity);
    }

    public static ReconcilerConfig defaults() {
        return new ReconcilerConfig(true,
                                    DEFAULT_TICK_INTERVAL,
                                    DEFAULT_NORMAL_PHASE_WARMUP,
                                    ReconcilerRulesConfig.enforcingDefaults(),
                                    DEFAULT_RECENT_DECISIONS_CAPACITY);
    }
}
