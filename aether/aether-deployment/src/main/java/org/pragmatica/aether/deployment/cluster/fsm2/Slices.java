// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.Option;


/// The slice pair's transitions (spec C10 to C11b): what a node's observation and the leader's own goal keys cause.
///
/// An observation is acted on only when it answers the **current** generation of a goal (R-3.3.10): a late answer to a
/// retracted goal drives and counts nothing (NT-94, NT-102). A row that is stale on positive evidence reads as absent
/// and fires nothing (R-1.6.2).
public sealed interface Slices {
    // -- C10: ObservedPut ------------------------------------------------------------------------------------------
    static ClusterModel onObservedPut(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        Indexer.indexObserved(m, ctx, pair, row);
        if (m.departed.contains(pair.node()) || m.departing.containsKey(pair.node())) {
            return m;
        }

        if (Rows.isStale(m, pair, row)) {
            ctx.metric("aether_deployment_fsm_stale_rows_total", Map.of("fsm", "cluster", "side", "leader"));

            return m;
        }

        return m.desired.get(pair)
                        .fold(() -> Strays.onStrayRow(m, ctx, pair, row),
                              goal -> answered(m, ctx, pair, row, goal));
    }

    private static ClusterModel answered(ClusterModel m,
                                         Ctx ctx,
                                         SlicePair pair,
                                         SliceObservedValue row,
                                         SliceDesiredValue goal) {
        if (row.state() == SliceObservedState.UNLOADED) {
            vacated(m, ctx, pair, row);
        }

        if (!row.forGeneration().equals(goal.generation())) {
            return m;
        }

        m.reissues = m.reissues.remove(pair);

        return switch (row.state()) {
            case LOADED -> goal.goal() == SliceGoal.LOADED
                           ? activate(m, ctx, pair, row)
                           : m;
            case ACTIVE -> goal.goal() == SliceGoal.ACTIVE
                           ? onActive(m, ctx, pair, row)
                           : m;
            case FAILED -> goal.goal() != SliceGoal.UNLOADED
                           ? Failures.onFailed(m, ctx, pair, row)
                           : m;
            default -> m;
        };
    }

    /// C10e: an UNLOADED answer (at any generation not below the recorded one) ends the exclusion of the node, and the
    /// pair is vacant for placement (R-1.6.3).
    private static ClusterModel vacated(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        clearExclusion(m, ctx, pair, row.forGeneration());
        Reconcile.request(m, ctx);

        return m;
    }

    private static ClusterModel clearExclusion(ClusterModel m, Ctx ctx, SlicePair pair, Generation answered) {
        m.judgments.get(pair.artifact())
                   .flatMap(judgment -> Option.option(judgment.unresponsive().get(pair.node())))
                   .filter(entry -> answered.compareTo(entry.generation()) >= 0)
                   .onPresent(_ -> Outbox.artifact(m,
                                                   pair.artifact(),
                                                   intent -> intent.withOp(new JudgmentOp.ClearUnresponsive(pair.node()))));

        return m;
    }

    // -- activation (C10a, R-3.3.5) --------------------------------------------------------------------------------
    /// C10a: the pair is LOADED at its current generation under goal LOADED. The gate, in order: the committed target
    /// still permits the version (else the goal becomes UNLOADED, #1068); no schema record holds the slice; every
    /// dependency has an ACTIVE instance. Passing it writes goal ACTIVE at a fresh generation.
    static ClusterModel activate(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        var alreadyHeadingThere = m.effectiveGoal(pair).map(goal -> goal != SliceGoal.LOADED).or(true);

        if (alreadyHeadingThere) {
            return m;
        }

        if (!Blueprints.permits(m, pair.artifact())) {
            ctx.warn("activation-unpermitted",
                     pair.artifact().asString(),
                     "No committed SliceTarget names " + pair.artifact()
                    + ": goal UNLOADED instead of ACTIVE on " + pair.node().id());

            return Outbox.goal(m, pair, SliceGoal.UNLOADED, GoalMode.FRESH, Option.none(), "unpermitted");
        }

        var blocking = Schemas.blocking(m, pair.artifact());

        if (!blocking.isEmpty()) {
            return held(m, ctx, pair, Schemas.signature(blocking));
        }

        releaseHold(m, ctx, pair);

        return dependenciesActive(m, pair.artifact())
               ? Outbox.goal(m, pair, SliceGoal.ACTIVE, GoalMode.FRESH, Option.none(), "activate")
               : m;
    }

    /// WARN once per distinct hold signature (the dedup of #760, NT/B-041).
    private static ClusterModel held(ClusterModel m, Ctx ctx, SlicePair pair, String signature) {
        var changed = !m.holdSignatures.get(pair).map(signature::equals).or(false);

        m.holdSignatures = m.holdSignatures.put(pair, signature);
        if (changed) {
            ctx.warn("schema-hold",
                     pair.artifact().asString(),
                     "Slice " + pair.artifact()
                    + " held in LOADED, waiting for schema migrations to complete: " + signature);
        }

        return m;
    }

    private static ClusterModel releaseHold(ClusterModel m, Ctx ctx, SlicePair pair) {
        m.holdSignatures.get(pair).onPresent(_ -> announceRelease(m, ctx, pair));

        return m;
    }

    private static ClusterModel announceRelease(ClusterModel m, Ctx ctx, SlicePair pair) {
        m.holdSignatures = m.holdSignatures.remove(pair);
        ctx.warn("schema-hold-cleared",
                 pair.artifact().asString(),
                 "Slice " + pair.artifact() + " schema hold cleared, resuming activation");

        return m;
    }

    /// Every dependency has at least one ACTIVE instance (the active count, R-2.6).
    static boolean dependenciesActive(ClusterModel m, Artifact artifact) {
        return m.dependencies.get(artifact)
                             .map(dependencies -> dependencies.toList()
                                                              .stream()
                                                              .allMatch(dependency -> Rows.activeCount(m, dependency) > 0))
                             .or(true);
    }

    /// C12 COMPLETED and the dependency edge: every LOADED instance of every committed slice is re-evaluated.
    static ClusterModel retryHeld(ClusterModel m, Ctx ctx) {
        m.targets.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()
                                                            .toString()))
                 .map(entry -> entry.getKey()
                                    .withVersion(entry.getValue().currentVersion()))
                 .forEach(artifact -> activateLoaded(m, ctx, artifact));

        return m;
    }

    /// C1 / NT-06: a LOADED observation found at rebuild is activated once the gate passes.
    static ClusterModel activateLoadedAtRebuild(ClusterModel m, Ctx ctx) {
        return retryHeld(m, ctx);
    }

    private static ClusterModel activateLoaded(ClusterModel m, Ctx ctx, Artifact artifact) {
        Idx.members(m.observedByArtifact, artifact)
           .toList()
           .stream()
           .sorted()
           .map(node -> SlicePair.slicePair(node, artifact))
           .forEach(pair -> activateIfLoaded(m, ctx, pair));

        return m;
    }

    private static ClusterModel activateIfLoaded(ClusterModel m, Ctx ctx, SlicePair pair) {
        Rows.current(m, pair)
            .filter(row -> m.desired.get(pair)
                                    .map(goal -> loadedUnderGoal(row, goal))
                                    .or(false))
            .onPresent(row -> activate(m, ctx, pair, row));

        return m;
    }

    private static boolean loadedUnderGoal(SliceObservedValue row, SliceDesiredValue goal) {
        return row.state() == SliceObservedState.LOADED
               && goal.goal() == SliceGoal.LOADED
               && row.forGeneration()
                     .equals(goal.generation());
    }

    // -- C10b ------------------------------------------------------------------------------------------------------
    /// C10b: the instance is ACTIVE under goal ACTIVE. The node's retry count is reset (R-4.2.3), the dependents waiting
    /// on this artifact are re-evaluated, and an apply that this completes is repaired (R-4.1.5).
    private static ClusterModel onActive(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        var artifact = pair.artifact();

        m.judgments.get(artifact)
                   .filter(judgment -> judgment.retries()
                                               .containsKey(pair.node()))
                   .onPresent(_ -> Outbox.artifact(m,
                                                   artifact,
                                                   intent -> intent.withOp(new JudgmentOp.ResetRetries(pair.node()))));
        Idx.members(m.dependents, artifact)
           .toList()
           .stream()
           .sorted(Comparator.comparing(Artifact::asString))
           .forEach(dependent -> activateLoaded(m, ctx, dependent));

        return Outcomes.repair(m, ctx, artifact);
    }

    // -- C11: ObservedRemove ---------------------------------------------------------------------------------------
    /// C11: the row is gone. With goal UNLOADED the desired key is garbage (batched); otherwise, unless the artifact is
    /// permanently failed, a reconcile in 1 s re-derives the pair (B-062). A removal ends the node's exclusion too (C10e).
    static ClusterModel onObservedRemove(ClusterModel m, Ctx ctx, SlicePair pair) {
        Indexer.removeObserved(m, pair);
        m.failureRouted = m.failureRouted.remove(pair);
        m.judgments.get(pair.artifact())
                   .filter(judgment -> judgment.unresponsive()
                                               .containsKey(pair.node()))
                   .onPresent(_ -> Outbox.artifact(m,
                                                   pair.artifact(),
                                                   intent -> intent.withOp(new JudgmentOp.ClearUnresponsive(pair.node()))));
        var goal = m.desired.get(pair);

        if (goal.map(value -> value.goal() == SliceGoal.UNLOADED).or(false)) {
            return Gc.removeDesired(m, pair);
        }

        return permanentlyFailed(m, pair.artifact())
               ? m
               : Lifecycle.schedule(m,
                                    ctx,
                                    TimerId.timerId(TimerId.Kind.RECONCILE,
                                                    "observed-removed:" + pair.node()
                                                                              .id()
                                                   + ":" + pair.artifact()
                                                               .asString()),
                                    1_000L);
    }

    static boolean permanentlyFailed(ClusterModel m, Artifact artifact) {
        return m.judgments.get(artifact)
                          .map(judgment -> judgment.permanentFailure())
                          .or(false);
    }

    // -- C11b: the leader's own goal keys, notified back -----------------------------------------------------------
    static ClusterModel onDesiredPut(ClusterModel m, Ctx ctx, SlicePair pair, SliceDesiredValue value) {
        return Indexer.indexDesired(m, ctx, pair, value);
    }

    static ClusterModel onDesiredRemove(ClusterModel m, Ctx ctx, SlicePair pair) {
        return Indexer.removeDesired(m, pair);
    }

    record unused() implements Slices {}
}
