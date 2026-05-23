// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.aether.slice.kvstore.AetherValue.StopReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Per-peer membership state in the cluster-membership FSM (spec §3; cluster-convergence-
/// reconciler-spec §5.1, step H collapses the prior 7-state alphabet to 6 by unifying the
/// `Decommissioned` and `FailedDrain` terminal records into the single [Stopped] record carrying
/// a [StopReason] sidecar — the same collapse applied at the KV-layer `NodeLifecycleState` enum
/// (step I).
///
/// State is fully reconstructible from KV (invariant I1): each variant maps cleanly to
/// the combination of `NodeLifecycleKey[peer]` presence/value plus the `ProvisioningSlotKey`
/// whose `assignedNodeId == peer`. There is no hidden in-memory state — `slotId` and
/// `updatedAtMs` are KV-derived metadata carried for convenience, not authoritative data.
public sealed interface MembershipFsmState {
    NodeId peer();

    record Untracked(NodeId peer) implements MembershipFsmState {}

    record Provisioning(NodeId peer, String slotId) implements MembershipFsmState {}

    record Joining(NodeId peer, long joinedAtMs, Option<String> slotId) implements MembershipFsmState {}

    record OnDuty(NodeId peer, long updatedAtMs) implements MembershipFsmState {}

    record Draining(NodeId peer, long drainStartedAtMs, DrainReason reason) implements MembershipFsmState {}

    /// Terminal state. `reason` is the [StopReason] sidecar carried from the originating
    /// `LifecycleCommand` (or synthesised from the originating event — SWIM-driven failure
    /// maps to `FORCED`, drain-success to `GRACEFUL`, drain-timeout to `DRAIN_FAILED`).
    /// `swimDriven` is kept as a separate observability flag — it distinguishes SWIM-detected
    /// failure (`SwimFaulty`/`SwimDeparted`) from operator/drain-driven decommission, which was
    /// previously consumed by the H.4 refractory gate (removed) but remains informational on
    /// the reducer surface and in tests.
    record Stopped(NodeId peer, long stoppedAtMs, StopReason reason, boolean swimDriven) implements MembershipFsmState {}

    static Untracked untracked(NodeId peer) {
        return new Untracked(peer);
    }

    static Provisioning provisioning(NodeId peer, String slotId) {
        return new Provisioning(peer, slotId);
    }

    static Joining joining(NodeId peer, long joinedAtMs, Option<String> slotId) {
        return new Joining(peer, joinedAtMs, slotId);
    }

    static OnDuty onDuty(NodeId peer, long updatedAtMs) {
        return new OnDuty(peer, updatedAtMs);
    }

    static Draining draining(NodeId peer, long drainStartedAtMs, DrainReason reason) {
        return new Draining(peer, drainStartedAtMs, reason);
    }

    static Stopped stopped(NodeId peer, long stoppedAtMs, StopReason reason, boolean swimDriven) {
        return new Stopped(peer, stoppedAtMs, reason, swimDriven);
    }

    /// Convenience factory for tests / replay paths that synthesise a `Stopped` state without
    /// known `swimDriven` provenance — defaults to `false` (operator/drain-driven) and the
    /// supplied `StopReason`.
    static Stopped stopped(NodeId peer, long stoppedAtMs, StopReason reason) {
        return new Stopped(peer, stoppedAtMs, reason, false);
    }
}
