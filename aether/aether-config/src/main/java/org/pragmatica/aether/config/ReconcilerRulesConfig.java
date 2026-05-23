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
/// Phase 5 PR-E flips the five enforce-in-Phase-5 rules via `enforcingDefaults()`; two rules
/// (`joiningStuckAlert`, `stoppedZombie`) stay audit-only by design (observability signals,
/// not action triggers per spec §7.1). Operators retain a per-rule escape hatch via
/// `[reconciler.rules.<rule>] enforce=false` in `aether.toml`.
public record ReconcilerRulesConfig(RuleSpec joiningTimeout,
                                    RuleSpec joiningStuckAlert,
                                    RuleSpec onDutyFaulty,
                                    RuleSpec drainTimeout,
                                    RuleSpec generationLifecycleGap,
                                    RuleSpec swimLifecycleGap,
                                    RuleSpec stoppedZombie) {
    /// Phase 4 PR-D dry-run factory — every rule enabled, every rule audit-only. Retained
    /// after the Phase 5 PR-E flip as the operator escape hatch: setting
    /// `[reconciler.rules.*] enforce=false` in `aether.toml` resolves back to this shape
    /// per-rule, and tests/operators may pass this factory directly to
    /// `ReconcilerConfig` to disable enforcement globally.
    public static ReconcilerRulesConfig dryRunDefaults() {
        return new ReconcilerRulesConfig(RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.dryRun());
    }

    /// Phase 5 PR-E enforcing factory — five rules flip to `enforce=true`; two rules stay
    /// audit-only forever per spec §7.1:
    ///
    ///   - `joiningStuckAlert` — observation-only alert for stuck-but-alive containers;
    ///     no destructive action is desired (the operator decides whether to intervene).
    ///   - `stoppedZombie`     — invariant violation surface; emits an alert so the
    ///     responsible component can be diagnosed, but does not destroy the zombie
    ///     itself (containers are usually killed by the orchestration layer separately).
    ///
    /// All seven rules remain `enabled=true` — operators flip back to dry-run per rule
    /// (or wholesale via `ReconcilerConfig` construction in tests) but rules are never
    /// auto-disabled by the defaults.
    public static ReconcilerRulesConfig enforcingDefaults() {
        return new ReconcilerRulesConfig(RuleSpec.enforcing(),
                                         RuleSpec.dryRun(),
                                         RuleSpec.enforcing(),
                                         RuleSpec.enforcing(),
                                         RuleSpec.enforcing(),
                                         RuleSpec.enforcing(),
                                         RuleSpec.dryRun());
    }
}
