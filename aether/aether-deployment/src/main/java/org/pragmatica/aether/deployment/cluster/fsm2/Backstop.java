// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.deployment.cluster.fsm2.Absence.NodeClass;
import org.pragmatica.consensus.NodeId;


/// The departure backstop (spec reconcile step 7, C9 to C11, R-2.17): when a departure input is lost, the node class's own
/// signals imply it, after the class's own bound, and the FSM **records** it (C17s). It records and never removes: the rows
/// go only through C17b after the record commits, and the directive only through source-confirmed termination (R-4.3.2).
///
/// - A **core** absent from the core view for more than `B_rm`.
/// - An **assigned worker** of a community that is neither retiring nor dissolved, whose announcement is fresh, missing from
///   it and from the local alive view continuously for more than `B_w`, and whose community's breaker does not hold.
/// - An **unassigned worker** missing from every roster and from the local alive view for more than `B_w`.
///
/// **Community breaker** (R-2.17): when at least two workers of a community, and more than half of its members, are absent
/// at once, nothing is recorded for that community in this pass. The threshold is taste, not derived (marked guess).
public sealed interface Backstop {
    /// Step 7.
    static ClusterModel run(ClusterModel m, Ctx ctx) {
        Absence.refreshAll(m, ctx);
        announceBreakers(m, ctx);
        Absence.candidates(m)
               .stream()
               .sorted()
               .filter(node -> !m.departed.contains(node) && !m.departing.containsKey(node))
               .filter(node -> warrants(m, ctx, node))
               .forEach(node -> record(m, ctx, node));

        return m;
    }

    /// Whether step 7 would record `node` now: it has been absent for longer than its class's bound, and an assigned
    /// worker's community breaker does not hold. Used by the pass itself and by every C17s resubmission (R-4.3.0).
    static boolean warrants(ClusterModel m, Ctx ctx, NodeId node) {
        var since = m.absentSince.get(node);

        if (since.isEmpty() || Absence.verdict(m, ctx, node) != Absence.Verdict.ABSENT) {
            return false;
        }

        var nodeClass = Absence.classOf(m, node);

        return ctx.monoNs() - since.or(0L) > boundOf(ctx, nodeClass)
               && !(nodeClass == NodeClass.ASSIGNED_WORKER && breakerHolds(m, Absence.communityOf(m, node)));
    }

    private static long boundOf(Ctx ctx, NodeClass nodeClass) {
        return nodeClass == NodeClass.CORE
               ? ctx.settings()
                    .bounds()
                    .coreRemoval()
               : ctx.settings()
                    .bounds()
                    .workerRemoval();
    }

    /// R-2.17: at least 2 absent and more than half of the community's members.
    static boolean breakerHolds(ClusterModel m, String community) {
        var members = members(m, community);
        var absent = members.stream().filter(m.absentSince::containsKey).count();

        return absent >= 2 && absent * 2 > members.size();
    }

    private static List<NodeId> members(ClusterModel m, String community) {
        return Idx.members(m.assignedByCommunity, community)
                  .toList()
                  .stream()
                  .filter(node -> !m.departing.containsKey(node))
                  .toList();
    }

    /// `departure-inferred-suppressed`: once per community until the condition clears (R-2.17). The counter counts every
    /// recording the breaker held back (marked guess).
    private static ClusterModel announceBreakers(ClusterModel m, Ctx ctx) {
        m.assignedByCommunity.keys().stream().sorted().forEach(community -> announceBreaker(m, ctx, community));

        return m;
    }

    private static ClusterModel announceBreaker(ClusterModel m, Ctx ctx, String community) {
        return breakerHolds(m, community)
               ? warnOnce(countHeldBack(m, ctx, community), ctx, community)
               : clearWarned(m, community);
    }

    private static ClusterModel clearWarned(ClusterModel m, String community) {
        m.suppressedWarned = m.suppressedWarned.remove(community);

        return m;
    }

    private static ClusterModel countHeldBack(ClusterModel m, Ctx ctx, String community) {
        var held = members(m, community).stream().filter(node -> warrantsIgnoringBreaker(m, ctx, node)).count();

        if (held > 0) {
            ctx.metric("aether_deployment_fsm_departures_inferred_suppressed_total",
                       held,
                       Map.of("fsm", "cluster", "community", community));
        }

        return m;
    }

    /// Once per community until the breaker clears (R-2.17).
    private static ClusterModel warnOnce(ClusterModel m, Ctx ctx, String community) {
        if (m.suppressedWarned.contains(community)) {
            return m;
        }

        m.suppressedWarned = m.suppressedWarned.add(community);
        ctx.warn("departure-inferred-suppressed",
                 community,
                 "Community " + community
                + ": " + members(m, community).stream().filter(m.absentSince::containsKey).count()
                + " of " + members(m, community).size()
                + " members are absent at once; no departure is recorded for it");

        return m;
    }

    private static boolean warrantsIgnoringBreaker(ClusterModel m, Ctx ctx, NodeId node) {
        var since = m.absentSince.get(node);

        return since.map(at -> ctx.monoNs() - at > boundOf(ctx,
                                                           Absence.classOf(m, node)))
                    .or(false);
    }

    private static ClusterModel record(ClusterModel m, Ctx ctx, NodeId node) {
        var nodeClass = Absence.classOf(m, node);
        var since = m.absentSince.get(node).or(ctx.monoNs());
        var announced = nodeClass == NodeClass.ASSIGNED_WORKER
                        ? ", announcement age " + Absence.announcementAgeNs(m,
                                                                            ctx,
                                                                            Absence.communityOf(m, node))
                                                         .or(-1L)
                         + " ns"
                        : "";

        ctx.warn("departure-inferred",
                 node.id(),
                 "Recording " + node.id()
                + " (" + Absence.labelOf(nodeClass)
                + ") as departed: absent since " + since
                + " ns, bound " + boundOf(ctx, nodeClass)
                + " ns, expected record " + m.records.get(node) + announced);
        ctx.metric("aether_deployment_fsm_departures_inferred_total",
                   Map.of("fsm", "cluster", "class", Absence.labelOf(nodeClass)));
        DepartureFlow.infer(m, ctx, node);

        return m;
    }

    record unused() implements Backstop {}
}
