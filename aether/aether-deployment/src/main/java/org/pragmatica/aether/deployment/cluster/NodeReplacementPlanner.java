// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.lang.Option;


/// #1543 part E — the decision function of the replacement reconciler: what ONE committed record should do next, given
/// what the cluster looks like right now. Pure: no clock, no lock, no I/O, so every phase, every failure row and every
/// "the old node is already dead" case is a table of inputs and outputs.
///
/// The driver ([NodeReplacementReconciler]) executes a [Plan] in a fixed order: the [Effect] first, then the
/// compare-and-set of the record. A plan carries the record to commit on success and the one to commit on failure
/// (either may be `none`: hold and retry on the next tick). Nothing here remembers anything between calls: the record is
/// the whole state, so a new leader resumes from it and re-derives the rest from the observation.
///
/// ## Phases (core)
/// `PROVISIONING` → `JOINING` → `SWAPPING` → `CANARY` → `DRAINING_OLD` → `RETIRING_OLD` → `DONE`, with `REVERTING` after a
/// failed canary and `ROLLED_BACK` / `FAILED_KEPT_BOTH` as the other terminal outcomes. A dead original skips
/// `DRAINING_OLD`: a corpse is never drained, it is retired.
///
/// ## The caught-up gate
/// `JOINING → SWAPPING` is committed only on `caughtUp`, and only `SWAPPING` authorizes the voter swap
/// ([NodeReplacementIndex#voterSwaps]). `caughtUp` is the SAME predicate the voter reconciler uses to admit a candidate:
/// the replacement is a core member, ON_DUTY, and passes core admission. Consensus-state catch-up past the swap slot is
/// then enforced by `SWAPPING` itself, which leaves only when the engine reports the new roster settled. DHT replicas and
/// stream partitions the old node held are NOT waited for before the swap: they move with the drain that follows, and
/// `RETIRING_OLD` is not `DONE` until the hand-off reports settled.
public final class NodeReplacementPlanner {
    private NodeReplacementPlanner() {}

    /// What a plan does to the world before it commits the record.
    public enum Effect {
        NONE,
        PROVISION,
        TERMINATE_REPLACEMENT,
        DRAIN_OLD,
        RETIRE_OLD
    }

    /// Where the old node's operator-style drain stands.
    public enum DrainState {
        NOT_REQUESTED,
        IN_PROGRESS,
        COMPLETE
    }

    /// The observable facts one decision reads. Booleans are named for the answer they give.
    public record Observation(long now,
                              boolean oldAlive,
                              boolean replacementKnown,
                              boolean replacementAlive,
                              boolean replacementCaughtUp,
                              boolean oldIsVoter,
                              boolean replacementIsVoter,
                              boolean rosterSettled,
                              boolean replacementReady,
                              String replacementVersion,
                              DrainState oldDrain,
                              String drainBlockedBy,
                              boolean oldDecommissioned,
                              boolean handoffSettled) {}

    /// Phase budgets. Every phase is bounded, so every replacement ends in a terminal phase.
    public record Timings(long provisioningMs,
                          long joiningMs,
                          long swappingMs,
                          long canaryMs,
                          long canaryWaitMs,
                          long drainingMs,
                          long retiringMs) {
        public static Timings defaults() {
            return new Timings(120_000L, 600_000L, 180_000L, 120_000L, 0L, 600_000L, 300_000L);
        }
    }

    /// What to do, and what the record becomes on success (`next`) or on failure of the effect (`onFailure`).
    public record Plan(Effect effect, Option<NodeReplacementValue> next, Option<NodeReplacementValue> onFailure) {
        static Plan hold() {
            return new Plan(Effect.NONE, Option.none(), Option.none());
        }

        static Plan commit(NodeReplacementValue next) {
            return new Plan(Effect.NONE, Option.some(next), Option.none());
        }

        static Plan act(Effect effect, NodeReplacementValue next, NodeReplacementValue onFailure) {
            return new Plan(effect, Option.some(next), Option.some(onFailure));
        }

        static Plan act(Effect effect) {
            return new Plan(effect, Option.none(), Option.none());
        }
    }

    /// Reason markers committed into `reason` so an operator-visible transition happens once per commit.
    public static final String JOIN_OVERDUE = "join-overdue";
    public static final String DRAIN_BLOCKED = "drain-blocked: ";
    public static final String CANARY_OK_SINCE = "canary-ok-since:";

    public static Plan plan(NodeReplacementValue record, Observation o, Timings t) {
        return switch (record.phase()) {
            case PROVISIONING -> provisioning(record, o, t);
            case JOINING -> joining(record, o, t);
            case SWAPPING -> swapping(record, o, t);
            case CANARY -> canary(record, o, t);
            case DRAINING_OLD -> drainingOld(record, o, t);
            case RETIRING_OLD -> retiringOld(record, o, t);
            case REVERTING -> reverting(record, o, t);
            case DONE, ROLLED_BACK, FAILED_KEPT_BOTH, UNKNOWN -> Plan.hold();
        };
    }

    private static Plan provisioning(NodeReplacementValue r, Observation o, Timings t) {
        if (o.now() > r.phaseDeadlineMs()) {
            return rollBack(r, o, "provisioning deadline");
        }

        if (isExternal(r) || o.replacementKnown()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.JOINING,
                                          o.now() + t.joiningMs(),
                                          ""));
        }

        return Plan.act(Effect.PROVISION,
                        r.advanced(NodeReplacementPhase.JOINING,
                                   o.now() + t.joiningMs(),
                                   ""),
                        r.advanced(NodeReplacementPhase.ROLLED_BACK, o.now(), "provisioning refused"));
    }

    private static Plan joining(NodeReplacementValue r, Observation o, Timings t) {
        if (o.replacementKnown() && !o.replacementAlive() && !isExternal(r)) {
            return rollBack(r, o, "replacement died while joining");
        }

        if (o.now() > r.phaseDeadlineMs()) {
            return rollBack(r, o, "join deadline");
        }

        if (o.replacementCaughtUp()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.SWAPPING,
                                          o.now() + t.swappingMs(),
                                          ""));
        }

        var overdueAt = r.phaseDeadlineMs() - t.joiningMs() / 2;

        if (o.now() > overdueAt && !JOIN_OVERDUE.equals(r.reason())) {
            return Plan.commit(r.advanced(NodeReplacementPhase.JOINING, r.phaseDeadlineMs(), JOIN_OVERDUE));
        }

        return Plan.hold();
    }

    private static Plan swapping(NodeReplacementValue r, Observation o, Timings t) {
        if (o.replacementIsVoter() && !o.oldIsVoter() && o.rosterSettled()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.CANARY,
                                          o.now() + t.canaryMs(),
                                          ""));
        }

        if (o.replacementKnown() && !o.replacementAlive()) {
            return o.oldIsVoter() && !o.replacementIsVoter()
                   ? rollBack(r, o, "replacement died before the swap")
                   : Plan.commit(r.advanced(NodeReplacementPhase.FAILED_KEPT_BOTH,
                                            o.now(),
                                            "replacement died after the swap"));
        }

        if (o.now() > r.phaseDeadlineMs()) {
            return o.oldIsVoter() && !o.replacementIsVoter()
                   ? rollBack(r, o, "swap deadline")
                   : Plan.commit(r.advanced(NodeReplacementPhase.FAILED_KEPT_BOTH,
                                            o.now(),
                                            "swap applied but not settled"));
        }

        return Plan.hold();
    }

    private static Plan canary(NodeReplacementValue r, Observation o, Timings t) {
        var versionOk = r.targetVersion().isEmpty() || r.targetVersion().equals(o.replacementVersion());
        var healthy = o.replacementAlive() && o.replacementReady() && versionOk;

        if (healthy && canaryHeldLongEnough(r, o, t)) {
            return Plan.commit(r.advanced(NodeReplacementPhase.DRAINING_OLD,
                                          o.now() + t.drainingMs(),
                                          ""));
        }

        if (healthy && t.canaryWaitMs() > 0 && !r.reason().startsWith(CANARY_OK_SINCE)) {
            return Plan.commit(r.advanced(NodeReplacementPhase.CANARY, r.phaseDeadlineMs(), CANARY_OK_SINCE + o.now()));
        }

        if (!o.replacementAlive() || o.now() > r.phaseDeadlineMs()) {
            return revertOrKeep(r,
                                o,
                                t,
                                o.replacementAlive()
                                ? "canary deadline"
                                : "replacement died in canary");
        }

        return Plan.hold();
    }

    private static boolean canaryHeldLongEnough(NodeReplacementValue r, Observation o, Timings t) {
        if (t.canaryWaitMs() <= 0) {
            return true;
        }

        return r.reason()
                .startsWith(CANARY_OK_SINCE) && o.now() - since(r.reason()) >= t.canaryWaitMs();
    }

    private static long since(String reason) {
        return Long.parseLong(reason.substring(CANARY_OK_SINCE.length()));
    }

    /// A failed canary swaps the original back when the original is still alive: the pairing keeps it protected until it
    /// holds its seat again. When it is gone there is nothing to go back to, and the replacement is kept.
    private static Plan revertOrKeep(NodeReplacementValue r, Observation o, Timings t, String why) {
        return o.oldAlive()
               ? Plan.commit(r.advanced(NodeReplacementPhase.REVERTING,
                                        o.now() + t.swappingMs(),
                                        why))
               : Plan.commit(r.advanced(NodeReplacementPhase.FAILED_KEPT_BOTH, o.now(), why + "; the original is gone"));
    }

    private static Plan reverting(NodeReplacementValue r, Observation o, Timings t) {
        if (o.oldIsVoter() && !o.replacementIsVoter() && o.rosterSettled()) {
            return Plan.act(Effect.TERMINATE_REPLACEMENT,
                            r.advanced(NodeReplacementPhase.ROLLED_BACK, o.now(), r.reason()),
                            r.advanced(NodeReplacementPhase.ROLLED_BACK, o.now(), r.reason()));
        }

        if (o.now() > r.phaseDeadlineMs()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.FAILED_KEPT_BOTH, o.now(), "swap back did not settle"));
        }

        return Plan.hold();
    }

    private static Plan drainingOld(NodeReplacementValue r, Observation o, Timings t) {
        if (!o.oldAlive()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.RETIRING_OLD,
                                          o.now() + t.retiringMs(),
                                          ""));
        }

        if (o.oldDrain() == DrainState.COMPLETE) {
            return Plan.commit(r.advanced(NodeReplacementPhase.RETIRING_OLD,
                                          o.now() + t.retiringMs(),
                                          ""));
        }

        if (o.now() > r.phaseDeadlineMs()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.FAILED_KEPT_BOTH, o.now(), blocked(r, o)));
        }

        return drainStep(r, o);
    }

    private static Plan drainStep(NodeReplacementValue r, Observation o) {
        var blocked = !o.drainBlockedBy().isEmpty();
        var markedBlocked = r.reason().startsWith(DRAIN_BLOCKED);

        if (blocked && !markedBlocked) {
            return Plan.commit(r.advanced(NodeReplacementPhase.DRAINING_OLD,
                                          r.phaseDeadlineMs(),
                                          DRAIN_BLOCKED + o.drainBlockedBy()));
        }

        if (!blocked && markedBlocked) {
            return Plan.commit(r.advanced(NodeReplacementPhase.DRAINING_OLD, r.phaseDeadlineMs(), ""));
        }

        return Plan.act(Effect.DRAIN_OLD);
    }

    private static String blocked(NodeReplacementValue r, Observation o) {
        return o.drainBlockedBy()
                .isEmpty()
               ? "drain did not complete"
               : DRAIN_BLOCKED + o.drainBlockedBy();
    }

    private static Plan retiringOld(NodeReplacementValue r, Observation o, Timings t) {
        if (o.oldDecommissioned() && o.handoffSettled()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.DONE, o.now(), ""));
        }

        if (o.now() > r.phaseDeadlineMs()) {
            return Plan.commit(r.advanced(NodeReplacementPhase.DONE,
                                          o.now(),
                                          "retirement overdue: the cluster is already correct"));
        }

        return Plan.act(Effect.RETIRE_OLD);
    }

    private static Plan rollBack(NodeReplacementValue r, Observation o, String why) {
        var done = r.advanced(NodeReplacementPhase.ROLLED_BACK, o.now(), why);

        return Plan.act(Effect.TERMINATE_REPLACEMENT, done, done);
    }

    private static boolean isExternal(NodeReplacementValue r) {
        return NodeReplacementValue.MODE_EXTERNAL.equals(r.mode());
    }
}
