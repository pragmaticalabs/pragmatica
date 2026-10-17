// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.worker.health.CommunityMemberDirectory;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;

import java.net.InetSocketAddress;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the worker-side community filter (GAP 2): the raw SWIM alive set is narrowed to
/// this worker's community via the committed `ActivationDirectiveValue` (as indexed by the
/// `CommunityMemberDirectory`) before it reaches `GovernorAnnouncer.onMembershipChange`.
/// Cross-community members are excluded; a community-of-one self-elects; the resulting
/// announcement carries only community members. Candidacy is by ROLE and community (H13, #1840).
class CommunityMembershipFilterTest {
    private static final NodeId SELF = NodeId.nodeId("worker-5").unwrap();
    private static final NodeId PEER_SAME = NodeId.nodeId("worker-8").unwrap();   // same community, higher id
    private static final NodeId PEER_OTHER = NodeId.nodeId("worker-9").unwrap();  // DIFFERENT community
    private static final NodeId CORE_LOWER = NodeId.nodeId("core-1").unwrap();    // core, lower id than every worker
    private static final String COMMUNITY = "src-a-w-0";
    private static final String OTHER_COMMUNITY = "src-b-w-0";

    private CommunityMemberDirectory directory;

    @BeforeEach
    void setUp() {
        directory = CommunityMemberDirectory.communityMemberDirectory();
    }

    private void worker(NodeId node, String community) {
        directory.put(node, ActivationDirectiveValue.worker(community, ""));
    }

    @Test
    void communityAliveMembers_excludesOtherCommunityMembers_keepsOwnCommunity() {
        worker(SELF, COMMUNITY);
        worker(PEER_SAME, COMMUNITY);
        worker(PEER_OTHER, OTHER_COMMUNITY);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_SAME), alive(PEER_OTHER)),
                                                                       directory,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactlyInAnyOrder(SELF, PEER_SAME);
    }

    @Test
    void communityAliveMembers_communityOfOne_returnsOnlySelf() {
        worker(SELF, COMMUNITY);
        worker(PEER_OTHER, OTHER_COMMUNITY);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_OTHER)),
                                                                       directory,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    @Test
    void communityAliveMembers_aliveMemberWithoutCommittedDirective_excluded() {
        worker(SELF, COMMUNITY);

        // PEER_SAME is alive on SWIM but has no committed ActivationDirective yet.
        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_SAME)),
                                                                       directory,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    /// H13: a core is excluded by its committed ROLE. Its directive here deliberately carries the worker's
    /// community, so the old "empty community" accident cannot be what drops it; the lowest id in the view
    /// is the core, so a role-blind filter would hand it the nomination.
    @Test
    void communityAliveMembers_coreRoleDirectiveNamingTheCommunity_isNotACandidate() {
        worker(SELF, COMMUNITY);
        directory.put(CORE_LOWER, new ActivationDirectiveValue(ActivationDirectiveValue.CORE, COMMUNITY, ""));

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(CORE_LOWER), alive(SELF)),
                                                                       directory,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    @Test
    void communityAliveMembers_coreWithoutDirective_isNotACandidate() {
        worker(SELF, COMMUNITY);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(CORE_LOWER), alive(SELF)),
                                                                       directory,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    @Test
    void communityAliveMembers_removedDirective_leavesTheCandidateSet() {
        worker(SELF, COMMUNITY);
        worker(PEER_SAME, COMMUNITY);
        directory.remove(PEER_SAME);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_SAME)),
                                                                       directory,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    private static SwimMember alive(NodeId id) {
        return SwimMember.swimMember(id, SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0));
    }
}
