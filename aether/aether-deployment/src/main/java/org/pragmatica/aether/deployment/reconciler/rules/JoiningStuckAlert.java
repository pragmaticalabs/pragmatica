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
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/// Rule `JoiningStuckAlert` (cluster-convergence-reconciler-spec §7.1, row 2).
///
/// Trigger: a peer's `NodeLifecycleValue.state == JOINING` AND SWIM observation is
/// `HEALTHY` AND `JOIN_DEADLINE × 3` has elapsed — i.e. the container is alive but the
/// node is not progressing past JOINING. This is the "stuck, but not crashed" case;
/// stays audit-only ALWAYS (even in Phase 5 PR-E) per the spec — alerting is the
/// intended remediation channel.
///
/// The rule emits a `ForceDecommission` shape so the audit stream carries the same
/// payload schema as the enforcing rules; the reconciler will short-circuit the
/// dispatch when the rule's `enforce` toggle is false (which it permanently is for
/// `JoiningStuckAlert`).
///
/// Skips peers in `activeSyncHolds` — a node consuming a long KV-sync snapshot can
/// legitimately appear stuck.
public final class JoiningStuckAlert implements ReconciliationRule {
    public static final String NAME = "JoiningStuckAlert";
    public static final double BUDGET_MULTIPLIER = 3.0;

    private JoiningStuckAlert() {}

    public static JoiningStuckAlert joiningStuckAlert() {
        return new JoiningStuckAlert();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().joiningStuckAlert().enabled()) {
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
        if (!swimHealthy(snapshot.swimHealth(), peer)) {return;}
        if (!deadlineExceeded(snapshot, peer, value)) {return;}

        var justification = Causes.cause("JoiningStuckAlert: peer "
                                         + peer.id()
                                         + " has been JOINING past JOIN_DEADLINE × "
                                         + BUDGET_MULTIPLIER
                                         + " while SWIM Healthy — container alive but not progressing");
        sink.add(new ReconciliationAction(peer,
                                          new ForceDecommission(peer,
                                                                StopReason.FORCED,
                                                                justification,
                                                                snapshot.at()),
                                          justification));
    }

    private static boolean swimHealthy(Map<NodeId, SwimHealth> swim, NodeId peer) {
        return swim.get(peer) == SwimHealth.HEALTHY;
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
