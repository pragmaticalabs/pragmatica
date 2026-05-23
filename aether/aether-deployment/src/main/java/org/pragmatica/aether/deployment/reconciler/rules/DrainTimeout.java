// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.slice.kvstore.AetherValue.DrainDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.List;


/// Rule `DrainTimeout` (cluster-convergence-reconciler-spec §7.1, row 4).
///
/// Trigger: a peer's `NodeLifecycleValue.state == DRAINING` AND `DRAIN_DEADLINE × 1.5`
/// has elapsed since the DRAINING entry was written. The DRAIN_DEADLINE atom (Phase 1
/// step J) provides the exact entry-time wall clock when available; the fallback is
/// `value.updatedAt()`.
///
/// Behaviour: emit `ForceDecommission(StopReason.DRAIN_FAILED)` so the post-drain
/// terminal state carries the correct `StopReason` sidecar. Replaces the old
/// `FAILED_DRAIN` direct-write path with a command-routed equivalent.
///
/// Skips peers in `activeSyncHolds` — a draining node should not be syncing, but the
/// skip is cheap insurance.
public final class DrainTimeout implements ReconciliationRule {
    public static final String NAME = "DrainTimeout";
    public static final double BUDGET_MULTIPLIER = 1.5;

    private DrainTimeout() {}

    public static DrainTimeout drainTimeout() {
        return new DrainTimeout();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().drainTimeout().enabled()) {
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
        if (value.state() != NodeLifecycleState.DRAINING) {return;}
        if (snapshot.isSyncHeld(peer)) {return;}
        if (!deadlineExceeded(snapshot, peer, value)) {return;}

        var justification = Causes.cause("DrainTimeout: peer "
                                         + peer.id()
                                         + " has been DRAINING past DRAIN_DEADLINE × "
                                         + BUDGET_MULTIPLIER);
        sink.add(new ReconciliationAction(peer,
                                          new ForceDecommission(peer,
                                                                StopReason.DRAIN_FAILED,
                                                                justification,
                                                                snapshot.at()),
                                          justification));
    }

    private static boolean deadlineExceeded(ReconciliationSnapshot snapshot,
                                            NodeId peer,
                                            NodeLifecycleValue value) {
        var budgetMs = (long) (snapshot.fsmConfig().drainTimeout().millis() * BUDGET_MULTIPLIER);
        var enteredAtMs = snapshot.drainDeadlineFor(peer)
                                  .map(DrainDeadlineValue::deadlineMs)
                                  .map(deadlineMs -> deadlineMs - snapshot.fsmConfig().drainTimeout().millis())
                                  .or(value.updatedAt());
        return snapshot.nowMs() - enteredAtMs >= budgetMs;
    }
}
