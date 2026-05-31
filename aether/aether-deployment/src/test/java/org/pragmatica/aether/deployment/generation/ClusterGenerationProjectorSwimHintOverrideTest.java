// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.ClusterGenerationProjector.ProjectionInput;
import org.pragmatica.aether.slice.generation.ClusterGenerationSnapshot;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Regression test for the `swimHints` override path in
/// `ClusterGenerationProjector.deriveHealthHint`.
///
/// Membership-v2 finale: presence IS membership, so every present member is `HEALTHY` by
/// construction. The leader-side `swimHints` map plumbed through `ProjectionInput` is the SOLE
/// mechanism that downgrades a member's projected `healthHint` (FAULTY / SUSPECTED), so CTM can
/// see a deficit and drive auto-heal promptly after SWIM detection.
class ClusterGenerationProjectorSwimHintOverrideTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final ClusterGenerationProjector PROJECTOR = ClusterGenerationProjector.clusterGenerationProjector();

    @Nested
    class Override {
        @Test
        void presentPeerWithFaultySwimHint_projectsAsFaulty() {
            var lifecycles = Map.of(NODE_A,
                                     MemberLifecycle.memberLifecycle("host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.FAULTY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void presentPeerWithSuspectedSwimHint_projectsAsSuspected() {
            var lifecycles = Map.of(NODE_A,
                                     MemberLifecycle.memberLifecycle("host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.SUSPECTED);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void presentPeerWithHealthySwimHint_projectsAsHealthy() {
            var lifecycles = Map.of(NODE_A,
                                     MemberLifecycle.memberLifecycle("host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.HEALTHY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void absentSwimHint_fallsBackToHealthyByConstruction() {
            var lifecycles = Map.of(NODE_A,
                                     MemberLifecycle.memberLifecycle("host-a", 9001));

            var snapshot = projectWithSwimHints(lifecycles, Map.of());

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }
    }

    @Nested
    class Priority {
        @Test
        void presentPeerWithFaultySwimHint_projectsAsFaulty_swimHintWins() {
            var lifecycles = Map.of(NODE_A,
                                     MemberLifecycle.memberLifecycle("host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.FAULTY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void presentPeerWithFaultySwimHint_excludedFromHealthyOnDutyCount() {
            var lifecycles = Map.of(NODE_A,
                                     MemberLifecycle.memberLifecycle("host-a", 9001),
                                    NODE_B,
                                     MemberLifecycle.memberLifecycle("host-b", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.FAULTY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            // Presence members are on duty; healthy-on-duty count reflects the SWIM override:
            // NODE_A is FAULTY (excluded); NODE_B is HEALTHY (included).
            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
            assertThat(snapshot.coreMembers().get(NODE_B).healthHint()).isEqualTo(HealthHint.HEALTHY);
            var healthyOnDuty = countHealthyOnDuty(snapshot);
            assertThat(healthyOnDuty).isEqualTo(1);
        }

        private long countHealthyOnDuty(ClusterGenerationSnapshot snapshot) {
            return snapshot.coreMembers().values().stream()
                                                  .filter(member -> member.healthHint() == HealthHint.HEALTHY)
                                                  .count();
        }
    }

    private static ClusterGenerationSnapshot projectWithSwimHints(Map<NodeId, MemberLifecycle> lifecycles,
                                                                  Map<NodeId, HealthHint> swimHints) {
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
                                                     swimHints);
        return PROJECTOR.project(input);
    }
}
