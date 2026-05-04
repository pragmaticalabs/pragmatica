// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.stream.StreamAddress;
import org.pragmatica.consensus.NodeId;

import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the sealed {@link ClusterEvent} hierarchy (spec §6.4):
///   - Sealed parent permits the closed-set 27 variants + {@link ExtendedEvent}.
///   - Pattern-matching switch over the parent is exhaustive.
///   - {@link ExtendedEvent} is implementable from outside the framework module (non-sealed).
///   - {@link StreamRegistered} / {@link StreamDeleted} carry the affected {@link StreamAddress}.
///   - {@link EventId} compares by sequence first, nodeId tie-break.
class ClusterEventTest {

    private static final NodeId NODE_A = new NodeId("node-a");
    private static final NodeId NODE_B = new NodeId("node-b");

    @Nested
    class SealedHierarchy {

        @Test
        void clusterEventIsSealed() {
            assertThat(ClusterEvent.class.isSealed()).isTrue();
        }

        @Test
        void closedSetCountIs27_plusExtendedEvent() {
            var permitted = ClusterEvent.class.getPermittedSubclasses();
            // 27 closed variants + ExtendedEvent = 28 permitted subtypes
            assertThat(permitted).hasSize(28);
        }

        @Test
        void extendedEventIsNonSealed() {
            assertThat(ExtendedEvent.class.isSealed()).isFalse();
        }

        @Test
        void exhaustiveSwitchCompiles() {
            ClusterEvent event = new ClusterEvent.NodeJoined(new EventId(1L, NODE_A),
                                                             Instant.EPOCH,
                                                             NODE_A,
                                                             ClusterEvent.Severity.INFO,
                                                             "test",
                                                             Map.of());
            var tag = dispatch(event);
            assertThat(tag).isEqualTo("NODE_JOINED");
        }
    }

    @Nested
    class StreamLifecycleVariants {

        @Test
        void streamRegistered_carriesAddress() {
            var address = StreamAddress.streamAddress("com.example.app", "orders", "1.0.0").unwrap();
            var event = new ClusterEvent.StreamRegistered(new EventId(1L, NODE_A),
                                                          Instant.EPOCH,
                                                          NODE_A,
                                                          ClusterEvent.Severity.INFO,
                                                          "stream registered",
                                                          Map.of(),
                                                          address);

            assertThat(event.address()).isEqualTo(address);
            assertThat(event).isInstanceOf(ClusterEvent.class);
        }

        @Test
        void streamDeleted_carriesAddress() {
            var address = StreamAddress.streamAddress("com.example.app", "orders", "1.0.0").unwrap();
            var event = new ClusterEvent.StreamDeleted(new EventId(1L, NODE_A),
                                                       Instant.EPOCH,
                                                       NODE_A,
                                                       ClusterEvent.Severity.INFO,
                                                       "stream deleted",
                                                       Map.of(),
                                                       address);

            assertThat(event.address()).isEqualTo(address);
            assertThat(event).isInstanceOf(ClusterEvent.class);
        }
    }

    @Nested
    class ExtendedEventHatch {

        @Test
        void thirdPartyExtensionIsImplementable() {
            ClusterEvent event = new TestExtension(new EventId(42L, NODE_A),
                                                    Instant.EPOCH,
                                                    NODE_A,
                                                    ClusterEvent.Severity.INFO,
                                                    "ext",
                                                    Map.of("k", "v"));

            assertThat(event).isInstanceOf(ExtendedEvent.class);
            var tag = dispatch(event);
            assertThat(tag).isEqualTo("test:test-extension");
        }
    }

    @Nested
    class EventIdOrdering {

        @Test
        void compareTo_ordersBySequenceFirst() {
            var lower = new EventId(1L, NODE_B);
            var higher = new EventId(2L, NODE_A);

            assertThat(lower.compareTo(higher)).isNegative();
        }

        @Test
        void compareTo_tieBreaksOnNodeId() {
            var nodeA = new EventId(5L, NODE_A);
            var nodeB = new EventId(5L, NODE_B);

            assertThat(nodeA.compareTo(nodeB)).isNegative();
        }

        @Test
        void compareTo_equalIdsAreEqual() {
            var first = new EventId(7L, NODE_A);
            var second = new EventId(7L, NODE_A);

            assertThat(first.compareTo(second)).isZero();
        }
    }

    @Nested
    class EventIdAllocation {

        @Test
        void next_yieldsMonotonicSequencesStampedWithNodeId() {
            var allocator = EventIdAllocator.eventIdAllocator(NODE_A);

            var first = allocator.next();
            var second = allocator.next();

            assertThat(first.nodeId()).isEqualTo(NODE_A);
            assertThat(second.nodeId()).isEqualTo(NODE_A);
            assertThat(second.sequence()).isGreaterThan(first.sequence());
        }
    }

    /// Exhaustive sealed switch — verifies the compiler accepts every closed-set variant plus the
    /// `ExtendedEvent` arm. Returning a string per variant gives the test something to assert.
    @SuppressWarnings("JBCT-PAT-01") private static String dispatch(ClusterEvent event) {
        return switch (event) {
            case ClusterEvent.NodeJoined ignored -> "NODE_JOINED";
            case ClusterEvent.NodeLeft ignored -> "NODE_LEFT";
            case ClusterEvent.NodeFailed ignored -> "NODE_FAILED";
            case ClusterEvent.LeaderElected ignored -> "LEADER_ELECTED";
            case ClusterEvent.LeaderLost ignored -> "LEADER_LOST";
            case ClusterEvent.QuorumEstablished ignored -> "QUORUM_ESTABLISHED";
            case ClusterEvent.QuorumLost ignored -> "QUORUM_LOST";
            case ClusterEvent.DeploymentStarted ignored -> "DEPLOYMENT_STARTED";
            case ClusterEvent.DeploymentCompleted ignored -> "DEPLOYMENT_COMPLETED";
            case ClusterEvent.DeploymentFailed ignored -> "DEPLOYMENT_FAILED";
            case ClusterEvent.ScaleUp ignored -> "SCALE_UP";
            case ClusterEvent.ScaleDown ignored -> "SCALE_DOWN";
            case ClusterEvent.SliceFailure ignored -> "SLICE_FAILURE";
            case ClusterEvent.ConnectionEstablished ignored -> "CONNECTION_ESTABLISHED";
            case ClusterEvent.ConnectionFailed ignored -> "CONNECTION_FAILED";
            case ClusterEvent.CommunityScaleRequest ignored -> "COMMUNITY_SCALE_REQUEST";
            case ClusterEvent.CommunityMetricsSnapshot ignored -> "COMMUNITY_METRICS_SNAPSHOT";
            case ClusterEvent.AccessDenied ignored -> "ACCESS_DENIED";
            case ClusterEvent.NodeLifecycleChanged ignored -> "NODE_LIFECYCLE_CHANGED";
            case ClusterEvent.ConfigChanged ignored -> "CONFIG_CHANGED";
            case ClusterEvent.BackupCreated ignored -> "BACKUP_CREATED";
            case ClusterEvent.BackupRestored ignored -> "BACKUP_RESTORED";
            case ClusterEvent.BlueprintDeployed ignored -> "BLUEPRINT_DEPLOYED";
            case ClusterEvent.BlueprintDeleted ignored -> "BLUEPRINT_DELETED";
            case ClusterEvent.GenerationChanged ignored -> "GENERATION_CHANGED";
            case ClusterEvent.StreamRegistered ignored -> "STREAM_REGISTERED";
            case ClusterEvent.StreamDeleted ignored -> "STREAM_DELETED";
            case ExtendedEvent ext -> ext.discriminator();
        };
    }

    /// Test-only third-party extension event variant. Demonstrates that {@link ExtendedEvent} is
    /// implementable outside the framework module (the spec §6.4.1 contract for the open hatch).
    record TestExtension(EventId id, Instant timestamp, NodeId sourceNode,
                         ClusterEvent.Severity severity, String summary, Map<String, String> details)
            implements ExtendedEvent {
        @Override public String discriminator() {
            return "test:test-extension";
        }
    }
}
