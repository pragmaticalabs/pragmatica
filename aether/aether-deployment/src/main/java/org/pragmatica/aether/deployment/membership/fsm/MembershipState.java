// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.DownHysteresisMet;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.DrainRequested;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.JoinGraceExpiredNeverHealthy;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.LivenessGone;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.PeerConnected;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.PeerDisconnected;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.Stopped;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimDeparted;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimFaulty;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimHealthy;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimSuspect;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.SwimUnknown;
import org.pragmatica.aether.deployment.membership.fsm.MembershipEvent.UpHysteresisMet;
import org.pragmatica.lang.Contract;
import org.pragmatica.statemachine.FsmState;
import org.pragmatica.statemachine.TransitionRequest;


/// Per-member shadow membership FSM (Phase 1, model only — no wiring, no behaviour change).
///
/// Each instance tracks the lifecycle of one peer identity as observed through SWIM gossip,
/// transport connectivity, hysteresis debouncing, and operator drain/stop signals. The cardinal
/// invariant is `countsTowardEffective()`: it answers "does this member contribute to the
/// effective on-duty count?" — and crucially a transient flap (MEMBER → SUSPECT) must NOT drop the
/// count, otherwise a momentary SWIM doubt collapses quorum/effective-size accounting (the churn
/// cure, spec §3.1).
///
/// States (spec §3.1):
/// - [`Observed`] — seen, not yet promoted. `countsTowardEffective() = false`.
/// - [`Member`] — promoted, on-duty core. `countsTowardEffective() = true`.
/// - [`Suspect`] — was MEMBER, transient doubt, debouncing. `countsTowardEffective() = true`.
/// - [`Departing`] — confirmed leaving. `false`.
/// - [`Dead`] — confirmed gone; terminal for this identity, carries the incarnation it died at.
///   `false`.
///
/// DEAD stamping: on entry to DEAD the FSM records `terminalIncarnation` from the highest SWIM
/// incarnation seen so far for this identity (tracked in [`MembershipContext#lastSeenIncarnation`]).
/// `SwimFaulty` / `SwimSuspect` / `SwimDeparted` update that high-water mark as they flow through.
/// The `Stopped` / `JoinGraceExpiredNeverHealthy` / drain-driven DEAD edges therefore stamp the
/// last incarnation the cluster ever attributed to this identity. Rejoin fencing (spec §4 bug #2):
/// a `SwimHealthy(inc)` arriving in DEAD reopens the identity (→ OBSERVED) only when
/// `inc > terminalIncarnation`; a same-or-lower incarnation is a stale gossip echo and is ignored.
public sealed interface MembershipState extends FsmState<MembershipState, MembershipEvent> permits MembershipState.Observed, MembershipState.Member, MembershipState.Suspect, MembershipState.Departing, MembershipState.Dead {
    MembershipContext ctx();
    /// Whether this member contributes to the effective on-duty count. The flap-resistant cure:
    /// SUSPECT still counts, so a transient SWIM doubt never drops the effective size.
    boolean countsTowardEffective();

    // --- State records ---
    /// Seen, not yet promoted. Does not count toward the effective set.
    record Observed(MembershipContext ctx) implements MembershipState {
        @Override
        public boolean countsTowardEffective() {
            return false;
        }

        @Contract
        @Override
        public void handle(MembershipEvent event, TransitionRequest<MembershipState, MembershipEvent> tx) {
            switch (event) {
                case SwimHealthy e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case PeerConnected _ -> tx.ignore();
                case UpHysteresisMet _ -> tx.transitionTo(ctx.memberState());
                case SwimSuspect e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case SwimFaulty e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case PeerDisconnected _ -> tx.ignore();
                case LivenessGone _ -> tx.ignore();
                case DownHysteresisMet _ -> tx.ignore();
                case SwimDeparted e -> departViaSwim(ctx, e, tx);
                case SwimUnknown _ -> tx.ignore();
                case DrainRequested _ -> tx.transitionTo(ctx.departing());
                case Stopped _ -> tx.transitionTo(ctx.dead());
                case JoinGraceExpiredNeverHealthy _ -> tx.transitionTo(ctx.dead());
            }
        }
    }

    /// Promoted, on-duty core. Counts toward the effective set.
    record Member(MembershipContext ctx) implements MembershipState {
        @Override
        public boolean countsTowardEffective() {
            return true;
        }

        @Contract
        @Override
        public void handle(MembershipEvent event, TransitionRequest<MembershipState, MembershipEvent> tx) {
            switch (event) {
                case SwimHealthy e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case PeerConnected _ -> tx.ignore();
                case UpHysteresisMet _ -> tx.ignore();
                case SwimSuspect e -> doubtToSuspect(ctx, e.incarnation(), tx);
                case SwimFaulty e -> doubtToSuspect(ctx, e.incarnation(), tx);
                case PeerDisconnected _ -> tx.transitionTo(ctx.suspect());
                case LivenessGone _ -> tx.transitionTo(ctx.suspect());
                case DownHysteresisMet _ -> tx.ignore();
                case SwimDeparted e -> departViaSwim(ctx, e, tx);
                case SwimUnknown _ -> tx.ignore();
                case DrainRequested _ -> tx.transitionTo(ctx.departing());
                case Stopped _ -> tx.transitionTo(ctx.dead());
                case JoinGraceExpiredNeverHealthy _ -> tx.ignore();
            }
        }
    }

    /// Was MEMBER, transient doubt, debouncing. STILL counts toward the effective set — a flap must
    /// not drop the count (spec §3.1 churn cure). Exits to MEMBER (recover) or DEPARTING (bounded,
    /// invariant I4).
    record Suspect(MembershipContext ctx) implements MembershipState {
        @Override
        public boolean countsTowardEffective() {
            return true;
        }

        @Contract
        @Override
        public void handle(MembershipEvent event, TransitionRequest<MembershipState, MembershipEvent> tx) {
            switch (event) {
                case SwimHealthy e -> recoverToMember(ctx, e.incarnation(), tx);
                // Death-ward boundary rule (Wave 7, ratified): transport may report DEATH, never
                // LIFE. A transport connection must not revive a SWIM-suspected member — recovery
                // goes ONLY through SwimHealthy (SWIM probe-ack authority) or UpHysteresisMet
                // (presence hysteresis). Observed live: a falsely-suspected node was
                // transport-revived seconds before SWIM killed it anyway.
                case PeerConnected _ -> tx.ignore();
                case UpHysteresisMet _ -> tx.transitionTo(ctx.memberState());
                case SwimSuspect e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case SwimFaulty e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case PeerDisconnected _ -> tx.ignore();
                case LivenessGone _ -> tx.ignore();
                case DownHysteresisMet _ -> tx.transitionTo(ctx.departing());
                case SwimDeparted e -> departViaSwim(ctx, e, tx);
                case SwimUnknown _ -> tx.ignore();
                case DrainRequested _ -> tx.transitionTo(ctx.departing());
                case Stopped _ -> tx.transitionTo(ctx.dead());
                case JoinGraceExpiredNeverHealthy _ -> tx.ignore();
            }
        }
    }

    /// Confirmed leaving — decision pending. Does not count toward the effective set. No longer an
    /// inescapable trap (H2, cluster-topology-overhaul Wave 7): besides the terminal `Stopped`
    /// edge, a `SwimHealthy` carrying a STRICTLY HIGHER incarnation than the high-water mark
    /// recovers the member back to MEMBER (it refuted / restarted mid-drain — mere liveness at the
    /// known incarnation does NOT cancel a drain), and the manager-armed DEPARTING timeout
    /// ([`MembershipFsm`]) terminalizes a drainer that goes silent via a delayed `Stopped`. All
    /// other signals are absorbed.
    record Departing(MembershipContext ctx) implements MembershipState {
        @Override
        public boolean countsTowardEffective() {
            return false;
        }

        @Contract
        @Override
        public void handle(MembershipEvent event, TransitionRequest<MembershipState, MembershipEvent> tx) {
            switch (event) {
                case Stopped _ -> tx.transitionTo(ctx.dead());
                case SwimDeparted e -> tx.handle(() -> ctx.observeIncarnation(e.incarnation()));
                case SwimHealthy e -> recoverFromDepartingIfNewer(ctx, e.incarnation(), tx);
                case PeerConnected _, UpHysteresisMet _, SwimSuspect _, SwimFaulty _, PeerDisconnected _, LivenessGone _, DownHysteresisMet _, SwimUnknown _, DrainRequested _, JoinGraceExpiredNeverHealthy _ -> tx.ignore();
            }
        }
    }

    /// Confirmed gone; terminal for this identity. Carries the incarnation it died at. A
    /// higher-incarnation `SwimHealthy` reopens the identity (rejoin → OBSERVED); a same-or-lower
    /// incarnation is a stale echo and is ignored. Does not count toward the effective set.
    record Dead(MembershipContext ctx, long terminalIncarnation) implements MembershipState {
        @Override
        public boolean countsTowardEffective() {
            return false;
        }

        @Contract
        @Override
        public void handle(MembershipEvent event, TransitionRequest<MembershipState, MembershipEvent> tx) {
            switch (event) {
                case SwimHealthy e -> rejoinIfNewer(ctx, this, e.incarnation(), tx);
                case PeerConnected _ -> tx.ignore();
                case UpHysteresisMet _ -> tx.ignore();
                case SwimSuspect _ -> tx.ignore();
                case SwimFaulty _ -> tx.ignore();
                case PeerDisconnected _ -> tx.ignore();
                case LivenessGone _ -> tx.ignore();
                case DownHysteresisMet _ -> tx.ignore();
                case SwimDeparted _ -> tx.ignore();
                case SwimUnknown _ -> tx.ignore();
                case DrainRequested _ -> tx.ignore();
                case Stopped _ -> tx.ignore();
                case JoinGraceExpiredNeverHealthy _ -> tx.ignore();
            }
        }
    }

    // --- Shared action helpers ---
    /// MEMBER/SUSPECT doubt path (`SwimSuspect` / `SwimFaulty`): record the incarnation for DEAD
    /// stamping, then move to SUSPECT (which still counts toward effective).
    private static void doubtToSuspect(MembershipContext ctx,
                                       long incarnation,
                                       TransitionRequest<MembershipState, MembershipEvent> tx) {
        ctx.observeIncarnation(incarnation);
        tx.transitionTo(ctx.suspect());
    }

    /// SUSPECT recovery (`SwimHealthy`): record the incarnation, then move back to MEMBER. The
    /// effective count was never dropped — SUSPECT counts — so this is a clean flap recovery.
    private static void recoverToMember(MembershipContext ctx,
                                        long incarnation,
                                        TransitionRequest<MembershipState, MembershipEvent> tx) {
        ctx.observeIncarnation(incarnation);
        tx.transitionTo(ctx.memberState());
    }

    /// Graceful SWIM-driven leave (`SwimDeparted`): record the incarnation, then move to DEPARTING.
    /// The terminal DEAD edge follows on a subsequent `Stopped`.
    private static void departViaSwim(MembershipContext ctx,
                                      SwimDeparted event,
                                      TransitionRequest<MembershipState, MembershipEvent> tx) {
        ctx.observeIncarnation(event.incarnation());
        tx.transitionTo(ctx.departing());
    }

    /// DEPARTING recovery (H2, cluster-topology-overhaul Wave 7): a `SwimHealthy(inc)` recovers a
    /// drainer back to MEMBER ONLY when `inc` STRICTLY exceeds the incarnation high-water mark — the
    /// node refuted a suspicion / restarted mid-drain, i.e. fresh evidence of life. Mere liveness at
    /// an already-seen incarnation does NOT cancel a drain (an operator-drained node keeps gossiping
    /// healthy at its current incarnation while it drains). Mirrors [`#rejoinIfNewer`]'s fencing.
    private static void recoverFromDepartingIfNewer(MembershipContext ctx,
                                                    long incarnation,
                                                    TransitionRequest<MembershipState, MembershipEvent> tx) {
        if (incarnation > ctx.lastSeenIncarnation()) {
            ctx.observeIncarnation(incarnation);
            tx.transitionTo(ctx.memberState());
        } else {
            tx.ignore();
        }
    }

    /// Rejoin fencing in DEAD: a `SwimHealthy(inc)` reopens the identity only when `inc` exceeds the
    /// incarnation this identity died at; otherwise the gossip is stale and is ignored.
    private static void rejoinIfNewer(MembershipContext ctx,
                                      Dead current,
                                      long incarnation,
                                      TransitionRequest<MembershipState, MembershipEvent> tx) {
        if (incarnation > current.terminalIncarnation()) {
            tx.transitionTo(ctx.observedRejoin(incarnation));
        } else {
            tx.ignore();
        }
    }
}
