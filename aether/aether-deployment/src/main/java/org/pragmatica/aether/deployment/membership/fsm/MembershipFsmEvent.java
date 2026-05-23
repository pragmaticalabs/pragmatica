// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;


/// Cluster-membership FSM input event vocabulary (spec §4, Q3=C decision: no `Tick`).
///
/// Ten event types feed the reducer. Each carries the `peer` it targets and an
/// `HlcTimestamp at` captured at event-creation time (RC1 Step 4 — replaces the
/// pre-RC1 `long nowMs`). The HLC value is sourced from a per-node `HlcClock` and is
/// used both for KV-write metadata (`NodeLifecycleValue.transitionedAt`) and for cross-
/// node causal ordering. The reducer itself is pure and reads `event.at()` rather than
/// calling a clock.
///
/// `SlotSpawned` is intentionally absent (§4.2): it does not change FSM state, only updates
/// the leader's slot-to-peer mapping. `SuspectObserved` and `UnknownObserved` are also absent —
/// SWIM-internal transient states fold into `SwimFaulty` via the existing suspect-timeout
/// machinery in `SwimProtocol`.
///
/// **Topology-observation refactor (Step 2).** `TransportReachable` / `TransportUnreachable`
/// are sourced from the `ReachabilityAggregator` quorum snapshot (see
/// `reachability-aggregator-spec.md` "Periodic Observation Mode"). They provide a faster
/// detection path (~1s transport disconnect) than SWIM (~10-15s) and target the JOINING-window
/// kill scenario S01. Conditional aggregator-quorum gating arrives in Step 4; this step adds
/// the events + 14 reducer cells with no gate, per spec §16.
public sealed interface MembershipFsmEvent {
    NodeId peer();

    HlcTimestamp at();

    record SwimHealthy(NodeId peer, long incarnation, HlcTimestamp at) implements MembershipFsmEvent{}

    record SwimFaulty(NodeId peer, long incarnation, HlcTimestamp at) implements MembershipFsmEvent{}

    record SwimDeparted(NodeId peer, long incarnation, HlcTimestamp at) implements MembershipFsmEvent{}

    record SlotClaimed(NodeId peer, String slotId, HlcTimestamp at) implements MembershipFsmEvent{}

    record OperatorDrain(NodeId peer, DrainReason reason, HlcTimestamp at) implements MembershipFsmEvent{}

    record OperatorDecommission(NodeId peer, boolean force, HlcTimestamp at) implements MembershipFsmEvent{}

    record DrainOutcome(NodeId peer, boolean success, HlcTimestamp at) implements MembershipFsmEvent{}

    record JoinDeadlineExpired(NodeId peer, HlcTimestamp at) implements MembershipFsmEvent{}

    record TransportReachable(NodeId peer, HlcTimestamp at) implements MembershipFsmEvent{}

    record TransportUnreachable(NodeId peer, HlcTimestamp at) implements MembershipFsmEvent{}
}
