// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementPhase;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeReplacementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunState;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeRunValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.UpgradeStop;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// #1543 part F — what the rolling-upgrade run does next. A pure function of the committed run, the cluster as observed and the
/// committed replacement records; nothing is remembered between calls, so a new leader plans exactly what the old one would have.
///
/// The run replaces one node at a time through the merged replacement machinery and reads each replacement's committed phase:
/// `DONE` moves on; `ROLLED_BACK` or `FAILED_KEPT_BOTH` PAUSE the run (it never skips a node that could not be replaced). An operator's
/// pause or abort takes effect only when the replacement in flight is terminal, never mid-phase, so no record is ever abandoned.
/// A node is skipped once it is gone or already reports the target version; before COMPLETED the cluster is re-read, and any member
/// still on another version (a node that joined during the run) is appended, so "COMPLETED" means every replaceable node reports the target.
public sealed interface UpgradeRunPlanner {
    record unused() implements UpgradeRunPlanner {}

    /// One member as the run sees it: `role` is `core`, `worker` or `spot`; `version` is its advertised version (`""` = none).
    record Member(String role, String version) {
        boolean replaceable() {
            return "core".equalsIgnoreCase(role) || "worker".equalsIgnoreCase(role);
        }
    }

    /// The cluster as the run reads it: live members and every committed replacement record (original → record).
    record Observation(Map<NodeId, Member> members, Map<NodeId, NodeReplacementValue> records) {}

    /// What the reconciler must do before committing `next`.
    sealed interface Action {
        /// Nothing to do but (maybe) commit `next`.
        record Hold() implements Action {}

        /// Start the replacement of `node`, then commit `next`.
        record Begin(NodeId node) implements Action {}
    }

    record Plan(Action action, Option<UpgradeRunValue> next) {
        static Plan hold() {
            return new Plan(new Action.Hold(), Option.none());
        }

        static Plan commit(UpgradeRunValue next) {
            return new Plan(new Action.Hold(), Option.some(next));
        }

        static Plan begin(NodeId node, UpgradeRunValue next) {
            return new Plan(new Action.Begin(node), Option.some(next));
        }
    }

    static Plan plan(UpgradeRunValue run, Observation observation, long now) {
        if (run.state() != UpgradeRunState.RUNNING) {
            return Plan.hold();
        }

        return run.inFlight()
                  .isEmpty()
               ? idle(run, run, observation, now)
               : inFlight(run, observation, now);
    }

    private static Plan inFlight(UpgradeRunValue run, Observation observation, long now) {
        var original = new NodeId(run.inFlight());

        return Option.option(observation.records().get(original)).fold(() -> vanished(run, original, observation, now),
                                                                       record -> judged(run,
                                                                                        original,
                                                                                        record,
                                                                                        observation,
                                                                                        now));
    }

    /// The replacement record is not there. If the original is gone the step is done; if it is still a member the record never
    /// landed (or was removed), so the node is tried again.
    private static Plan vanished(UpgradeRunValue run, NodeId original, Observation observation, long now) {
        return observation.members()
                          .containsKey(original)
               ? idle(run,
                      run.with(run.index(), "", UpgradeRunState.RUNNING, run.stop(), "", now),
                      observation,
                      now)
               : idle(run, advanced(run, now), observation, now);
    }

    private static Plan judged(UpgradeRunValue run,
                               NodeId original,
                               NodeReplacementValue record,
                               Observation observation,
                               long now) {
        return switch (record.phase()) {
            case DONE -> idle(run, advanced(run, now), observation, now);
            case ROLLED_BACK -> stopped(run,
                                        "replacement of " + original.id() + " was rolled back: " + record.reason() + "; fix the cause and resume to try the node again",
                                        now);
            case FAILED_KEPT_BOTH -> stopped(run,
                                             "replacement of " + original.id() + " stopped with both nodes kept: " + record.reason() + "; settle it (POST /api/v1/nodes/replacements/settle/" + original.id() + ") and resume",
                                             now);
            case UNKNOWN -> stopped(run,
                                    "replacement of " + original.id() + " is in a phase this node does not know",
                                    now);
            case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING -> Plan.hold();
        };
    }

    /// A replacement ended badly (or unknown): an abort request wins and ends the run, anything else pauses it.
    private static Plan stopped(UpgradeRunValue run, String reason, long now) {
        return run.stop() == UpgradeStop.ABORT
               ? Plan.commit(run.with(run.index(),
                                      run.inFlight(),
                                      UpgradeRunState.ABORTED,
                                      UpgradeStop.NONE,
                                      "aborted; " + reason,
                                      now))
               : Plan.commit(run.with(run.index(), run.inFlight(), UpgradeRunState.PAUSED, UpgradeStop.NONE, reason, now));
    }

    private static UpgradeRunValue advanced(UpgradeRunValue run, long now) {
        return run.with(run.index() + 1, "", UpgradeRunState.RUNNING, run.stop(), "", now);
    }

    /// No replacement in flight. `base` is the run as committed; `run` may already carry progress made in this planning step.
    private static Plan idle(UpgradeRunValue base, UpgradeRunValue run, Observation observation, long now) {
        return switch (run.stop()) {
            case ABORT -> Plan.commit(run.with(run.index(),
                                               "",
                                               UpgradeRunState.ABORTED,
                                               UpgradeStop.NONE,
                                               "aborted by an operator after " + run.index() + " of " + run.order().size() + " nodes",
                                               now));
            case PAUSE, UNKNOWN -> Plan.commit(run.with(run.index(),
                                                        "",
                                                        UpgradeRunState.PAUSED,
                                                        UpgradeStop.NONE,
                                                        "paused by an operator after " + run.index() + " of " + run.order().size() + " nodes",
                                                        now));
            case NONE -> next(base, run, observation, now);
        };
    }

    private static Plan next(UpgradeRunValue base, UpgradeRunValue run, Observation observation, long now) {
        var position = firstNeeding(run, observation);

        if (position >= run.order().size()) {
            return finish(run, observation, now);
        }

        var candidate = run.order().get(position);
        var foreign = observation.records()
                                 .entrySet()
                                 .stream()
                                 .filter(entry -> holdsCapacity(entry.getValue().phase()))
                                 .toList();
        var own = foreign.stream().filter(entry -> entry.getKey()
                                                        .equals(candidate)).findFirst();

        if (own.isPresent()) {
            return Plan.commit(run.with(position, candidate.id(), UpgradeRunState.RUNNING, run.stop(), "", now));
        }

        if (!foreign.isEmpty()) {
            return waiting(base, run, position, foreign.getFirst(), now);
        }

        return Plan.begin(candidate,
                          run.with(position, candidate.id(), UpgradeRunState.RUNNING, run.stop(), "", now));
    }

    /// Another node's replacement holds the cluster's one slot. A running one ends by its own deadlines, so wait; one that waits for an
    /// operator to settle it would hold the run forever, so the run pauses and says so.
    private static Plan waiting(UpgradeRunValue base,
                                UpgradeRunValue run,
                                int position,
                                Map.Entry<NodeId, NodeReplacementValue> other,
                                long now) {
        if (other.getValue().phase() == NodeReplacementPhase.FAILED_KEPT_BOTH) {
            return Plan.commit(run.with(position,
                                        "",
                                        UpgradeRunState.PAUSED,
                                        UpgradeStop.NONE,
                                        "replacement of " + other.getKey()
                                                                 .id()
                                       + " stopped with both nodes kept; settle it (POST /api/v1/nodes/replacements/settle/" + other.getKey()
                                                                                                                                    .id()
                                       + ") and resume",
                                        now));
        }

        return position == base.index() && run == base
               ? Plan.hold()
               : Plan.commit(run.with(position, "", UpgradeRunState.RUNNING, run.stop(), "", now));
    }

    /// Everything on the list is done. Re-read the cluster: a member that joined during the run and is not on the target version is
    /// added, so COMPLETED is a statement about the cluster, not about the list.
    private static Plan finish(UpgradeRunValue run, Observation observation, long now) {
        var stragglers = observation.members()
                                    .entrySet()
                                    .stream()
                                    .filter(entry -> entry.getValue()
                                                          .replaceable() && !run.targetVersion()
                                                                                .equals(entry.getValue().version()))
                                    .filter(entry -> !run.order()
                                                         .contains(entry.getKey()))
                                    .sorted(Comparator.<Map.Entry<NodeId, Member>, Boolean> comparing(entry -> !"core".equalsIgnoreCase(entry.getValue()
                                                                                                                                             .role())).thenComparing(entry -> entry.getKey()
                                                                                                                                                                                   .id()))
                                    .map(Map.Entry::getKey)
                                    .toList();

        if (stragglers.isEmpty()) {
            return Plan.commit(run.with(run.order().size(),
                                        "",
                                        UpgradeRunState.COMPLETED,
                                        UpgradeStop.NONE,
                                        "",
                                        now));
        }

        var extended = new ArrayList<>(run.order());

        extended.addAll(stragglers);

        return Plan.commit(run.withOrder(List.copyOf(extended), now));
    }

    /// The first position at or after `index` whose node still has to be replaced: it is a replaceable member not on the target version.
    private static int firstNeeding(UpgradeRunValue run, Observation observation) {
        var position = run.index();

        while (position < run.order().size() && !needs(run,
                                                       observation,
                                                       run.order().get(position))) {
            position++;
        }

        return position;
    }

    private static boolean needs(UpgradeRunValue run, Observation observation, NodeId node) {
        return Option.option(observation.members().get(node))
                     .filter(Member::replaceable)
                     .filter(member -> !run.targetVersion()
                                           .equals(member.version()))
                     .isPresent();
    }

    /// A replacement holds the cluster's one slot while it runs and while it waits, kept-both, to be settled.
    static boolean holdsCapacity(NodeReplacementPhase phase) {
        return switch (phase) {
            case DONE, ROLLED_BACK -> false;
            case PROVISIONING, JOINING, SWAPPING, CANARY, DRAINING_OLD, RETIRING_OLD, REVERTING, FAILED_KEPT_BOTH, UNKNOWN -> true;
        };
    }
}
