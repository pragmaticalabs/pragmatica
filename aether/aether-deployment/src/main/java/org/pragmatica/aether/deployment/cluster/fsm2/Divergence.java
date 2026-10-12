// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.ReissueMark;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UnresponsiveEntry;
import org.pragmatica.lang.Option;


/// Goal/state divergence (spec 6.1, C2, reconcile step 8): a pair whose goal is not reflected by its current observation
/// is given a bounded time to catch up, is then told again, and is finally given up on.
///
/// - **age** = `min(nowMs - max(issuedAt, transitionedAt_g), monoNs - max(seenAt_g, admittedAt(n), incarnationStart))`: every
///   term only delays, so no clock-skew assumption is needed for safety (R-6.1.2).
/// - after `1 x T` and after `2 x T`: the **identical** goal is re-issued, guarded, once per threshold per pair and
///   generation. It repairs a lost observed write.
/// - after `3 x T`: the node is **unresponsive**. Goal LOADED or ACTIVE: the goal is retracted (UNLOADED at a fresh
///   generation) and `unresponsive[n]` recorded, in the artifact's one transaction. Goal UNLOADED: the judgment only, no
///   generation bump.
/// - **No row is ever removed from a live node** (C1, R-6.1.5): a stuck row is not counted and the slice is re-placed.
public sealed interface Divergence {
    long SECOND = 1_000L;
    long LOAD_T = 120 * SECOND;
    long ACTIVATE_T = 90 * SECOND;
    long DEACTIVATE_T = 30 * SECOND;
    long UNLOAD_T = 120 * SECOND;
    int EXPIRY_FACTOR = 10;

    /// Step 8.
    static ClusterModel run(ClusterModel m, Ctx ctx) {
        m.divergent.toList()
                   .stream()
                   .sorted(Rows.PAIR_ORDER)
                   .forEach(pair -> m.desired.get(pair)
                                             .onPresent(goal -> check(m, ctx, pair, goal)));

        return expire(m, ctx);
    }

    private static ClusterModel check(ClusterModel m, Ctx ctx, SlicePair pair, SliceDesiredValue goal) {
        var row = Rows.current(m, pair);
        var timeout = timeoutOf(goal.goal(), row);
        var age = ageMs(m, ctx, pair, goal, row);

        if (age > 3 * timeout) {
            return unresponsive(m, ctx, pair, goal);
        }

        var level = age > 2 * timeout
                    ? 2
                    : age > timeout
                      ? 1
                      : 0;

        return level > markOf(m, pair, goal)
               ? reissue(m, ctx, pair, goal, level)
               : m;
    }

    /// The transition timeout T of the pair's expected next step (R-6.1.1, R-6.1.4).
    static long timeoutOf(SliceGoal goal, Option<SliceObservedValue> row) {
        var state = row.map(SliceObservedValue::state);

        return switch (goal) {
            case LOADED -> state.map(value -> value == SliceObservedState.ACTIVE || value == SliceObservedState.DEACTIVATING
                                              ? DEACTIVATE_T
                                              : LOAD_T).or(LOAD_T);
            case ACTIVE -> state.map(value -> value == SliceObservedState.LOADED || value == SliceObservedState.ACTIVATING
                                              ? ACTIVATE_T
                                              : LOAD_T).or(LOAD_T);
            case UNLOADED, UNKNOWN -> UNLOAD_T;
        };
    }

    static long ageMs(ClusterModel m, Ctx ctx, SlicePair pair, SliceDesiredValue goal, Option<SliceObservedValue> row) {
        var transitioned = row.filter(value -> value.forGeneration()
                                                    .equals(goal.generation()))
                              .map(SliceObservedValue::transitionedAt)
                              .or(0L);
        var wall = ctx.nowMs() - Math.max(goal.issuedAt(), transitioned);
        var seen = Math.max(Math.max(m.seenAt.get(pair).or(m.incarnationStart),
                                     m.admittedAt.get(pair.node()).or(m.incarnationStart)),
                            m.incarnationStart);
        var mono = (ctx.monoNs() - seen) / 1_000_000L;

        return Math.min(wall, mono);
    }

    private static int markOf(ClusterModel m, SlicePair pair, SliceDesiredValue goal) {
        return m.reissues.get(pair)
                         .filter(mark -> mark.generation()
                                             .equals(goal.generation()))
                         .map(ReissueMark::level)
                         .or(0);
    }

    private static ClusterModel reissue(ClusterModel m, Ctx ctx, SlicePair pair, SliceDesiredValue goal, int level) {
        m.reissues = m.reissues.put(pair, new ReissueMark(goal.generation(), level));
        ctx.metric("aether_deployment_fsm_divergence_total", Map.of("fsm", "cluster", "stage", "reissue"));

        return Outbox.goal(m, pair, goal.goal(), GoalMode.REISSUE, Option.none(), "reissue");
    }

    /// 3T: retract and record in one transaction (NT-31), or record only when the goal is already UNLOADED (NT-33).
    private static ClusterModel unresponsive(ClusterModel m, Ctx ctx, SlicePair pair, SliceDesiredValue goal) {
        var artifact = pair.artifact();
        var recorded = m.judgments.get(artifact)
                                  .map(judgment -> judgment.unresponsive()
                                                           .get(pair.node()))
                                  .map(Option::option)
                                  .or(Option.none());

        if (recorded.map(entry -> entry.generation()
                                       .compareTo(goal.generation()) >= 0).or(false)) {
            return m;
        }

        var entry = new UnresponsiveEntry(goal.generation(), goal.goal(), ctx.nowMs());

        Outbox.artifact(m,
                        artifact,
                        intent -> intent.withOp(new JudgmentOp.Retract(pair.node(), entry)));
        if (goal.goal() != SliceGoal.UNLOADED) {
            Outbox.artifactGoal(m, pair, SliceGoal.UNLOADED);
        }

        ctx.warn("slice-unresponsive",
                 artifact.asString(),
                 "Node " + pair.node().id() + " did not answer goal " + goal.goal() + " for " + artifact);
        ctx.metric("aether_deployment_fsm_divergence_total", Map.of("fsm", "cluster", "stage", "retract"));

        return m;
    }

    /// R-4.2.5: an exclusion expires when both the wall term and the monotonic term have reached `10 x T`.
    private static ClusterModel expire(ClusterModel m, Ctx ctx) {
        var limit = EXPIRY_FACTOR * LOAD_T;

        m.unresponsiveByNode.keys()
                            .stream()
                            .sorted()
                            .forEach(node -> Idx.members(m.unresponsiveByNode, node)
                                                .toList()
                                                .forEach(artifact -> expireOne(m, ctx, node, artifact, limit)));

        return m;
    }

    private static void expireOne(ClusterModel m,
                                  Ctx ctx,
                                  org.pragmatica.consensus.NodeId node,
                                  org.pragmatica.aether.artifact.Artifact artifact,
                                  long limit) {
        m.judgments.get(artifact)
                   .flatMap(judgment -> Option.option(judgment.unresponsive().get(node)))
                   .filter(entry -> Math.min(ctx.nowMs() - entry.at(),
                                             (ctx.monoNs() - m.incarnationStart) / 1_000_000L) >= limit)
                   .onPresent(_ -> {
                                  Outbox.artifact(m,
                                                  artifact,
                                                  intent -> intent.withOp(new JudgmentOp.ClearUnresponsive(node)));
                                  ctx.warn("slice-unresponsive-expired",
                                           artifact.asString(),
                                           "Exclusion of " + node.id() + " for " + artifact + " expired");
                                  ctx.metric("aether_deployment_fsm_divergence_total",
                                             Map.of("fsm", "cluster", "stage", "expire"));
                              });
    }

    record unused() implements Divergence {}
}
