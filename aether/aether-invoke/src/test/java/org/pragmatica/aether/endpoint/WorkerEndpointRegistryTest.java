package org.pragmatica.aether.endpoint;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.MethodName;
import org.pragmatica.consensus.NodeId;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.endpoint.WorkerEndpointEntry.workerEndpointEntry;
import static org.pragmatica.aether.endpoint.WorkerEndpointRegistry.workerEndpointRegistry;
import static org.pragmatica.aether.endpoint.WorkerGroupHealthReport.workerGroupHealthReport;

class WorkerEndpointRegistryTest {
    private WorkerEndpointRegistry registry;
    private Artifact artifact;
    private MethodName method;
    private NodeId workerA;
    private NodeId workerB;
    private NodeId workerC;
    private NodeId governor;

    @BeforeEach
    void setUp() {
        registry = workerEndpointRegistry();
        artifact = Artifact.artifact("org.example:my-slice:1.0.0").unwrap();
        method = MethodName.methodName("processRequest").unwrap();
        workerA = new NodeId("worker-a");
        workerB = new NodeId("worker-b");
        workerC = new NodeId("worker-c");
        governor = new NodeId("governor-1");
    }

    private WorkerGroupHealthReport buildReport(List<WorkerEndpointEntry> endpoints, List<NodeId> members) {
        return workerGroupHealthReport(governor, "main", endpoints, members);
    }

    @Nested
    class RegisterWorkerEndpoints {
        @Test
        void registerWorkerEndpoints_withValidReport_populatesRegistry() {
            var entries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0),
                workerEndpointEntry(artifact, method, workerB, 1)
            );
            var report = buildReport(entries, List.of(workerA, workerB));

            registry.registerWorkerEndpoints(report);

            assertThat(registry.isEmpty()).isFalse();
            assertThat(registry.allWorkerEndpoints(artifact, method)).hasSize(2);
        }

        @Test
        void registerWorkerEndpoints_replacesExistingGroupEntries() {
            var initialEntries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0)
            );
            registry.registerWorkerEndpoints(buildReport(initialEntries, List.of(workerA)));

            var updatedEntries = List.of(
                workerEndpointEntry(artifact, method, workerB, 0),
                workerEndpointEntry(artifact, method, workerC, 1)
            );
            registry.registerWorkerEndpoints(buildReport(updatedEntries, List.of(workerB, workerC)));

            var endpoints = registry.allWorkerEndpoints(artifact, method);

            assertThat(endpoints).hasSize(2);
            assertThat(endpoints).extracting(WorkerEndpointEntry::workerNodeId)
                                 .containsExactlyInAnyOrder(workerB, workerC);
        }
    }

    @Nested
    class SelectWorkerEndpoint {
        @Test
        void selectWorkerEndpoint_emptyRegistry_returnsNone() {
            var result = registry.selectWorkerEndpoint(artifact, method);

            assertThat(result.isEmpty()).isTrue();
        }

        @Test
        void selectWorkerEndpoint_withEndpoints_roundRobinsAcrossNodes() {
            var entries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0),
                workerEndpointEntry(artifact, method, workerB, 1),
                workerEndpointEntry(artifact, method, workerC, 2)
            );
            registry.registerWorkerEndpoints(buildReport(entries, List.of(workerA, workerB, workerC)));

            var first = registry.selectWorkerEndpoint(artifact, method);
            var second = registry.selectWorkerEndpoint(artifact, method);
            var third = registry.selectWorkerEndpoint(artifact, method);
            var fourth = registry.selectWorkerEndpoint(artifact, method);

            assertThat(first.isPresent()).isTrue();
            assertThat(second.isPresent()).isTrue();
            assertThat(third.isPresent()).isTrue();
            // Fourth call wraps around to the same as first
            assertThat(fourth.unwrap().workerNodeId()).isEqualTo(first.unwrap().workerNodeId());
        }

        @Test
        void selectWorkerEndpoint_noMatchingArtifact_returnsNone() {
            var entries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0)
            );
            registry.registerWorkerEndpoints(buildReport(entries, List.of(workerA)));

            var otherArtifact = Artifact.artifact("org.other:other-slice:2.0.0").unwrap();
            var result = registry.selectWorkerEndpoint(otherArtifact, method);

            assertThat(result.isEmpty()).isTrue();
        }
    }

    @Nested
    class RemoveGroup {
        @Test
        void removeGroup_existingGroup_removesAllEntries() {
            var entries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0),
                workerEndpointEntry(artifact, method, workerB, 1)
            );
            registry.registerWorkerEndpoints(buildReport(entries, List.of(workerA, workerB)));

            registry.removeGroup("main");

            assertThat(registry.isEmpty()).isTrue();
            assertThat(registry.allWorkerEndpoints(artifact, method)).isEmpty();
        }

        @Test
        void removeGroup_nonExistentGroup_noEffect() {
            var entries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0)
            );
            registry.registerWorkerEndpoints(buildReport(entries, List.of(workerA)));

            registry.removeGroup("other-group");

            assertThat(registry.isEmpty()).isFalse();
        }
    }

    @Nested
    class WorkerCount {
        @Test
        void workerCount_emptyRegistry_returnsZero() {
            assertThat(registry.workerCount()).isZero();
        }

        @Test
        void workerCount_multipleEndpointsSameWorker_countsDistinct() {
            var otherMethod = MethodName.methodName("otherMethod").unwrap();
            var entries = List.of(
                workerEndpointEntry(artifact, method, workerA, 0),
                workerEndpointEntry(artifact, otherMethod, workerA, 1),
                workerEndpointEntry(artifact, method, workerB, 2)
            );
            registry.registerWorkerEndpoints(buildReport(entries, List.of(workerA, workerB)));

            assertThat(registry.workerCount()).isEqualTo(2);
        }
    }
}
