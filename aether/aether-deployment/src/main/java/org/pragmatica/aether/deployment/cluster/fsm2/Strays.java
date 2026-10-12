// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;


/// Stray rows (spec R-3.3.11, step 2b): a current, non-UNLOADED observed row with no desired key, on a counting node.
///
/// A stray is **adopted** when the node is not departed, departing or unresponsive for the artifact, the version is
/// permitted, `placed(a)` is below the desired count and the observed state is live: the goal written is the row's own
/// (ACTIVE for ACTIVATING or ACTIVE, else LOADED) **at the row's own generation**, so the adoption settles the pair and
/// nothing is re-issued (NT-120). Otherwise it is **condemned**: goal UNLOADED at a fresh generation, with
/// `stray-instance`. Both are guarded on the observed value.
public sealed interface Strays {
    /// An observed put on a pair with no desired key (C10 with no goal).
    static ClusterModel onStrayRow(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        return m.stray.contains(pair)
               ? handle(m, ctx, pair, row)
               : m;
    }

    /// Step 2b: every stray pair on a counting node (non-counting workers are neither adopted nor condemned, M-5).
    static ClusterModel sweep(ClusterModel m, Ctx ctx) {
        m.stray.toList()
               .stream()
               .sorted(Rows.PAIR_ORDER)
               .forEach(pair -> m.observed.get(pair)
                                          .onPresent(row -> handle(m, ctx, pair, row)));

        return m;
    }

    private static ClusterModel handle(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        if (!Rows.counts(m, pair.node()) || m.effectiveGoal(pair).isPresent()) {
            return m;
        }

        if (adoptable(m, pair, row)) {
            ctx.metric("aether_deployment_fsm_stray_total", Map.of("fsm", "cluster", "action", "adopt"));
        }

        return adoptable(m, pair, row)
               ? Outbox.goal(m,
                             pair,
                             adoptedGoal(row),
                             GoalMode.ADOPT,
                             Option.some(row.forGeneration()),
                             "adopt")
               : condemn(m, ctx, pair);
    }

    private static boolean adoptable(ClusterModel m, SlicePair pair, SliceObservedValue row) {
        var artifact = pair.artifact();

        return ! Rows.isUnresponsive(m, pair.node(), artifact)
               && Blueprints.permits(m, artifact)
               && Rows.placed(m, artifact) < desiredCount(m, pair)
               && Rows.isLive(row.state());
    }

    /// The committed target's count for the base.
    static int desiredCount(ClusterModel m, SlicePair pair) {
        return m.targets.get(pair.artifact().base())
                        .map(target -> target.targetInstances())
                        .or(0);
    }

    private static SliceGoal adoptedGoal(SliceObservedValue row) {
        return row.state() == SliceObservedState.ACTIVATING || row.state() == SliceObservedState.ACTIVE
               ? SliceGoal.ACTIVE
               : SliceGoal.LOADED;
    }

    private static ClusterModel condemn(ClusterModel m, Ctx ctx, SlicePair pair) {
        ctx.warn("stray-instance",
                 pair.artifact().asString(),
                 "Condemning stray instance of " + pair.artifact() + " on " + pair.node().id());
        ctx.metric("aether_deployment_fsm_stray_total", Map.of("fsm", "cluster", "action", "condemn"));

        return Outbox.goal(m, pair, SliceGoal.UNLOADED, GoalMode.FRESH, Option.none(), "stray");
    }

    record unused() implements Strays {}
}
