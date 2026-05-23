// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;


/// Topology-observation refactor Step 4 — aggregator-quorum gate consulted by the reducer
/// at the `(ON_DUTY, SwimFaulty)` and `(ON_DUTY, TransportUnreachable)` cells (spec §16
/// scenarios S04, S05, S13, S17).
///
/// **Purpose.** A single SWIM-faulty or transport-unreachable observation on an ON_DUTY peer
/// is no longer sufficient to drive a decommission write — the reducer first confirms that
/// the cluster-wide `ReachabilityAggregator` snapshot agrees that the peer is UNREACHABLE
/// with quorum. This converts brief flaps (S04), partitions seen by a single observer (S05),
/// and SWIM-only failures lacking transport corroboration (S13) into reducer nops.
///
/// **Cold-start fallback.** When no aggregator snapshot is available yet (e.g., the node
/// just became leader and the periodic emission has not yet produced its first snapshot),
/// the gate returns `true` so the FSM falls back to the pre-Step-4 behavior — trust the
/// event source. Transport-event cells naturally do not fire in this window because the
/// aggregator is also the producer of transport events.
///
/// **Where the gate is consulted.** Only the two ON_DUTY decommission cells listed above.
/// The `(JOINING, TransportUnreachable)` cell stays UNGATED — JOINING has no SWIM-HEALTHY
/// history to lose, and transport is its primary detection signal (spec §16 scenario S01).
/// All other reducer cells ignore the gate entirely.
///
/// **Purity preservation.** The gate is passed as a parameter to `reducer.apply(...)` and is
/// not a field on the reducer object. The reducer remains a pure `(state, event, gate) →
/// Outcome` function — easy to test, easy to reason about.
@FunctionalInterface
public interface ReachabilityGate {
    /// `true` when the aggregator confirms UNREACHABLE with quorum for `peer`, OR when no
    /// snapshot is available (cold-start fallback). `false` when the aggregator reports
    /// REACHABLE-quorum or UNKNOWN for `peer` — i.e., the observation is not a confirmed
    /// cluster-wide failure and the reducer should treat the cell as a nop.
    boolean isConfirmedUnreachable(NodeId peer);

    /// Permissive gate for tests and legacy callers that do not require aggregator
    /// confirmation. Equivalent to the pre-Step-4 reducer behavior.
    ReachabilityGate ALWAYS_CONFIRMED = peer -> true;
}
