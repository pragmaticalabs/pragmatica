// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.deployment.cluster.ClusterDeploymentManager.DeploymentAtomicity;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentApplyValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DeploymentOutcomeStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;


/// The apply deadline (R-4.1.7, #970): an open apply whose slices report nothing reaches a terminal instead of staying IN_PROGRESS for
/// ever. Driven by `Outcomes.sweep` (reconcile step 9), one clock per open apply, no scheduler of its own.
///
/// - Clock: this leader's monotonic reading, from the first pass that held the open apply. A fresh `Rebuild` has no clocks, and a resume
///   after a suspension drops them ([#rearm]), so a leader never judges time it could not observe; a leader change restarts the clock
///   (L-21). Wall time (`openedAt`) is never compared.
/// - Pause: while a datasource owned by the blueprint is PENDING or MIGRATING the interval does not count, up to
///   `applySchemaPauseMax` in total; a FAILED datasource does not pause. The gate state read at a pass is credited to the interval that
///   ended at it (error: at most one tick).
/// - Terminal: ALL_OR_NOTHING rolls back (`Outcomes.rollbackOnDeadline`), BEST_EFFORT writes FAILED with no rollback
///   (`Outcomes.failOnDeadline`); both carry the cause [#EXCEEDED] and the slices that are not ACTIVE. A success already decided or in
///   flight wins.
/// - Failure direction: re-drive, never condemn. An apply record the FSM cannot read never enters `applies`, and a non-positive
///   `applyDeadline` is an unreadable bound; neither terminates an apply.
/// - Operator: one `apply-deadline-exceeded` warning per episode (a family of the committed open set, #2834), open while the
///   blueprint's outcome is the deadline terminal of the warned attempt, closed with `apply-deadline-cleared` once the outcome is replaced
///   (a republish) or removed.
public sealed interface Deadlines {
    /// The cause of the deadline terminal and the code of its warning.
    String EXCEEDED = "apply-deadline-exceeded";
    Set<SchemaStatus> PAUSING = Set.of(SchemaStatus.PENDING, SchemaStatus.MIGRATING);
    long NS_PER_MS = 1_000_000L;

    /// The clock of one open apply: the attempt it measures, its start and its last reading (monotonic ns), and the paused time so far.
    record ApplyClock(String attemptId, long startNs, long lastNs, long pausedNs) {
        static ApplyClock applyClock(String attemptId, long nowNs) {
            return new ApplyClock(attemptId, nowNs, nowNs, 0L);
        }

        ApplyClock advanced(long nowNs, boolean gateOpen, long pauseMaxNs) {
            var paused = gateOpen
                         ? Math.min(pauseMaxNs, pausedNs + nowNs - lastNs)
                         : pausedNs;

            return new ApplyClock(attemptId, startNs, nowNs, paused);
        }

        long unpausedNs() {
            return lastNs - startNs - pausedNs;
        }
    }

    static ClusterModel sweep(ClusterModel m, Ctx ctx) {
        var open = openApplies(m);

        settle(m, ctx);
        forget(m, open);
        open.forEach(entry -> check(m, ctx, entry.getKey(), entry.getValue()));

        return m;
    }

    /// A resumed leader did not observe the suspended interval: every clock starts again at its next pass.
    static ClusterModel rearm(ClusterModel m) {
        m.applyClocks.keys().forEach(id -> m.applyClocks = m.applyClocks.remove(id));

        return m;
    }

    private static List<Map.Entry<BlueprintId, DeploymentApplyValue>> openApplies(ClusterModel m) {
        return m.applies.entries()
                        .stream()
                        .filter(entry -> declaresSlices(m,
                                                        entry.getKey()))
                        .filter(entry -> Blueprints.isOpen(m,
                                                           entry.getKey(),
                                                           entry.getValue()))
                        .filter(entry -> Outcomes.attemptOf(m,
                                                            entry.getKey())
                                                 .equals(entry.getValue().attemptId()))
                        .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                   .asString()))
                        .toList();
    }

    private static boolean declaresSlices(ClusterModel m, BlueprintId id) {
        return m.blueprints.get(id)
                           .map(value -> !value.registerOnly())
                           .or(false);
    }

    /// The clock of an apply that is no longer open (completed, rolled back, removed) is dropped, so a republish starts a new one.
    private static ClusterModel forget(ClusterModel m, List<Map.Entry<BlueprintId, DeploymentApplyValue>> open) {
        var ids = open.stream().map(Map.Entry::getKey).collect(Collectors.toSet());

        m.applyClocks.keys()
                     .stream()
                     .filter(id -> !ids.contains(id))
                     .forEach(id -> m.applyClocks = m.applyClocks.remove(id));

        return m;
    }

    private static ClusterModel check(ClusterModel m, Ctx ctx, BlueprintId id, DeploymentApplyValue apply) {
        var clock = advance(m, ctx, id, apply);

        return isExpired(ctx, clock)
               ? terminate(m, ctx, id, apply, clock)
               : m;
    }

    private static ApplyClock advance(ClusterModel m, Ctx ctx, BlueprintId id, DeploymentApplyValue apply) {
        var now = ctx.monoNs();
        var next = m.applyClocks.get(id)
                                .filter(clock -> clock.attemptId()
                                                      .equals(apply.attemptId()))
                                .map(clock -> clock.advanced(now,
                                                             schemaGateOpen(m, id),
                                                             pauseMaxNs(ctx)))
                                .or(ApplyClock.applyClock(apply.attemptId(),
                                                          now));

        m.applyClocks = m.applyClocks.put(id, next);

        return next;
    }

    private static long pauseMaxNs(Ctx ctx) {
        return Math.max(0L,
                        ctx.settings().timing().applySchemaPauseMax()) * NS_PER_MS;
    }

    private static boolean isExpired(Ctx ctx, ApplyClock clock) {
        var deadline = ctx.settings().timing().applyDeadline();

        return deadline > 0L && clock.unpausedNs() >= deadline * NS_PER_MS;
    }

    /// Whether a datasource owned by the blueprint's base is PENDING or MIGRATING while the blueprint requires a schema.
    private static boolean schemaGateOpen(ClusterModel m, BlueprintId id) {
        return Schemas.schemaRequired(m, id) && m.schemas.values()
                                                         .stream()
                                                         .filter(schema -> PAUSING.contains(schema.status()))
                                                         .anyMatch(schema -> schema.owningBlueprint()
                                                                                   .base()
                                                                                   .equals(id.base()));
    }

    private static ClusterModel terminate(ClusterModel m,
                                          Ctx ctx,
                                          BlueprintId id,
                                          DeploymentApplyValue apply,
                                          ApplyClock clock) {
        if (Outcomes.isDeployed(m, id) || Outcomes.succeededInFlight(m, id)) {
            return m;
        }

        var failing = notActive(m, id);

        if (ctx.settings().atomicity() == DeploymentAtomicity.ALL_OR_NOTHING) {
            Outcomes.rollbackOnDeadline(m, ctx, id, failing);
        } else {
            Outcomes.failOnDeadline(m, ctx, id, failing);
        }

        return raise(m, ctx, id, apply.attemptId(), failing, clock.unpausedNs() / NS_PER_MS);
    }

    /// The declared slices with no counted ACTIVE instance, in load order.
    private static List<String> notActive(ClusterModel m, BlueprintId id) {
        return m.blueprints.get(id)
                           .map(value -> value.blueprint()
                                              .loadOrder()
                                              .stream()
                                              .filter(slice -> Rows.activeCount(m,
                                                                                slice.artifact()) == 0)
                                              .map(slice -> slice.artifact()
                                                                 .asString())
                                              .toList())
                           .or(List.of());
    }

    // -- the warning (a family of the committed open set, #2834) -----------------------------------------------------
    /// Once per episode: an open warning, this leader's or inherited (`Warnings.inherit`), is not raised again.
    private static ClusterModel raise(ClusterModel m,
                                      Ctx ctx,
                                      BlueprintId id,
                                      String attempt,
                                      List<String> failing,
                                      long elapsedMs) {
        if (m.applyDeadlineWarned.containsKey(id)) {
            return m;
        }

        m.applyDeadlineWarned = m.applyDeadlineWarned.put(id, attempt);
        ctx.warn(Warnings.APPLY_DEADLINE,
                 id.asString(),
                 id.asString()
                + ": " + EXCEEDED
                + " after " + elapsedMs
                + " ms of unpaused time, slices not ACTIVE: " + failing
                + "; republish or delete the blueprint");

        return m;
    }

    /// Level-triggered close: an open warning stands while the blueprint's outcome is the warned attempt's, still IN_PROGRESS (the terminal is
    /// in flight) or the deadline terminal. Any other outcome, or none, ends the episode.
    private static ClusterModel settle(ClusterModel m, Ctx ctx) {
        m.applyDeadlineWarned.entries()
                             .stream()
                             .sorted(Comparator.comparing(entry -> entry.getKey()
                                                                        .asString()))
                             .filter(entry -> !standing(m,
                                                        entry.getKey(),
                                                        entry.getValue()))
                             .forEach(entry -> clear(m,
                                                     ctx,
                                                     entry.getKey()));

        return m;
    }

    private static boolean standing(ClusterModel m, BlueprintId id, String attempt) {
        return m.outcomes.get(id)
                         .filter(outcome -> outcome.describesAttempt(attempt))
                         .map(outcome -> outcome.status() == DeploymentOutcomeStatus.IN_PROGRESS || EXCEEDED.equals(outcome.cause()))
                         .or(false);
    }

    private static ClusterModel clear(ClusterModel m, Ctx ctx, BlueprintId id) {
        m.applyDeadlineWarned = m.applyDeadlineWarned.remove(id);
        ctx.warn(Recoveries.APPLY_DEADLINE_CLEARED,
                 id.asString(),
                 id.asString() + ": the apply deadline terminal was replaced or removed");

        return m;
    }

    record unused() implements Deadlines {}
}
