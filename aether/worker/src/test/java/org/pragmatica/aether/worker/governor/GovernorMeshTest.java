package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorMeshTest {
    private GovernorMesh mesh;

    @BeforeEach
    void setUp() {
        mesh = GovernorMesh.governorMesh();
    }

    private static NodeId id(String name) {
        return NodeId.nodeId(name).unwrap();
    }

    @Nested
    class Registration {

        @Test
        void registerGovernor_storesMapping_whenCalled() {
            mesh.registerGovernor("community-1", id("governor-a"));

            assertThat(mesh.governorFor("community-1").isPresent()).isTrue();
            assertThat(mesh.governorFor("community-1").unwrap()).isEqualTo(id("governor-a"));
        }

        @Test
        void registerGovernor_replacesExisting_whenCalledAgain() {
            mesh.registerGovernor("community-1", id("governor-a"));
            mesh.registerGovernor("community-1", id("governor-b"));

            assertThat(mesh.governorFor("community-1").unwrap()).isEqualTo(id("governor-b"));
        }

        @Test
        void unregisterGovernor_removesMapping_whenExists() {
            mesh.registerGovernor("community-1", id("governor-a"));
            mesh.unregisterGovernor("community-1");

            assertThat(mesh.governorFor("community-1").isEmpty()).isTrue();
        }

        @Test
        void registerGovernor_withTcpAddress_storesMapping() {
            mesh.registerGovernor("community-1", id("governor-a"), "10.0.1.5:7201");

            assertThat(mesh.governorFor("community-1").isPresent()).isTrue();
            assertThat(mesh.governorFor("community-1").unwrap()).isEqualTo(id("governor-a"));
        }

        @Test
        void registerGovernor_withEmptyTcpAddress_storesMapping() {
            mesh.registerGovernor("community-1", id("governor-a"), "");

            assertThat(mesh.governorFor("community-1").isPresent()).isTrue();
        }

        @Test
        void unregisterGovernor_noOp_whenNotExists() {
            mesh.unregisterGovernor("nonexistent");

            assertThat(mesh.allGovernors()).isEmpty();
        }
    }

    @Nested
    class Queries {

        @Test
        void governorFor_returnsNone_whenUnknownCommunity() {
            assertThat(mesh.governorFor("unknown").isEmpty()).isTrue();
        }

        @Test
        void allGovernors_returnsSnapshot_ofCurrentMappings() {
            mesh.registerGovernor("community-1", id("governor-a"));
            mesh.registerGovernor("community-2", id("governor-b"));

            var snapshot = mesh.allGovernors();

            assertThat(snapshot).hasSize(2);
            assertThat(snapshot).containsEntry("community-1", id("governor-a"));
            assertThat(snapshot).containsEntry("community-2", id("governor-b"));
        }

        @Test
        void allGovernors_returnsImmutableSnapshot_afterModification() {
            mesh.registerGovernor("community-1", id("governor-a"));
            var snapshot = mesh.allGovernors();

            mesh.registerGovernor("community-2", id("governor-b"));

            assertThat(snapshot).hasSize(1);
            assertThat(mesh.allGovernors()).hasSize(2);
        }

        @Test
        void hasGovernor_returnsTrue_whenRegistered() {
            mesh.registerGovernor("community-1", id("governor-a"));

            assertThat(mesh.hasGovernor("community-1")).isTrue();
        }

        @Test
        void hasGovernor_returnsFalse_whenNotRegistered() {
            assertThat(mesh.hasGovernor("unknown")).isFalse();
        }
    }
}
