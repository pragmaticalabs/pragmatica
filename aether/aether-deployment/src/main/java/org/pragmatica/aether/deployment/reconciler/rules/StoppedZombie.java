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
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;


/// Rule `StoppedZombie` (cluster-convergence-reconciler-spec §7.1, row 7).
///
/// Trigger: a peer's `NodeLifecycleValue.state == STOPPED` AND SWIM still reports
/// `HEALTHY` — i.e. the KV says the node is terminated but the container is still
/// running and gossipping. This is an invariant violation and surfaces audit-only —
/// the spec is explicit that this rule stays audit-only forever (Phase 5 PR-E does
/// NOT flip it).
///
/// The audit emission carries a `ForceDecommission` shape so the audit-stream schema
/// is consistent across rules; the reconciler short-circuits dispatch when
/// `enforce=false`.
public final class StoppedZombie implements ReconciliationRule {
    public static final String NAME = "StoppedZombie";

    private StoppedZombie() {}

    public static StoppedZombie stoppedZombie() {
        return new StoppedZombie();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().stoppedZombie().enabled()) {
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
        if (value.state() != NodeLifecycleState.STOPPED) {return;}
        if (!swimAlive(snapshot.swimHealth(), peer)) {return;}

        var justification = Causes.cause("StoppedZombie: peer "
                                         + peer.id()
                                         + " is KV-STOPPED but SWIM still reports HEALTHY — invariant violation");
        sink.add(new ReconciliationAction(peer,
                                          new ForceDecommission(peer,
                                                                StopReason.FORCED,
                                                                justification,
                                                                HlcTimestamp.ZERO),
                                          justification));
    }

    private static boolean swimAlive(Map<NodeId, SwimHealth> swim, NodeId peer) {
        return swim.get(peer) == SwimHealth.HEALTHY;
    }
}
