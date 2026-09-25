// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.swim.SwimMember;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;


/// Verifies the worker-side community filter (GAP 2): the raw SWIM alive set is narrowed to
/// this worker's community via the committed `ActivationDirectiveValue.communityId` before it
/// reaches `GovernorAnnouncer.onMembershipChange`. Cross-community members are excluded; a
/// community-of-one self-elects; the resulting announcement carries only community members.
class CommunityMembershipFilterTest {
    private static final NodeId SELF = NodeId.nodeId("worker-5").unwrap();
    private static final NodeId PEER_SAME = NodeId.nodeId("worker-8").unwrap();   // same community, higher id
    private static final NodeId PEER_LOWER_SAME = NodeId.nodeId("worker-2").unwrap();  // same community, lower id
    private static final NodeId PEER_OTHER = NodeId.nodeId("worker-9").unwrap();  // DIFFERENT community
    private static final String COMMUNITY = "src-a-w-0";
    private static final String OTHER_COMMUNITY = "src-b-w-0";

    private RecordingKVStore kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new RecordingKVStore();
    }

    @Test
    void communityAliveMembers_excludesOtherCommunityMembers_keepsOwnCommunity() {
        kvStore.put(SELF, COMMUNITY);
        kvStore.put(PEER_SAME, COMMUNITY);
        kvStore.put(PEER_OTHER, OTHER_COMMUNITY);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_SAME), alive(PEER_OTHER)),
                                                                       kvStore,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactlyInAnyOrder(SELF, PEER_SAME);
    }

    @Test
    void communityAliveMembers_communityOfOne_returnsOnlySelf() {
        kvStore.put(SELF, COMMUNITY);
        kvStore.put(PEER_OTHER, OTHER_COMMUNITY);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_OTHER)),
                                                                       kvStore,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    @Test
    void communityAliveMembers_aliveMemberWithoutCommittedDirective_excluded() {
        kvStore.put(SELF, COMMUNITY);

        // PEER_SAME is alive on SWIM but has no committed ActivationDirective yet.
        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_SAME)),
                                                                       kvStore,
                                                                       COMMUNITY);

        assertThat(filtered.stream().map(SwimMember::nodeId).toList()).containsExactly(SELF);
    }

    private static SwimMember alive(NodeId id) {
        return SwimMember.swimMember(id, SwimMember.MemberState.ALIVE, 0, new InetSocketAddress("127.0.0.1", 0));
    }

    /// Seeds committed worker `ActivationDirectiveValue`s and exposes them through the
    /// `snapshot()` path the filter relies on (the only KVStore surface it touches).
    private static final class RecordingKVStore extends KVStore<AetherKey, AetherValue> {
        private final Map<AetherKey, AetherValue> storage = new ConcurrentHashMap<>();

        private RecordingKVStore() {
            super(null, null, null);
        }

        private void put(NodeId nodeId, String communityId) {
            storage.put(new AetherKey.ActivationDirectiveKey(nodeId), ActivationDirectiveValue.worker(communityId, ""));
        }

        @Override
        public Map<AetherKey, AetherValue> snapshot() {
            return Map.copyOf(storage);
        }
    }

}
