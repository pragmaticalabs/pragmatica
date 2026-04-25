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
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;


/// Theme A — Fix 3: regression test for the swimHints override path in
/// `ClusterGenerationProjector.deriveHealthHint`.
///
/// Without the override, `CoreMember.healthHint()` is derived solely from `NodeLifecycleState`
/// — meaning a peer SWIM has marked FAULTY remains projected as `HEALTHY` until the eviction
/// path writes `DECOMMISSIONED` (>=10 misses, ~25 s after detection). This delay leaves
/// `MembershipView.healthyOnDutyCount()` blind to detected failures, so CTM sees no deficit and
/// auto-heal stalls.
///
/// After the fix, the leader-side `swimHints` map is plumbed through `ProjectionInput` and
/// overrides the lifecycle-derived hint when its value is **strictly worse**
/// (FAULTY > SUSPECTED > HEALTHY).
class ClusterGenerationProjectorSwimHintOverrideTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final ClusterGenerationProjector PROJECTOR = ClusterGenerationProjector.clusterGenerationProjector();

    @Nested
    class Override {
        @Test
        void onDutyPeerWithFaultySwimHint_projectsAsFaulty() {
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.FAULTY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void onDutyPeerWithSuspectedSwimHint_projectsAsSuspected() {
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.SUSPECTED);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void onDutyPeerWithHealthySwimHint_projectsAsHealthy() {
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.HEALTHY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void absentSwimHint_fallsBackToLifecycleDerivedHint() {
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, "host-a", 9001));

            var snapshot = projectWithSwimHints(lifecycles, Map.of());

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }
    }

    @Nested
    class Priority {
        @Test
        void drainingLifecycleWithFaultySwimHint_projectsAsFaulty_swimHintIsWorse() {
            // Lifecycle DRAINING -> SUSPECTED baseline; swim hint FAULTY is strictly worse.
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, "host-a", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.FAULTY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void decommissionedLifecycleWithSuspectedSwimHint_projectsAsFaulty_lifecycleIsWorse() {
            // Lifecycle DECOMMISSIONED -> FAULTY baseline; swim hint SUSPECTED is weaker — must NOT downgrade.
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                                            "host-a",
                                                                            9001));
            var swimHints = Map.of(NODE_A, HealthHint.SUSPECTED);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void onDutyLifecycleWithFaultySwimHint_excludedFromHealthyOnDutyCount() {
            var lifecycles = Map.of(NODE_A,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-a", 9001),
                                    NODE_B,
                                     NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-b", 9001));
            var swimHints = Map.of(NODE_A, HealthHint.FAULTY);

            var snapshot = projectWithSwimHints(lifecycles, swimHints);

            // Local count of ON_DUTY + HEALTHY members reflects the override:
            // NODE_A is ON_DUTY-but-FAULTY (excluded); NODE_B is ON_DUTY + HEALTHY (included).
            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
            assertThat(snapshot.coreMembers().get(NODE_B).healthHint()).isEqualTo(HealthHint.HEALTHY);
            var healthyOnDuty = countHealthyOnDuty(snapshot);
            assertThat(healthyOnDuty).isEqualTo(1);
        }

        private long countHealthyOnDuty(ClusterGenerationSnapshot snapshot) {
            return snapshot.coreMembers().values().stream()
                                                  .filter(member -> member.lifecycle() == NodeLifecycleState.ON_DUTY)
                                                  .filter(member -> member.healthHint() == HealthHint.HEALTHY)
                                                  .count();
        }
    }

    private static ClusterGenerationSnapshot projectWithSwimHints(Map<NodeId, NodeLifecycleValue> lifecycles,
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
