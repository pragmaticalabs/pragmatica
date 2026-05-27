// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;


/// φ-warmth predicate consulted by the reducer at the `(ON_DUTY, SwimFaulty)` cell (issue #231,
/// leader-side φ-accrual handoff). Replaces the former `ReachabilityGate` aggregator-quorum gate.
///
/// **Why the quorum gate is gone.** After the distributed control-plane removal the leader is the
/// SOLE prober — only the leader sends ClusterSync pings and receives pongs — and the SOLE
/// lifecycle writer. There is no second observer to disagree with, so the cluster-wide
/// `ReachabilityAggregator` quorum snapshot is no longer the right abstraction for liveness. The
/// leader's own per-peer pong stream IS the observation, and φ-accrual on that stream IS the
/// debounce. One observer needs no quorum — silence measured at the leader is authoritative.
///
/// **The warmth handoff.** φ-accrual can only detect a "regularly-heard → silent" transition: it
/// needs `minSamples` inter-arrival intervals before φ reflects real surprisal rather than the
/// warmup floor. A never-warmed peer is invisible to φ. So liveness ownership is split per-peer on
/// warmth:
///   - φ COLD for `peer` (unknown, or still in warmup) → φ has nothing to say → SWIM owns the
///     death decision, exactly as before this change. A `SwimFaulty` on a cold peer decommissions.
///   - φ WARM for `peer` → φ owns liveness. A `SwimFaulty` while φ still hears the peer's pongs is
///     treated as a SWIM false-positive and nopped — the peer is ponging, so it is alive. Once the
///     peer truly goes silent, φ saturates past Φ_evict and the leader-local detector (PhiObserver)
///     issues the `ForceDecommission` command directly; the reducer cell does not need to fire.
///
/// **Cell scope.** Consulted ONLY at `(ON_DUTY, SwimFaulty)`. The `(ON_DUTY, TransportUnreachable)`
/// cell is UNGATED — a closed QUIC channel is a definitive, non-flapping signal that needs no
/// warmth check. `(ON_DUTY, SwimDeparted)` is also unconditional (explicit leave). The 30s
/// `OnDutyFaulty` reconciler remains the backstop for any death the live path misses.
///
/// **Purity preservation.** Passed as a parameter to `reducer.apply(...)`, never a field — the
/// reducer stays a pure `(state, event, warmth) → Outcome` function.
@FunctionalInterface
public interface PhiWarmth {
    /// `true` when φ is meaningful (warm) for `peer` — the per-peer pong window holds at least
    /// `minSamples` inter-arrival intervals, so φ reflects real surprisal and owns liveness for
    /// `peer`. `false` when φ is cold (unknown peer, or still in warmup), in which case SWIM owns
    /// the death decision.
    boolean isWarm(NodeId peer);

    /// φ has no data for any peer → SWIM owns liveness everywhere. This is the drop-in replacement
    /// for the former `ReachabilityGate.ALWAYS_CONFIRMED`: `COLD + SwimFaulty` decommissions,
    /// matching the pre-handoff `ALWAYS_CONFIRMED + SwimFaulty → STOPPED` behavior. Used by the
    /// command path (commands never consult φ) and by tests asserting the SWIM-owns branch.
    PhiWarmth COLD = peer -> false;

    /// φ is warm for every peer → φ owns liveness everywhere. Used by tests asserting the
    /// φ-owns/nop branch (a SWIM-faulty-but-still-ponging peer survives).
    PhiWarmth WARM = peer -> true;
}
