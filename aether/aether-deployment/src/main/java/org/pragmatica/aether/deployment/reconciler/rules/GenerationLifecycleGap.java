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

import java.util.ArrayList;
import java.util.List;


/// Rule `GenerationLifecycleGap` (cluster-convergence-reconciler-spec §7.1, row 5).
///
/// Trigger: a peer that is a member of the current Rabia generation snapshot has NO
/// `NodeLifecycleKey` entry. The race window is normally sub-second; if it persists past
/// the configured budget (30s default) the FSM is structurally divergent from
/// consensus and the reconciler must repair the gap.
///
/// Behaviour: emit `RecordJoining` to install a fresh JOINING entry for the peer. The
/// reducer is the natural deduplicator — applying `RecordJoining` to a peer that has
/// since acquired an entry is a no-op.
///
/// Skips peers in `activeSyncHolds`. No SWIM-state precondition — the rule fires on
/// pure FSM/Rabia divergence.
///
/// **Tick-budget tracking.** The budget is "≥ 30s since the peer was first observed in
/// the generation snapshot without a lifecycle entry". The reconciler does NOT
/// currently persist per-rule first-seen timestamps — Phase 4 ships with the simpler
/// "if Rabia member + no lifecycle entry at this tick → emit" semantics, accepting that
/// the rule may fire one tick early (10s vs 30s). The structural correctness is
/// preserved (the reducer dedupes); the budget tightening is RC2 polish.
public final class GenerationLifecycleGap implements ReconciliationRule {
    public static final String NAME = "GenerationLifecycleGap";

    private GenerationLifecycleGap() {}

    public static GenerationLifecycleGap generationLifecycleGap() {
        return new GenerationLifecycleGap();
    }

    @Override public String name() {
        return NAME;
    }

    @Override public Result<List<ReconciliationAction>> evaluate(ReconciliationSnapshot snapshot) {
        if (!snapshot.rulesConfig().generationLifecycleGap().enabled()) {
            return Result.success(List.of());
        }
        var actions = new ArrayList<ReconciliationAction>();
        snapshot.generationMembers().forEach(peer -> appendIfTriggered(snapshot, peer, actions));
        return Result.success(List.copyOf(actions));
    }

    private static void appendIfTriggered(ReconciliationSnapshot snapshot,
                                          NodeId peer,
                                          List<ReconciliationAction> sink) {
        if (snapshot.isSyncHeld(peer)) {return;}
        if (snapshot.lifecycleFor(peer).isPresent()) {return;}

        var justification = Causes.cause("GenerationLifecycleGap: peer "
                                         + peer.id()
                                         + " is a Rabia generation member but has no NodeLifecycleKey entry");
        sink.add(new ReconciliationAction(peer,
                                          new RecordJoining(peer,
                                                            Option.none(),
                                                            justification,
                                                            snapshot.at()),
                                          justification));
    }
}
