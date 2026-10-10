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
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
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
    int MAX_CAUSE_CHARS = 300;
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
        /// The source of a record that was created with none (a node of a cluster bootstrapped from static PEERS has no source label): the
        /// config's sole declaring source, else a typed refusal. Called once per record; the answer is committed into the record.
        Result<String> resolveSource(NodeId original, NodeReplacementValue record);
        /// Run `effect` for this record. Idempotent.
        Promise<EffectResult> execute(Effect effect, NodeId original, NodeReplacementValue record);
    }

    static NodeReplacementReconciler nodeReplacementReconciler(Environment environment, Timings timings) {
        return nodeReplacementReconciler(environment,
                                         timings,
                                         TimeSpan.timeSpan(30).seconds());
    }

    /// `commitBound`: how long a compare-and-set may stay unanswered before the tick gives up on it. The next tick re-reads the
    /// committed records, so a commit that lands late is simply seen; a commit that never answers must not stop the ticks.
    static NodeReplacementReconciler nodeReplacementReconciler(Environment environment,
                                                               Timings timings,
                                                               TimeSpan commitBound) {
        return nodeReplacementReconciler(environment,
                                         timings,
                                         commitBound,
                                         effect -> defaultEffectBound(effect, timings));
    }

    /// How long an effect may stay unanswered before the tick gives up on it (an effect that outlives its bound is treated as not
    /// done yet and the planner decides again). PROVISION gets twice its budget, every other effect 30 s.
    static TimeSpan defaultEffectBound(Effect effect, Timings timings) {
        return effect == Effect.PROVISION
               ? TimeSpan.timeSpan(Math.max(30_000L, 2 * timings.provisioningMs())).millis()
               : TimeSpan.timeSpan(30).seconds();
    }

    static NodeReplacementReconciler nodeReplacementReconciler(Environment environment,
                                                               Timings timings,
                                                               TimeSpan commitBound,
                                                               java.util.function.Function<Effect, TimeSpan> effectBounds) {
        record reconciler(Environment environment,
                          Timings timings,
                          AtomicBoolean running,
                          TimeSpan commitBound,
                          java.util.function.Function<Effect, TimeSpan> effectBounds) implements NodeReplacementReconciler {
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
                            0).onResultRun(() -> running.set(false));
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

                if (record.source().isBlank()) {
                    return resolveOnce(original, record);
                }

                var plan = NodeReplacementPlanner.plan(record, environment.observe(original, record), timings);

                if (plan.effect() == Effect.NONE) {
                    return plan.next()
                               .fold(Promise::unitPromise,
                                     next -> commit(original, record, next));
                }
                // The tick does not end, and the next one does not start, while an effect is pending: re-opening the tick
                // would run the same effect again (a second provision of the same id) before the first has answered.
                // Every effect is bounded, so a hung provider cannot stall the driver for good; an effect that outlives
                // its bound is treated as not done yet, and the planner decides again from the observation.
                return environment.execute(plan.effect(),
                                           original,
                                           record)
                                  .timeout(effectBounds.apply(plan.effect()))
                                  .recover(cause -> new EffectResult.Deferred("effect not finished: " + cause.message()))
                                  .flatMap(result -> settle(original, record, plan, result));
            }

            /// A blank source is resolved ONCE, before any effect: the answer is committed into the record (compare-and-set on the record
            /// as read) and the tick goes on with it. No later effect, and no later leader, derives it again or reads a blank as "default".
            /// A refusal (no source declares the role, or several do) ends a replacement that has not begun and holds one that has,
            /// with the reason in the record.
            private Promise<Unit> resolveOnce(NodeId original, NodeReplacementValue record) {
                return environment.resolveSource(original, record)
                                  .fold(cause -> refuseUnresolved(original, record, cause),
                                        name -> commitSource(original,
                                                             record,
                                                             record.withSource(name)));
            }

            private Promise<Unit> commitSource(NodeId original,
                                               NodeReplacementValue before,
                                               NodeReplacementValue resolved) {
                return environment.commit(original, before, resolved)
                                  .timeout(commitBound)
                                  .recover(_ -> false)
                                  .flatMap(committed -> committed
                                                        ? advance(original, resolved)
                                                        : Promise.unitPromise());
            }

            private Promise<Unit> refuseUnresolved(NodeId original, NodeReplacementValue record, Cause cause) {
                var why = cause.message().length() > MAX_CAUSE_CHARS
                          ? cause.message().substring(0, MAX_CAUSE_CHARS)
                          : cause.message();

                if (record.phase() == NodeReplacementPhase.PROVISIONING) {
                    return commit(original,
                                  record,
                                  record.advanced(NodeReplacementPhase.ROLLED_BACK,
                                                  environment.observe(original, record).now(),
                                                  "provisioning refused: " + why));
                }

                return record.reason()
                             .equals(why)
                       ? Promise.unitPromise()
                       : commit(original, record, record.withReason(why));
            }

            private Promise<Unit> settle(NodeId original,
                                         NodeReplacementValue record,
                                         NodeReplacementPlanner.Plan plan,
                                         EffectResult result) {
                return switch (result) {
                    case EffectResult.Done _ -> plan.next().fold(Promise::unitPromise,
                                                                 next -> commit(original, record, next));
                    case EffectResult.Failed failed -> plan.onFailure().fold(Promise::unitPromise,
                                                                             next -> commit(original,
                                                                                            record,
                                                                                            withCause(next, failed)));
                    case EffectResult.Deferred _ -> Promise.unitPromise();
                };
            }

            /// The record's reason is what the operator event and the upgrade run print: a refusal says WHY ("No configured source for
            /// replacement default"), not only that it happened. Bounded, because the record is replicated.
            private static NodeReplacementValue withCause(NodeReplacementValue next, EffectResult.Failed failed) {
                var cause = failed.reason().length() > MAX_CAUSE_CHARS
                            ? failed.reason().substring(0, MAX_CAUSE_CHARS)
                            : failed.reason();

                return cause.isBlank()
                       ? next
                       : next.withReason(next.reason() + ": " + cause);
            }

            private Promise<Unit> commit(NodeId original, NodeReplacementValue before, NodeReplacementValue next) {
                return environment.commit(original, before, next)
                                  .timeout(commitBound)
                                  .recover(_ -> false)
                                  .mapToUnit();
            }
        }

        return new reconciler(environment, timings, new AtomicBoolean(), commitBound, effectBounds);
    }

    static boolean isTerminal(NodeReplacementPhase phase) {
        return switch (phase) {
            case DONE, ROLLED_BACK, FAILED_KEPT_BOTH, UNKNOWN -> true;
            case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING -> false;
        };
    }
}
