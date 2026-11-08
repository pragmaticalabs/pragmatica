// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.lang.utils.JitterUtil;


/// A FAILED observation at the current generation (C10c, R-3.3.6, R-3.3.7, R-4.2.2).
///
/// The failed pair's goal becomes UNLOADED, and the artifact's judgment is updated in the **same transaction**: the
/// permanent mark for a fatal failure, the node's retry count for a transient one. All the failures of one drain merge
/// into one judgment mutation (NT-108). Settling a failure routes `DeploymentFailed` and honours the declared
/// atomicity: ALL_OR_NOTHING rolls back each open apply that declares the artifact, BEST_EFFORT merges the failure
/// into the outcome record.
public sealed interface Failures {
    String UNKNOWN_REASON = "Unknown failure";
    long MIN_JITTER_MS = 1L;

    static ClusterModel onFailed(ClusterModel m, Ctx ctx, SlicePair pair, SliceObservedValue row) {
        if (m.effectiveGoal(pair).map(goal -> goal == SliceGoal.UNLOADED).or(false)) {
            return m;
        }

        var reason = row.failureReason().or(UNKNOWN_REASON);

        Outbox.artifactGoal(m, pair, SliceGoal.UNLOADED);
        Slices.holdGone(m, ctx, pair, "the slice failed: " + reason);

        return row.fatal()
               ? settle(m, ctx, pair, reason)
               : transientFailure(m, ctx, pair, reason);
    }

    /// Level-triggered safety net (step 2c): a FAILED row at the current generation whose failure handling was lost (a
    /// refused or failed transaction) is handled again. The failure counts only once it is durable.
    static ClusterModel sweep(ClusterModel m, Ctx ctx) {
        m.failedPairs.toList()
                     .stream()
                     .sorted(Rows.PAIR_ORDER)
                     .forEach(pair -> m.observed.get(pair)
                                                .onPresent(row -> onFailed(m, ctx, pair, row)));

        return closeSettled(m, ctx);
    }

    /// D.6 row 14, level-triggered with the sweep: a reported artifact whose judgment has since settled (permanently failed) or that has an
    /// ACTIVE instance again closes its episode: `retry-exhausted-settled`, same subject.
    private static ClusterModel closeSettled(ClusterModel m, Ctx ctx) {
        m.retryExhaustedWarned.toList()
                              .stream()
                              .sorted(Comparator.comparing(Artifact::asString))
                              .filter(artifact -> Slices.permanentlyFailed(m, artifact) || hasActiveInstance(m, artifact))
                              .forEach(artifact -> closeOne(m,
                                                            ctx,
                                                            artifact,
                                                            Slices.permanentlyFailed(m, artifact)
                                                            ? "the judgment settled"
                                                            : "an instance is ACTIVE"));

        return m;
    }

    private static boolean hasActiveInstance(ClusterModel m, Artifact artifact) {
        return Idx.members(m.observedByArtifact, artifact)
                  .toList()
                  .stream()
                  .anyMatch(node -> m.observed.get(SlicePair.slicePair(node, artifact))
                                              .map(row -> row.state() == SliceObservedState.ACTIVE)
                                              .or(false));
    }

    /// Closes every reported `retry-exhausted-unsettled` of `base` with `retry-exhausted-settled`, the reason being `why`.
    static ClusterModel closeReported(ClusterModel m, Ctx ctx, ArtifactBase base, String why) {
        m.retryExhaustedWarned.toList()
                              .stream()
                              .filter(artifact -> artifact.base()
                                                          .equals(base))
                              .sorted(Comparator.comparing(Artifact::asString))
                              .forEach(artifact -> closeOne(m, ctx, artifact, why));

        return m;
    }

    private static ClusterModel closeOne(ClusterModel m, Ctx ctx, Artifact artifact, String why) {
        m.retryExhaustedWarned = m.retryExhaustedWarned.remove(artifact);
        ctx.warn(Recoveries.RETRY_EXHAUSTED_SETTLED,
                 artifact.asString(),
                 "Retries of " + artifact.asString() + ": " + why);

        return m;
    }

    /// R-3.3.6: the terminal both failure branches converge on.
    private static ClusterModel settle(ClusterModel m, Ctx ctx, SlicePair pair, String reason) {
        var artifact = pair.artifact();

        if (Slices.permanentlyFailed(m, artifact) || markedThisDrain(m, artifact)) {
            return m;
        }

        Outbox.artifact(m,
                        artifact,
                        intent -> intent.withOp(new JudgmentOp.Fatal(Outcomes.declaringAttempts(m, artifact))));
        routeFailed(m, ctx, pair, reason);

        return ctx.settings()
                  .atomicity() == DeploymentAtomicity.ALL_OR_NOTHING
               ? Outcomes.rollbackFor(m, ctx, artifact, reason)
               : Outcomes.recordBestEffortFailure(m, ctx, artifact, reason);
    }

    private static boolean markedThisDrain(ClusterModel m, Artifact artifact) {
        return m.artifactIntents.get(artifact)
                                .map(intent -> intent.ops()
                                                     .stream()
                                                     .anyMatch(op -> op instanceof JudgmentOp.Fatal))
                                .or(false);
    }

    /// R-3.3.7: the counter for (artifact, node) goes up in the same transaction. Counts 1 to 5 reconcile after
    /// `min(2^(n-1), 30)` s with jitter; the sixth is exhaustion (B-050), which condemns only while an apply is
    /// outstanding (#922).
    private static ClusterModel transientFailure(ClusterModel m, Ctx ctx, SlicePair pair, String reason) {
        var artifact = pair.artifact();
        var count = retries(m, pair) + 1;

        if (count > ctx.settings().timing().maxRetries()) {
            return exhausted(m, ctx, pair, reason);
        }

        var delayMs = retryDelayMs(ctx, pair, count);

        Outbox.artifact(m,
                        artifact,
                        intent -> intent.withOp(new JudgmentOp.Transient(pair.node())));
        m.retryAt = m.retryAt.put(pair, ctx.monoNs() + delayMs * 1_000_000L);

        return Lifecycle.schedule(m, ctx, retryTimer(pair), delayMs);
    }

    /// The count after this failure's predecessors: the committed count plus the transients already in this drain's intent.
    private static int retries(ClusterModel m, SlicePair pair) {
        var committed = m.judgments.get(pair.artifact())
                                   .map(judgment -> judgment.retries()
                                                            .getOrDefault(pair.node(),
                                                                          0))
                                   .or(0);
        var pending = m.artifactIntents.get(pair.artifact())
                                       .map(intent -> (int) intent.ops()
                                                                  .stream()
                                                                  .filter(op -> op instanceof JudgmentOp.Transient transientOp && transientOp.node()
                                                                                                                                             .equals(pair.node()))
                                                                  .count())
                                       .or(0);

        return committed + pending;
    }

    private static ClusterModel exhausted(ClusterModel m, Ctx ctx, SlicePair pair, String reason) {
        var artifact = pair.artifact();

        Outbox.artifact(m,
                        artifact,
                        intent -> intent.withOp(new JudgmentOp.Exhausted(pair.node())));
        if (Outcomes.applyOutstanding(m, artifact)) {
            return settle(m, ctx, pair, reason);
        }

        routeFailed(m, ctx, pair, reason);
        m.retryExhaustedWarned = m.retryExhaustedWarned.add(artifact);
        ctx.warn("retry-exhausted-unsettled",
                 artifact.asString(),
                 "Max retries exceeded for " + artifact
                + " on " + pair.node().id()
                + ": " + reason
                + " - not marking permanently failed, no blueprint apply is outstanding");

        return m;
    }

    /// The announcement rides the failure transaction and goes out when that transaction is accepted. A write that is lost is decided
    /// again by the failure sweep and announced when the new transaction lands; an accepted one leaves the pair at goal UNLOADED, which
    /// the sweep does not decide again, so a failure is announced once.
    private static ClusterModel routeFailed(ClusterModel m, Ctx ctx, SlicePair pair, String reason) {
        return Outbox.artifact(m,
                               pair.artifact(),
                               intent -> intent.withRouted(new RoutedEvent.DeploymentFailed(pair.artifact(),
                                                                                            pair.node(),
                                                                                            reason,
                                                                                            ctx.nowMs())));
    }

    private static TimerId retryTimer(SlicePair pair) {
        return TimerId.timerId(TimerId.Kind.RECONCILE,
                               "retry:" + pair.node().id() + ":" + pair.artifact().asString());
    }

    /// `min(2^(n-1), 30)` seconds, jittered by the injected source (R-3.3.7).
    static long retryDelayMs(Ctx ctx, SlicePair pair, int count) {
        var timing = ctx.settings().timing();
        var base = Math.min(timing.retryBase() << (count - 1), timing.retryMax());
        var subject = pair.node().id() + ":" + pair.artifact().asString() + ":" + count;

        return JitterUtil.applyJitter(base,
                                      JitterUtil.MIN_FACTOR_DEFAULT,
                                      JitterUtil.MAX_FACTOR_DEFAULT,
                                      () -> ctx.settings()
                                               .jitter()
                                               .applyAsDouble(subject));
    }

    record unused() implements Failures {}
}
