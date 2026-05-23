// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.config.ReconcilerRulesConfig;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimHealth;

import java.util.Map;
import java.util.Set;


/// Immutable per-tick view delivered to every `ReconciliationRule`. Built once at the top
/// of `LifecycleReconcilerRecord.reconcile()` from the KV store, SWIM observation buffer,
/// generation snapshot, sync-hold registry, and configured budgets — then handed to each
/// enabled rule in turn. Rules read from this view ONLY; the snapshot survives only for
/// the duration of the tick.
///
/// Field semantics:
///   - `lifecycleEntries` — every `NodeLifecycleKey` atom currently persisted in KV, keyed
///     by peer. Empty `Option`-style absence is encoded by missing-key; a peer that has
///     no FSM entry is simply not in the map.
///   - `swimHealth` — most recent observed SWIM health per peer. Peers with no SWIM
///     observation are absent. `SwimHealth.UNKNOWN` is preserved when the SWIM layer
///     explicitly emitted UNKNOWN.
///   - `swimSinceMs` — wall-clock millis at which the peer was first observed in its
///     current `swimHealth` value. Used by rules whose budget is "SWIM state X persisted
///     for ≥ T". Absent for peers with no SWIM observation history.
///   - `generationMembers` — set of node IDs that the Rabia generation snapshot currently
///     considers core members. Empty when no snapshot is available (rule
///     `GenerationLifecycleGap` no-ops in that case).
///   - `joinDeadlines` / `drainDeadlines` — per-peer KV deadline atoms (Phase 1 step J).
///     Used by `JoiningTimeout` / `DrainTimeout`. Absent entries mean "no deadline atom
///     written" — the rule then falls back to `lifecycleEntries.get(peer).updatedAt()`
///     plus the configured budget multiplier.
///   - `activeSyncHolds` — peers currently consuming a KV-sync snapshot. Force-decommission
///     rules MUST skip these (Phase 2 PR-B `SyncHoldRegistry.activeHolds()`).
///   - `nowMs` — wall-clock instant the tick was sampled. Rules MUST use this rather than
///     calling `System.currentTimeMillis()` directly so deterministic tests can inject a
///     clock.
///   - `fsmConfig` — `MembershipFsmConfig.joinDeadline()` / `drainTimeout()` budgets.
///     Rules multiply these by their per-rule multiplier (1.5×, 3×, …) to derive the
///     effective threshold.
///   - `rulesConfig` — per-rule `enabled` + `enforce` toggles. Rules consult their own
///     `enabled` toggle and short-circuit when disabled; `enforce` is consulted in the
///     reconciler itself (rule output is generated either way so the audit-only event
///     captures the would-have-fired set).
public record ReconciliationSnapshot(Map<NodeId, NodeLifecycleValue> lifecycleEntries,
                                     Map<NodeId, SwimHealth> swimHealth,
                                     Map<NodeId, Long> swimSinceMs,
                                     Set<NodeId> generationMembers,
                                     Map<NodeId, JoinDeadlineValue> joinDeadlines,
                                     Map<NodeId, DrainDeadlineValue> drainDeadlines,
                                     Set<NodeId> activeSyncHolds,
                                     long nowMs,
                                     MembershipFsmConfig fsmConfig,
                                     ReconcilerRulesConfig rulesConfig) {
    public ReconciliationSnapshot {
        lifecycleEntries = Map.copyOf(lifecycleEntries);
        swimHealth = Map.copyOf(swimHealth);
        swimSinceMs = Map.copyOf(swimSinceMs);
        generationMembers = Set.copyOf(generationMembers);
        joinDeadlines = Map.copyOf(joinDeadlines);
        drainDeadlines = Map.copyOf(drainDeadlines);
        activeSyncHolds = Set.copyOf(activeSyncHolds);
    }

    public Option<NodeLifecycleValue> lifecycleFor(NodeId peer) {
        return Option.option(lifecycleEntries.get(peer));
    }

    public Option<SwimHealth> swimFor(NodeId peer) {
        return Option.option(swimHealth.get(peer));
    }

    public Option<Long> swimSinceFor(NodeId peer) {
        return Option.option(swimSinceMs.get(peer));
    }

    public Option<JoinDeadlineValue> joinDeadlineFor(NodeId peer) {
        return Option.option(joinDeadlines.get(peer));
    }

    public Option<DrainDeadlineValue> drainDeadlineFor(NodeId peer) {
        return Option.option(drainDeadlines.get(peer));
    }

    public boolean isSyncHeld(NodeId peer) {
        return activeSyncHolds.contains(peer);
    }
}
