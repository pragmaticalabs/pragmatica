// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Effect;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Observation;
import org.pragmatica.aether.deployment.cluster.NodeReplacementPlanner.Timings;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// #1543 part E — the leader-side driver of committed [NodeReplacementValue] records. One tick walks every live record,
/// asks [NodeReplacementPlanner] what to do, performs the effect, and commits the next record by compare-and-set on the
/// EXACT record it read. Nothing is remembered between ticks: a new leader (or a restarted reconciler) reads the same
/// records and continues, and every effect is idempotent against actual state.
///
/// No lock is taken here and none is held across a callback: the driver is called from a scheduler thread, never from
/// an FSM listener (#1946's rule).
public interface NodeReplacementReconciler {
    Promise<Unit> reconcile();

    /// The outcome of an effect.
    sealed interface EffectResult {
        /// Done (or already done): commit the plan's success record.
        record Done() implements EffectResult {}

        /// Refused for good: commit the plan's failure record, if it has one.
        record Failed(String reason) implements EffectResult {}

        /// Not now (circuit open, still in flight): hold and retry on the next tick.
        record Deferred(String reason) implements EffectResult {}
    }

    /// Everything the driver needs from the node. Production binds it in `AetherNode`; tests bind a model of the cluster.
    interface Environment {
        /// This node is the active leader.
        boolean isLeader();
        /// Every committed record, original → record.
        Map<NodeId, NodeReplacementValue> records();
        /// Compare-and-set on the committed record: `true` only when it still equals `expected` and `next` was applied.
        Promise<Boolean> commit(NodeId original, NodeReplacementValue expected, NodeReplacementValue next);
        /// The current facts for this record.
        Observation observe(NodeId original, NodeReplacementValue record);
        /// Run `effect` for this record. Idempotent.
        Promise<EffectResult> execute(Effect effect, NodeId original, NodeReplacementValue record);
        /// A transition was committed. Operator events are derived here, once per commit.
        Unit announce(NodeId original, Option<NodeReplacementValue> before, NodeReplacementValue after);
    }

    static NodeReplacementReconciler nodeReplacementReconciler(Environment environment, Timings timings) {
        record reconciler(Environment environment, Timings timings, AtomicBoolean running) implements NodeReplacementReconciler {
            @Override
            public Promise<Unit> reconcile() {
                if (!environment.isLeader() || !running.compareAndSet(false, true)) {
                    return Promise.unitPromise();
                }

                return step(environment.records()
                                       .entrySet()
                                       .stream()
                                       .sorted(Map.Entry.comparingByKey(java.util.Comparator.comparing(NodeId::id)))
                                       .map(entry -> Map.entry(entry.getKey(),
                                                               entry.getValue()))
                                       .toList(),
                            0).timeout(TimeSpan.timeSpan(30).seconds())
                           .onResultRun(() -> running.set(false));
            }

            private Promise<Unit> step(java.util.List<Map.Entry<NodeId, NodeReplacementValue>> live, int index) {
                if (index >= live.size() || !environment.isLeader()) {
                    return Promise.unitPromise();
                }

                var entry = live.get(index);

                return advance(entry.getKey(), entry.getValue()).flatMap(_ -> step(live, index + 1));
            }

            private Promise<Unit> advance(NodeId original, NodeReplacementValue record) {
                if (isTerminal(record.phase())) {
                    return Promise.unitPromise();
                }

                var plan = NodeReplacementPlanner.plan(record, environment.observe(original, record), timings);

                if (plan.effect() == Effect.NONE) {
                    return plan.next()
                               .fold(Promise::unitPromise,
                                     next -> commit(original, record, next));
                }

                return environment.execute(plan.effect(),
                                           original,
                                           record)
                                  .flatMap(result -> settle(original, record, plan, result));
            }

            private Promise<Unit> settle(NodeId original,
                                         NodeReplacementValue record,
                                         NodeReplacementPlanner.Plan plan,
                                         EffectResult result) {
                return switch (result) {
                    case EffectResult.Done _ -> plan.next().fold(Promise::unitPromise,
                                                                 next -> commit(original, record, next));
                    case EffectResult.Failed _ -> plan.onFailure().fold(Promise::unitPromise,
                                                                        next -> commit(original, record, next));
                    case EffectResult.Deferred _ -> Promise.unitPromise();
                };
            }

            private Promise<Unit> commit(NodeId original, NodeReplacementValue before, NodeReplacementValue next) {
                return environment.commit(original, before, next)
                                  .map(accepted -> accepted
                                                   ? environment.announce(original,
                                                                          Option.some(before),
                                                                          next)
                                                   : Unit.unit());
            }
        }

        return new reconciler(environment, timings, new AtomicBoolean());
    }

    static boolean isTerminal(NodeReplacementPhase phase) {
        return switch (phase) {
            case DONE, ROLLED_BACK, FAILED_KEPT_BOTH, UNKNOWN -> true;
            case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING -> false;
        };
    }
}
