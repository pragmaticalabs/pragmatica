// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.membership.fsm;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
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
import org.pragmatica.consensus.NodeId;
import org.pragmatica.statemachine.FsmTestHarness;

import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Table-driven, deterministic verification of the per-member membership FSM ([`MembershipState`]).
/// Covers the spec §4 bug scenarios (join-grace, terminal-vs-rejoin fencing, effective-preserved-
/// under-flap, churn damping, clean-departure single-DEAD-edge) plus the structural invariants
/// (I4 SUSPECT exits bounded; I5 every (state,event) defined — no exception; `countsTowardEffective`
/// matches the spec table).
class MembershipStateTest {
    private static final NodeId MEMBER = new NodeId("node-7");

    private static FsmTestHarness<MembershipState, MembershipEvent> observedHarness() {
        return FsmTestHarness.<MembershipState, MembershipEvent>harness(
                "membership-test",
                fsm -> new MembershipContext(fsm, MEMBER).observed());
    }

    private static FsmTestHarness<MembershipState, MembershipEvent> harnessInMember() {
        var h = observedHarness();
        h.dispatch(new UpHysteresisMet());
        return h;
    }

    /// Drives the FSM from OBSERVED all the way to DEAD stamped at `incarnation`: record the
    /// incarnation via a SWIM doubt event, then terminate via Stopped.
    private static FsmTestHarness<MembershipState, MembershipEvent> harnessInDead(long incarnation) {
        var h = harnessInMember();
        h.dispatch(new SwimFaulty(incarnation));
        h.dispatch(new Stopped());
        return h;
    }

    @Nested
    class BugScenarios {
        /// Spec §4 #1 join-grace: OBSERVED + JoinGraceExpiredNeverHealthy → DEAD. A member that
        /// never reached healthy is never silently counted — it goes terminal.
        @Test
        void joinGrace_observedNeverHealthy_transitionsToDead() {
            var h = observedHarness();

            h.dispatch(new JoinGraceExpiredNeverHealthy());

            assertThat(h.state()).isInstanceOf(MembershipState.Dead.class);
            assertThat(h.state().countsTowardEffective()).isFalse();
        }

        /// Spec §4 #2 terminal-vs-rejoin fencing: DEAD(inc=100). A SwimHealthy at the same
        /// incarnation is a stale echo (stays DEAD); a strictly higher incarnation is a genuine
        /// rejoin (→ OBSERVED).
        @Test
        void rejoinFence_sameIncarnation_staysDead() {
            var h = harnessInDead(100);
            assertThat(((MembershipState.Dead) h.state()).terminalIncarnation()).isEqualTo(100);

            h.dispatch(new SwimHealthy(100));

            assertThat(h.state())
                    .as("same-incarnation SwimHealthy is a stale echo — stays DEAD")
                    .isInstanceOf(MembershipState.Dead.class);
        }

        @Test
        void rejoinFence_lowerIncarnation_staysDead() {
            var h = harnessInDead(100);

            h.dispatch(new SwimHealthy(99));

            assertThat(h.state()).isInstanceOf(MembershipState.Dead.class);
        }

        @Test
        void rejoinFence_higherIncarnation_reopensToObserved() {
            var h = harnessInDead(100);

            h.dispatch(new SwimHealthy(101));

            assertThat(h.state())
                    .as("higher-incarnation SwimHealthy is a genuine rejoin → OBSERVED")
                    .isInstanceOf(MembershipState.Observed.class);
            assertThat(h.state().countsTowardEffective()).isFalse();
        }

        /// Spec §4 #3 effective-preserved-under-flap: MEMBER → SwimSuspect → SUSPECT, count STILL
        /// true; SwimHealthy → MEMBER recovered, count never dropped.
        @Test
        void effectivePreserved_memberFlapsToSuspectAndRecovers_countNeverDrops() {
            var h = harnessInMember();
            assertThat(h.state()).isInstanceOf(MembershipState.Member.class);
            assertThat(h.state().countsTowardEffective()).isTrue();

            h.dispatch(new SwimSuspect(5));
            assertThat(h.state()).isInstanceOf(MembershipState.Suspect.class);
            assertThat(h.state().countsTowardEffective())
                    .as("SUSPECT still counts — a flap must not drop the effective count")
                    .isTrue();

            h.dispatch(new SwimHealthy(6));
            assertThat(h.state()).isInstanceOf(MembershipState.Member.class);
            assertThat(h.state().countsTowardEffective()).isTrue();
        }

        /// Spec §4 #4 churn: MEMBER ⇄ SUSPECT damped — never leaves the counted set on a transient.
        @Test
        void churn_memberSuspectOscillation_alwaysCounts() {
            var h = harnessInMember();

            for (var i = 0; i < 10; i++) {
                h.dispatch(new SwimSuspect(i));
                assertThat(h.state().countsTowardEffective()).isTrue();
                h.dispatch(new SwimHealthy(i));
                assertThat(h.state().countsTowardEffective()).isTrue();
            }

            assertThat(h.state()).isInstanceOf(MembershipState.Member.class);
        }

        /// Spec §4 #5 clean-departure edge: MEMBER → DrainRequested → DEPARTING → Stopped → DEAD,
        /// exactly one DEAD edge.
        @Test
        void cleanDeparture_memberDrainStop_singleDeadEdge() {
            var h = harnessInMember();

            h.dispatch(new DrainRequested());
            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);
            assertThat(h.state().countsTowardEffective()).isFalse();

            h.dispatch(new Stopped());
            assertThat(h.state()).isInstanceOf(MembershipState.Dead.class);

            var deadEdges = h.transitions().stream()
                             .filter(t -> t.to() instanceof MembershipState.Dead)
                             .count();
            assertThat(deadEdges).as("exactly one DEAD edge on the clean-departure path").isEqualTo(1L);
        }
    }

    @Nested
    class Promotion {
        @Test
        void observed_upHysteresisMet_promotesToMember() {
            var h = observedHarness();

            h.dispatch(new UpHysteresisMet());

            assertThat(h.state()).isInstanceOf(MembershipState.Member.class);
        }

        @Test
        void observed_swimHealthy_staysObserved() {
            var h = observedHarness();

            h.dispatch(new SwimHealthy(3));

            assertThat(h.state()).isInstanceOf(MembershipState.Observed.class);
        }
    }

    @Nested
    class SuspectExits {
        /// Invariant I4: SUSPECT exits to MEMBER (recover) or DEPARTING (give up) — bounded.
        @Test
        void suspect_downHysteresisMet_transitionsToDeparting() {
            var h = harnessInMember();
            h.dispatch(new SwimSuspect(2));

            h.dispatch(new DownHysteresisMet());

            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);
        }

        @Test
        void suspect_drainRequested_transitionsToDeparting() {
            var h = harnessInMember();
            h.dispatch(new SwimSuspect(2));

            h.dispatch(new DrainRequested());

            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);
        }

        /// Death-ward boundary rule (Wave 7): transport may report DEATH, never LIFE — a transport
        /// connection must NOT revive a SWIM-suspected member. Recovery goes only through
        /// `SwimHealthy` (SWIM probe-ack authority) or `UpHysteresisMet` (presence hysteresis).
        @Test
        void suspect_peerConnected_staysSuspect() {
            var h = harnessInMember();
            h.dispatch(new PeerDisconnected());
            assertThat(h.state()).isInstanceOf(MembershipState.Suspect.class);

            h.dispatch(new PeerConnected());

            assertThat(h.state())
                    .as("transport may report death, never life — PeerConnected does not recover SUSPECT")
                    .isInstanceOf(MembershipState.Suspect.class);
            assertThat(h.state().countsTowardEffective()).isTrue();
        }

        @Test
        void suspect_upHysteresisMet_recoversToMember() {
            var h = harnessInMember();
            h.dispatch(new PeerDisconnected());
            assertThat(h.state()).isInstanceOf(MembershipState.Suspect.class);

            h.dispatch(new UpHysteresisMet());

            assertThat(h.state()).isInstanceOf(MembershipState.Member.class);
        }
    }

    @Nested
    class GracefulSwimLeave {
        @Test
        void member_swimDeparted_thenStopped_reachesDead() {
            var h = harnessInMember();

            h.dispatch(new SwimDeparted(9));
            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);

            h.dispatch(new Stopped());
            assertThat(h.state()).isInstanceOf(MembershipState.Dead.class);
        }
    }

    @Nested
    class IgnoredSignals {
        /// SwimUnknown biases the next sample only — never a transition, in every non-terminal
        /// state.
        @Test
        void swimUnknown_neverTransitions() {
            var observed = observedHarness();
            observed.dispatch(new SwimUnknown(1));
            assertThat(observed.state()).isInstanceOf(MembershipState.Observed.class);

            var member = harnessInMember();
            member.dispatch(new SwimUnknown(1));
            assertThat(member.state()).isInstanceOf(MembershipState.Member.class);

            var suspect = harnessInMember();
            suspect.dispatch(new SwimSuspect(1));
            suspect.dispatch(new SwimUnknown(1));
            assertThat(suspect.state()).isInstanceOf(MembershipState.Suspect.class);
        }

        /// H2 (Wave 7): DEPARTING absorbs everything except `Stopped` and a STRICTLY-NEWER-incarnation
        /// `SwimHealthy`. A same-incarnation `SwimHealthy` (mere liveness while draining) and all
        /// other signals are absorbed.
        @Test
        void departing_absorbsNonStopSignals_atKnownIncarnation() {
            var h = harnessInMember();
            h.dispatch(new SwimSuspect(50));   // raise the incarnation high-water mark to 50
            h.dispatch(new SwimHealthy(50));   // recover at 50 (SUSPECT → MEMBER)
            h.dispatch(new DrainRequested());
            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);

            h.dispatch(new SwimHealthy(50));   // same incarnation — mere liveness, no recovery
            h.dispatch(new SwimSuspect(50));
            h.dispatch(new PeerConnected());
            h.dispatch(new UpHysteresisMet());

            assertThat(h.state())
                    .as("DEPARTING absorbs everything but Stopped and a NEWER-incarnation SwimHealthy")
                    .isInstanceOf(MembershipState.Departing.class);
        }

        /// H2 (Wave 7): a `SwimHealthy` carrying a STRICTLY HIGHER incarnation than the high-water
        /// mark recovers a DEPARTING member back to MEMBER (recovered mid-drain — fresh evidence of
        /// life, mirroring the DEAD rejoin fence).
        @Test
        void departing_swimHealthyNewerIncarnation_recoversToMember() {
            var h = harnessInMember();
            h.dispatch(new SwimSuspect(50));   // high-water mark = 50
            h.dispatch(new SwimHealthy(50));
            h.dispatch(new DrainRequested());
            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);

            h.dispatch(new SwimHealthy(51));   // strictly higher — recovered mid-drain

            assertThat(h.state()).isInstanceOf(MembershipState.Member.class);
            assertThat(h.state().countsTowardEffective()).isTrue();
        }

        /// H2 fence: a LOWER-incarnation `SwimHealthy` (stale gossip echo) never recovers DEPARTING.
        @Test
        void departing_swimHealthyLowerIncarnation_staysDeparting() {
            var h = harnessInMember();
            h.dispatch(new SwimSuspect(50));
            h.dispatch(new SwimHealthy(50));
            h.dispatch(new DrainRequested());

            h.dispatch(new SwimHealthy(49));

            assertThat(h.state()).isInstanceOf(MembershipState.Departing.class);
        }
    }

    @Nested
    class Invariants {
        /// Invariant I5: every (state, event) pair is defined — dispatching ANY event in ANY state
        /// must not throw and must leave the FSM in a valid state.
        @ParameterizedTest
        @MethodSource("allEventsForEveryState")
        void everyStateEventPair_isDefined_noException(String stateLabel,
                                                       FsmTestHarness<MembershipState, MembershipEvent> h,
                                                       MembershipEvent event) {
            h.dispatch(event);

            assertThat(h.state())
                    .as("(%s, %s) leaves the FSM in a defined state", stateLabel, event)
                    .isNotNull();
        }

        static Stream<org.junit.jupiter.params.provider.Arguments> allEventsForEveryState() {
            return everyState().stream()
                               .flatMap(MembershipStateTest::pairWithEachEvent);
        }

        /// `countsTowardEffective()` matches the spec §3.1 table: OBSERVED/DEPARTING/DEAD false,
        /// MEMBER/SUSPECT true.
        @Test
        void countsTowardEffective_matchesSpecTable() {
            assertThat(observedHarness().state().countsTowardEffective()).isFalse();
            assertThat(harnessInMember().state().countsTowardEffective()).isTrue();

            var suspect = harnessInMember();
            suspect.dispatch(new SwimSuspect(1));
            assertThat(suspect.state().countsTowardEffective()).isTrue();

            var departing = harnessInMember();
            departing.dispatch(new DrainRequested());
            assertThat(departing.state().countsTowardEffective()).isFalse();

            assertThat(harnessInDead(1).state().countsTowardEffective()).isFalse();
        }
    }

    // --- Parameterized fixture builders ---

    private record StateFixture(String label, java.util.function.Supplier<FsmTestHarness<MembershipState, MembershipEvent>> builder) {}

    private static List<StateFixture> everyState() {
        return List.of(new StateFixture("OBSERVED", MembershipStateTest::observedHarness),
                       new StateFixture("MEMBER", MembershipStateTest::harnessInMember),
                       new StateFixture("SUSPECT", MembershipStateTest::suspectHarness),
                       new StateFixture("DEPARTING", MembershipStateTest::departingHarness),
                       new StateFixture("DEAD", () -> harnessInDead(1)));
    }

    private static FsmTestHarness<MembershipState, MembershipEvent> suspectHarness() {
        var h = harnessInMember();
        h.dispatch(new SwimSuspect(1));
        return h;
    }

    private static FsmTestHarness<MembershipState, MembershipEvent> departingHarness() {
        var h = harnessInMember();
        h.dispatch(new DrainRequested());
        return h;
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> pairWithEachEvent(StateFixture fixture) {
        return allEvents().stream()
                          .map(event -> org.junit.jupiter.params.provider.Arguments.of(fixture.label(),
                                                                                       fixture.builder().get(),
                                                                                       event));
    }

    private static List<MembershipEvent> allEvents() {
        return List.of(new SwimHealthy(1),
                       new SwimSuspect(1),
                       new SwimFaulty(1),
                       new SwimDeparted(1),
                       new SwimUnknown(1),
                       new PeerConnected(),
                       new PeerDisconnected(),
                       new LivenessGone(),
                       new UpHysteresisMet(),
                       new DownHysteresisMet(),
                       new DrainRequested(),
                       new Stopped(),
                       new JoinGraceExpiredNeverHealthy());
    }
}
