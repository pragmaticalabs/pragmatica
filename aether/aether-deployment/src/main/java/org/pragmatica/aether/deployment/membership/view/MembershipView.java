// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot;
import org.pragmatica.cluster.metrics.AggregatedReachabilitySnapshot.ReachabilityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Contract;
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
/// → STOPPED` write pathway (`MembershipFsm`) with a derived view that combines:
///
/// 1. The local SWIM `HealthSnapshot` — authoritative for "is this peer currently reachable?"
/// 2. The consensus-replicated `NodeLifecycleKey` KV — operator-declared overrides
///    (`JOINING`, `DRAINING`, `STOPPED`) only.
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
/// - **JOINING / DRAINING / STOPPED in KV** → emit that state. Operator
///   intent overrides SWIM observation. A peer that SWIM still reports HEALTHY but KV says
///   STOPPED is excluded from "operationally available" sets — operator wants it gone.
/// - **HEALTHY in SWIM, no KV entry (or KV says `ON_DUTY` from legacy writes)** → emit
///   `ON_DUTY`. This is the new bootstrap path: SWIM admission alone is sufficient. No
///   explicit `(UNTRACKED, SwimHealthy) → ON_DUTY` write is required.
/// - **FAULTY or UNKNOWN in SWIM with no KV entry** → peer is absent from the view. No need
///   to write `STOPPED` to KV — the view simply stops including the peer.
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
        return get(peer).map(MemberView::status)
                  .or(MemberStatus.UNTRACKED);
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
    /// (post-step-I collapse) for the 4 KV-replicated values; adds `UNTRACKED` (peer absent
    /// everywhere — legacy view callers expect this as the "default zero" state). Pre-step-I
    /// `DECOMMISSIONED` / `FAILED_DRAIN` callers see `STOPPED` — the discriminator survives on
    /// `NodeLifecycleValue.stopReason()` (FORCED / GRACEFUL / DRAIN_FAILED).
    enum MemberStatus {
        UNTRACKED,
        JOINING,
        ON_DUTY,
        DRAINING,
        STOPPED
    }

    /// **Strict factory — RC1 Step 5 default for every external reader.**
    ///
    /// Routes, dashboard, CTM, `/api/cluster/onduty` — all consume this variant. When the
    /// `inQuorum` supplier reports `false` the view forces every derived `ON_DUTY` to
    /// `UNTRACKED`: a minority-side node MUST NOT advertise on-duty peers that the majority
    /// has likely re-routed work past. Operator-declared override states (JOINING /
    /// DRAINING / STOPPED) survive — those reflect consensus-replicated
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
        @Contract
        void forEachLifecycle(BiConsumer<NodeLifecycleKey, NodeLifecycleValue> consumer);
    }

    final class MembershipViewImpl implements MembershipView {
        private final SwimHealthProvider swimHealth;
        private final BooleanSupplier inQuorum;
        private final Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshot;

        /// RC1 membership-v2 step 1: `lifecycleKv` is accepted by the factories (callers in
        /// `AetherNode` + tests still pass the `NodeLifecycleKey` scanner) but no longer read —
        /// the view is now derived purely from SWIM. The parameter is retained for source
        /// compatibility until the `NodeLifecycleKey` atom itself is deleted in a later step.
        private MembershipViewImpl(SwimHealthProvider swimHealth,
                                   @SuppressWarnings("unused") LifecycleKvReader lifecycleKv,
                                   BooleanSupplier inQuorum,
                                   Supplier<Option<AggregatedReachabilitySnapshot>> reachabilitySnapshot) {
            this.swimHealth = swimHealth;
            this.inQuorum = inQuorum;
            this.reachabilitySnapshot = reachabilitySnapshot;
        }

        @Override
        public Map<NodeId, MemberView> snapshot() {
            var quorate = inQuorum.getAsBoolean();
            var snapshot = reachabilitySnapshot.get();
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var view = new HashMap<NodeId, MemberView>();
            swim.peerHealth().forEach((peer, swimState) -> view.put(peer,
                                                                    deriveFromSwim(peer, swimState, quorate, snapshot)));

            return Map.copyOf(view);
        }

        @Override
        public Option<MemberView> get(NodeId peer) {
            var quorate = inQuorum.getAsBoolean();
            var snapshot = reachabilitySnapshot.get();
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var swimState = swim.healthOf(peer).or(SwimHealth.UNKNOWN);

            return swimOnlyEntry(peer, swimState, quorate, snapshot);
        }

        private static Option<MemberView> swimOnlyEntry(NodeId peer,
                                                        SwimHealth swimState,
                                                        boolean quorate,
                                                        Option<AggregatedReachabilitySnapshot> reachabilitySnapshot) {
            if (swimState != SwimHealth.HEALTHY) {
                return Option.none();
            }

            return Option.some(deriveFromSwim(peer, swimState, quorate, reachabilitySnapshot));
        }

        /// RC1 membership-v2 step 1: derive purely from SWIM (+ aggregated reachability as a
        /// promoter). The `NodeLifecycleKey` KV-override path is dropped — SWIM is the single
        /// source of "alive", matching the intended v2 end state. A HEALTHY peer becomes
        /// `ON_DUTY` when quorate (subject to a cluster-canonical UNREACHABLE downgrade);
        /// every other SWIM observation is `UNTRACKED`. `lifecycle()` is always `none()`.
        private static MemberView deriveFromSwim(NodeId peer,
                                                 SwimHealth swimState,
                                                 boolean quorate,
                                                 Option<AggregatedReachabilitySnapshot> reachabilitySnapshot) {
            var status = resolveStatus(peer, swimState, quorate, reachabilitySnapshot);

            return new MemberView(peer, status, swimState, Option.none());
        }

        private static MemberStatus resolveStatus(NodeId peer,
                                                  SwimHealth swimState,
                                                  boolean quorate,
                                                  Option<AggregatedReachabilitySnapshot> reachabilitySnapshot) {
            return swimState == SwimHealth.HEALTHY
                   ? resolveOnDutyStatus(peer, swimState, quorate, reachabilitySnapshot)
                   : MemberStatus.UNTRACKED;
        }

        /// `swimState == HEALTHY` requires confirmation. Resolution order — the aggregated
        /// reachability snapshot is a **SECOND confirmation source** that PROMOTES, never
        /// DEMOTES on absence-of-information. The only demotion the snapshot can drive is an
        /// explicit cluster-canonical UNREACHABLE quorum.
        ///
        /// 1. **Non-quorate** → `UNTRACKED`. A minority-side reader MUST NOT advertise
        ///    ON_DUTY peers; the majority may have re-routed work past them.
        /// 2. **Local SWIM HEALTHY + quorate** → `ON_DUTY` (fast path; snapshot not consulted).
        /// 3. **Snapshot present + per-peer kind = UNREACHABLE** → `UNTRACKED`. The cluster has
        ///    voted with a quorum of UNREACHABLE observers; honour the transport-honest
        ///    downgrade.
        /// 4. **All other cases** (`Option.none()`, snapshot kind `REACHABLE`, snapshot kind
        ///    `UNKNOWN`, peer absent from `states()` map) → `ON_DUTY`. KV says ON_DUTY and
        ///    the snapshot has not produced a quorate negative vote, so fall back to the
        ///    KV-authoritative state. This is the cold-boot path that allows a 5-node cluster
        ///    to converge: followers' snapshot starts as `Option.none()`, the leader's
        ///    self-fold contributes only 1 observer (below the symmetric quorum threshold of
        ///    ⌈N/2⌉+1), so the snapshot returns `UNKNOWN` per target. Treating either as a
        ///    demotion strands the cluster in `UNTRACKED` for the duration of convergence —
        ///    see `reachability-aggregator-spec.md` Layer 5 and the "Supersession note
        ///    (2026-05-18)" describing why `UNKNOWN` is the steady-state on a stable cluster.
        ///
        /// **Why snapshot is a PROMOTER, not a GATE.** The spec contract (Layer 5) is: when
        /// SWIM has not yet reported HEALTHY for a `kvState == ON_DUTY` peer, the snapshot
        /// provides cluster-canonical evidence to either confirm reachability (REACHABLE → keep
        /// ON_DUTY) or override it (UNREACHABLE → demote). Absence of evidence
        /// (`Option.none()` / `UNKNOWN` / peer-missing) is **non-information** — it cannot
        /// override the KV-authoritative state, otherwise cold-boot convergence deadlocks.
        ///
        /// See `aether/docs/specs/membership-architecture-spec.md` §16 (S05/S06) and
        /// `aether/docs/specs/reachability-aggregator-spec.md` Layer 5.
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

            return reachabilitySnapshot.fold(() -> MemberStatus.ON_DUTY, snap -> kindFromSnapshot(snap, peer));
        }

        /// Explicit projection of a per-peer `ReachabilityKind` into `MemberStatus` for the
        /// `kvState == ON_DUTY + !SWIM.HEALTHY` case. Only `UNREACHABLE` demotes — every
        /// other outcome (REACHABLE, UNKNOWN, peer absent from states map) keeps the
        /// KV-authoritative `ON_DUTY`. See the doc-comment on
        /// [#resolveOnDutyStatus] for the spec-contract rationale.
        private static MemberStatus kindFromSnapshot(AggregatedReachabilitySnapshot snap, NodeId peer) {
            var entry = snap.states().get(peer);

            if (entry == null) {
                return MemberStatus.ON_DUTY;
            }

            return projectKind(entry);
        }

        private static MemberStatus projectKind(ReachabilityState entry) {
            return switch (entry.kind()) {
                case REACHABLE -> MemberStatus.ON_DUTY;
                case UNREACHABLE -> MemberStatus.UNTRACKED;
                case UNKNOWN -> MemberStatus.ON_DUTY;
            };
        }
    }
}
