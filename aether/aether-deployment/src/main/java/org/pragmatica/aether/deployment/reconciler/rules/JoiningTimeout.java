// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.ForceDecommission;
import org.pragmatica.aether.slice.kvstore.AetherValue.JoinDeadlineValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/// Rule `JoiningTimeout` (cluster-convergence-reconciler-spec §7.1, row 1).
///
/// Trigger: a peer's `NodeLifecycleValue.state == JOINING` AND SWIM observation is
/// `FAULTY` or absent ("container demonstrably gone") AND `JOIN_DEADLINE × 1.5` has
/// elapsed since the JOINING entry was written.
///
/// Behaviour: emit `ForceDecommission(StopReason.FORCED)` to clean up the orphaned
/// JOINING entry. Skips peers in `activeSyncHolds` — a node consuming a KV-sync
/// snapshot can legitimately be JOINING-and-quiet for the duration of the sync.
///
/// Phase 4 PR-D ships with `enforce=false` (audit-only). Phase 5 PR-E flips to
/// enforcing once dry-run validation confirms low false-positive rate.
public final class JoiningTimeout implements ReconciliationRule {
    public static final String NAME = "JoiningTimeout";
    public static final double BUDGET_MULTIPLIER = 1.5;

    private JoiningTimeout() {}

    public static JoiningTimeout joiningTimeout() {
        return new JoiningTimeout();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().joiningTimeout().enabled()) {
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
        if (value.state() != NodeLifecycleState.JOINING) {return;}
        if (snapshot.isSyncHeld(peer)) {return;}
        if (!swimAbsentOrFaulty(snapshot.swimHealth(), peer)) {return;}
        if (!deadlineExceeded(snapshot, peer, value)) {return;}

        var justification = Causes.cause("JoiningTimeout: peer "
                                         + peer.id()
                                         + " has been JOINING past JOIN_DEADLINE × "
                                         + BUDGET_MULTIPLIER
                                         + " with SWIM Faulty/absent");
        sink.add(new ReconciliationAction(peer,
                                          new ForceDecommission(peer,
                                                                StopReason.FORCED,
                                                                justification,
                                                                HlcTimestamp.ZERO),
                                          justification));
    }

    private static boolean swimAbsentOrFaulty(Map<NodeId, SwimHealth> swim, NodeId peer) {
        var health = swim.get(peer);
        return health == null || health == SwimHealth.FAULTY;
    }

    private static boolean deadlineExceeded(ReconciliationSnapshot snapshot,
                                            NodeId peer,
                                            NodeLifecycleValue value) {
        var budgetMs = (long) (snapshot.fsmConfig().joinDeadline().millis() * BUDGET_MULTIPLIER);
        var enteredAtMs = snapshot.joinDeadlineFor(peer)
                                  .map(JoinDeadlineValue::deadlineMs)
                                  .map(deadlineMs -> deadlineMs - snapshot.fsmConfig().joinDeadline().millis())
                                  .or(value.updatedAt());
        return snapshot.nowMs() - enteredAtMs >= budgetMs;
    }
}
