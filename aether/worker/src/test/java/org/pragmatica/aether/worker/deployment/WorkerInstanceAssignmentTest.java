package org.pragmatica.aether.worker.deployment;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.consensus.NodeId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class WorkerInstanceAssignmentTest {
    private static NodeId id(String name) {
        return NodeId.nodeId(name).unwrap();
    }

    private static Artifact artifact(String coords) {
        return Artifact.artifact(coords).unwrap();
    }

    @Nested
    class AssignedInstances {
        @Test
        void assignedInstances_returnsZero_whenNoMembers() {
            var result = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), 3, List.of(), id("worker-1"));

            assertThat(result).isZero();
        }

        @Test
        void assignedInstances_returnsZero_whenZeroTarget() {
            var result = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), 0, List.of(id("worker-1")), id("worker-1"));

            assertThat(result).isZero();
        }

        @Test
        void assignedInstances_assignsAll_whenSingleMember() {
            var result = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), 3, List.of(id("worker-1")), id("worker-1"));

            assertThat(result).isEqualTo(3);
        }

        @Test
        void assignedInstances_distributesFairly_acrossMembers() {
            var members = List.of(id("worker-1"), id("worker-2"), id("worker-3"));
            var total = 0;

            for (var member : members) {
                total += WorkerInstanceAssignment.assignedInstances(
                    artifact("com.example:test:1.0.0"), 6, members, member);
            }

            assertThat(total).isEqualTo(6);
        }

        @Test
        void assignedInstances_handlesUnevenDistribution() {
            var members = List.of(id("worker-1"), id("worker-2"), id("worker-3"));
            var total = 0;

            for (var member : members) {
                total += WorkerInstanceAssignment.assignedInstances(
                    artifact("com.example:test:1.0.0"), 7, members, member);
            }

            assertThat(total).isEqualTo(7);
        }

        @Test
        void assignedInstances_isDeterministic() {
            var members = List.of(id("worker-1"), id("worker-2"), id("worker-3"));
            var first = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), 5, members, id("worker-2"));
            var second = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), 5, members, id("worker-2"));

            assertThat(first).isEqualTo(second);
        }

        @Test
        void assignedInstances_returnsZero_whenSelfNotInMembers() {
            var members = List.of(id("worker-1"), id("worker-2"));
            var result = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), 3, members, id("worker-3"));

            assertThat(result).isZero();
        }

        @Test
        void assignedInstances_returnsZero_whenNegativeTarget() {
            var result = WorkerInstanceAssignment.assignedInstances(
                artifact("com.example:test:1.0.0"), -1, List.of(id("worker-1")), id("worker-1"));

            assertThat(result).isZero();
        }

        @Test
        void assignedInstances_eachMemberGetsAtLeastBase_whenEvenlyDivisible() {
            var members = List.of(id("worker-1"), id("worker-2"), id("worker-3"));

            for (var member : members) {
                var assigned = WorkerInstanceAssignment.assignedInstances(
                    artifact("com.example:test:1.0.0"), 9, members, member);
                assertThat(assigned).isEqualTo(3);
            }
        }
    }
}
