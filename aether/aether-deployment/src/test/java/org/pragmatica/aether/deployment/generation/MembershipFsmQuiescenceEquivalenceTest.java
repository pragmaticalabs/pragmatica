// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.ClusterGenerationProjector.ProjectionInput;
import org.pragmatica.aether.deployment.membership.fsm.MembershipFsm;
import org.pragmatica.aether.deployment.membership.ntt.NodeTopologyTracker;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.swim.HealthSnapshot;

import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;

/// Behavior-preservation harness for the membership-FSM unification (Wave D, consumer #4).
///
/// The cluster-quiescence gate ([`ClusterGenerationProjector#deriveClusterQuiescence`]) is #68-critical
/// and must NOT change semantics when its health source migrates from the SWIM-hints registry to the
/// authoritative [`MembershipFsm`]. These tests drive a real FSM into representative states, take its
/// [`MembershipFsm#healthHints`] projection, and assert the projector yields the SAME
/// DEGRADED / QUIESCED verdict it would have produced from an equivalent hand-built swimHints map —
/// proving the migration is verdict-equivalent for the states the gate distinguishes (FAULTY,
/// SUSPECTED, healthy).
class MembershipFsmQuiescenceEquivalenceTest {
    private static final NodeId A = new NodeId("node-a");
    private static final NodeId B = new NodeId("node-b");
    private static final NodeId C = new NodeId("node-c");
    private static final NodeId NTT_SELF = new NodeId("ntt-self");
    private static final TimeSpan INTERVAL = TimeSpan.timeSpan(100).millis();
    private static final ClusterGenerationProjector PROJECTOR = ClusterGenerationProjector.clusterGenerationProjector();

    @Test
    void allHealthyMembers_fsmHints_quiesce_matchingEmptySwimHints() {
        var manager = activeManager();
        promoteToMember(manager, A);
        promoteToMember(manager, B);

        assertVerdictEquivalent(manager, Map.of());
        assertThat(projectFrom(manager.healthHints(), members(A, B)).quiescence()).isEqualTo(
            projectFrom(Map.of(), members(A, B)).quiescence());
    }

    @Test
    void faultyMember_fsmHints_degrade_matchingFaultySwimHints() {
        var manager = activeManager();
        promoteToMember(manager, A);
        driveToDead(manager, B, 4L);

        assertVerdictEquivalent(manager, Map.of(B, HealthHint.FAULTY));
    }

    @Test
    void suspectMember_fsmHints_degrade_matchingSuspectedSwimHints() {
        var manager = activeManager();
        promoteToMember(manager, A);
        promoteToMember(manager, B);
        manager.onSwimSuspect(B, 2L);

        assertVerdictEquivalent(manager, Map.of(B, HealthHint.SUSPECTED));
    }

    @Test
    void mixedFaultyAndSuspect_fsmHints_degrade_matchingMixedSwimHints() {
        var manager = activeManager();
        promoteToMember(manager, A);
        manager.onSwimSuspect(B, 2L);
        promoteToMember(manager, B);
        manager.onSwimSuspect(B, 3L);
        driveToDead(manager, C, 4L);

        assertVerdictEquivalent(manager, Map.of(B, HealthHint.SUSPECTED, C, HealthHint.FAULTY));
    }

    /// Assert that projecting with the FSM-derived hints yields BOTH (a) the identical
    /// `healthHints()` map the swimHints path would have carried, and (b) the identical quiescence
    /// verdict. The lifecycle (presence) set is the union of all member ids the FSM tracks, so the
    /// projector sees the same members under both sources.
    private void assertVerdictEquivalent(MembershipFsm manager, Map<NodeId, HealthHint> expectedSwimHints) {
        assertThat(manager.healthHints()).isEqualTo(expectedSwimHints);

        var lifecycles = members(A, B, C);
        var fromFsm = projectFrom(manager.healthHints(), lifecycles);
        var fromSwim = projectFrom(expectedSwimHints, lifecycles);

        assertThat(fromFsm.quiescence()).isEqualTo(fromSwim.quiescence());
    }

    private static ClusterGenerationSnapshot projectFrom(Map<NodeId, HealthHint> hints,
                                                         Map<NodeId, MemberLifecycle> lifecycles) {
        var input = ProjectionInput.projectionInput(1L,
                                                    0L,
                                                    3,
                                                    GenerationReason.LEADER_ELECTED,
                                                    HlcTimestamp.ZERO,
                                                    lifecycles,
                                                    Map.of(),
                                                    Map.of(),
                                                    Map.of(),
                                                    Map.of(),
                                                    Map.of(),
                                                    Map.of(),
                                                    Set.of(),
                                                    hints);
        return PROJECTOR.project(input);
    }

    private static Map<NodeId, MemberLifecycle> members(NodeId... ids) {
        var result = new java.util.LinkedHashMap<NodeId, MemberLifecycle>();
        for (var id : ids) {
            result.put(id, MemberLifecycle.memberLifecycle("host-" + id.id(), 9001));
        }
        return Map.copyOf(result);
    }

    private static MembershipFsm activeManager() {
        Supplier<HealthSnapshot> health = () -> HealthSnapshot.healthSnapshot(Map.of());
        var ntt = NodeTopologyTracker.nodeTopologyTracker(NTT_SELF, health, INTERVAL, 2, 3, () -> 0L);
        return MembershipFsm.membershipFsm(ntt);
    }

    private static void promoteToMember(MembershipFsm manager, NodeId id) {
        manager.onSwimHealthy(id, 1L);
    }

    private static void driveToDead(MembershipFsm manager, NodeId id, long incarnation) {
        promoteToMember(manager, id);
        manager.onSwimFaulty(id, incarnation);
        manager.onLivenessGone(id);
    }
}
