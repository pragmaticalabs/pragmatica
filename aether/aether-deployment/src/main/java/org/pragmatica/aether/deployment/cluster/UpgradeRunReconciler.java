// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;


/// #1543 part F — the leader-side driver of the committed [UpgradeRunValue]. One tick reads the run, asks [UpgradeRunPlanner] what to do,
/// performs the one effect (starting a replacement), and commits the next run by compare-and-set on the EXACT run it read. Nothing is
/// remembered between ticks: a new leader (or a restarted reconciler) reads the same run and continues, and the planner adopts a replacement
/// that was started but whose run commit was lost. Called from a scheduler thread, never from an FSM listener.
public interface UpgradeRunReconciler {
    Promise<Unit> reconcile();

    /// The outcome of asking for a replacement.
    sealed interface BeginResult {
        /// The replacement was started.
        record Started() implements BeginResult {}

        /// Not now (another replacement holds the slot): hold and retry on the next tick.
        record Deferred(String reason) implements BeginResult {}

        /// Refused for good: pause the run and say why.
        record Refused(String reason) implements BeginResult {}
    }

    /// Everything the driver needs from the node. Production binds it in `AetherNode`; tests bind a model of the cluster.
    interface Environment {
        boolean isLeader();
        Option<UpgradeRunValue> run();
        /// Compare-and-set on the committed run: `true` only when it still equals `expected` and `next` was applied.
        Promise<Boolean> commit(UpgradeRunValue expected, UpgradeRunValue next);
        UpgradeRunPlanner.Observation observe();
        Promise<BeginResult> begin(NodeId node, String targetVersion);
        long now();
    }

    static UpgradeRunReconciler upgradeRunReconciler(Environment environment) {
        return upgradeRunReconciler(environment,
                                    TimeSpan.timeSpan(30).seconds());
    }

    /// `bound`: how long a commit or a begin may stay unanswered before the tick gives up on it; the next tick re-reads the committed state.
    static UpgradeRunReconciler upgradeRunReconciler(Environment environment, TimeSpan bound) {
        record reconciler(Environment environment, AtomicBoolean running, TimeSpan bound) implements UpgradeRunReconciler {
            @Override
            public Promise<Unit> reconcile() {
                if (!environment.isLeader() || !running.compareAndSet(false, true)) {
                    return Promise.unitPromise();
                }
                // A tick that throws before it has a promise (a bad observation) must still release the guard, or no later tick would run.
                return Result.lift(this::tick).fold(cause -> {
                                                        running.set(false);

                                                        return cause.<Unit> promise();
                                                    },
                                                    promise -> promise.onResultRun(() -> running.set(false)));
            }

            private Promise<Unit> tick() {
                return environment.run()
                                  .filter(run -> run.state() == UpgradeRunState.RUNNING)
                                  .fold(Promise::unitPromise, this::advance);
            }

            private Promise<Unit> advance(UpgradeRunValue run) {
                var plan = UpgradeRunPlanner.plan(run, environment.observe(), environment.now());

                return switch (plan.action()) {
                    case UpgradeRunPlanner.Action.Begin begin -> begin(run, begin.node(), plan);
                    case UpgradeRunPlanner.Action.Hold _ -> plan.next().fold(Promise::unitPromise,
                                                                             next -> commit(run, next));
                };
            }

            private Promise<Unit> begin(UpgradeRunValue run, NodeId node, UpgradeRunPlanner.Plan plan) {
                return environment.begin(node,
                                         run.targetVersion())
                                  .timeout(bound)
                                  .recover(cause -> new BeginResult.Deferred("replacement not started: " + cause.message()))
                                  .flatMap(result -> switch (result) {
                    case BeginResult.Started _ -> plan.next().fold(Promise::unitPromise,
                                                                   next -> commit(run, next));
                    case BeginResult.Deferred _ -> Promise.unitPromise();
                    case BeginResult.Refused refused -> commit(run,
                                                               run.with(run.index(),
                                                                        "",
                                                                        UpgradeRunState.PAUSED,
                                                                        org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop.NONE,
                                                                        "could not start the replacement of " + node.id() + ": " + refused.reason(),
                                                                        environment.now()));
                });
            }

            private Promise<Unit> commit(UpgradeRunValue before, UpgradeRunValue next) {
                return environment.commit(before, next)
                                  .timeout(bound)
                                  .recover(_ -> false)
                                  .mapToUnit();
            }
        }

        return new reconciler(environment, new AtomicBoolean(), bound);
    }
}
