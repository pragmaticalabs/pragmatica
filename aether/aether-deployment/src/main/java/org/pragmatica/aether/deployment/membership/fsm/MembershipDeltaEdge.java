// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;

/// Typed membership-delta edge emitted by [MembershipFsm] on its own lifecycle edges
/// (cluster-topology-overhaul spec, Wave 4 / architecture §4.1 output (b) — the FSM's third
/// output alongside `desiredConnections()` and `onConfirmedDeparture`).
///
/// Emission semantics (exactly-once by construction, from the single
/// `MemberTracking#dispatch` chokepoint):
/// - [Kind#JOINED] — fired on the member's FIRST entry into MEMBER (the OBSERVED→MEMBER
///   promotion; the only path into the counted set — `Observed` never transitions to
///   SUSPECT directly, and SUSPECT→MEMBER is a recovery of an already-joined member).
///   Sets the per-tracking `everJoined` flag.
/// - [Kind#REMOVED] — fired on a fresh edge into DEAD ONLY for a member that was
///   previously JOINED (`everJoined`); an OBSERVED→DEAD member that never reached MEMBER
///   (join-grace expiry, drain-before-promotion) emits NOTHING — it never counted, so its
///   death is a no-op delta (the spec's tangential consideration). Clears `everJoined`,
///   so a fenced higher-incarnation rejoin emits a fresh JOINED.
///
/// A pure notification with a cheap payload, fired under the per-member monitor —
/// listeners MUST be cheap and non-blocking (the [MembershipDeltaProjector] enqueues and
/// returns). Deliberately decoupled from `TopologyObserver.evaluateQuorumState` (spec
/// §3.1 hard constraint): nothing on this edge may route into the quorum evaluator.
///
/// @param node        the member whose counted-set membership changed
/// @param kind        JOINED (first promotion into MEMBER) or REMOVED (death of a JOINED member)
/// @param incarnation the member's SWIM-incarnation high-water mark after the transition
/// @param role        the member's last-known descriptor role label (blank when unknown;
///                    blank/unknown counts as core, matching [MemberDescriptor#isCore])
public record MembershipDeltaEdge(NodeId node, Kind kind, long incarnation, String role) {
    /// The two counted-set membership edges the FSM emits.
    public enum Kind {
        JOINED,
        REMOVED
    }
}
