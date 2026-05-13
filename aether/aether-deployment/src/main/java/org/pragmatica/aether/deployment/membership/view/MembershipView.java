// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.view;

import org.pragmatica.aether.slice.kvstore.AetherKey.NodeLifecycleKey;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;
import org.pragmatica.swim.HealthSnapshot;
import org.pragmatica.swim.SwimHealth;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
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

    /// Wiring-time factory. Pure function: callers provide a `SwimHealthProvider` (typically
    /// the local SWIM detector) and a `LifecycleKvReader` (typically the consensus
    /// KVStore). The returned view recomputes on every `snapshot()` call — no caching, no
    /// background refresh, no event subscriptions. This is the I7 (event-driven) realisation
    /// of "membership derives from inputs at read time."
    static MembershipView membershipView(SwimHealthProvider swimHealth, LifecycleKvReader lifecycleKv) {
        return new MembershipViewImpl(swimHealth, lifecycleKv);
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

        private MembershipViewImpl(SwimHealthProvider swimHealth, LifecycleKvReader lifecycleKv) {
            this.swimHealth = swimHealth;
            this.lifecycleKv = lifecycleKv;
        }

        @Override
        public Map<NodeId, MemberView> snapshot() {
            var lifecycleByPeer = collectLifecycleEntries();
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var view = new HashMap<NodeId, MemberView>();
            lifecycleByPeer.forEach((peer, lifecycle) -> view.put(peer, deriveFromKv(peer, lifecycle, swim)));
            swim.peerHealth().forEach((peer, swimState) -> view.computeIfAbsent(peer,
                                                                                 _ -> deriveFromSwimOnly(peer, swimState)));
            return Map.copyOf(view);
        }

        @Override
        public Option<MemberView> get(NodeId peer) {
            var lifecycle = readLifecycleFor(peer);
            var swim = swimHealth.get().or(HealthSnapshot.healthSnapshot(Map.of()));
            var swimState = swim.healthOf(peer).or(SwimHealth.UNKNOWN);
            if (lifecycle.isPresent()) {
                return Option.some(deriveFromKv(peer, lifecycle.unwrap(), swim));
            }
            if (swimState == SwimHealth.HEALTHY) {
                return Option.some(new MemberView(peer, MemberStatus.ON_DUTY, swimState, Option.none()));
            }
            return Option.none();
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

        private static MemberView deriveFromKv(NodeId peer, NodeLifecycleValue lifecycle, HealthSnapshot swim) {
            var swimState = swim.healthOf(peer).or(SwimHealth.UNKNOWN);
            var status = mapKvState(lifecycle.state(), swimState);
            return new MemberView(peer, status, swimState, Option.some(lifecycle));
        }

        private static MemberView deriveFromSwimOnly(NodeId peer, SwimHealth swimState) {
            var status = swimState == SwimHealth.HEALTHY ? MemberStatus.ON_DUTY : MemberStatus.UNTRACKED;
            return new MemberView(peer, status, swimState, Option.none());
        }

        private static MemberStatus mapKvState(NodeLifecycleState kvState, SwimHealth swimState) {
            return switch (kvState) {
                case JOINING -> MemberStatus.JOINING;
                case DRAINING, SHUTTING_DOWN -> MemberStatus.DRAINING;
                case DECOMMISSIONED -> MemberStatus.DECOMMISSIONED;
                case FAILED_DRAIN -> MemberStatus.FAILED_DRAIN;
                case ON_DUTY -> swimState == SwimHealth.HEALTHY
                                ? MemberStatus.ON_DUTY
                                : MemberStatus.UNTRACKED;
            };
        }
    }
}
