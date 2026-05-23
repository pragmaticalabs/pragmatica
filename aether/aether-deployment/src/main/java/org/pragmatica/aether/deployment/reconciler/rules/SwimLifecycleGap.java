// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.reconciler.rules;

import org.pragmatica.aether.deployment.membership.fsm.LifecycleCommand.RecordJoining;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.List;


/// Rule `SwimLifecycleGap` (cluster-convergence-reconciler-spec §7.1, row 6).
///
/// Trigger: a peer observed as SWIM-Alive (`HEALTHY`) AND has NO `NodeLifecycleKey`
/// entry AND has been SWIM-Alive past the configured budget (30s default).
///
/// Behaviour: audit-only — the spec calls for "no historical NodeLifecycleKey entry for
/// this nodeId in audit log within last 1h" lookback guard. Phase 4 PR-D ships
/// audit-only (no lookback gate); Phase 5 PR-E adds the lookback guard before flipping
/// to enforcing. The audit emission carries a `RecordJoining` shape so the audit-stream
/// schema matches the enforcing variant.
///
/// Skips peers in `activeSyncHolds`.
public final class SwimLifecycleGap implements ReconciliationRule {
    public static final String NAME = "SwimLifecycleGap";
    public static final long DEFAULT_BUDGET_MS = 30_000L;

    private SwimLifecycleGap() {}

    public static SwimLifecycleGap swimLifecycleGap() {
        return new SwimLifecycleGap();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().swimLifecycleGap().enabled()) {
            return Result.success(List.of());
        }
        var actions = new ArrayList<ReconciliationAction>();
        snapshot.swimHealth().forEach((peer, _) -> appendIfTriggered(snapshot, peer, actions));
        return Result.success(List.copyOf(actions));
    }

    private static void appendIfTriggered(ReconciliationSnapshot snapshot,
                                          NodeId peer,
                                          List<ReconciliationAction> sink) {
        if (snapshot.isSyncHeld(peer)) {return;}
        if (snapshot.swimHealth().get(peer) != SwimHealth.HEALTHY) {return;}
        if (snapshot.lifecycleFor(peer).isPresent()) {return;}
        if (!aliveDurationExceeded(snapshot, peer)) {return;}

        var justification = Causes.cause("SwimLifecycleGap: peer "
                                         + peer.id()
                                         + " has been SWIM-Healthy for "
                                         + DEFAULT_BUDGET_MS
                                         + "ms with no NodeLifecycleKey entry");
        sink.add(new ReconciliationAction(peer,
                                          new RecordJoining(peer,
                                                            Option.none(),
                                                            justification,
                                                            snapshot.at()),
                                          justification));
    }

    private static boolean aliveDurationExceeded(ReconciliationSnapshot snapshot, NodeId peer) {
        return snapshot.swimSinceFor(peer)
                       .map(since -> snapshot.nowMs() - since >= DEFAULT_BUDGET_MS)
                       .or(false);
    }
}
