// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
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
