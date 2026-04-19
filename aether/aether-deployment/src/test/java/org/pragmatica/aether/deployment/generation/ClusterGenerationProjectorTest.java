// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.generation;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.deployment.generation.ClusterGenerationProjector.ProjectionInput;
import org.pragmatica.aether.slice.generation.ClusterMode;
import org.pragmatica.aether.slice.generation.ClusterQuiescence;
import org.pragmatica.aether.slice.generation.CommunityQuiescence;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.generation.GenerationReason;
import org.pragmatica.aether.slice.generation.HealthHint;
import org.pragmatica.aether.slice.kvstore.AetherValue.DhtPartitionOwnershipValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleState;
import org.pragmatica.aether.slice.kvstore.AetherValue.NodeLifecycleValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SpokesmanValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


class ClusterGenerationProjectorTest {
    private static final NodeId NODE_A = NodeId.nodeId("node-a").unwrap();
    private static final NodeId NODE_B = NodeId.nodeId("node-b").unwrap();
    private static final NodeId NODE_C = NodeId.nodeId("node-c").unwrap();
    private static final ClusterGenerationProjector PROJECTOR = ClusterGenerationProjector.clusterGenerationProjector();

    private static ProjectionInput emptyInput() {
        return ProjectionInput.projectionInput(7L,
                                               3L,
                                               3,
                                               GenerationReason.PERIODIC_REFRESH,
                                               HlcTimestamp.ZERO,
                                               Map.of(),
                                               Map.of(),
                                               Map.of(),
                                               Map.of(),
                                               Map.of(),
                                               Map.of(),
                                               Map.of());
    }

    @Nested
    class CoreMembers {
        @Test
        void project_lifecycleEntries_becomeCoreMembers() {
            var lifecycles = Map.of(NODE_A,
                                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY, "host-a", 9001));
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
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.coreMembers()).containsOnlyKeys(NODE_A);
            assertThat(snapshot.coreMembers().get(NODE_A).host()).isEqualTo("host-a");
            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.HEALTHY);
        }

        @Test
        void project_drainingLifecycle_mapsToSuspectedHint() {
            var lifecycles = Map.of(NODE_A,
                                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, "host-a", 9001));
            var input = inputWithLifecycles(lifecycles);

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.SUSPECTED);
        }

        @Test
        void project_decommissionedLifecycle_mapsToFaultyHint() {
            var lifecycles = Map.of(NODE_A,
                                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED, "host-a", 9001));
            var input = inputWithLifecycles(lifecycles);

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.coreMembers().get(NODE_A).healthHint()).isEqualTo(HealthHint.FAULTY);
        }

        @Test
        void project_lastSeenEpoch_pulledFromInputMap() {
            var lastSeen = Map.of(NODE_A, Epoch.epoch(7L, 42L));
            var input = ProjectionInput.projectionInput(7L,
                                                         43L,
                                                         3,
                                                         GenerationReason.PERIODIC_REFRESH,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(NODE_A,
                                                                NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.ON_DUTY,
                                                                                                       "host-a",
                                                                                                       9001)),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         lastSeen,
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.coreMembers().get(NODE_A).lastSeenEpoch()).isEqualTo(Epoch.epoch(7L, 42L));
        }

        private ProjectionInput inputWithLifecycles(Map<NodeId, NodeLifecycleValue> lifecycles) {
            return ProjectionInput.projectionInput(1L,
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
                                                     Map.of());
        }
    }

    @Nested
    class Communities {
        @Test
        void project_governorAnnouncement_becomesCommunitySummary() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A,
                                                                                    List.of(NODE_A, NODE_B),
                                                                                    "10.0.0.1:9001");
            var input = inputWithGovernors(Map.of("worker-pool-a", announcement));

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.communities()).containsOnlyKeys("worker-pool-a");
            assertThat(snapshot.communities().get("worker-pool-a").governorNodeId()).isEqualTo(NODE_A);
            assertThat(snapshot.communities().get("worker-pool-a").memberCount()).isEqualTo(2);
        }

        @Test
        void project_spokesmanAtom_populatesAssignedSpokesman() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A,
                                                                                    List.of(NODE_A),
                                                                                    "10.0.0.1:9001");
            var spokesmen = Map.of(NODE_B,
                                   SpokesmanValue.spokesmanValue(List.of("worker-pool-a"),
                                                                  Epoch.epoch(1L, 1L),
                                                                  HlcTimestamp.ZERO,
                                                                  1L));
            var input = ProjectionInput.projectionInput(1L,
                                                         2L,
                                                         3,
                                                         GenerationReason.COMMUNITY_FORMED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of("worker-pool-a", announcement),
                                                         Map.of(),
                                                         spokesmen,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            var assigned = snapshot.communities().get("worker-pool-a").assignedSpokesman();
            assertThat(assigned.isPresent()).isTrue();
            assigned.onPresent(id -> assertThat(id).isEqualTo(NODE_B));
        }

        @Test
        void project_dissolvedCommunity_quiescenceIsDissolving() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 0).withDissolved();
            var input = inputWithGovernors(Map.of("worker-pool-a", announcement));

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.communities().get("worker-pool-a").quiescence()).isEqualTo(CommunityQuiescence.DISSOLVING);
        }

        @Test
        void project_communityEpochAheadOfLastAck_quiescenceIsConverging() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A,
                                                                                    List.of(NODE_A),
                                                                                    "10.0.0.1:9001",
                                                                                    System.currentTimeMillis(),
                                                                                    2L,
                                                                                    Epoch.epoch(2L, 3L),
                                                                                    Epoch.ZERO,
                                                                                    HlcTimestamp.ZERO,
                                                                                    false);
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.COMMUNITY_FORMED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of("worker-pool-a", announcement),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of("worker-pool-a", Epoch.epoch(2L, 1L)),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.communities().get("worker-pool-a").quiescence()).isEqualTo(CommunityQuiescence.CONVERGING);
        }

        @Test
        void project_communityEpochMatchesLastAck_quiescenceIsQuiesced() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A,
                                                                                    List.of(NODE_A),
                                                                                    "10.0.0.1:9001",
                                                                                    System.currentTimeMillis(),
                                                                                    2L,
                                                                                    Epoch.epoch(2L, 3L),
                                                                                    Epoch.ZERO,
                                                                                    HlcTimestamp.ZERO,
                                                                                    false);
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.COMMUNITY_FORMED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of("worker-pool-a", announcement),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of("worker-pool-a", Epoch.epoch(2L, 3L)),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.communities().get("worker-pool-a").quiescence()).isEqualTo(CommunityQuiescence.QUIESCED);
        }

        private ProjectionInput inputWithGovernors(Map<String, GovernorAnnouncementValue> governors) {
            return ProjectionInput.projectionInput(1L,
                                                     0L,
                                                     3,
                                                     GenerationReason.COMMUNITY_FORMED,
                                                     HlcTimestamp.ZERO,
                                                     Map.of(),
                                                     governors,
                                                     Map.of(),
                                                     Map.of(),
                                                     Map.of(),
                                                     Map.of(),
                                                     Map.of());
        }
    }

    @Nested
    class Partitions {
        @Test
        void project_partitionValues_becomePartitionOwners() {
            var ownership = DhtPartitionOwnershipValue.dhtPartitionOwnershipValue(NODE_A,
                                                                                   "core",
                                                                                   Epoch.epoch(1L, 0L),
                                                                                   1L,
                                                                                   HlcTimestamp.ZERO);
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.LEADER_ELECTED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of("core", ownership),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.partitions()).containsOnlyKeys("core");
            assertThat(snapshot.partitions().get("core").ownerNodeId()).isEqualTo(NODE_A);
            assertThat(snapshot.partitions().get("core").ownershipTerm()).isEqualTo(1L);
        }
    }

    @Nested
    class DerivedMode {
        @Test
        void project_onlyCoreCommunity_modeIsCoreOnly() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 1);
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.LEADER_ELECTED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of("core", announcement),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.derivedMode()).isEqualTo(ClusterMode.CORE_ONLY);
        }

        @Test
        void project_anyWorkerCommunity_modeIsHierarchical() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A, 1);
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.COMMUNITY_FORMED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of("worker-pool-a", announcement),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.derivedMode()).isEqualTo(ClusterMode.HIERARCHICAL);
        }

        @Test
        void project_noCommunities_modeIsCoreOnly() {
            var snapshot = PROJECTOR.project(emptyInput());

            assertThat(snapshot.derivedMode()).isEqualTo(ClusterMode.CORE_ONLY);
        }
    }

    @Nested
    class ClusterQuiescenceRules {
        @Test
        void project_allHealthyNoPending_clusterIsQuiesced() {
            var snapshot = PROJECTOR.project(emptyInput());

            assertThat(snapshot.quiescence()).isEqualTo(ClusterQuiescence.QUIESCED);
            assertThat(snapshot.quiescenceDetail()).isEmpty();
        }

        @Test
        void project_faultyMember_clusterIsDegraded() {
            var lifecycles = Map.of(NODE_A,
                                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DECOMMISSIONED,
                                                                           "host-a",
                                                                           9001));
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.HEALTH_CHANGE,
                                                         HlcTimestamp.ZERO,
                                                         lifecycles,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.quiescence()).isEqualTo(ClusterQuiescence.DEGRADED);
            assertThat(snapshot.quiescenceDetail()).contains("FAULTY");
        }

        @Test
        void project_suspectedMember_clusterIsDegraded() {
            var lifecycles = Map.of(NODE_A,
                                    NodeLifecycleValue.nodeLifecycleValue(NodeLifecycleState.DRAINING, "host-a", 9001));
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.HEALTH_CHANGE,
                                                         HlcTimestamp.ZERO,
                                                         lifecycles,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.quiescence()).isEqualTo(ClusterQuiescence.DEGRADED);
            assertThat(snapshot.quiescenceDetail()).contains("SUSPECTED");
        }

        @Test
        void project_communityAwaitingSpokesman_clusterIsConverging() {
            var announcement = GovernorAnnouncementValue.governorAnnouncementValue(NODE_A,
                                                                                    List.of(NODE_A),
                                                                                    "10.0.0.1:9001");
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.COMMUNITY_FORMED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of("worker-pool-a", announcement),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.quiescence()).isEqualTo(ClusterQuiescence.CONVERGING);
            assertThat(snapshot.quiescenceDetail()).contains("awaiting spokesman");
        }
    }

    @Nested
    class Epochs {
        @Test
        void project_rabiaTermAndCounter_formExpectedEpoch() {
            var input = ProjectionInput.projectionInput(42L,
                                                         17L,
                                                         3,
                                                         GenerationReason.LEADER_ELECTED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.epoch()).isEqualTo(Epoch.epoch(42L, 17L));
            assertThat(snapshot.rabiaTerm()).isEqualTo(42L);
        }

        @Test
        void project_preservesReason() {
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         3,
                                                         GenerationReason.MEMBER_REMOVED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.reason()).isEqualTo(GenerationReason.MEMBER_REMOVED);
        }

        @Test
        void project_preservesDesiredCoreSize() {
            var input = ProjectionInput.projectionInput(1L,
                                                         0L,
                                                         5,
                                                         GenerationReason.CLUSTER_SIZE_CHANGED,
                                                         HlcTimestamp.ZERO,
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of(),
                                                         Map.of());

            var snapshot = PROJECTOR.project(input);

            assertThat(snapshot.desiredCoreSize()).isEqualTo(5);
        }
    }
}
