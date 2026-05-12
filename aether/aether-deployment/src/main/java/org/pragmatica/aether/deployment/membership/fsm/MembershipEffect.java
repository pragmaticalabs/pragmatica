// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;


/// Side-effect descriptor emitted by the FSM reducer (spec §10.2, I7).
///
/// The reducer is pure: it returns these descriptors instead of performing I/O. The wiring
/// layer (E.3/E.4) translates each descriptor into a real scheduler / DrainCoordinator /
/// event-bus call. Categories:
///
/// - `ScheduleTimer` / `CancelTimer` — one-shot timer lifecycle (spec §4.1 row 5, §10.2).
///   The FSM owns no periodic scheduler.
/// - `InvokeDrain` — fire `DrainCoordinator.awaitDrainAck`; its outcome surfaces as a
///   `DrainOutcome` event (spec §8.2). Distinct from the `Put(L=DRAINING)` KV write,
///   which is already represented in `Outcome.writes`.
/// - `CancelDrain` — operator-forced decommission during `DRAINING` cancels the awaiting
///   coordinator before writing `Put(L=DECOMMISSIONED)` (spec §5 row 5 col 6, force=true).
/// - `EmitDomainEvent` — `NODE_JOINING` / `NODE_ON_DUTY` / `NODE_FAILED` / `NODE_DRAINED` /
///   `NODE_DRAIN_FAILED` published downstream. Carries a free-form `reason` for audit.
public sealed interface MembershipEffect {
    record ScheduleTimer(NodeId peer, TimerKind kind, TimeSpan delay) implements MembershipEffect{}

    record CancelTimer(NodeId peer, TimerKind kind) implements MembershipEffect{}

    record InvokeDrain(NodeId peer, DrainReason reason) implements MembershipEffect{}

    record CancelDrain(NodeId peer) implements MembershipEffect{}

    record EmitDomainEvent(NodeId peer, MembershipDomainEvent event, String reason) implements MembershipEffect{}

    enum TimerKind {
        JOIN_DEADLINE
    }

    enum MembershipDomainEvent {
        NODE_JOINING,
        NODE_ON_DUTY,
        NODE_FAILED,
        NODE_DRAINED,
        NODE_DRAIN_FAILED
    }
}
