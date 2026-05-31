// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


/// **H-series structural refactor: SWIM is the single source of truth for "alive".**
///
/// Replaces the leader-driven membership-FSM write pathway with a view derived purely from the
/// local SWIM `HealthSnapshot` — authoritative for "is this peer currently reachable?".
///
/// **Why this design.** The pre-v2 model maintained four parallel stores of membership truth
/// (SWIM alive set, Rabia consensus group, a node-lifecycle KV atom, an in-memory FSM shadow).
/// Each fix patched drift between two of them and exposed the next: chaos kill produced revival
/// storms, leader takeover stranded peers, orphan replacements appeared as ghosts. The
/// structural fix — completed in the membership-v2 finale — is to delete the redundant
/// node-lifecycle KV store entirely and compute the answer at read time from SWIM.
///
/// **Rule set (per-peer):** membership collapsed to pure presence. A peer is **present** iff it
/// is HEALTHY in SWIM and the local node is quorate; otherwise it is **absent**. The synthetic
/// lifecycle layer is gone — the only real node work-state is `NodeReportedState` (SYNCING /
/// READY / DRAINING), reported on the metrics pong and surfaced separately by the routes that
/// need it.
///
/// **RC1 Step 5 — quorum gating.** External readers of the view (HTTP routes, dashboard, CTM)
/// MUST NOT trust local SWIM data while the node is non-quorate, otherwise a minority
/// partition would claim present peers it cannot actually direct work to. The
/// [#strict(SwimHealthProvider, BooleanSupplier)] factory enforces this: when
/// `inQuorum.getAsBoolean()` is `false`, every peer is reported absent, `presentMembers()`
/// returns the empty set, and `isPresent(peer)` returns `false`. The quorum bit MUST be the
/// same `AtomicBoolean` `TopologyObserver` mutates; duplicating the source re-creates exactly
/// the drift bug this gating exists to eliminate.
///
/// Bootstrap-internal callers that drive the cluster TOWARD quorum (notably the
/// `TopologyObserver` quorum-evaluation loop itself) must use
/// [#bootstrapAware(SwimHealthProvider)] instead, otherwise the quorum-bit will never flip
/// from `false` (no peers visible → no decisions emitted → no quorum). The two-factory split
/// makes the bypass auditable: grep for `bootstrapAware(` to find every site that elects to
/// read minority-state.
///
/// **Invariants preserved:**
///
/// - Leader takeover is trivial: the new leader's SWIM view already contains every alive
///   peer (SWIM detector survives consensus leadership churn — it's a local subsystem). No
///   re-observation / replay protocol is needed.
/// - Reconstructibility (I1, spec): the view is a pure function of SWIM state, itself
///   reconstructible from gossip.
public interface MembershipView {
    /// Always-quorate quorum supplier — equivalent to `bootstrapAware`. Used internally by
    /// the [#membershipView(SwimHealthProvider)] factory and by tests that exercise
    /// pure-function semantics without quorum gating.
    BooleanSupplier ALWAYS_IN_QUORUM = () -> true;

    /// Snapshot of the cluster's effective membership at call time.
    ///
    /// Result is a flat map keyed by `NodeId` containing only **present** peers. Peers absent
    /// from the map are absent from the view — SWIM has not admitted them (or the local node is
    /// non-quorate).
    Map<NodeId, MemberView> snapshot();

    /// Single-peer lookup. Returns `Option.none()` for peers absent from the view.
    Option<MemberView> get(NodeId peer);

    /// `true` iff the peer is present (HEALTHY in SWIM and the local node is quorate). Default
    /// drop-in for callers that previously gated on per-peer presence.
    default boolean isPresent(NodeId peer) {
        return get(peer).isPresent();
    }

    /// All peers currently present. Convenience for the common "count healthy cores" /
    /// "list active peers" call sites.
    default Set<NodeId> presentMembers() {
        return snapshot().keySet();
    }

    /// Per-peer view record. `swimHealth` is the raw SWIM observation (for diagnostics +
    /// audit-trail callers). SWIM is the single source of truth — the node-lifecycle KV atom
    /// was deleted in the membership-v2 finale, so there is no per-peer KV override component
    /// anymore. A peer present in the view IS present in the cluster — there is no separate
    /// status axis.
    record MemberView(NodeId peer, SwimHealth swimHealth) {}

    /// **Strict factory — RC1 Step 5 default for every external reader.**
    ///
    /// Routes, dashboard, CTM, `/api/cluster/onduty` — all consume this variant. When the
    /// `inQuorum` supplier reports `false` the view reports every peer absent: a minority-side
    /// node MUST NOT advertise present peers that the majority has likely re-routed work past.
    ///
    /// `inQuorum` MUST be backed by the same `AtomicBoolean` that `TopologyObserver` mutates
    /// (exposed via `TopologyObserver#inQuorum()`). Constructing a second quorum source here
    /// re-creates the bug class this gating exists to eliminate.
    static MembershipView strict(SwimHealthProvider swimHealth,
                                 BooleanSupplier inQuorum) {
        return new MembershipViewImpl(swimHealth, inQuorum);
    }

    /// **Bootstrap-aware factory — internal use only.**
    ///
    /// Returns content (present peers) even when non-quorate. Required by callers that
    /// drive the cluster TOWARD quorum: if `TopologyObserver` consults a strict view to
    /// decide whether to emit `MembershipDecision` deltas, the view returns empty → no
    /// deltas → quorum never establishes (deadlock). The bootstrap-aware variant breaks
    /// that circular dependency at the cost of leaking minority-state to its internal
    /// caller. Grep for `bootstrapAware(` to audit every site that opts out of quorum
    /// gating.
    static MembershipView bootstrapAware(SwimHealthProvider swimHealth) {
        return new MembershipViewImpl(swimHealth, ALWAYS_IN_QUORUM);
    }

    /// Legacy factory preserved for pure-function tests and the
    /// `ClusterPhaseView.legacyView` adapter. Equivalent to
    /// [#bootstrapAware(SwimHealthProvider)]: returns content regardless
    /// of quorum status. New external callers MUST use [#strict] instead.
    static MembershipView membershipView(SwimHealthProvider swimHealth) {
        return bootstrapAware(swimHealth);
    }

    @FunctionalInterface
    interface SwimHealthProvider extends Supplier<Option<HealthSnapshot>> {}

    final class MembershipViewImpl implements MembershipView {
        private final SwimHealthProvider swimHealth;
        private final BooleanSupplier inQuorum;

        /// RC1 membership-v2 finale: the node-lifecycle KV atom is deleted; the view is
        /// derived purely from SWIM.
        private MembershipViewImpl(SwimHealthProvider swimHealth,
                                   BooleanSupplier inQuorum) {
            this.swimHealth = swimHealth;
            this.inQuorum = inQuorum;
        }

        @Override
        public Map<NodeId, MemberView> snapshot() {
            var quorate = inQuorum.getAsBoolean();
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var view = new HashMap<NodeId, MemberView>();
            swim.peerHealth().forEach((peer, swimState) -> putIfPresent(view, peer, swimState, quorate));

            return Map.copyOf(view);
        }

        private static void putIfPresent(Map<NodeId, MemberView> view,
                                         NodeId peer,
                                         SwimHealth swimState,
                                         boolean quorate) {
            if (isPresent(swimState, quorate)) {
                view.put(peer, new MemberView(peer, swimState));
            }
        }

        @Override
        public Option<MemberView> get(NodeId peer) {
            var quorate = inQuorum.getAsBoolean();
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var swimState = swim.healthOf(peer).or(SwimHealth.UNKNOWN);

            return isPresent(swimState, quorate)
                   ? Option.some(new MemberView(peer, swimState))
                   : Option.none();
        }

        /// RC1 membership-v2 finale: presence is derived purely from SWIM. A peer is present
        /// iff it is HEALTHY in SWIM and the local node is quorate.
        private static boolean isPresent(SwimHealth swimState, boolean quorate) {
            return swimState == SwimHealth.HEALTHY && quorate;
        }
    }
}
