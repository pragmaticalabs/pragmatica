// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;


/// Per-rule enable + enforce toggles for the seven `LifecycleReconciler` rules
/// (cluster-convergence-reconciler-spec §7.1). One `RuleSpec` per rule:
///
///   - `joiningTimeout`           — SWIM Faulty/Departed AND `JOIN_DEADLINE × 1.5` elapsed
///   - `joiningStuckAlert`        — SWIM Alive past `JOIN_DEADLINE × 3`
///   - `onDutyFaulty`             — SWIM Faulty for `SWIM_FAULTY_DECLARATION × 3`
///   - `drainTimeout`             — DRAINING + `DRAIN_DEADLINE × 1.5` elapsed
///   - `generationLifecycleGap`   — Rabia member with no lifecycle entry for 30s
///   - `swimLifecycleGap`         — SWIM peer with no lifecycle entry for 30s (audit-only)
///   - `stoppedZombie`            — STOPPED in KV but SWIM still Alive (audit-only)
///
/// Phase 4 PR-D defaults: ALL rules `enabled=true`, `enforce=false` (dry-run).
/// Phase 5 PR-E will flip the five enforce-in-Phase-5 rules; two rules
/// (`joiningStuckAlert`, `stoppedZombie`) stay audit-only by design.
public record ReconcilerRulesConfig(RuleSpec joiningTimeout,
                                    RuleSpec joiningStuckAlert,
                                    RuleSpec onDutyFaulty,
                                    RuleSpec drainTimeout,
                                    RuleSpec generationLifecycleGap,
                                    RuleSpec swimLifecycleGap,
                                    RuleSpec stoppedZombie) {
    /// Phase 4 PR-D default — every rule enabled, every rule audit-only. Operators flip
    /// individual rules to `enforce=true` after Phase 4 dry-run validation confirms the
    /// false-positive rate is acceptable.
    public static ReconcilerRulesConfig dryRunDefaults() {
        return new ReconcilerRulesConfig(RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun());
    }
}
