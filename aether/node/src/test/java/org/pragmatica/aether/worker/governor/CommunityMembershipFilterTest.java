// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.worker.governor;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.swim.SwimMember;

import java.net.InetSocketAddress;
import java.util.ArrayList;
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

    @Test
    void onMembershipChange_communityFilteredSelf_selfElectsAndAnnouncesOnlyCommunity() {
        kvStore.put(SELF, COMMUNITY);
        kvStore.put(PEER_SAME, COMMUNITY);
        kvStore.put(PEER_OTHER, OTHER_COMMUNITY);
        var cluster = new RecordingClusterNode();
        var announcer = announcerFor(cluster);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_SAME), alive(PEER_OTHER)),
                                                                       kvStore,
                                                                       COMMUNITY);
        announcer.onMembershipChange(filtered);

        assertThat(announcer.isGovernor()).isTrue();
        assertThat(cluster.batches).hasSize(1);
        var value = writtenAnnouncement(cluster);
        assertThat(value.governorId()).isEqualTo(SELF);
        assertThat(value.members()).containsExactlyInAnyOrder(SELF, PEER_SAME);
        assertThat(value.members()).doesNotContain(PEER_OTHER);
        assertThat(value.memberCount()).isEqualTo(2);
        assertThat(value.dissolved()).isFalse();
    }

    @Test
    void onMembershipChange_communityOfOne_selfElectsAndAnnounces() {
        kvStore.put(SELF, COMMUNITY);
        var cluster = new RecordingClusterNode();
        var announcer = announcerFor(cluster);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF)), kvStore, COMMUNITY);
        announcer.onMembershipChange(filtered);

        assertThat(announcer.isGovernor()).isTrue();
        assertThat(cluster.batches).hasSize(1);
        var value = writtenAnnouncement(cluster);
        assertThat(value.governorId()).isEqualTo(SELF);
        assertThat(value.members()).containsExactly(SELF);
        assertThat(value.memberCount()).isEqualTo(1);
    }

    @Test
    void onMembershipChange_lowerIdPeerInCommunity_selfBecomesFollowerNoWrite() {
        kvStore.put(SELF, COMMUNITY);
        kvStore.put(PEER_LOWER_SAME, COMMUNITY);
        var cluster = new RecordingClusterNode();
        var announcer = announcerFor(cluster);

        var filtered = CommunityMembershipFilter.communityAliveMembers(List.of(alive(SELF), alive(PEER_LOWER_SAME)),
                                                                       kvStore,
                                                                       COMMUNITY);
        announcer.onMembershipChange(filtered);

        assertThat(announcer.isGovernor()).isFalse();
        assertThat(announcer.currentGovernor().stream().toList()).containsExactly(PEER_LOWER_SAME);
        assertThat(cluster.batches).isEmpty();
    }

    private static GovernorAnnouncer announcerFor(RecordingClusterNode cluster) {
        var announcer = GovernorAnnouncer.governorAnnouncer(SELF,
                                                            cluster,
                                                            HlcClock.hlcClock(SELF),
                                                            () -> COMMUNITY,
                                                            () -> "host:9000",
                                                            () -> Epoch.ZERO);
        announcer.start();

        return announcer;
    }

    private static GovernorAnnouncementValue writtenAnnouncement(RecordingClusterNode cluster) {
        var command = cluster.batches.getFirst().getFirst();
        assertThat(command).isInstanceOf(KVCommand.Put.class);
        var put = (KVCommand.Put<?, ?>) command;
        assertThat(put.key()).isInstanceOf(GovernorAnnouncementKey.class);

        return (GovernorAnnouncementValue) put.value();
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

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final List<List<KVCommand<AetherKey>>> batches = new ArrayList<>();

        @Override public NodeId self() {return SELF;}

        @Override public TopologyManager topologyManager() {
            throw new UnsupportedOperationException("not used");
        }

        @Override public Promise<Unit> start() {return Promise.success(Unit.unit());}
        @Override public Promise<Unit> stop() {return Promise.success(Unit.unit());}

        @SuppressWarnings({"unchecked", "rawtypes"})
        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
            batches.add(List.copyOf(commands));
            return (Promise) Promise.success(List.of());
        }
    }
}
