// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Comparator;
import java.util.List;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.deployment.cluster.fsm2.ClusterModel.GoalMode;
import org.pragmatica.aether.deployment.cluster.fsm2.TimerId.Kind;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceGoal;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Drain evictions (C18, reconcile step 4, B-060, B-072): a draining node's instances are replaced elsewhere, one at a time.
///
/// For the first instance on the node, a replacement is placed on the least-loaded eligible node that does not hold the
/// artifact; once an ACTIVE replacement exists the original gets goal UNLOADED (NT-41). The loop steps every
/// `drainStep` (2 s) until the node holds nothing, and is abandoned when the node is no longer draining. A drain report
/// that repeats while the loop runs starts nothing new (the loop is a set member).
public sealed interface Drains {
    static ClusterModel onDraining(ClusterModel m, Ctx ctx, NodeId node) {
        return start(m, ctx, node);
    }

    /// Step 4: abandon loops of nodes that stopped draining; start (or resume) one for every draining node without a loop.
    static ClusterModel resume(ClusterModel m, Ctx ctx) {
        m.drainLoops.toList()
                    .stream()
                    .filter(node -> !m.seams.draining()
                                            .contains(node))
                    .forEach(node -> abandon(m, ctx, node));
        m.seams.draining().stream().sorted().forEach(node -> start(m, ctx, node));

        return m;
    }

    private static ClusterModel start(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.drainLoops.contains(node) || !m.seams.draining().contains(node)) {
            return m;
        }

        m.drainLoops = m.drainLoops.add(node);

        return advance(m, ctx, node);
    }

    private static ClusterModel abandon(ClusterModel m, Ctx ctx, NodeId node) {
        m.drainLoops = m.drainLoops.remove(node);

        return Lifecycle.cancel(m, ctx, timer(node));
    }

    private static TimerId timer(NodeId node) {
        return TimerId.timerId(Kind.DRAIN_STEP, node.id());
    }

    static ClusterModel step(ClusterModel m, Ctx ctx, String subject) {
        var node = new NodeId(subject);

        return m.drainLoops.contains(node)
               ? advance(m, ctx, node)
               : m;
    }

    /// One step of the loop for `node`.
    private static ClusterModel advance(ClusterModel m, Ctx ctx, NodeId node) {
        if (!m.seams.draining().contains(node)) {
            return abandon(m, ctx, node);
        }

        var held = heldBy(m, node);

        if (held.isEmpty()) {
            m.drainLoops = m.drainLoops.remove(node);
            ctx.emit(new ClusterEffect.Log("Drain complete for node " + node.id()));

            return m;
        }

        evict(m, ctx, held.getFirst());

        return Lifecycle.schedule(m,
                                  ctx,
                                  timer(node),
                                  ctx.settings().timing().drainStep());
    }

    private static List<SlicePair> heldBy(ClusterModel m, NodeId node) {
        return Idx.members(m.desiredByNode, node)
                  .toList()
                  .stream()
                  .map(artifact -> SlicePair.slicePair(node, artifact))
                  .filter(pair -> Rows.wantsInstance(m, pair))
                  .sorted(Rows.PAIR_ORDER)
                  .toList();
    }

    private static ClusterModel evict(ClusterModel m, Ctx ctx, SlicePair original) {
        var artifact = original.artifact();

        if (hasActiveReplacement(m, artifact)) {
            Outbox.goal(m, original, SliceGoal.UNLOADED, GoalMode.FRESH, Option.none(), "drain");

            return m;
        }

        if (replacementPlaced(m, artifact)) {
            return m;
        }

        replacementNode(m, artifact).onPresent(node -> deployReplacement(m, ctx, artifact, node));

        return m;
    }

    private static boolean hasActiveReplacement(ClusterModel m, Artifact artifact) {
        return Idx.members(m.observedByArtifact, artifact)
                  .toList()
                  .stream()
                  .filter(node -> !m.seams.draining()
                                          .contains(node))
                  .map(node -> SlicePair.slicePair(node, artifact))
                  .filter(pair -> Rows.counts(m,
                                              pair.node()))
                  .anyMatch(pair -> Rows.current(m, pair)
                                        .map(row -> row.state() == SliceObservedState.ACTIVE)
                                        .or(false));
    }

    private static boolean replacementPlaced(ClusterModel m, Artifact artifact) {
        return Rows.allInstances(m, artifact)
                   .stream()
                   .anyMatch(pair -> !m.seams.draining()
                                             .contains(pair.node()) && Rows.counts(m,
                                                                                   pair.node()));
    }

    private static Option<NodeId> replacementNode(ClusterModel m, Artifact artifact) {
        return Placement.cores(m)
                        .stream()
                        .filter(node -> !Rows.occupies(m,
                                                       SlicePair.slicePair(node, artifact)))
                        .min(Comparator.<NodeId, Integer> comparing(node -> Placement.load(m, node)).thenComparing(Comparator.naturalOrder()))
                        .map(Option::some)
                        .orElse(Option.none());
    }

    private static ClusterModel deployReplacement(ClusterModel m, Ctx ctx, Artifact artifact, NodeId node) {
        var current = Rows.allInstances(m, artifact).size();

        Outbox.goal(m,
                    SlicePair.slicePair(node, artifact),
                    SliceGoal.LOADED,
                    GoalMode.FRESH,
                    Option.none(),
                    "drain-replacement");
        ctx.route(new RoutedEvent.ReconciliationAdjustment(artifact, current, current + 1));

        return m;
    }

    /// B-060: a blueprint with an instance on a draining node is not placed by the reconcile; the drain loop owns it.
    static boolean hasInstanceOnDrainingNode(ClusterModel m, Artifact artifact) {
        return Rows.allInstances(m, artifact)
                   .stream()
                   .anyMatch(pair -> m.seams.draining()
                                            .contains(pair.node()));
    }

    record unused() implements Drains {}
}
