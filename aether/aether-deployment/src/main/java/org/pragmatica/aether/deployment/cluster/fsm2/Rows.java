// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.stream.Stream;
import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceDesiredValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Reads over the slice-side model that more than one transition needs: the staleness of an observed row (R-1.6.2,
/// C8), the placement predicates (`isLive`, `occupies`, R-1.6.2), the instance and count definitions (R-2.6) and the
/// set of nodes whose placements count (R-3.3.3).
///
/// Every function here is a pure read of a model. None of them iterates a model-wide index; the ones that walk
/// "the instances of one artifact" are O(instances of that artifact), which is the order 7.3 allows.
public sealed interface Rows {
    // -- staleness (R-1.6.2, C8) -----------------------------------------------------------------------------------
    /// Stale iff both tokens are known (non-zero) and they differ. Positive evidence only: an unknown token on
    /// either side is current (C8).
    static boolean isStale(ClusterModel m, SlicePair pair, SliceObservedValue row) {
        var rowToken = row.incarnation().bootToken();
        var admitted = m.admissions.get(pair.node()).map(Admission::token).or(0L);

        return rowToken != 0L
               && admitted != 0L
               && rowToken != admitted;
    }

    /// The observed row of a pair that counts: present and not stale. A stale row reads as absent (R-1.6.2).
    static Option<SliceObservedValue> current(ClusterModel m, SlicePair pair) {
        return m.observed.get(pair)
                         .filter(row -> !isStale(m, pair, row));
    }

    // -- liveness and occupation (R-1.6.2) -------------------------------------------------------------------------
    /// The positive list: LOADING, LOADED, ACTIVATING, ACTIVE, DEACTIVATING, UNKNOWN.
    static boolean isLive(SliceObservedState state) {
        return switch (state) {
            case LOADING, LOADED, ACTIVATING, ACTIVE, DEACTIVATING, UNKNOWN -> true;
            case FAILED, UNLOADING, UNLOADED -> false;
        };
    }

    static boolean isEarlierInChain(SliceObservedState state) {
        return state == SliceObservedState.LOADING || state == SliceObservedState.LOADED || state == SliceObservedState.ACTIVATING;
    }

    static boolean isTransitional(SliceObservedState state) {
        return state == SliceObservedState.LOADING || state == SliceObservedState.ACTIVATING || state == SliceObservedState.DEACTIVATING || state == SliceObservedState.UNLOADING;
    }

    /// `occupies(pair)`: the effective goal is LOADED or ACTIVE, or a current observed row exists whose state is not
    /// UNLOADED. A pair is vacant iff it does not occupy.
    static boolean occupies(ClusterModel m, SlicePair pair) {
        return wantsInstance(m, pair) || current(m, pair).map(row -> row.state() != SliceObservedState.UNLOADED)
                                                .or(false);
    }

    /// The effective goal is LOADED or ACTIVE: an instance in the sense of R-2.6.
    static boolean wantsInstance(ClusterModel m, SlicePair pair) {
        return m.effectiveGoal(pair)
                .map(goal -> goal == SliceGoal.LOADED || goal == SliceGoal.ACTIVE)
                .or(false);
    }

    /// A pair is settled when its goal is reflected by its current observation (6.1); a settled pair is never divergent.
    /// A FAILED row under a goal other than UNLOADED belongs to the failure handling (C10c), not to divergence.
    static boolean isSettled(SliceDesiredValue goal, Option<SliceObservedValue> row) {
        var state = row.map(SliceObservedValue::state);

        if (state.map(value -> value == SliceObservedState.FAILED).or(false) && goal.goal() != SliceGoal.UNLOADED) {
            return true;
        }

        return switch (goal.goal()) {
            case LOADED -> reflects(goal, row, SliceObservedState.LOADED);
            case ACTIVE -> reflects(goal, row, SliceObservedState.ACTIVE);
            case UNLOADED -> state.map(value -> value == SliceObservedState.UNLOADED).or(true);
            case UNKNOWN -> true;
        };
    }

    private static boolean reflects(SliceDesiredValue goal,
                                    Option<SliceObservedValue> row,
                                    SliceObservedState expected) {
        return row.map(value -> value.state() == expected && value.forGeneration()
                                                                  .equals(goal.generation()))
                  .or(false);
    }

    // -- instances and counts (R-2.6) ------------------------------------------------------------------------------
    /// The pairs of `artifact` whose effective goal is LOADED or ACTIVE (committed goals plus the goals in flight).
    static List<SlicePair> instances(ClusterModel m, Artifact artifact) {
        return Idx.members(m.desiredByArtifact, artifact)
                  .toList()
                  .stream()
                  .map(node -> SlicePair.slicePair(node, artifact))
                  .filter(pair -> wantsInstance(m, pair))
                  .sorted(PAIR_ORDER)
                  .toList();
    }

    /// Pairs with a goal in flight and no committed desired key yet, for `artifact`.
    static List<SlicePair> pendingOnly(ClusterModel m, Artifact artifact) {
        return Idx.members(m.pendingByArtifact, artifact)
                  .toList()
                  .stream()
                  .map(node -> SlicePair.slicePair(node, artifact))
                  .filter(pair -> !m.desired.containsKey(pair))
                  .filter(pair -> wantsInstance(m, pair))
                  .sorted(PAIR_ORDER)
                  .toList();
    }

    /// Every instance of `artifact`: committed and in flight.
    static List<SlicePair> allInstances(ClusterModel m, Artifact artifact) {
        return Stream.concat(instances(m, artifact).stream(),
                             pendingOnly(m, artifact).stream())
                     .sorted(PAIR_ORDER)
                     .toList();
    }

    /// `placed(a)`: instances on counting nodes, excluding departed, departing and unresponsive nodes (R-2.6).
    static int placed(ClusterModel m, Artifact artifact) {
        return (int) allInstances(m, artifact).stream()
                                 .filter(pair -> countsForPlacement(m,
                                                                    pair.node(),
                                                                    artifact))
                                 .count();
    }

    static boolean countsForPlacement(ClusterModel m, NodeId node, Artifact artifact) {
        return counts(m, node) && !isUnresponsive(m, node, artifact);
    }

    static boolean isUnresponsive(ClusterModel m, NodeId node, Artifact artifact) {
        return m.judgments.get(artifact)
                          .map(judgment -> judgment.unresponsive()
                                                   .containsKey(node))
                          .or(false);
    }

    /// Counted, current observations with `isLive()` (R-2.6): the live count.
    static int liveCount(ClusterModel m, Artifact artifact) {
        return (int) countedRows(m, artifact).stream()
                                .filter(row -> isLive(row.state()))
                                .count();
    }

    /// Counted, current observations with state ACTIVE (R-2.6): the active count used by the verdict, the
    /// dependency gate, the drain guard and `minAvailable`.
    static int activeCount(ClusterModel m, Artifact artifact) {
        return (int) countedRows(m, artifact).stream()
                                .filter(row -> row.state() == SliceObservedState.ACTIVE)
                                .count();
    }

    /// The current observed rows of `artifact` on counting nodes. An observation counts iff its `forGeneration` equals
    /// the current generation, or it is older and the current goal is not UNLOADED (R-3.3.10).
    static List<SliceObservedValue> countedRows(ClusterModel m, Artifact artifact) {
        return Idx.members(m.observedByArtifact, artifact)
                  .toList()
                  .stream()
                  .map(node -> SlicePair.slicePair(node, artifact))
                  .filter(pair -> counts(m,
                                         pair.node()))
                  .filter(pair -> countsObservation(m, pair))
                  .flatMap(pair -> current(m, pair).stream())
                  .toList();
    }

    static boolean countsObservation(ClusterModel m, SlicePair pair) {
        var row = m.observed.get(pair);
        var goal = m.desired.get(pair);

        if (row.isEmpty() || goal.isEmpty()) {
            return row.isPresent();
        }

        var forGeneration = row.map(SliceObservedValue::forGeneration).or(new Generation(0L, 0L));
        var current = goal.map(SliceDesiredValue::generation).or(new Generation(0L, 0L));
        var comparison = forGeneration.compareTo(current);

        return comparison == 0 || (comparison < 0 && goal.map(value -> value.goal() != SliceGoal.UNLOADED)
                                                         .or(false));
    }

    // -- counting nodes (R-3.3.2, R-3.3.3) -------------------------------------------------------------------------
    /// Counting node set membership (R-3.3.3): counted cores minus departed and departing, plus recorded workers in a
    /// non-dissolved roster or locally alive. With no roster at all, or an unwired local seam, every recorded worker
    /// counts.
    static boolean counts(ClusterModel m, NodeId node) {
        if (m.departed.contains(node) || m.departing.containsKey(node)) {
            return false;
        }

        return m.seams.countedCores()
                      .contains(node) || countsAsWorker(m, node);
    }

    static boolean countsAsWorker(ClusterModel m, NodeId node) {
        return recordedWorker(m, node)
               && communityAllowsPlacement(m, node)
               && (noRosterSignal(m) || presentInAnySignal(m, node));
    }

    /// A WORKER directive that names a community: the node is a recorded worker (R-3.3.2).
    static boolean recordedWorker(ClusterModel m, NodeId node) {
        return m.directives.get(node)
                           .map(Rows::isCommunityWorker)
                           .or(false);
    }

    static boolean isCommunityWorker(ActivationDirectiveValue directive) {
        return ActivationDirectiveValue.WORKER.equals(directive.role()) && !directive.communityId()
                                                                                     .isEmpty();
    }

    /// J3: a DISSOLVING or DISSOLVED community leaves its workers uncounted and unplaceable.
    static boolean communityAllowsPlacement(ClusterModel m, NodeId node) {
        return m.directives.get(node)
                           .flatMap(directive -> m.communities.get(directive.communityId()))
                           .map(community -> switch (community.state()) {
                                    case DISSOLVING, DISSOLVED -> false;
                                    default -> true;
                                })
                           .or(true);
    }

    static boolean noRosterSignal(ClusterModel m) {
        return m.rosterCount.isEmpty() || m.seams.localAlive()
                                                 .isEmpty();
    }

    static boolean presentInAnySignal(ClusterModel m, NodeId node) {
        return m.rosterCount.containsKey(node) || m.seams.localAlive()
                                                         .map(alive -> alive.contains(node))
                                                         .or(false);
    }

    // -- ordering --------------------------------------------------------------------------------------------------
    Comparator<SlicePair> PAIR_ORDER = Comparator.comparing((SlicePair pair) -> pair.node()
                                                                                    .id()).thenComparing(pair -> pair.artifact()
                                                                                                                     .asString());

    /// R-3.3.9: ties by NodeId.
    static List<NodeId> byId(List<NodeId> nodes) {
        return nodes.stream()
                    .sorted(Comparator.comparing(NodeId::id))
                    .toList();
    }

    static boolean isDeparted(DepartureState state) {
        return state == DepartureState.DEPARTED;
    }

    record unused() implements Rows {}
}
