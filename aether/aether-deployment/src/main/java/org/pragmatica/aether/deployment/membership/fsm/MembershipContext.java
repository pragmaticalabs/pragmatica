// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.consensus.NodeId;
import org.pragmatica.statemachine.Fsm;

import java.util.concurrent.atomic.AtomicLong;


/// Shared per-member context for the membership FSM ([`MembershipState`]). Mirrors the
/// `LeaderElectionContext` idiom: one instance per FSM, holds the `Fsm` reference (bound at
/// construction via the constructor-driven initial-state factory), the tracked member's `NodeId`,
/// the per-FSM data-free state singletons (OBSERVED / MEMBER / SUSPECT / DEPARTING), and the SWIM
/// incarnation high-water mark used to stamp [`MembershipState.Dead#terminalIncarnation`].
///
/// State identity is by reference (CAS). Data-free states are singletons so a flap that re-enters
/// MEMBER/SUSPECT resolves to the same object. [`MembershipState.Dead`] is data-carrying (it holds
/// the terminal incarnation) and is therefore constructed fresh on every entry via [`#dead`]; a
/// rejoin produces a fresh OBSERVED instance via [`#observedRejoin`].
///
/// Thread safety: the incarnation high-water mark is an `AtomicLong`. State singletons are `final`.
public final class MembershipContext {
    private final Fsm<MembershipState, MembershipEvent> fsm;
    private final NodeId member;
    // Per-FSM data-free state singletons — CAS comparisons against them are reference-stable.
    private final MembershipState.Observed observed;
    private final MembershipState.Member memberState;
    private final MembershipState.Suspect suspect;
    private final MembershipState.Departing departing;
    /// Highest SWIM incarnation observed for this identity. Stamped onto [`MembershipState.Dead`] on
    /// entry, so the terminal DEAD edge records the last incarnation the cluster ever attributed to
    /// this identity — even when the DEAD-triggering event (`Stopped`, `JoinGraceExpiredNeverHealthy`,
    /// drain) carries no incarnation of its own.
    private final AtomicLong lastSeenIncarnation = new AtomicLong(0);

    MembershipContext(Fsm<MembershipState, MembershipEvent> fsm, NodeId member) {
        this.fsm = fsm;
        this.member = member;
        this.observed = new MembershipState.Observed(this);
        this.memberState = new MembershipState.Member(this);
        this.suspect = new MembershipState.Suspect(this);
        this.departing = new MembershipState.Departing(this);
    }

    public Fsm<MembershipState, MembershipEvent> fsm() {
        return fsm;
    }

    public NodeId member() {
        return member;
    }

    /// Record a SWIM incarnation, advancing the high-water mark monotonically. Stale (lower)
    /// incarnations never lower the mark.
    public void observeIncarnation(long incarnation) {
        lastSeenIncarnation.accumulateAndGet(incarnation, Math::max);
    }

    public long lastSeenIncarnation() {
        return lastSeenIncarnation.get();
    }

    // --- Per-FSM state instances ---
    public MembershipState.Observed observed() {
        return observed;
    }

    /// Fresh OBSERVED instance for a rejoin: resets the incarnation high-water mark to the rejoining
    /// incarnation so a subsequent DEAD edge stamps the new tenure correctly. Returns the shared
    /// OBSERVED singleton (data-free) after re-seeding the mark.
    public MembershipState.Observed observedRejoin(long incarnation) {
        lastSeenIncarnation.set(incarnation);

        return observed;
    }

    public MembershipState.Member memberState() {
        return memberState;
    }

    public MembershipState.Suspect suspect() {
        return suspect;
    }

    public MembershipState.Departing departing() {
        return departing;
    }

    /// Fresh DEAD instance per entry, stamped with the current incarnation high-water mark.
    public MembershipState.Dead dead() {
        return new MembershipState.Dead(this, lastSeenIncarnation.get());
    }
}
