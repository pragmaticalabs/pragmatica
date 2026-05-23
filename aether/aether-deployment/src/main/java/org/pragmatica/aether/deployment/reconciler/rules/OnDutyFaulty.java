// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/// Rule `OnDutyFaulty` (cluster-convergence-reconciler-spec §7.1, row 3).
///
/// Trigger: a peer's `NodeLifecycleValue.state == ON_DUTY` AND SWIM observation is
/// `FAULTY` AND has been `FAULTY` for at least `SWIM_FAULTY_DECLARATION × 3`
/// (`SWIM_FAULTY_DECLARATION_MS × BUDGET_MULTIPLIER` = 30s default).
///
/// Behaviour: emit `ForceDecommission(StopReason.FORCED)` so the orphaned ON_DUTY entry
/// is cleaned up. Skips peers in `activeSyncHolds`.
///
/// The spec calls for "SWIM emitted positive `Faulty` (not mere absence from `Alive`)" —
/// `snapshot.swimHealth().get(peer) == FAULTY` strictly enforces this; an absent peer
/// returns null and is ignored.
public final class OnDutyFaulty implements ReconciliationRule {
    public static final String NAME = "OnDutyFaulty";
    /// SWIM `suspectTimeout` default is 10s (`SwimConfig.DEFAULT.suspectTimeout()`).
    /// The reconciler uses a fixed 10s baseline so this rule can evaluate without
    /// pulling `SwimConfig` through the reconciler dependency chain — RC2 #N can plumb
    /// the live `suspectTimeout` if false positives are observed.
    public static final long SWIM_FAULTY_DECLARATION_MS = 10_000L;
    public static final double BUDGET_MULTIPLIER = 3.0;

    private OnDutyFaulty() {}

    public static OnDutyFaulty onDutyFaulty() {
        return new OnDutyFaulty();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().onDutyFaulty().enabled()) {
            return Result.success(List.of());
        }
        var actions = new ArrayList<ReconciliationAction>();
        snapshot.lifecycleEntries().forEach((peer, value) -> appendIfTriggered(snapshot, peer, value, actions));
        return Result.success(List.copyOf(actions));
    }

    private static void appendIfTriggered(ReconciliationSnapshot snapshot,
                                          NodeId peer,
                                          NodeLifecycleValue value,
                                          List<ReconciliationAction> sink) {
        if (value.state() != NodeLifecycleState.ON_DUTY) {return;}
        if (snapshot.isSyncHeld(peer)) {return;}
        if (!swimFaulty(snapshot.swimHealth(), peer)) {return;}
        if (!faultyDurationExceeded(snapshot, peer)) {return;}

        var justification = Causes.cause("OnDutyFaulty: peer "
                                         + peer.id()
                                         + " has been ON_DUTY+FAULTY for SWIM_FAULTY_DECLARATION × "
                                         + BUDGET_MULTIPLIER);
        sink.add(new ReconciliationAction(peer,
                                          new ForceDecommission(peer,
                                                                StopReason.FORCED,
                                                                justification,
                                                                snapshot.at()),
                                          justification));
    }

    private static boolean swimFaulty(Map<NodeId, SwimHealth> swim, NodeId peer) {
        return swim.get(peer) == SwimHealth.FAULTY;
    }

    private static boolean faultyDurationExceeded(ReconciliationSnapshot snapshot, NodeId peer) {
        var budgetMs = (long) (SWIM_FAULTY_DECLARATION_MS * BUDGET_MULTIPLIER);
        return snapshot.swimSinceFor(peer)
                       .map(since -> snapshot.nowMs() - since >= budgetMs)
                       .or(false);
    }
}
