// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.drain.DrainCoordinator.DrainReason;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.Option;


/// Per-peer membership state in the cluster-membership FSM (spec §3, 7 states).
///
/// State is fully reconstructible from KV (invariant I1): each variant maps cleanly to
/// the combination of `NodeLifecycleKey[peer]` presence/value plus the `ProvisioningSlotKey`
/// whose `assignedNodeId == peer`. There is no hidden in-memory state — `slotId` and
/// `updatedAtMs` are KV-derived metadata carried for convenience, not authoritative data.
public sealed interface MembershipFsmState {
    NodeId peer();

    record Untracked(NodeId peer) implements MembershipFsmState{}

    record Provisioning(NodeId peer, String slotId) implements MembershipFsmState{}

    record Joining(NodeId peer, long joinedAtMs, Option<String> slotId) implements MembershipFsmState{}

    record OnDuty(NodeId peer, long updatedAtMs) implements MembershipFsmState{}

    record Draining(NodeId peer, long drainStartedAtMs, DrainReason reason) implements MembershipFsmState{}

    /// `swimDriven` distinguishes SWIM-detected failure (`SwimFaulty`/`SwimDeparted`) from
    /// operator/drain-driven decommission. Only `swimDriven=true` triggers the SWIM-Healthy
    /// revival refractory in `ClusterMembershipReducer.decommissionedSwimHealthy` — operator-
    /// initiated decommissions remain eligible for fast-restart revival within the TTL.
    record Decommissioned(NodeId peer, long decommissionedAtMs, boolean swimDriven) implements MembershipFsmState{}

    record FailedDrain(NodeId peer, long failedAtMs) implements MembershipFsmState{}

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

    static Decommissioned decommissioned(NodeId peer, long decommissionedAtMs) {
        return new Decommissioned(peer, decommissionedAtMs, false);
    }

    static Decommissioned decommissioned(NodeId peer, long decommissionedAtMs, boolean swimDriven) {
        return new Decommissioned(peer, decommissionedAtMs, swimDriven);
    }

    static FailedDrain failedDrain(NodeId peer, long failedAtMs) {
        return new FailedDrain(peer, failedAtMs);
    }
}
