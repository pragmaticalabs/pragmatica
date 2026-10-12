// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.DepartureCause;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Absence, judged per node class (spec R-2.15, C9 to C11): the leader-local, monotonic measure `absentSince` for every
/// node that holds a directive or observed rows.
///
/// - **Core**: absent iff the core view is wired and does not list the node.
/// - **Assigned worker** (a WORKER directive naming community `c`): **exempt** when `c` is retiring (`targetSize == 0`) or
///   DISSOLVED; **unjudgeable** while `c`'s announcement is not fresh (R-2.14c) or the local alive supplier is unwired;
///   otherwise absent iff it is in no roster and not locally alive.
/// - **Unassigned worker**: the B-017 dual-signal rule: unjudgeable while no roster exists (`M` empty) or the local supplier
///   is unwired; otherwise absent iff in neither.
///
/// `absentSince[n]` is set on the first absent evaluation and cleared on any present, exempt or unjudgeable one. It is never
/// earlier than the leader's own activation. Nothing here removes anything.
public sealed interface Absence {
    String CORE = "core";
    String WORKER = "worker";

    /// The verdict of one evaluation.
    enum Verdict {
        PRESENT,
        ABSENT,
        EXEMPT,
        UNJUDGEABLE
    }

    /// The class of a node (R-2.15, "common to every class"): the directive's role, else membership.
    enum NodeClass {
        CORE,
        ASSIGNED_WORKER,
        UNASSIGNED_WORKER
    }

    static NodeClass classOf(ClusterModel m, NodeId node) {
        var directive = m.directives.get(node);

        if (directive.map(Rows::isCommunityWorker).or(false)) {
            return NodeClass.ASSIGNED_WORKER;
        }

        var core = directive.map(value -> ActivationDirectiveValue.CORE.equals(value.role())).or(false) || m.seams.countedCores()
                                                                                                                  .contains(node) || m.seams.coreView()
                                                                                                                                            .map(view -> view.contains(node))
                                                                                                                                            .or(false);

        return core
               ? NodeClass.CORE
               : NodeClass.UNASSIGNED_WORKER;
    }

    static String labelOf(NodeClass nodeClass) {
        return nodeClass == NodeClass.CORE
               ? CORE
               : WORKER;
    }

    static String communityOf(ClusterModel m, NodeId node) {
        return m.directives.get(node)
                           .map(ActivationDirectiveValue::communityId)
                           .or("");
    }

    // -- evaluation ------------------------------------------------------------------------------------------------
    static Verdict verdict(ClusterModel m, Ctx ctx, NodeId node) {
        return switch (classOf(m, node)) {
            case CORE -> coreVerdict(m, node);
            case ASSIGNED_WORKER -> assignedVerdict(m, ctx, node);
            case UNASSIGNED_WORKER -> unassignedVerdict(m, node);
        };
    }

    private static Verdict coreVerdict(ClusterModel m, NodeId node) {
        return m.seams.coreView()
                      .fold(() -> Verdict.UNJUDGEABLE,
                            view -> view.contains(node)
                                    ? Verdict.PRESENT
                                    : Verdict.ABSENT);
    }

    private static Verdict assignedVerdict(ClusterModel m, Ctx ctx, NodeId node) {
        var community = communityOf(m, node);

        if (isRetiringOrDissolved(m, community)) {
            return Verdict.EXEMPT;
        }

        if (!isFresh(m, ctx, community) || m.seams.localAlive().isEmpty()) {
            return Verdict.UNJUDGEABLE;
        }

        return inOwnRoster(m, community, node) || inOtherRoster(m, community, node) || locallyAlive(m, node)
               ? Verdict.PRESENT
               : Verdict.ABSENT;
    }

    private static Verdict unassignedVerdict(ClusterModel m, NodeId node) {
        if (m.rosterCount.isEmpty() || m.seams.localAlive().isEmpty()) {
            return Verdict.UNJUDGEABLE;
        }

        return m.rosterCount.containsKey(node) || locallyAlive(m, node)
               ? Verdict.PRESENT
               : Verdict.ABSENT;
    }

    private static boolean locallyAlive(ClusterModel m, NodeId node) {
        return m.seams.localAlive()
                      .map(alive -> alive.contains(node))
                      .or(false);
    }

    private static boolean inOwnRoster(ClusterModel m, String community, NodeId node) {
        return m.rosters.get(community)
                        .filter(roster -> !roster.dissolved())
                        .map(roster -> roster.members()
                                             .contains(node))
                        .or(false);
    }

    /// Any other non-dissolved roster that lists the node (conservative: freshness is not required of the other rosters).
    private static boolean inOtherRoster(ClusterModel m, String community, NodeId node) {
        var own = inOwnRoster(m, community, node)
                  ? 1
                  : 0;

        return m.rosterCount.get(node)
                            .or(0) - own > 0;
    }

    /// C11 (1), M8-1: retiring is `targetSize == 0`, dissolved is state DISSOLVED. DISSOLVING is not a key: main never writes it.
    static boolean isRetiringOrDissolved(ClusterModel m, String community) {
        return m.communities.get(community)
                            .map(Absence::retiringOrDissolved)
                            .or(false);
    }

    private static boolean retiringOrDissolved(CommunityValue community) {
        return community.targetSize() == 0 || community.state() == CommunityState.DISSOLVED;
    }

    /// R-2.14c: fresh iff this leader has seen a Put for the community in this incarnation, its age is below `S_ann`, and the
    /// announcement is not dissolved.
    static boolean isFresh(ClusterModel m, Ctx ctx, String community) {
        return announcementAgeNs(m, ctx, community).map(age -> age < ctx.settings()
                                                                        .bounds()
                                                                        .announcementFreshness())
                                .or(false) && m.rosters.get(community)
                                                       .map(roster -> !roster.dissolved())
                                                       .or(false);
    }

    static Option<Long> announcementAgeNs(ClusterModel m, Ctx ctx, String community) {
        return m.announcementSeenAt.get(community)
                                   .map(seen -> ctx.monoNs() - seen);
    }

    // -- bookkeeping -----------------------------------------------------------------------------------------------
    /// The nodes that hold a directive or observed rows (the scope of step 7).
    static Set<NodeId> candidates(ClusterModel m) {
        var nodes = new HashSet<NodeId>(m.directives.keys());

        nodes.addAll(m.observedByNode.keys());

        return nodes;
    }

    /// C11e: re-evaluate one node and update `absentSince`.
    static ClusterModel refresh(ClusterModel m, Ctx ctx, NodeId node) {
        if (m.departed.contains(node) || m.departing.containsKey(node)) {
            m.absentSince = m.absentSince.remove(node);

            return m;
        }

        switch (verdict(m, ctx, node)) {
            case ABSENT -> markAbsent(m, ctx, node);
            case UNJUDGEABLE -> unjudgeable(m, ctx, node);
            case PRESENT -> reappeared(m, ctx, node);
            case EXEMPT -> clear(m, node);
        }

        return m;
    }

    private static ClusterModel reappeared(ClusterModel m, Ctx ctx, NodeId node) {
        clear(m, node);

        return Joins.reappeared(m, ctx, node);
    }

    private static void markAbsent(ClusterModel m, Ctx ctx, NodeId node) {
        if (!m.absentSince.containsKey(node)) {
            m.absentSince = m.absentSince.put(node, ctx.monoNs());
        }
    }

    /// `worker-absence-unjudgeable`: once per Active incarnation per class (C10).
    private static void unjudgeable(ClusterModel m, Ctx ctx, NodeId node) {
        var label = labelOf(classOf(m, node));

        clear(m, node);
        if (!m.unjudgeableWarned.contains(label)) {
            m.unjudgeableWarned = m.unjudgeableWarned.add(label);
            ctx.warn("worker-absence-unjudgeable",
                     label,
                     "The " + label
                    + " class cannot be judged for absence on " + node.id()
                    + ": its signals are missing, stale or unwired");
        }
    }

    /// Every node of interest, on a seam refresh or a tick (O(nodes with a directive or rows)).
    static ClusterModel refreshAll(ClusterModel m, Ctx ctx) {
        candidates(m).stream().sorted().forEach(node -> refresh(m, ctx, node));

        return m;
    }

    static ClusterModel clear(ClusterModel m, NodeId node) {
        m.absentSince = m.absentSince.remove(node);

        return m;
    }

    /// The cause a recorded inference carries.
    static DepartureCause inferredCause() {
        return DepartureCause.STALE;
    }

    /// For metrics: absent node counts by class label.
    static Map<String, Long> absentCounts(ClusterModel m) {
        return m.absentSince.keys()
                            .stream()
                            .collect(java.util.stream.Collectors.groupingBy(node -> labelOf(classOf(m, node)),
                                                                            java.util.stream.Collectors.counting()));
    }

    record unused() implements Absence {}
}
