// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.SubmitKind;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.TxDraft;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceTargetKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.lang.Option;


/// Orphans (reconcile step 6, #1068): a current observed row whose version the committed target does not permit gets goal
/// UNLOADED. The full observed scan is an **audit** that runs every tenth tick (R-7.3.1); the incremental arms (a target
/// change, removal or routing removal) do the rest at the event.
public sealed interface Orphans {
    int AUDIT_EVERY_TICKS = 10;

    static ClusterModel run(ClusterModel m, Ctx ctx) {
        if (!m.auditDue) {
            return m;
        }

        m.auditDue = false;
        m.observed.keys()
                  .stream()
                  .sorted(Rows.PAIR_ORDER)
                  .filter(pair -> isOrphan(m, pair))
                  .forEach(pair -> Outbox.goal(m,
                                               pair,
                                               SliceGoal.UNLOADED,
                                               GoalMode.FRESH,
                                               Option.none(),
                                               "orphan"));

        return m;
    }

    /// An orphan target survives this many consecutive ticks of observation before it is removed (G2 6.5, 2-tick hysteresis).
    int ORPHAN_TARGET_TICKS = 2;

    /// Reconcile step 6b (CF-8, `newLeader_orphanTarget_removed`): a committed target whose owning blueprint is absent from the mirror is
    /// removed. This is the level-triggered half of the blueprint delete: a removal batch lost with its leader's term leaves no event for
    /// a new leader to act on. At most one scan per tick. A target is removed only after `ORPHAN_TARGET_TICKS` consecutive
    /// observations and is never removed while it could still be owned (see `isOrphanTarget`). The removal is a mutation expecting the
    /// target at the value observed, so a target that moved is not removed; it is not parked: a lost or refused removal is derived again
    /// two ticks later, until the target is gone.
    static ClusterModel runTargets(ClusterModel m) {
        if (m.orphanTargetScanTick == m.tickCount) {
            return m;
        }

        m.orphanTargetScanTick = m.tickCount;
        var candidates = m.targets.keys()
                                  .stream()
                                  .filter(base -> isOrphanTarget(m, base))
                                  .sorted(Comparator.comparing(ArtifactBase::asString))
                                  .toList();

        m.orphanTargets = observed(m, candidates);
        removeAged(m, candidates);

        return m;
    }

    private static PMap<ArtifactBase, Long> observed(ClusterModel m, List<ArtifactBase> candidates) {
        var next = PMap.<ArtifactBase, Long> empty(m.scans);

        for (var base : candidates) {
            next = next.put(base,
                            m.orphanTargets.get(base).or(m.tickCount));
        }

        return next;
    }

    private static ClusterModel removeAged(ClusterModel m, List<ArtifactBase> candidates) {
        candidates.stream()
                  .filter(base -> m.tickCount - m.orphanTargets.get(base)
                                                               .or(m.tickCount) >= ORPHAN_TARGET_TICKS)
                  .forEach(base -> removeOrphan(m, base));

        return m;
    }

    /// The observation restarts at the removal, so a removal that is lost or refused is derived again two ticks later.
    private static ClusterModel removeOrphan(ClusterModel m, ArtifactBase base) {
        removal(m, base).onPresent(mutation -> Outbox.direct(m,
                                                             TxDraft.draft(SubmitKind.ORPHAN_TARGET,
                                                                           mutation.key(),
                                                                           List.of(mutation))));
        m.orphanTargets = m.orphanTargets.put(base, m.tickCount);

        return m;
    }

    private static Option<KVCommand.Mutation<AetherKey, AetherValue>> removal(ClusterModel m, ArtifactBase base) {
        return m.targets.get(base)
                        .map(target -> new KVCommand.Mutation<AetherKey, AetherValue>(new SliceTargetKey(base),
                                                                                      Option.some((AetherValue) target),
                                                                                      Option.none()));
    }

    /// The target names an owner that the mirror does not hold, and no other holder is recorded: `ownerByBase` has no entry for the
    /// base (it holds the blueprint that last indexed the base, as a different-id restore re-owning the kept slices does, G2 6.5; the
    /// entry is cleared when that blueprint is removed, even while another present blueprint still declares the base), no put of it is
    /// in flight, and no rolling update names it. A target with no owner at all was set by an operator and is not an orphan.
    private static boolean isOrphanTarget(ClusterModel m, ArtifactBase base) {
        return m.targets.get(base)
                        .flatMap(target -> target.owningBlueprint())
                        .map(owner -> !m.blueprints.containsKey(owner))
                        .or(false)
               && !m.ownerByBase.containsKey(base)
               && !m.pendingTargetPuts.containsKey(base)
               && !m.routings.containsKey(base);
    }

    private static boolean isOrphan(ClusterModel m, SlicePair pair) {
        return Rows.current(m, pair)
                   .filter(row -> row.state() != SliceObservedState.UNLOADED)
                   .isPresent()
               && m.desired.containsKey(pair)
               && m.effectiveGoal(pair)
                   .map(goal -> goal != SliceGoal.UNLOADED)
                   .or(false)
               && !Blueprints.permits(m, pair.artifact());
    }

    record unused() implements Orphans {}
}
