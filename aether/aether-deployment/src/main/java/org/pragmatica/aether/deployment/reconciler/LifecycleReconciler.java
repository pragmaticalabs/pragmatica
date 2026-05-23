// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler;

import org.pragmatica.aether.config.ReconcilerConfig;
import org.pragmatica.aether.deployment.audit.CommandLifecycleEvent;
import org.pragmatica.aether.deployment.cluster.LifecycleWriter;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsmConfig;
import org.pragmatica.aether.slice.StreamPublisher;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ClusterPhase;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.MembershipView;
import org.pragmatica.lang.Contract;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.SwimHealth;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.function.Supplier;


/// Public surface of the Phase 4 PR-D leader-only `LifecycleReconciler` component
/// (cluster-convergence-reconciler-spec §7). Mirrors the `ClusterTopologyManager` /
/// `ClusterTopologyManagerRecord` split — operators wire by interface; the record-shaped
/// implementation handles the periodic tick + leader lifecycle.
///
/// The reconciler is activated when this node acquires the leader lease (`activate()`)
/// and deactivated on lease loss (`deactivate()`). Reconciliation ticks are scheduled at
/// the configured `tickInterval` and are no-ops while the cluster phase is not `NORMAL`.
///
/// The status accessors (`active()`, `lastTickAt()`, `lastActionAt()`, `ruleStatuses()`,
/// `recentDecisions()`) back the `GET /api/nodes/lifecycle/reconciler` endpoint and are
/// safe to read from any thread.
public interface LifecycleReconciler {
    @Contract void activate();

    @Contract void deactivate();

    boolean active();

    ClusterPhase observedPhase();

    Option<Long> lastTickAt();

    Option<Long> lastActionAt();

    List<RuleStatus> ruleStatuses();

    List<RuleDecision> recentDecisions();

    /// Per-rule snapshot surfaced through `GET /api/nodes/lifecycle/reconciler`.
    record RuleStatus(String name,
                      boolean enabled,
                      boolean enforce,
                      Option<Long> lastFiredAtMs,
                      long fireCount) {}

    /// Single decision recorded in the reconciler's ring buffer.
    record RuleDecision(String ruleName,
                        String peer,
                        String commandType,
                        String reasonTag,
                        String justification,
                        boolean enforced,
                        long atMs) {}

    /// Build a leader-only reconciler bound to the supplied collaborators. The instance
    /// returned is dormant — `activate()` must be called when this node acquires the
    /// leader lease (typically from `AetherNode.toggleReconcilerOnLeaderChange`).
    ///
    /// `swimHealthSnapshot` is a `Supplier<Map<NodeId, SwimHealth>>` polled once per
    /// tick; the reconciler maintains its own per-peer "since" timestamps by diffing
    /// consecutive snapshots — no external "since" feed is required.
    static LifecycleReconciler lifecycleReconciler(Supplier<ClusterPhase> phaseSupplier,
                                                   KVStore<AetherKey, AetherValue> kvStore,
                                                   Supplier<Option<MembershipView>> generationSnapshot,
                                                   Supplier<Map<NodeId, SwimHealth>> swimHealthSnapshot,
                                                   Supplier<Set<NodeId>> activeSyncHolds,
                                                   LifecycleWriter lifecycleWriter,
                                                   StreamPublisher<CommandLifecycleEvent> auditPublisher,
                                                   MembershipFsmConfig fsmConfig,
                                                   ReconcilerConfig config,
                                                   LongSupplier clock) {
        return LifecycleReconcilerRecord.lifecycleReconcilerRecord(phaseSupplier,
                                                                   kvStore,
                                                                   generationSnapshot,
                                                                   swimHealthSnapshot,
                                                                   activeSyncHolds,
                                                                   lifecycleWriter,
                                                                   auditPublisher,
                                                                   fsmConfig,
                                                                   config,
                                                                   clock);
    }
}
