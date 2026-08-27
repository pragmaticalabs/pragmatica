// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko.
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.statemachine.FsmObserver;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.deployment.generation.PresenceGenerationSnapshotSource.presenceGenerationSnapshotSource;

/// #557 — the COMPOSITION that makes boot quorum a reachability claim, pinned end to end.
///
/// ## The gap this closes
///
/// `MembershipFsmObservedMembersTest` pins `coreObservedMembers` against `coreCountedMembers` one
/// layer down, and `TopologyObserverConnectivityQuorumTest` pins the BOOTING/legacy path. Between
/// them sat the seam that actually decides boot quorum on the NORMAL path — and every case in that
/// second class wires a noop or absent membership view, so **nothing exercised
/// `PresenceGenerationSnapshotSource` against a real seed-only FSM**.
///
/// The consequence was concrete, not theoretical: the wiring is one supplier reference
/// (`AetherNode.presenceMemberSupplier`), and swapping it from `coreObservedMembers` back to
/// `coreCountedMembers` would silently restore #557 — boot quorum declared from configuration —
/// with every existing test still green. This class fails if that happens.
///
/// It is worth stating why the gap mattered beyond coverage arithmetic. `PresenceGenerationSnapshotSource`
/// carried a docstring claiming the supplier was `coreCountedMembers()` for some time after the
/// rewire made it `coreObservedMembers`. #557's own diagnosis comment then described this path as
/// "health assumed, not observed" — a conclusion drawn from the stale text rather than the code. A
/// test that reads the real composition is the thing that keeps prose and behaviour from drifting
/// apart again.
///
/// ## What this pins, and what it HONESTLY does not
///
/// It pins the composition: given the observed projection, the source refuses to publish a view
/// until a quorum is genuinely reachable, and given the counted projection it does not. Mutation-
/// checked — rewiring the helper below to `coreCountedMembers` turns two of these red.
///
/// It does **not** read `AetherNode.presenceMemberSupplier`. That supplier is a local inside a
/// 5000-line assembly method with no test seam, so this class MIRRORS the production wiring rather
/// than asserting it. A refactor that rewires `AetherNode` itself would leave these tests green.
/// What the class does instead is make the consequence of the wrong choice permanent and explicit
/// (see the discriminator below), so the decision is documented where the next reader will meet it.
/// Closing that last gap needs a seam on `AetherNode` and is tracked separately — claiming it here
/// would be exactly the overreach this ticket's history is a lesson in.
///
/// ## What the latch means here
///
/// `currentMembershipView()` returns `none()` until the member count first reaches quorum, which is
/// what lets `TopologyObserver` fall back to its (now connectivity-intersected) legacy count during
/// cold start. Feeding it an OBSERVED set means the latch cannot flip on configuration alone.
class PresenceGenerationSnapshotSourceQuorumCompositionTest {
    private static final NodeId SELF = new NodeId("node-self");
    private static final NodeId PEER_B = new NodeId("node-b");
    private static final NodeId PEER_C = new NodeId("node-c");

    private static final int CORE_SIZE = 3;
    private static final TimeSpan SHORT_BACKSTOP = TimeSpan.timeSpan(40).millis();
    private static final long NO_HINT_DECAY = Long.MAX_VALUE;

    /// THE #557 REGRESSION GUARD. A boot-seeded FSM knows all three cores as MEMBERs before a packet
    /// has moved. Wired through the OBSERVED projection, the source must refuse to publish a view —
    /// so `TopologyObserver` cannot take its `MembershipView` branch and cannot declare quorum.
    @Test
    void bootSeededClusterWithNoReachability_publishesNoView_soQuorumCannotBeDeclared() {
        var fsm = bootSeededCluster();

        assertThat(fsm.coreObservedMembers(SELF))
            .as("precondition: with no reachability evidence the observed projection is self only")
            .containsExactly(SELF);

        var source = observedSource(fsm);

        assertThat(source.currentMembershipView().isEmpty())
            .as("a cluster that has configured 3 cores but reached none of them must NOT publish a "
                + "membership view — publishing one lets TopologyObserver declare quorum against a "
                + "network it has never contacted, which is #557")
            .isTrue();
    }

    /// The DISCRIMINATOR, and the reason this test is not vacuous. The same source wired to the
    /// COUNTED projection — the pre-#557 wiring, and the one a careless refactor would restore —
    /// publishes a view immediately, with quorum satisfied and zero peers reachable. Without this
    /// case the test above would pass just as well against a source that never publishes anything.
    @Test
    void theCountedProjectionWouldDeclareQuorumWithZeroReachability_whichIsWhyTheWiringMatters() {
        var fsm = bootSeededCluster();
        var regressionWiring = presenceGenerationSnapshotSource(fsm::coreCountedMembers,
                                                                () -> CORE_SIZE,
                                                                Set::of,
                                                                () -> 0L);
        var view = regressionWiring.currentMembershipView();

        assertThat(view.isPresent())
            .as("counted membership reports all 3 configured cores at seed time, so this wiring "
                + "publishes a view before any contact — demonstrating the defect the observed "
                + "wiring prevents")
            .isTrue();
        assertThat(view.unwrap().healthyOnDutyCount())
            .as("and it reports a full quorum numerator with zero peers reachable — this is exactly "
                + "the boot-time quorum-from-configuration that #557 reported")
            .isGreaterThanOrEqualTo(CORE_SIZE / 2 + 1);
    }

    /// The gate must OPEN on real evidence, or the guard above would be indistinguishable from a
    /// cluster that can never form. One observed peer plus self is the majority of three.
    @Test
    void onceAQuorumIsActuallyReachable_theViewIsPublished() {
        var fsm = bootSeededCluster();
        var source = observedSource(fsm);

        fsm.onPeerConnected(PEER_B);

        var view = source.currentMembershipView();

        assertThat(view.isPresent())
            .as("self plus one QUIC-connected peer is 2 of 3 — a reachable majority, so the view "
                + "must be published and quorum may be declared")
            .isTrue();
        assertThat(view.unwrap().healthyOnDutyCount())
            .as("the numerator counts self and the observed peer, not the unreached third core")
            .isEqualTo(2);
    }

    /// SWIM ALIVE is the other admissible evidence, and it must count equally — a peer can be
    /// SWIM-observed before the QUIC handshake completes, and refusing that would delay formation
    /// for no safety gain.
    @Test
    void swimObservedPeerCountsAsReachableEvidence() {
        var fsm = bootSeededCluster();
        var source = observedSource(fsm);

        fsm.onSwimHealthy(PEER_B, 1L);

        assertThat(source.currentMembershipView().isPresent())
            .as("SWIM ALIVE is observed reachability just as a completed handshake is")
            .isTrue();
    }

    /// The latch is one-way BY DESIGN: once a reachable quorum has been seen, a later sub-quorum
    /// count is reported as-is to drive dissolve rather than silently reverting to the legacy
    /// fallback. Pinned so the refusal above is never mistaken for a level that flaps.
    @Test
    void afterQuorumIsReached_theViewKeepsBeingPublishedEvenIfObservationDrops() {
        var fsm = bootSeededCluster();
        var source = observedSource(fsm);

        fsm.onPeerConnected(PEER_B);
        assertThat(source.currentMembershipView().isPresent()).isTrue();

        fsm.onPeerDisconnected(PEER_B);

        assertThat(source.currentMembershipView().isPresent())
            .as("the quorum latch is one-way — after formation the presence view owns quorum "
                + "permanently, and a shrinking count must drive dissolve rather than fall back to "
                + "the boot-time path")
            .isTrue();
    }

    private static PresenceGenerationSnapshotSource observedSource(MembershipFsm fsm) {
        // The PRODUCTION wiring, mirroring AetherNode.presenceMemberSupplier.
        return presenceGenerationSnapshotSource(() -> fsm.coreObservedMembers(SELF),
                                                () -> CORE_SIZE,
                                                Set::of,
                                                () -> 0L);
    }

    private static MembershipFsm bootSeededCluster() {
        var fsm = MembershipFsm.membershipFsm(FsmObserver.noop(),
                                              System::currentTimeMillis,
                                              NO_HINT_DECAY,
                                              SHORT_BACKSTOP);

        fsm.seed(Set.of(SELF, PEER_B, PEER_C));

        return fsm;
    }
}
