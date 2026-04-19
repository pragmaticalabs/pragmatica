// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.slice.kvstore;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class GovernorAnnouncementValueTest {
    @Nested
    class BackwardCompatibleFactories {
        @Test
        void governorAnnouncementValue_governorAndCount_hasZeroDefaults() {
            var governor = NodeId.nodeId("g-1").unwrap();

            var v = GovernorAnnouncementValue.governorAnnouncementValue(governor, 3);

            assertThat(v.governorId()).isEqualTo(governor);
            assertThat(v.memberCount()).isEqualTo(3);
            assertThat(v.communityTerm()).isZero();
            assertThat(v.communityEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
            assertThat(v.dissolved()).isFalse();
        }

        @Test
        void governorAnnouncementValue_withAnnouncedAt_preservesTimestamp() {
            var governor = NodeId.nodeId("g-1").unwrap();

            var v = GovernorAnnouncementValue.governorAnnouncementValue(governor, 5, 1710072000000L);

            assertThat(v.announcedAt()).isEqualTo(1710072000000L);
            assertThat(v.dissolved()).isFalse();
        }

        @Test
        void governorAnnouncementValue_withMembersAndAddress_copiesMembers() {
            var governor = NodeId.nodeId("g-1").unwrap();
            var members = List.of(NodeId.nodeId("w-1").unwrap(), NodeId.nodeId("w-2").unwrap());

            var v = GovernorAnnouncementValue.governorAnnouncementValue(governor, members, "10.0.0.1:7201");

            assertThat(v.members()).containsExactlyElementsOf(members);
            assertThat(v.tcpAddress()).isEqualTo("10.0.0.1:7201");
            assertThat(v.memberCount()).isEqualTo(2);
        }
    }

    @Nested
    class NewFactory {
        @Test
        void governorAnnouncementValue_withFullContext_populatesAllFields() {
            var governor = NodeId.nodeId("g-1").unwrap();
            var members = List.of(NodeId.nodeId("w-1").unwrap());
            var communityEpoch = Epoch.epoch(2L, 0L);
            var coreEpoch = Epoch.epoch(7L, 42L);
            var hlc = new HlcTimestamp(100L, "core-leader");

            var v = GovernorAnnouncementValue.governorAnnouncementValue(governor,
                                                                        members,
                                                                        "10.0.0.1:7201",
                                                                        1234L,
                                                                        2L,
                                                                        communityEpoch,
                                                                        coreEpoch,
                                                                        hlc,
                                                                        false);

            assertThat(v.communityTerm()).isEqualTo(2L);
            assertThat(v.communityEpoch()).isEqualTo(communityEpoch);
            assertThat(v.observedCoreEpoch()).isEqualTo(coreEpoch);
            assertThat(v.transitionedAt()).isEqualTo(hlc);
            assertThat(v.dissolved()).isFalse();
        }

        @Test
        void construct_nullMembers_normalizesToEmptyList() {
            var governor = NodeId.nodeId("g-1").unwrap();

            var v = new GovernorAnnouncementValue(governor, 0, null, "", 0L, 0L, null, null, null, false);

            assertThat(v.members()).isEmpty();
            assertThat(v.tcpAddress()).isEmpty();
            assertThat(v.communityEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.observedCoreEpoch()).isEqualTo(Epoch.ZERO);
            assertThat(v.transitionedAt()).isEqualTo(HlcTimestamp.ZERO);
        }
    }

    @Nested
    class Mutations {
        @Test
        void withGovernorChange_bumpsCommunityTerm() {
            var original = GovernorAnnouncementValue.governorAnnouncementValue(NodeId.nodeId("g-1").unwrap(),
                                                                               List.of(NodeId.nodeId("w-1").unwrap()),
                                                                               "10.0.0.1:7201");
            var newGovernor = NodeId.nodeId("g-2").unwrap();

            var next = original.withGovernorChange(newGovernor,
                                                   List.of(NodeId.nodeId("w-1").unwrap()),
                                                   "10.0.0.2:7201",
                                                   Epoch.epoch(3L, 5L),
                                                   new HlcTimestamp(50L, "core-1"));

            assertThat(next.governorId()).isEqualTo(newGovernor);
            assertThat(next.communityTerm()).isEqualTo(original.communityTerm() + 1);
            assertThat(next.communityEpoch()).isEqualTo(Epoch.epoch(1L, 0L));
            assertThat(next.observedCoreEpoch()).isEqualTo(Epoch.epoch(3L, 5L));
            assertThat(next.dissolved()).isFalse();
        }

        @Test
        void withDissolved_marksDissolvedAndPreservesGovernor() {
            var original = GovernorAnnouncementValue.governorAnnouncementValue(NodeId.nodeId("g-1").unwrap(),
                                                                               List.of(NodeId.nodeId("w-1").unwrap()),
                                                                               "10.0.0.1:7201");

            var next = original.withDissolved();

            assertThat(next.dissolved()).isTrue();
            assertThat(next.governorId()).isEqualTo(original.governorId());
            assertThat(next.communityTerm()).isEqualTo(original.communityTerm());
        }

        @Test
        void withMemberCount_doesNotBumpCommunityTerm() {
            var original = GovernorAnnouncementValue.governorAnnouncementValue(NodeId.nodeId("g-1").unwrap(), 2);

            var next = original.withMemberCount(5);

            assertThat(next.memberCount()).isEqualTo(5);
            assertThat(next.communityTerm()).isEqualTo(original.communityTerm());
        }

        @Test
        void withMembers_doesNotBumpCommunityTerm() {
            var original = GovernorAnnouncementValue.governorAnnouncementValue(NodeId.nodeId("g-1").unwrap(),
                                                                               List.of(NodeId.nodeId("w-1").unwrap()),
                                                                               "10.0.0.1:7201");

            var next = original.withMembers(List.of(NodeId.nodeId("w-1").unwrap(), NodeId.nodeId("w-2").unwrap()),
                                            "10.0.0.1:7202");

            assertThat(next.memberCount()).isEqualTo(2);
            assertThat(next.communityTerm()).isEqualTo(original.communityTerm());
        }
    }
}
