// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.stream.Stream;
import java.util.HashMap;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactBase;
import org.pragmatica.aether.config.PlacementPolicy;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceTargetValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;


/// Placement per committed target (spec reconcile step 3, R-3.3.9, B-030 to B-033, B-060).
///
/// - The **audience** of a target is the placement policy applied to the allocation pool: the counted, ready, not
///   draining cores, and the placeable workers of ACTIVE communities.
/// - **Candidates** are the audience minus every node where `occupies(pair)` holds for the artifact, minus departed,
///   departing and unresponsive nodes (R-3.3.9, NT-14).
/// - **Least-loaded first**, ties by NodeId (B-030, NT-13). **Scale-down** takes victims from the most-loaded node first,
///   down to `minInstances` (B-032, NT-15). **Load before unload**: a displaced instance is retired only after the
///   instances in the audience are ACTIVE (B-033).
/// - All the goals of one pass are intents of one drain, and ride the per-node transactions of one flush.
public sealed interface Placement {
    /// Step 3: every committed target, in base order.
    static ClusterModel run(ClusterModel m, Ctx ctx) {
        m.targets.entries()
                 .stream()
                 .sorted(Comparator.comparing(entry -> entry.getKey()
                                                            .toString()))
                 .forEach(entry -> reconcileTarget(m,
                                                   ctx,
                                                   entry.getKey(),
                                                   entry.getValue()));

        return m;
    }

    private static ClusterModel reconcileTarget(ClusterModel m, Ctx ctx, ArtifactBase base, SliceTargetValue target) {
        var artifact = base.withVersion(target.currentVersion());

        if (Slices.permanentlyFailed(m, artifact) || Drains.hasInstanceOnDrainingNode(m, artifact)) {
            m.imbalancedTicks = m.imbalancedTicks.remove(artifact);

            return m;
        }

        Result.lift(() -> PlacementPolicy.valueOf(target.effectivePlacement()))
              .onFailure(cause -> ctx.warn("invalid-placement",
                                           artifact.asString(),
                                           "Invalid placement " + target.placement() + " for " + artifact))
              .onSuccess(policy -> reconcileWithPolicy(m, ctx, artifact, target, policy));

        return m;
    }

    private static ClusterModel reconcileWithPolicy(ClusterModel m,
                                                    Ctx ctx,
                                                    Artifact artifact,
                                                    SliceTargetValue target,
                                                    PlacementPolicy policy) {
        var audience = audience(m, policy);

        if (audience.isEmpty()) {
            return m;
        }

        var eligible = Set.copyOf(audience);
        var desired = target.targetInstances();
        var placed = Rows.allInstances(m, artifact)
                         .stream()
                         .filter(pair -> eligible.contains(pair.node()) && Rows.countsForPlacement(m,
                                                                                                   pair.node(),
                                                                                                   artifact))
                         .toList();

        if (placed.size() < desired) {
            scaleUp(m, ctx, artifact, audience, desired - placed.size());
        } else if (placed.size() > desired) {
            scaleDown(m, placed, placed.size() - desired, target.effectiveMinInstances());
        }

        var displaced = retireDisplaced(m, artifact, eligible, desired);

        if (placed.size() != desired) {
            adjusted(m, ctx, artifact, placed.size(), desired);
            m.imbalancedTicks = m.imbalancedTicks.remove(artifact);
        } else if (policy == PlacementPolicy.CORE_ONLY && !displaced) {
            Rebalance.consider(m, ctx, artifact, audience);
        }

        return m;
    }

    private static ClusterModel adjusted(ClusterModel m, Ctx ctx, Artifact artifact, int current, int desired) {
        ctx.route(new RoutedEvent.ReconciliationAdjustment(artifact, current, desired));
        ctx.emit(new ClusterEffect.Audit(desired > current
                                         ? "reconciliation-scale-up"
                                         : "reconciliation-scale-down",
                                         artifact.asString() + " " + current + " -> " + desired));

        return m;
    }

    // -- the audience ----------------------------------------------------------------------------------------------
    /// Allocatable cores: counted, ready and not draining (B-034, B-035), and counting (not departed, not departing).
    static List<NodeId> cores(ClusterModel m) {
        return Rows.byId(m.seams.countedCores()
                                .stream()
                                .filter(m.seams.ready()::contains)
                                .filter(node -> !m.seams.draining()
                                                        .contains(node))
                                .filter(node -> Rows.counts(m, node))
                                .toList());
    }

    /// Workers of ACTIVE communities that are placeable members of the community's roster, ready and not draining.
    static List<NodeId> mainWorkers(ClusterModel m) {
        return Rows.byId(workersByCommunity(m).values()
                                           .stream()
                                           .flatMap(List::stream)
                                           .distinct()
                                           .filter(m.seams.ready()::contains)
                                           .filter(node -> !m.seams.draining()
                                                                   .contains(node))
                                           .toList());
    }

    /// The live half of the community planner (B-076): the placeable members of every ACTIVE community's roster.
    static Map<String, List<NodeId>> workersByCommunity(ClusterModel m) {
        var out = new HashMap<String, List<NodeId>>();

        m.communities.entries()
                     .stream()
                     .filter(entry -> entry.getValue()
                                           .state() == CommunityState.ACTIVE)
                     .sorted(Comparator.comparing(Map.Entry::getKey))
                     .forEach(entry -> placeable(m,
                                                 entry.getKey()).onPresent(members -> out.put(entry.getKey(),
                                                                                              members)));

        return Map.copyOf(out);
    }

    private static Option<List<NodeId>> placeable(ClusterModel m, String community) {
        return m.rosters.get(community)
                        .map(roster -> roster.members()
                                             .isEmpty()
                                       ? List.of(roster.governorId())
                                       : roster.members())
                        .map(members -> members.stream()
                                               .filter(node -> Rows.recordedWorker(m, node) && Rows.counts(m, node))
                                               .distinct()
                                               .toList())
                        .filter(members -> !members.isEmpty());
    }

    static List<NodeId> audience(ClusterModel m, PlacementPolicy policy) {
        var cores = cores(m);
        var workers = mainWorkers(m);

        return switch (policy) {
            case CORE_ONLY -> cores;
            case WORKERS_PREFERRED -> workers.isEmpty()
                                      ? cores
                                      : workers;
            case WORKERS_ONLY -> workers;
            case ALL -> Rows.byId(Stream.concat(cores.stream(), workers.stream()).distinct().toList());
        };
    }

    // -- load ------------------------------------------------------------------------------------------------------
    /// The number of instances (goal LOADED or ACTIVE, committed or in flight) a node holds across all artifacts.
    static int load(ClusterModel m, NodeId node) {
        var artifacts = new HashSet<Artifact>(Idx.members(m.desiredByNode, node).toList());

        artifacts.addAll(Idx.members(m.pendingByNode, node).toList());

        return (int) artifacts.stream()
                              .filter(artifact -> Rows.wantsInstance(m,
                                                                     SlicePair.slicePair(node, artifact)))
                              .count();
    }

    // -- scale up and down -----------------------------------------------------------------------------------------
    /// R-3.3.7: a node whose transient failure is still within its retry delay is not chosen again yet.
    private static boolean backedOff(ClusterModel m, Ctx ctx, SlicePair pair) {
        return m.retryAt.get(pair)
                        .map(at -> at > ctx.monoNs())
                        .or(false);
    }

    private static ClusterModel scaleUp(ClusterModel m, Ctx ctx, Artifact artifact, List<NodeId> audience, int toAdd) {
        audience.stream()
                .filter(node -> !Rows.occupies(m,
                                               SlicePair.slicePair(node, artifact)))
                .filter(node -> Rows.countsForPlacement(m, node, artifact))
                .filter(node -> !backedOff(m,
                                           ctx,
                                           SlicePair.slicePair(node, artifact)))
                .sorted(Comparator.<NodeId, Integer> comparing(node -> load(m, node)).thenComparing(Comparator.naturalOrder()))
                .limit(toAdd)
                .toList()
                .forEach(node -> Outbox.goal(m,
                                             SlicePair.slicePair(node, artifact),
                                             SliceGoal.LOADED,
                                             GoalMode.FRESH,
                                             Option.none(),
                                             "place"));

        return m;
    }

    /// Victims come from the most-loaded node first, ties by NodeId, and never below `minInstances` (B-032).
    private static ClusterModel scaleDown(ClusterModel m, List<SlicePair> placed, int toRemove, int minInstances) {
        var count = Math.min(toRemove,
                             Math.max(0, placed.size() - minInstances));

        placed.stream()
              .sorted(Comparator.<SlicePair, Integer> comparing(pair -> load(m,
                                                                             pair.node()))
                                .reversed()
                                .thenComparing(pair -> pair.node()
                                                           .id()))
              .limit(count)
              .toList()
              .forEach(pair -> Outbox.goal(m,
                                           pair,
                                           SliceGoal.UNLOADED,
                                           GoalMode.FRESH,
                                           Option.none(),
                                           "scale-down"));

        return m;
    }

    /// B-033: instances on counting nodes outside the audience are retired only once the instances inside it are ACTIVE.
    /// Returns whether any displaced instance exists.
    private static boolean retireDisplaced(ClusterModel m, Artifact artifact, Set<NodeId> eligible, int desired) {
        var instances = Rows.allInstances(m, artifact);
        var displaced = instances.stream()
                                 .filter(pair -> !eligible.contains(pair.node()) && Rows.counts(m,
                                                                                                pair.node()))
                                 .toList();

        if (displaced.isEmpty()) {
            return false;
        }

        var active = instances.stream()
                              .filter(pair -> eligible.contains(pair.node()))
                              .filter(pair -> Rows.current(m, pair)
                                                  .map(row -> row.state() == SliceObservedState.ACTIVE)
                                                  .or(false))
                              .count();

        if (active >= desired) {
            displaced.forEach(pair -> Outbox.goal(m,
                                                  pair,
                                                  SliceGoal.UNLOADED,
                                                  GoalMode.FRESH,
                                                  Option.none(),
                                                  "displaced"));
        }

        return true;
    }

    /// Whether the community is allowed to take placements (R-3.3.4).
    static boolean communityAccepts(CommunityValue community) {
        return community.state() == CommunityState.ACTIVE;
    }

    record unused() implements Placement {}
}
