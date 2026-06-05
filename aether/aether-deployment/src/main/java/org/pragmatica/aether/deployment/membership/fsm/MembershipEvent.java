// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

/// Per-member events that drive the membership FSM ([`MembershipState`]). Each FSM instance tracks
/// exactly one peer identity; the `NodeId` is implied by which member's FSM receives the event, so
/// it is NOT carried on the event itself.
///
/// SWIM-sourced events carry the `incarnation` of the membership record that produced them. The
/// incarnation is monotonically increasing per identity and is the fencing token used to decide
/// whether a `SwimHealthy` observed after the FSM has reached [`MembershipState.Dead`] is a genuine
/// rejoin (higher incarnation) or a stale/duplicate gossip echo (same or lower incarnation). See
/// the DEAD handling in [`MembershipState`].
///
/// Hysteresis and liveness events (`UpHysteresisMet`, `DownHysteresisMet`, `LivenessGone`,
/// `PeerConnected`, `PeerDisconnected`) are debounce/transport signals raised by the (future)
/// shadow membership manager — they carry no incarnation because they are derived signals, not raw
/// SWIM samples.
public sealed interface MembershipEvent {
    /// SWIM reports the member ALIVE at `incarnation`.
    record SwimHealthy(long incarnation) implements MembershipEvent {}

    /// SWIM reports the member SUSPECT at `incarnation` (transient doubt — debounced, not terminal).
    record SwimSuspect(long incarnation) implements MembershipEvent {}

    /// SWIM reports the member FAULTY at `incarnation`. NOT terminal on its own — treated as the
    /// doubt/SUSPECT path; the last-seen faulty incarnation is recorded for DEAD stamping.
    record SwimFaulty(long incarnation) implements MembershipEvent {}

    /// SWIM reports the member DEPARTED gracefully at `incarnation` (leave intent).
    record SwimDeparted(long incarnation) implements MembershipEvent {}

    /// SWIM reports the member in the UNKNOWN bucket at `incarnation`. Biases the next sample only —
    /// the FSM does not transition on it.
    record SwimUnknown(long incarnation) implements MembershipEvent {}

    /// Transport reports a peer connection established.
    record PeerConnected() implements MembershipEvent {}

    /// Transport reports a peer connection dropped.
    record PeerDisconnected() implements MembershipEvent {}

    /// Composite liveness signal lost (no probe-ack within the liveness window).
    record LivenessGone() implements MembershipEvent {}

    /// Up-hysteresis (promotion debounce) satisfied — the member has been healthy long enough.
    record UpHysteresisMet() implements MembershipEvent {}

    /// Down-hysteresis (demotion debounce) satisfied — the member has been doubted long enough.
    record DownHysteresisMet() implements MembershipEvent {}

    /// Operator/controller requested this member drain (graceful departure).
    record DrainRequested() implements MembershipEvent {}

    /// This member stopped (confirmed terminal stop of the local process / explicit shutdown).
    record Stopped() implements MembershipEvent {}

    /// The join-grace window expired without the member ever reaching healthy — never silently
    /// counted; goes terminal.
    record JoinGraceExpiredNeverHealthy() implements MembershipEvent {}
}
