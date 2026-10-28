// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;


/// The reconcile pass (spec C19, R-3.3.8): the level-triggered half of the FSM. Event handlers record facts and queue a
/// reconcile; the pass re-derives everything that should be true from the model. A pass runs only while the committed
/// leader names this node (R-5.7.1 (a)).
///
/// Order: (1) awaited targets, (2) intake re-drive, (2b) strays, (3) placement, (4) drain loops, (5) community states,
/// (6) orphans, (7) the departure backstop, (8) divergence, (9) completion repair, (10) garbage collection.
public sealed interface Reconcile {
    /// R-3.1.5: at most one reconcile is queued; the flag is cleared when it is handled.
    static ClusterModel request(ClusterModel m, Ctx ctx) {
        if (!m.reconcileQueued) {
            m.reconcileQueued = true;
            ctx.emit(new ClusterEffect.Enqueue(new ClusterInput.Reconcile()));
        }

        return m;
    }

    static ClusterModel run(ClusterModel m, Ctx ctx) {
        m.reconcileQueued = false;
        if (Lifecycle.isSuspended(m, ctx)) {
            return m;
        }

        Outbox.expire(m, ctx);
        Outbox.replayAwaiting(m);
        Blueprints.sweepAwaited(m, ctx);
        Blueprints.redrive(m, ctx);
        Strays.sweep(m, ctx);
        Failures.sweep(m, ctx);
        Placement.run(m, ctx);
        Drains.resume(m, ctx);
        Communities.run(m, ctx);
        Orphans.run(m, ctx);
        Backstop.run(m, ctx);
        Divergence.run(m, ctx);
        Outcomes.sweep(m, ctx);
        collect(m, ctx);
        ctx.metric("aether_deployment_fsm_reconcile_passes_total", Map.of("fsm", "cluster"));

        return m;
    }

    /// Step 10: the desired keys of finished unloads, the rows of departed nodes, the ADMITTED rewrite, the pending joins.
    /// No record is removed here (R-4.3.5 narrowed by MQ-4).
    private static ClusterModel collect(ClusterModel m, Ctx ctx) {
        m.unloadedDesired.toList()
                         .stream()
                         .sorted(Rows.PAIR_ORDER)
                         .filter(pair -> !m.observed.containsKey(pair))
                         .filter(pair -> !m.pendingGoals.containsKey(pair))
                         .forEach(pair -> Gc.removeDesired(m, pair));
        DepartureFlow.sweep(m, ctx);
        DepartureFlow.rereadOverdue(m, ctx);

        return Joins.retryPending(m, ctx);
    }

    record unused() implements Reconcile {}
}
