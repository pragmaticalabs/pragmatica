// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.consensus.NodeId;


/// Cluster-membership FSM input event vocabulary (spec §4, Q3=C decision: no `Tick`).
///
/// Eight event types feed the reducer. Each carries the `peer` it targets and the `nowMs`
/// timestamp captured at event-creation time (used by transitions for KV-write metadata —
/// the reducer itself reads `nowMs()` rather than calling a clock, preserving purity).
///
/// `SlotSpawned` is intentionally absent (§4.2): it does not change FSM state, only updates
/// the leader's slot-to-peer mapping. `SuspectObserved` and `UnknownObserved` are also absent —
/// SWIM-internal transient states fold into `SwimFaulty` via the existing suspect-timeout
/// machinery in `SwimProtocol`.
public sealed interface MembershipFsmEvent {
    NodeId peer();
    long nowMs();

    record SwimHealthy(NodeId peer, long incarnation, long nowMs) implements MembershipFsmEvent{}

    record SwimFaulty(NodeId peer, long incarnation, long nowMs) implements MembershipFsmEvent{}

    record SwimDeparted(NodeId peer, long incarnation, long nowMs) implements MembershipFsmEvent{}

    record SlotClaimed(NodeId peer, String slotId, long nowMs) implements MembershipFsmEvent{}

    record OperatorDrain(NodeId peer, DrainReason reason, long nowMs) implements MembershipFsmEvent{}

    record OperatorDecommission(NodeId peer, boolean force, long nowMs) implements MembershipFsmEvent{}

    record DrainOutcome(NodeId peer, boolean success, long nowMs) implements MembershipFsmEvent{}

    record JoinDeadlineExpired(NodeId peer, long nowMs) implements MembershipFsmEvent{}
}
