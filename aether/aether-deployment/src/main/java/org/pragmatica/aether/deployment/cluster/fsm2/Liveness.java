// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm2;

import java.util.Map;
import java.util.Set;

import org.pragmatica.consensus.NodeId;


/// Core liveness evidence (spec R-2.11 (b), D-4 (ii), G-4): a core listed **continuously** in this leader's core SWIM view
/// (`coreView`, CI-15c) for one full suspicion window, counted from the leader's own observation, is joined. The `Rebuild`
/// snapshot's view is sampled at `Rebuild` and only starts the clock; it never supersedes by itself.
///
/// - **Window**: `AbsenceBounds#coreSuspicion` `[guess: one window, per the ruling]`.
/// - **Pending** (G-4): a departed core that `coreView` lists is liveness-pending, and its C17b is deferred until the window
///   resolves: positively when the window completes (the core becomes joined, so the record is superseded and rewritten), negatively
///   when the core leaves `coreView` or a removal input arrives.
/// - **With `coreView = none`** (unwired) nothing is tracked and nothing is pending (L-18 fail-safe: the backstop and the deferral
///   are no-ops).
public sealed interface Liveness {
    /// Sample the view and promote the cores whose window is complete. Called on a seam refresh, on `Rebuild` and on every tick.
    static ClusterModel refresh(ClusterModel m, Ctx ctx) {
        return promote(m.seams.coreView().fold(() -> unwired(m), view -> track(m, ctx, view)),
                       ctx);
    }

    /// Design-out: with no view there is no listing to measure, so the clocks and the vetoes are dropped with it.
    private static ClusterModel unwired(ClusterModel m) {
        m.livenessSince = PMap.empty(m.scans);
        m.livenessVetoed = PSet.empty(m.scans);

        return m;
    }

    private static ClusterModel track(ClusterModel m, Ctx ctx, Set<NodeId> view) {
        view.stream()
            .sorted()
            .filter(node -> !m.livenessSince.containsKey(node) && !m.livenessVetoed.contains(node))
            .forEach(node -> m.livenessSince = m.livenessSince.put(node,
                                                                   ctx.monoNs()));
        m.livenessSince.keys()
                       .stream()
                       .filter(node -> !view.contains(node))
                       .forEach(node -> m.livenessSince = m.livenessSince.remove(node));
        m.livenessVetoed.toList()
                        .stream()
                        .filter(node -> !view.contains(node))
                        .forEach(node -> m.livenessVetoed = m.livenessVetoed.remove(node));

        return m;
    }

    private static ClusterModel promote(ClusterModel m, Ctx ctx) {
        m.livenessSince.entries()
                       .stream()
                       .sorted(Map.Entry.comparingByKey())
                       .filter(entry -> ctx.monoNs() - entry.getValue() >= ctx.settings()
                                                                              .bounds()
                                                                              .coreSuspicion())
                       .map(Map.Entry::getKey)
                       .filter(node -> !m.joined.contains(node))
                       .forEach(node -> Joins.markJoined(m, ctx, node));

        return m;
    }

    /// A removal input (or a C17s submission) for `node` breaks its listing: the sample it came from is older than the removal, so
    /// the core accrues liveness again only after a sample that does not list it.
    static ClusterModel broken(ClusterModel m, NodeId node) {
        m.livenessSince = m.livenessSince.remove(node);
        if (m.seams.coreView().map(view -> view.contains(node)).or(false)) {
            m.livenessVetoed = m.livenessVetoed.add(node);
        }

        return m;
    }

    /// G-4: `node` is departed and this leader's view lists it, with its window unresolved. Only the removal of its rows waits.
    static boolean isPending(ClusterModel m, NodeId node) {
        return m.departed.contains(node)
               && !m.livenessVetoed.contains(node)
               && m.seams.coreView()
                         .map(view -> view.contains(node))
                         .or(false);
    }

    record unused() implements Liveness {}
}
