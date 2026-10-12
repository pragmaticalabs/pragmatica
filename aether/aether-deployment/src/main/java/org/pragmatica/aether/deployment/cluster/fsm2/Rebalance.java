// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Core load levelling (B-061): when the instance counts of the allocatable cores differ by more than one for the
/// hysteresis, one instance of an artifact moves from its most-loaded host to the least-loaded node that does not host it.
/// One move per reconcile pass, and only a core host can donate: a rebalance never pulls a placement off a worker.
public sealed interface Rebalance {
    static ClusterModel consider(ClusterModel m, Ctx ctx, Artifact artifact, List<NodeId> allocatable) {
        var hosts = Rows.allInstances(m, artifact).stream().map(SlicePair::node).collect(Collectors.toSet());

        if (hosts.isEmpty() || allocatable.size() < 2) {
            return level(m, artifact);
        }

        var maxLoad = allocatable.stream().mapToInt(node -> Placement.load(m, node)).max().orElse(0);
        var minLoad = allocatable.stream().mapToInt(node -> Placement.load(m, node)).min().orElse(0);

        if (maxLoad - minLoad <= 1) {
            return level(m, artifact);
        }

        var donor = Option.from(allocatable.stream()
                                           .filter(hosts::contains)
                                           .max(Comparator.<NodeId, Integer> comparing(node -> Placement.load(m, node)).thenComparing(Comparator.<NodeId> naturalOrder().reversed())));
        var target = Option.from(allocatable.stream()
                                            .filter(node -> !hosts.contains(node))
                                            .min(Comparator.<NodeId, Integer> comparing(node -> Placement.load(m, node)).thenComparing(Comparator.naturalOrder())));

        return donor.flatMap(from -> target.filter(to -> Placement.load(m, from) - Placement.load(m, to) > 1)
                                           .map(to -> move(m, ctx, artifact, from, to)))
                    .or(() -> level(m, artifact));
    }

    private static ClusterModel level(ClusterModel m, Artifact artifact) {
        m.imbalancedTicks = m.imbalancedTicks.remove(artifact);

        return m;
    }

    /// Hysteresis: the imbalance must persist for the configured number of ticks, and the pass must have budget left.
    private static ClusterModel move(ClusterModel m, Ctx ctx, Artifact artifact, NodeId donor, NodeId target) {
        var ticks = m.imbalancedTicks.get(artifact).or(0) + 1;

        if (ticks < ctx.settings().timing().rebalanceHysteresisTicks()) {
            m.imbalancedTicks = m.imbalancedTicks.put(artifact, ticks);

            return m;
        }

        if (!ctx.takeRebalanceBudget()) {
            return m;
        }

        level(m, artifact);
        Outbox.goal(m,
                    SlicePair.slicePair(target, artifact),
                    SliceGoal.LOADED,
                    GoalMode.FRESH,
                    Option.none(),
                    "rebalance");

        return Outbox.goal(m,
                           SlicePair.slicePair(donor, artifact),
                           SliceGoal.UNLOADED,
                           GoalMode.FRESH,
                           Option.none(),
                           "rebalance");
    }

    record unused() implements Rebalance {}
}
