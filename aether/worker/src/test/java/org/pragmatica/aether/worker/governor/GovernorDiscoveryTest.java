package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.consensus.NodeId;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorDiscoveryTest {
    private GovernorDiscovery discovery;

    @BeforeEach
    void setUp() {
        discovery = GovernorDiscovery.governorDiscovery();
    }

    private static NodeId id(String name) {
        return NodeId.nodeId(name).unwrap();
    }

    @Nested
    class Announcements {

        @Test
        void onGovernorAnnounced_registersGovernor_whenNew() {
            discovery.onGovernorAnnounced("community-1", id("governor-a"));

            assertThat(discovery.currentGovernor("community-1").isPresent()).isTrue();
            assertThat(discovery.currentGovernor("community-1").unwrap()).isEqualTo(id("governor-a"));
        }

        @Test
        void onGovernorAnnounced_replacesGovernor_whenChanged() {
            discovery.onGovernorAnnounced("community-1", id("governor-a"));
            discovery.onGovernorAnnounced("community-1", id("governor-b"));

            assertThat(discovery.currentGovernor("community-1").unwrap()).isEqualTo(id("governor-b"));
        }

        @Test
        void onGovernorAnnounced_noChange_whenSameGovernor() {
            discovery.onGovernorAnnounced("community-1", id("governor-a"));
            discovery.onGovernorAnnounced("community-1", id("governor-a"));

            assertThat(discovery.currentGovernor("community-1").unwrap()).isEqualTo(id("governor-a"));
            assertThat(discovery.allKnownGovernors()).hasSize(1);
        }
    }

    @Nested
    class Departures {

        @Test
        void onGovernorDeparted_removesGovernor_whenKnown() {
            discovery.onGovernorAnnounced("community-1", id("governor-a"));
            discovery.onGovernorDeparted("community-1");

            assertThat(discovery.currentGovernor("community-1").isEmpty()).isTrue();
        }

        @Test
        void onGovernorDeparted_noOp_whenUnknown() {
            discovery.onGovernorDeparted("unknown");

            assertThat(discovery.allKnownGovernors()).isEmpty();
        }
    }

    @Nested
    class Queries {

        @Test
        void currentGovernor_returnsNone_whenUnknownCommunity() {
            assertThat(discovery.currentGovernor("unknown").isEmpty()).isTrue();
        }

        @Test
        void allKnownGovernors_returnsImmutableSnapshot_ofCurrentState() {
            discovery.onGovernorAnnounced("community-1", id("governor-a"));
            discovery.onGovernorAnnounced("community-2", id("governor-b"));

            var snapshot = discovery.allKnownGovernors();

            assertThat(snapshot).hasSize(2);
            assertThat(snapshot).containsEntry("community-1", id("governor-a"));
            assertThat(snapshot).containsEntry("community-2", id("governor-b"));

            // Snapshot is immutable - further changes don't affect it
            discovery.onGovernorAnnounced("community-3", id("governor-c"));
            assertThat(snapshot).hasSize(2);
        }
    }
}
