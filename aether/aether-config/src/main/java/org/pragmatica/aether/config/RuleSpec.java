// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.config;


/// Per-rule toggle pair for the Phase 4 PR-D `LifecycleReconciler`. Each of the seven
/// reconciliation rules (cluster-convergence-reconciler-spec §7.1) carries its own
/// `RuleSpec`:
///
///   - `enabled` — when `false` the rule is skipped entirely at evaluation time; no
///                 audit-only event is emitted and no command is dispatched. Operators
///                 can disable a noisy rule without disabling the rest of the reconciler.
///   - `enforce` — when `true` the rule's emitted command is dispatched through
///                 `LifecycleWriter.applyCommand(...)` (which publishes
///                 `CommandReceived` + `CommandApplied` via the lifecycle writer's audit
///                 path). When `false` the reconciler publishes an audit-only
///                 `CommandLifecycleEvent.CommandReceived` with `accepted=false` and
///                 source `RECONCILER` so operators can see what the rule WOULD have
///                 done without any state mutation.
///
/// Phase 4 PR-D ships with `enforce=false` for ALL rules (dry-run). Phase 5 PR-E flips
/// the five rules marked as "enforce in Phase 5" in the spec (§7.1).
public record RuleSpec(boolean enabled, boolean enforce) {
    public static RuleSpec dryRun() {
        return new RuleSpec(true, false);
    }

    public static RuleSpec enforcing() {
        return new RuleSpec(true, true);
    }

    public static RuleSpec disabled() {
        return new RuleSpec(false, false);
    }
}
