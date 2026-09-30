// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.cluster.fsm;

import java.util.List;
import java.util.UUID;
import java.util.Set;

import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ActivationDirectiveKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #1652: the live-member count shared by the per-community FSM and `/cluster/communities`. A member
/// counts only when it is on the committed roster, still directed to this community, and not observed
/// absent; with no roster to count against the answer is absent, not zero.
class CommunityLiveMembersTest {
    private static final String COMMUNITY = "east";
    private static final NodeId LIVE = NodeId.nodeId("worker-1").unwrap();
    private static final NodeId ABSENT = NodeId.nodeId("worker-2").unwrap();
    private static final NodeId REDIRECTED = NodeId.nodeId("worker-3").unwrap();
    private static final NodeId UNDIRECTED = NodeId.nodeId("worker-4").unwrap();

    private static final LeaderValue LEADER_VALUE = LeaderValue.leaderValue(NodeId.nodeId("core-1").unwrap(), 1L);

    private KVStore<AetherKey, AetherValue> kvStore;

    @BeforeEach
    void setUp() {
        kvStore = new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        commitLeader();
    }

    @Test
    void communityLiveMembers_countsOnlyDirectedMembersNotObservedAbsent() {
        commit(GovernorAnnouncementKey.forCommunity(COMMUNITY), roster(List.of(LIVE, ABSENT, REDIRECTED, UNDIRECTED, LIVE), false));
        commit(ActivationDirectiveKey.activationDirectiveKey(LIVE), ActivationDirectiveValue.worker(COMMUNITY, ""));
        commit(ActivationDirectiveKey.activationDirectiveKey(ABSENT), ActivationDirectiveValue.worker(COMMUNITY, ""));
        commit(ActivationDirectiveKey.activationDirectiveKey(REDIRECTED), ActivationDirectiveValue.worker("west", ""));
        CommunityLivenessView liveness = node -> Set.of(ABSENT).contains(node);

        assertThat(CommunityLiveMembers.communityLiveMembers(kvStore, liveness, COMMUNITY)).isEqualTo(some(1));
    }

    @Test
    void communityLiveMembers_isAbsent_whenNoRosterIsCommitted() {
        assertThat(CommunityLiveMembers.communityLiveMembers(kvStore, CommunityLivenessView.unwired(), COMMUNITY)).isEqualTo(none());
    }

    @Test
    void communityLiveMembers_isAbsent_whenTheRosterIsDissolved() {
        commit(GovernorAnnouncementKey.forCommunity(COMMUNITY), roster(List.of(LIVE), true));
        commit(ActivationDirectiveKey.activationDirectiveKey(LIVE), ActivationDirectiveValue.worker(COMMUNITY, ""));

        assertThat(CommunityLiveMembers.communityLiveMembers(kvStore, CommunityLivenessView.unwired(), COMMUNITY)).isEqualTo(none());
    }

    /// `hasCommunityLiveness` tells "not wired" from "wired, nothing absent" by identity, so `unwired()`
    /// must hand out one instance.
    @Test
    void unwired_returnsOneInstance_soAnUnwiredViewIsRecognisable() {
        assertThat(CommunityLivenessView.unwired()).isSameAs(CommunityLivenessView.unwired());
    }

    /// Community records are `LeaderAuthorized`: the applier refuses a plain `Put` of one, so they are
    /// committed the way production commits them — a leader transaction under the committed leader.
    private void commit(AetherKey key, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                           UUID.randomUUID()
                                                                                                               .toString(),
                                                                                                           LEADER_VALUE,
                                                                                                           List.of(),
                                                                                                           List.of(new KVCommand.Mutation<>(key,
                                                                                                                                            kvStore.get(key),
                                                                                                                                            some(value)))))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader() {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER_VALUE))));
    }

    private static GovernorAnnouncementValue roster(List<NodeId> members, boolean dissolved) {
        return new GovernorAnnouncementValue(LIVE,
                                             members.size(),
                                             members,
                                             "10.0.0.1:9000",
                                             1700000000000L,
                                             5L,
                                             Epoch.ZERO,
                                             Epoch.ZERO,
                                             HlcTimestamp.ZERO,
                                             dissolved);
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
