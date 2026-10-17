// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.function.Supplier;

import org.pragmatica.aether.api.ClusterEvent.CommunityMemberJoined;
import org.pragmatica.aether.api.ClusterEvent.CommunityMemberLeft;
import org.pragmatica.aether.api.ClusterEvent.CommunityMinted;
import org.pragmatica.aether.api.ClusterEvent.CommunityStateChanged;
import org.pragmatica.aether.api.ClusterEvent.Severity;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #1652: the community lifecycle events are a pure diff of one committed write — before vs after.
class CommunityLifecycleEventsTest {
    private static final Supplier<HlcTimestamp> CLOCK = () -> HlcTimestamp.ZERO;
    private static final String COMMUNITY = "default:local:0";
    private static final NodeId GOVERNOR = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId WORKER_B = NodeId.nodeId("worker-2").unwrap();
    private static final NodeId WORKER_C = NodeId.nodeId("worker-3").unwrap();

    @Nested
    class CommunityRecord {
        @Test
        void fromCommunityPut_emitsMinted_whenNoRecordWasCommittedBefore() {
            var events = CommunityLifecycleEvents.fromCommunityPut(CLOCK, COMMUNITY, none(), community(CommunityState.FORMING, 3));

            assertThat(events).singleElement()
                              .isInstanceOfSatisfying(CommunityMinted.class,
                                                      event -> assertThat(event.details()).containsEntry("communityId", COMMUNITY)
                                                                                          .containsEntry("state", "FORMING")
                                                                                          .containsEntry("targetSize", "3"));
        }

        @Test
        void fromCommunityPut_emitsOneStateChangeCarryingTheEdge_whenTheStateMoves() {
            var events = CommunityLifecycleEvents.fromCommunityPut(CLOCK,
                                                                   COMMUNITY,
                                                                   some(community(CommunityState.FORMING, 3)),
                                                                   community(CommunityState.ACTIVE, 3));

            assertThat(events).singleElement()
                              .isInstanceOfSatisfying(CommunityStateChanged.class,
                                                      event -> assertThat(event.details()).containsEntry("from", "FORMING")
                                                                                          .containsEntry("to", "ACTIVE"));
            assertThat(events.getFirst().severity()).isEqualTo(Severity.INFO);
        }

        @Test
        void fromCommunityPut_isAWarning_onlyForTheEdgeIntoDegraded() {
            var degraded = CommunityLifecycleEvents.fromCommunityPut(CLOCK,
                                                                     COMMUNITY,
                                                                     some(community(CommunityState.ACTIVE, 3)),
                                                                     community(CommunityState.DEGRADED, 3));
            var recovered = CommunityLifecycleEvents.fromCommunityPut(CLOCK,
                                                                      COMMUNITY,
                                                                      some(community(CommunityState.DEGRADED, 3)),
                                                                      community(CommunityState.ACTIVE, 3));

            assertThat(degraded.getFirst().severity()).isEqualTo(Severity.WARNING);
            assertThat(recovered.getFirst().severity()).isEqualTo(Severity.INFO);
        }

        /// A target-size re-alignment rewrites the record without moving the state: not a lifecycle edge.
        @Test
        void fromCommunityPut_emitsNothing_whenOnlyTheTargetSizeChanges() {
            assertThat(CommunityLifecycleEvents.fromCommunityPut(CLOCK,
                                                                 COMMUNITY,
                                                                 some(community(CommunityState.ACTIVE, 3)),
                                                                 community(CommunityState.ACTIVE, 5))).isEmpty();
        }
    }

    @Nested
    class Roster {
        @Test
        void fromRosterPut_emitsAJoinPerMember_whenTheFirstRosterIsCommitted() {
            var events = CommunityLifecycleEvents.fromRosterPut(CLOCK, COMMUNITY, none(), roster(List.of(WORKER_B, GOVERNOR)));

            assertThat(events).allMatch(CommunityMemberJoined.class::isInstance)
                              .extracting(event -> event.details().get("nodeId"))
                              .containsExactly("worker-1", "worker-2");
            assertThat(events).allMatch(event -> "2".equals(event.details().get("memberCount")));
        }

        @Test
        void fromRosterPut_emitsJoinsThenLeaves_forExactlyTheMembersThatChanged() {
            var events = CommunityLifecycleEvents.fromRosterPut(CLOCK,
                                                                COMMUNITY,
                                                                some(roster(List.of(GOVERNOR, WORKER_B))),
                                                                roster(List.of(GOVERNOR, WORKER_C)));

            assertThat(events).hasSize(2);
            assertThat(events.get(0)).isInstanceOfSatisfying(CommunityMemberJoined.class,
                                                             event -> assertThat(event.details()).containsEntry("nodeId", "worker-3"));
            assertThat(events.get(1)).isInstanceOfSatisfying(CommunityMemberLeft.class,
                                                             event -> assertThat(event.details()).containsEntry("nodeId", "worker-2"));
        }

        /// A governor re-announcement with the same members rewrites the record: no membership changed.
        @Test
        void fromRosterPut_emitsNothing_whenTheMembersAreUnchanged() {
            assertThat(CommunityLifecycleEvents.fromRosterPut(CLOCK,
                                                              COMMUNITY,
                                                              some(roster(List.of(GOVERNOR, WORKER_B))),
                                                              roster(List.of(WORKER_B, GOVERNOR)))).isEmpty();
        }
    }

    private static CommunityValue community(CommunityState state, int targetSize) {
        return CommunityValue.communityValue("", ActivationDirectiveValue.WORKER, targetSize, state, 1L, none());
    }

    private static GovernorAnnouncementValue roster(List<NodeId> members) {
        return new GovernorAnnouncementValue(GOVERNOR,
                                             members.size(),
                                             members,
                                             "10.0.0.1:9000",
                                             1700000000000L,
                                             5L,
                                             Epoch.ZERO,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             false);
    }
}
