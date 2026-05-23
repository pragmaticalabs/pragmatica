// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;


/// Externally-directed lifecycle inputs (cluster-convergence-reconciler-spec §6).
///
/// Commands are the second branch of `MembershipFsmInput` alongside `MembershipFsmEvent`.
/// Unlike events (which describe observed facts), commands describe operator/reconciler
/// intent. Each command carries:
///   - `peer()` — target node id
///   - `at()` — HLC stamp at command emission (replicated to `transitionedAt` on accept)
///   - `justification()` — `Cause` describing why the command was issued (audit payload)
///
/// Commands enter the FSM through `LifecycleWriter.applyCommand`, which publishes
/// `CommandReceived` to the audit stream, dispatches through the reducer, and on consensus
/// accept publishes `CommandApplied`. Reducer behaviour for illegal command-on-state is
/// `no-op + audit entry with decision=ILLEGAL_TRANSITION`.
///
/// ### Variants
///
/// `ForceDecommission` — terminal transition to `STOPPED` with an explicit `StopReason`
/// sidecar. Sources: CTM scale-down (`FORCED`), drain coordinator on success (`GRACEFUL`),
/// drain coordinator/HTTP route on timeout (`DRAIN_FAILED`), reconciler `JoiningTimeout` /
/// `OnDutyFaulty` / `DrainTimeout` rules, operator API.
///
/// `ForceOnDuty` — transition to `ON_DUTY`. Sources: cluster-sync `readyCandidate` arrival
/// on the leader, reconciler post-sync convergence. Idempotent on already-`ON_DUTY`.
///
/// `RecordJoining` — register a `JOINING` entry for a peer. Sources: reconciler
/// `GenerationLifecycleGap` rule (Rabia member with no lifecycle entry past budget), CTM
/// slot-claimed flow when JOINING entry is missing.
///
/// `RequestReJoin` — reset to `Untracked` so the peer can re-enter `JOINING`. Sources:
/// drain coordinator on drain cancellation, operator API for forced re-join after stuck
/// `DRAINING`.
///
/// `ForceDrain` — transition to `DRAINING` carrying a `DrainReason` sidecar. Sources:
/// Operator API drain endpoint, CLI `aether nodes drain <id>`. Idempotent on
/// already-`DRAINING`.
public sealed interface LifecycleCommand extends MembershipFsmInput permits LifecycleCommand.ForceDecommission, LifecycleCommand.ForceOnDuty, LifecycleCommand.RecordJoining, LifecycleCommand.RequestReJoin, LifecycleCommand.ForceDrain {
    Cause justification();

    record ForceDecommission(NodeId peer, StopReason reason, Cause justification, HlcTimestamp at) implements LifecycleCommand {}

    record ForceOnDuty(NodeId peer, Cause justification, HlcTimestamp at) implements LifecycleCommand {}

    record RecordJoining(NodeId peer, Option<String> slotId, Cause justification, HlcTimestamp at) implements LifecycleCommand {}

    record RequestReJoin(NodeId peer, Cause justification, HlcTimestamp at) implements LifecycleCommand {}

    record ForceDrain(NodeId peer, DrainReason reason, Cause justification, HlcTimestamp at) implements LifecycleCommand {}
}
