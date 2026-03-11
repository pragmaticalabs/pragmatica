package org.pragmatica.aether.worker.group;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GroupAssignmentTest {

    private static NodeId node(String id) {
        return NodeId.nodeId(id).unwrap();
    }

    @Nested
    class Deterministic {
        @Test
        void computeGroups_sameInputs_sameOutputs() {
            var members = List.of(node("us-east-1-a"), node("us-east-1-b"), node("eu-west-1-c"));
            var result1 = GroupAssignment.computeGroups(members, "default", 100);
            var result2 = GroupAssignment.computeGroups(members, "default", 100);
            assertThat(result1).isEqualTo(result2);
        }

        @Test
        void computeGroups_differentOrder_sameOutputs() {
            var members1 = List.of(node("us-east-1-a"), node("us-east-1-b"), node("eu-west-1-c"));
            var members2 = List.of(node("eu-west-1-c"), node("us-east-1-a"), node("us-east-1-b"));
            assertThat(GroupAssignment.computeGroups(members1, "default", 100))
                .isEqualTo(GroupAssignment.computeGroups(members2, "default", 100));
        }
    }

    @Nested
    class ZoneGrouping {
        @Test
        void computeGroups_singleZone_oneGroup() {
            var members = List.of(node("local-a"), node("local-b"), node("local-c"));
            var result = GroupAssignment.computeGroups(members, "default", 100);
            assertThat(result).hasSize(1);
            assertThat(result.values().iterator().next()).hasSize(3);
        }

        @Test
        void computeGroups_twoZones_twoGroups() {
            var members = List.of(
                node("us-east-1-a"), node("us-east-1-b"),
                node("eu-west-1-c"), node("eu-west-1-d")
            );
            var result = GroupAssignment.computeGroups(members, "default", 100);
            assertThat(result).hasSize(2);
        }

        @Test
        void computeGroups_noZoneInNodeId_usesLocalZone() {
            var members = List.of(node("alpha"), node("beta"));
            var result = GroupAssignment.computeGroups(members, "default", 100);
            assertThat(result).hasSize(1);
            var key = result.keySet().iterator().next();
            assertThat(key.zone()).isEqualTo("local");
        }
    }

    @Nested
    class Splitting {
        @Test
        void computeGroups_exceedsMaxSize_splitsIntoSubgroups() {
            var members = List.of(
                node("zone1-a"), node("zone1-b"), node("zone1-c"),
                node("zone1-d"), node("zone1-e")
            );
            var result = GroupAssignment.computeGroups(members, "default", 2);
            assertThat(result.size()).isGreaterThanOrEqualTo(2);
            var totalMembers = result.values().stream().mapToInt(List::size).sum();
            assertThat(totalMembers).isEqualTo(5);
        }

        @Test
        void computeGroups_exactlyAtMax_noSplit() {
            var members = List.of(node("zone1-a"), node("zone1-b"));
            var result = GroupAssignment.computeGroups(members, "default", 2);
            assertThat(result).hasSize(1);
        }
    }

    @Nested
    class EdgeCases {
        @Test
        void computeGroups_emptyList_emptyResult() {
            var result = GroupAssignment.computeGroups(List.of(), "default", 100);
            assertThat(result).isEmpty();
        }

        @Test
        void computeGroups_singleMember_singleGroup() {
            var result = GroupAssignment.computeGroups(List.of(node("solo")), "default", 100);
            assertThat(result).hasSize(1);
            assertThat(result.values().iterator().next()).containsExactly(node("solo"));
        }

        @Test
        void computeGroups_largeGroup_allMembersPresent() {
            var members = new ArrayList<NodeId>();
            for (int i = 0; i < 150; i++) {
                members.add(node("zone1-node" + String.format("%03d", i)));
            }
            var result = GroupAssignment.computeGroups(members, "prod", 50);
            var totalMembers = result.values().stream().mapToInt(List::size).sum();
            assertThat(totalMembers).isEqualTo(150);
            assertThat(result.size()).isGreaterThanOrEqualTo(3);
        }
    }
}
