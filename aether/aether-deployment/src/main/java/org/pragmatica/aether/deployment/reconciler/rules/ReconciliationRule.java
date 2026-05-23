// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.lang.Result;

import java.util.List;


/// SPI implemented by each Phase 4 PR-D `LifecycleReconciler` rule
/// (cluster-convergence-reconciler-spec §7.1).
///
/// Rules are **pure** — `evaluate(snapshot)` reads the supplied immutable
/// `ReconciliationSnapshot` and returns the set of `ReconciliationAction`s the rule wants
/// emitted for this tick. No side effects: command dispatch and audit publishing live in
/// `LifecycleReconcilerRecord`. This split keeps each rule trivially unit-testable
/// (snapshot in / actions out) and lets the reconciler centrally honour per-rule
/// `enforce` toggles before any command crosses into `LifecycleWriter`.
///
/// Idempotency. Same snapshot → same action set. The FSM reducer is the natural
/// deduplicator (applying `ForceDecommission` twice to an already-STOPPED node is a
/// no-op), so emitting actions on every tick while the precondition holds is safe.
public interface ReconciliationRule {
    /// Stable identifier for the rule. Used as the `[reconciler.rules.<name>]` key in
    /// TOML config and as the discriminator on `CommandLifecycleEvent.reasonTag` /
    /// `recentDecisions` entries surfaced by the status endpoint.
    String name();

    /// Inspect the supplied snapshot and return the set of actions this rule wants
    /// emitted. The list MAY be empty (nothing to do this tick). Failures bubble up via
    /// the `Result` channel — the reconciler logs them and continues with the next rule
    /// rather than aborting the tick.
    Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot);
}
