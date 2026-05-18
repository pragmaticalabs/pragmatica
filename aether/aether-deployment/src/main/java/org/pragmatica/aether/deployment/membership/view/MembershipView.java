// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;


/// **H-series structural refactor: SWIM is the single source of truth for "alive".**
///
/// Replaces the leader-driven `(UNTRACKED, SwimHealthy) → ON_DUTY` / `(ON_DUTY, SwimFaulty)
/// → DECOMMISSIONED` write pathway (`MembershipFsm`) with a derived view that combines:
///
/// 1. The local SWIM `HealthSnapshot` — authoritative for "is this peer currently reachable?"
/// 2. The consensus-replicated `NodeLifecycleKey` KV — operator-declared overrides
///    (`JOINING`, `DRAINING`, `DECOMMISSIONED`, `FAILED_DRAIN`) only.
///
/// **Why this design.** The pre-H model maintained four parallel stores of membership truth
/// (SWIM alive set, Rabia consensus group, NodeLifecycleKey ON_DUTY entries, MembershipFsm
/// in-memory shadow). Each fix during the G-series patched drift between two of them and
/// exposed the next: chaos kill produced revival storms (G.1), leader takeover stranded peers
/// (G.4), orphan replacements appeared as ON_DUTY-less ghosts. The structural fix is to
/// eliminate the redundant `ON_DUTY` KV-write store and compute the answer at read time.
///
/// **Rule set (per-peer, in order):**
///
/// - **JOINING / DRAINING / DECOMMISSIONED / FAILED_DRAIN in KV** → emit that state. Operator
///   intent overrides SWIM observation. A peer that SWIM still reports HEALTHY but KV says
///   DECOMMISSIONED is excluded from "operationally available" sets — operator wants it gone.
/// - **HEALTHY in SWIM, no KV entry (or KV says `ON_DUTY` from legacy writes)** → emit
///   `ON_DUTY`. This is the new bootstrap path: SWIM admission alone is sufficient. No
///   explicit `(UNTRACKED, SwimHealthy) → ON_DUTY` write is required.
/// - **FAULTY or UNKNOWN in SWIM with no KV entry** → peer is absent from the view. No need
///   to write `DECOMMISSIONED` to KV — the view simply stops including the peer.
/// - **HEALTHY in SWIM with KV `JOINING`** → still `JOINING` (operator/slot-provisioning
///   intermediate state — KV wins until JOINING is cleared by a downstream actor).
///
/// **RC1 Step 5 — quorum gating.** External readers of the view (HTTP routes, dashboard, CTM)
/// MUST NOT trust local SWIM + KV data while the node is non-quorate, otherwise a minority
/// partition would claim ON_DUTY peers it cannot actually direct work to. The
/// [#strict(SwimHealthProvider, LifecycleKvReader, BooleanSupplier)] factory enforces this:
/// when `inQuorum.getAsBoolean()` is `false`, every derived `ON_DUTY` status is rewritten to
/// `UNTRACKED`, `onDutyPeers()` returns the empty list, and `statusOf(peer)` returns
/// `UNTRACKED`. The quorum bit MUST be the same `AtomicBoolean` `TopologyObserver` mutates;
/// duplicating the source re-creates exactly the drift bug this gating exists to eliminate.
///
/// Bootstrap-internal callers that drive the cluster TOWARD quorum (notably the
/// `TopologyObserver` quorum-evaluation loop itself) must use
/// [#bootstrapAware(SwimHealthProvider, LifecycleKvReader)] instead, otherwise the
/// quorum-bit will never flip from `false` (no peers visible → no decisions emitted → no
/// quorum). The two-factory split makes the bypass auditable: grep for `bootstrapAware(` to
/// find every site that elects to read minority-state.
///
/// **Invariants preserved:**
///
/// - Single-writer for operator-declared states: only the leader writes
///   `NodeLifecycleKey` for the 4 override states. SWIM-driven `(ON_DUTY)` writes are not
///   produced by this layer — that's H.3's destructive cleanup. During the H.1/H.2 transition
///   window, the legacy `MembershipFsm` continues to write ON_DUTY entries; this view treats
///   them as benign (rule #2 says ON_DUTY with HEALTHY SWIM is still ON_DUTY).
/// - Leader takeover is trivial: the new leader's SWIM view already contains every alive
///   peer (SWIM detector survives consensus leadership churn — it's a local subsystem). No
///   re-observation / replay protocol is needed.
/// - Reconstructibility (I1, spec): the view is a pure function of two inputs that are
///   themselves reconstructible (SWIM state from gossip, KV from consensus replication).
public interface MembershipView {
    /// Always-quorate quorum supplier — equivalent to `bootstrapAware`. Used internally by
    /// the legacy [#membershipView(SwimHealthProvider, LifecycleKvReader)] factory and by
    /// tests that exercise pure-function semantics without quorum gating.
    BooleanSupplier ALWAYS_IN_QUORUM = () -> true;

    /// Snapshot of the cluster's effective membership at call time.
    ///
    /// Result is a flat map keyed by `NodeId`. Peers absent from the map are equivalent to
    /// `UNTRACKED` in the legacy `MembershipFsmState` vocabulary — neither SWIM has admitted
    /// them nor does KV carry an override entry.
    Map<NodeId, MemberView> snapshot();

    /// Single-peer lookup. Returns `Option.none()` for peers absent from the view.
    Option<MemberView> get(NodeId peer);

    /// Effective lifecycle state for a single peer, with `UNTRACKED` as the default for
    /// absent peers. Useful as a drop-in for callers that previously read
    /// `MembershipFsm.snapshot().get(peer)`.
    default MemberStatus statusOf(NodeId peer) {
        return get(peer).map(MemberView::status).or(MemberStatus.UNTRACKED);
    }

    /// All peers currently effective as `ON_DUTY`. Convenience for the common
    /// "count healthy cores" / "list active peers" call sites.
    default List<NodeId> onDutyPeers() {
        var list = new ArrayList<NodeId>();
        snapshot().forEach((peer, view) -> appendIfOnDuty(list, peer, view));
        return List.copyOf(list);
    }

    private static void appendIfOnDuty(List<NodeId> list, NodeId peer, MemberView view) {
        if (view.status() == MemberStatus.ON_DUTY) {
            list.add(peer);
        }
    }

    /// Per-peer view record. `swimHealth` is the raw SWIM observation (for diagnostics +
    /// audit-trail callers); `status` is the H-series derived state used by routing and
    /// CTM accounting.
    record MemberView(NodeId peer, MemberStatus status, SwimHealth swimHealth, Option<NodeLifecycleValue> lifecycle) {}

    /// Effective lifecycle states the H-series view recognises. Mirrors `NodeLifecycleState`
    /// for the 5 KV-replicated values; adds `UNTRACKED` (peer absent everywhere — legacy
    /// view callers expect this as the "default zero" state).
    enum MemberStatus {
        UNTRACKED,
        JOINING,
        ON_DUTY,
        DRAINING,
        DECOMMISSIONED,
        FAILED_DRAIN
    }

    /// **Strict factory — RC1 Step 5 default for every external reader.**
    ///
    /// Routes, dashboard, CTM, `/api/cluster/onduty` — all consume this variant. When the
    /// `inQuorum` supplier reports `false` the view forces every derived `ON_DUTY` to
    /// `UNTRACKED`: a minority-side node MUST NOT advertise on-duty peers that the majority
    /// has likely re-routed work past. Operator-declared override states (JOINING /
    /// DRAINING / DECOMMISSIONED / FAILED_DRAIN) survive — those reflect consensus-replicated
    /// operator intent that remains true regardless of the local node's quorum status.
    ///
    /// `inQuorum` MUST be backed by the same `AtomicBoolean` that `TopologyObserver` mutates
    /// (exposed via `TopologyObserver#inQuorum()`). Constructing a second quorum source here
    /// re-creates the bug class this gating exists to eliminate.
    static MembershipView strict(SwimHealthProvider swimHealth,
                                 LifecycleKvReader lifecycleKv,
                                 BooleanSupplier inQuorum) {
        return new MembershipViewImpl(swimHealth, lifecycleKv, inQuorum, Option::none);
    }

    /// Strict factory extended with a cluster-canonical reachability snapshot supplier.
    /// The snapshot acts as a SECOND confirmation source for `kvState == ON_DUTY` peers
    /// whose local SWIM hasn't yet reported HEALTHY: if a quorum of cluster observers
    /// (⌈N/2⌉+1) sees the peer as REACHABLE, the effective status is `ON_DUTY` despite
    /// local SWIM lag. Snapshot is consulted ONLY for the ON_DUTY case — non-ON_DUTY
    /// lifecycle states (JOINING/DRAINING/etc.) are unaffected. Snapshot `UNREACHABLE`
    /// downgrades to `UNTRACKED` (transport-honesty). See
    /// `aether/docs/specs/reachability-aggregator-spec.md` Layer 5.
    static MembershipView strict(SwimHealthProvider swimHealth,
                                 LifecycleKvReader lifecycleKv,
                                 BooleanSupplier inQuorum,
                                 Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshot) {
        return new MembershipViewImpl(swimHealth, lifecycleKv, inQuorum, reachabilitySnapshot);
    }

    /// **Bootstrap-aware factory — internal use only.**
    ///
    /// Returns content (including ON_DUTY) even when non-quorate. Required by callers that
    /// drive the cluster TOWARD quorum: if `TopologyObserver` consults a strict view to
    /// decide whether to emit `MembershipDecision` deltas, the view returns empty → no
    /// deltas → quorum never establishes (deadlock). The bootstrap-aware variant breaks
    /// that circular dependency at the cost of leaking minority-state to its internal
    /// caller. Grep for `bootstrapAware(` to audit every site that opts out of quorum
    /// gating.
    static MembershipView bootstrapAware(SwimHealthProvider swimHealth, LifecycleKvReader lifecycleKv) {
        return new MembershipViewImpl(swimHealth, lifecycleKv, ALWAYS_IN_QUORUM, Option::none);
    }

    /// Legacy factory preserved for pure-function tests and the H.2b-era
    /// `ClusterPhaseView.legacyView` adapter. Equivalent to
    /// [#bootstrapAware(SwimHealthProvider, LifecycleKvReader)]: returns content regardless
    /// of quorum status. New external callers MUST use [#strict] instead.
    static MembershipView membershipView(SwimHealthProvider swimHealth, LifecycleKvReader lifecycleKv) {
        return bootstrapAware(swimHealth, lifecycleKv);
    }

    @FunctionalInterface
    interface SwimHealthProvider extends Supplier<Option<HealthSnapshot>> {}

    @FunctionalInterface
    interface LifecycleKvReader {
        void forEachLifecycle(BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer);
    }

    final class MembershipViewImpl implements MembershipView {
        private final SwimHealthProvider swimHealth;
        private final LifecycleKvReader lifecycleKv;
        private final BooleanSupplier inQuorum;
        private final Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshot;

        private MembershipViewImpl(SwimHealthProvider swimHealth,
                                   LifecycleKvReader lifecycleKv,
                                   BooleanSupplier inQuorum,
                                   Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshot) {
            this.swimHealth = swimHealth;
            this.lifecycleKv = lifecycleKv;
            this.inQuorum = inQuorum;
            this.reachabilitySnapshot = reachabilitySnapshot;
        }

        @Override
        public Map<NodeId, MemberView> snapshot() {
            var quorate = inQuorum.getAsBoolean();
            var snapshot = reachabilitySnapshot.get();
            var lifecycleByPeer = collectLifecycleEntries();
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var view = new HashMap<NodeId, MemberView>();
            lifecycleByPeer.forEach((peer, lifecycle) -> view.put(peer, deriveFromKv(peer, lifecycle, swim, quorate, snapshot)));
            swim.peerHealth().forEach((peer, swimState) -> view.computeIfAbsent(peer,
                                                                                 _ -> deriveFromSwimOnly(peer, swimState, quorate)));
            return Map.copyOf(view);
        }

        @Override
        public Option<MemberView> get(NodeId peer) {
            var quorate = inQuorum.getAsBoolean();
            var snapshot = reachabilitySnapshot.get();
            var lifecycle = readLifecycleFor(peer);
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var swimState = swim.healthOf(peer).or(SwimHealth.UNKNOWN);
            return lifecycle.map(value -> deriveFromKv(peer, value, swim, quorate, snapshot))
                            .orElse(() -> swimOnlyEntry(peer, swimState, quorate));
        }

        private static Option<MemberView> swimOnlyEntry(NodeId peer, SwimHealth swimState, boolean quorate) {
            if (swimState != SwimHealth.HEALTHY) {
                return Option.none();
            }
            var status = quorate ? MemberStatus.ON_DUTY : MemberStatus.UNTRACKED;
            return Option.some(new MemberView(peer, status, swimState, Option.none()));
        }

        private Option<NodeLifecycleValue> readLifecycleFor(NodeId peer) {
            var holder = new NodeLifecycleValue[1];
            lifecycleKv.forEachLifecycle((key, value) -> {
                if (key.nodeId().equals(peer)) {
                    holder[0] = value;
                }
            });
            return Option.option(holder[0]);
        }

        private Map<NodeId, NodeLifecycleValue> collectLifecycleEntries() {
            var map = new HashMap<NodeId, NodeLifecycleValue>();
            lifecycleKv.forEachLifecycle((key, value) -> map.put(key.nodeId(), value));
            return map;
        }

        private static MemberView deriveFromKv(NodeId peer,
                                               NodeLifecycleValue lifecycle,
                                               HealthSnapshot swim,
                                               boolean quorate,
                                               Option<AggregatedReachabilitySnapshot> reachabilitySnapshot) {
            var swimState = swim.healthOf(peer).or(SwimHealth.UNKNOWN);
            var status = mapKvState(peer, lifecycle.state(), swimState, quorate, reachabilitySnapshot);
            return new MemberView(peer, status, swimState, Option.some(lifecycle));
        }

        private static MemberView deriveFromSwimOnly(NodeId peer, SwimHealth swimState, boolean quorate) {
            var status = swimState == SwimHealth.HEALTHY && quorate
                         ? MemberStatus.ON_DUTY
                         : MemberStatus.UNTRACKED;
            return new MemberView(peer, status, swimState, Option.none());
        }

        private static MemberStatus mapKvState(NodeId peer,
                                               NodeLifecycleState kvState,
                                               SwimHealth swimState,
                                               boolean quorate,
                                               Option<AggregatedReachabilitySnapshot> reachabilitySnapshot) {
            return switch (kvState) {
                case JOINING -> MemberStatus.JOINING;
                case DRAINING, SHUTTING_DOWN -> MemberStatus.DRAINING;
                case DECOMMISSIONED -> MemberStatus.DECOMMISSIONED;
                case FAILED_DRAIN -> MemberStatus.FAILED_DRAIN;
                case ON_DUTY -> resolveOnDutyStatus(peer, swimState, quorate, reachabilitySnapshot);
            };
        }

        /// `kvState == ON_DUTY` requires confirmation. Resolution order:
        ///
        /// 1. **Non-quorate** → `UNTRACKED`. A minority-side reader MUST NOT advertise
        ///    ON_DUTY peers; the majority may have re-routed work past them.
        /// 2. **Local SWIM HEALTHY + quorate** → `ON_DUTY` (fast path; snapshot not consulted).
        /// 3. **Snapshot absent** (`Option.none()`) → `UNTRACKED`. Cold-start before the
        ///    first aggregator snapshot has been received; conservative.
        /// 4. **Snapshot present** → branch explicitly on the per-peer `ReachabilityKind`:
        ///    - `REACHABLE` → `ON_DUTY` (cluster-canonical quorum promotes despite SWIM lag).
        ///    - `UNREACHABLE` → `UNTRACKED` (transport-honest downgrade).
        ///    - `UNKNOWN` → `UNTRACKED` (see semantic note below).
        ///    - Peer absent from `states()` map → `UNTRACKED`.
        ///
        /// **UNKNOWN semantic — post-periodic-emission.** Prior to the periodic-observation
        /// refactor (reachability-aggregator-spec.md "Periodic Observation Mode"), observations
        /// were emitted only on QUIC transport transitions (ADD/EVICTED). On a steady-state
        /// cluster with no flaps, the aggregator's per-target observation buffers aged out and
        /// `UNKNOWN` simply meant "no transitions, no data" — it could equally describe a
        /// healthy stable cluster or an isolated peer. Downgrading UNKNOWN to UNTRACKED was
        /// therefore over-aggressive and only justified in conjunction with `Option.none()`.
        ///
        /// After the periodic-observation refactor, every node emits a `PeerConnectivityObservation`
        /// every 5s for every topology peer it considers reachable; observations live for 15s
        /// (3× period). A peer that produces `UNKNOWN` in the aggregated snapshot has therefore
        /// failed to attract a REACHABLE quorum across ≥3 emission cycles AND has not attracted
        /// an UNREACHABLE quorum either — i.e. it is **genuinely isolated** from this leader's
        /// observation set (cluster partition / network island / one-sided routing failure).
        /// Downgrading UNKNOWN to UNTRACKED is now the semantically correct projection, not a
        /// conservative guess. The combined effect is that UNKNOWN and `Option.none()` both
        /// reduce to UNTRACKED for the same operational reason: this reader has no positive
        /// evidence the peer is currently reachable.
        ///
        /// See `aether/docs/specs/membership-architecture-spec.md` §16 (S05/S06) and
        /// `aether/docs/specs/reachability-aggregator-spec.md` "Periodic Observation Mode".
        private static MemberStatus resolveOnDutyStatus(NodeId peer,
                                                        SwimHealth swimState,
                                                        boolean quorate,
                                                        Option<AggregatedReachabilitySnapshot> reachabilitySnapshot) {
            if (!quorate) {
                return MemberStatus.UNTRACKED;
            }
            if (swimState == SwimHealth.HEALTHY) {
                return MemberStatus.ON_DUTY;
            }
            return reachabilitySnapshot.fold(() -> MemberStatus.UNTRACKED,
                                              snap -> kindFromSnapshot(snap, peer));
        }

        /// Explicit projection of a per-peer `ReachabilityKind` into `MemberStatus`. Kept as a
        /// separate function so the three-way branch (REACHABLE / UNREACHABLE / UNKNOWN) is
        /// visible to readers rather than implicit in `snap.isReachable(peer)` returning false
        /// for both UNREACHABLE and UNKNOWN.
        private static MemberStatus kindFromSnapshot(AggregatedReachabilitySnapshot snap, NodeId peer) {
            var entry = snap.states().get(peer);
            if (entry == null) {
                return MemberStatus.UNTRACKED;
            }
            return projectKind(entry);
        }

        private static MemberStatus projectKind(ReachabilityState entry) {
            return switch (entry.kind()) {
                case REACHABLE -> MemberStatus.ON_DUTY;
                case UNREACHABLE -> MemberStatus.UNTRACKED;
                case UNKNOWN -> MemberStatus.UNTRACKED;
            };
        }
    }
}
