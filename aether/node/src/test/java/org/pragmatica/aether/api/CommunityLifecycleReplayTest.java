// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.ClusterEvent.CommunityMemberJoined;
import org.pragmatica.aether.api.ClusterEvent.CommunityMinted;
import org.pragmatica.aether.api.ClusterEvent.CommunityStateChanged;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.GovernorAnnouncementValue;
import org.pragmatica.aether.slice.kvstore.CommunityState;
import org.pragmatica.aether.slice.stream.FrameworkStreamConsumer;
import org.pragmatica.aether.slice.stream.FrameworkStreamPublisher;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.SystemStreamFactories;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.LeaderKey;
import org.pragmatica.cluster.state.kvstore.LeaderValue;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.hlc.HlcClock;
import org.pragmatica.hlc.HlcTimestamp;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #1652: exactly one event per committed community transition — none missed, none doubled.
///
/// The aggregator's replay gate is wired to the store's own `KVStore::isReplaying`, as `AetherNode` wires
/// it, so a notification replay (the `sync → activate → replay` burst, which re-raises every stored key
/// with `oldValue` from an empty view on a cold boot) must not re-publish a mint or a join that the live
/// commit already published.
class CommunityLifecycleReplayTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final LeaderValue LEADER_VALUE = LeaderValue.leaderValue(SELF, 1L);
    private static final String COMMUNITY = "default:local:0";
    private static final NodeId GOVERNOR = new NodeId("worker-1");
    private static final NodeId MEMBER = new NodeId("worker-2");

    private KVStore<AetherKey, AetherValue> kvStore;
    private ClusterEventAggregator aggregator;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        router.addRoute(ValuePut.class, this::dispatch);
        kvStore = new KVStore<>(router, CODEC, CODEC);
        aggregator = aggregator(kvStore);
        commitLeader();
    }

    @Test
    void everyCommittedEdge_publishesExactlyOneEvent_inCommitOrder() {
        var key = CommunityKey.communityKey(COMMUNITY);

        commit(key, none(), community(CommunityState.FORMING, 3));
        commit(key, kvStore.get(key), community(CommunityState.FORMING, 4));
        commit(key, kvStore.get(key), community(CommunityState.ACTIVE, 4));
        commit(key, kvStore.get(key), community(CommunityState.DEGRADED, 4));
        commit(key, kvStore.get(key), community(CommunityState.ACTIVE, 4));

        var events = events();

        assertThat(events).hasSize(4);
        assertThat(events.getFirst()).isInstanceOf(CommunityMinted.class);
        assertThat(events.subList(1, 4)).allMatch(CommunityStateChanged.class::isInstance)
                                        .extracting(event -> event.details().get("from") + "->" + event.details()
                                                                                                        .get("to"))
                                        .containsExactly("FORMING->ACTIVE", "ACTIVE->DEGRADED", "DEGRADED->ACTIVE");
    }

    @Test
    void notificationReplay_republishesNothing_thatTheLiveCommitAlreadyPublished() {
        var key = CommunityKey.communityKey(COMMUNITY);
        var rosterKey = GovernorAnnouncementKey.forCommunity(COMMUNITY);

        commit(key, none(), community(CommunityState.FORMING, 3));
        commit(rosterKey, none(), roster(List.of(GOVERNOR, MEMBER)));
        assertThat(events()).hasSize(3);

        kvStore.replayNotifications();

        assertThat(events()).hasSize(3);
        assertThat(events()).filteredOn(CommunityMinted.class::isInstance)
                            .hasSize(1);
        assertThat(events()).filteredOn(CommunityMemberJoined.class::isInstance)
                            .hasSize(2);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void dispatch(ValuePut put) {
        switch (put.cause().key()) {
            case CommunityKey _ -> aggregator.onCommunityPut(put);
            case GovernorAnnouncementKey _ -> aggregator.onGovernorAnnouncementPut(put);
            default -> {}
        }
    }

    private void commit(AetherKey key, Option<AetherValue> expected, AetherValue value) {
        kvStore.process(kvStore.createBatch(List.of(new KVCommand.LeaderTransaction<AetherKey, AetherValue>(key,
                                                                                                           UUID.randomUUID()
                                                                                                               .toString(),
                                                                                                           LEADER_VALUE,
                                                                                                           List.of(),
                                                                                                           List.of(new KVCommand.Mutation<>(key,
                                                                                                                                            expected,
                                                                                                                                            some(value)))))));
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void commitLeader() {
        kvStore.process(kvStore.createBatch((List) List.of(new KVCommand.Put<>(LeaderKey.INSTANCE, LEADER_VALUE))));
    }

    private List<ClusterEvent> events() {
        return aggregator.events()
                         .await()
                         .or(List.of());
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

    private static ClusterEventAggregator aggregator(KVStore<AetherKey, AetherValue> store) {
        var manager = StreamPartitionManager.streamPartitionManager(Long.MAX_VALUE);
        var config = StreamConfig.streamConfig(SystemStreams.CLUSTER_EVENTS.asString(),
                                               1,
                                               RetentionPolicy.retentionPolicy(10_000, 64L * 1024 * 1024, Long.MAX_VALUE, RetentionMode.ANY),
                                               "earliest",
                                               64L * 1024,
                                               ConsistencyMode.EVENTUAL,
                                               1);
        var publisher = new AtomicReference<FrameworkStreamPublisher<ClusterEvent>>(SystemStreamFactories.<ClusterEvent> systemStreamPublisher(SystemStreams.CLUSTER_EVENTS,
                                                                                                                                              manager,
                                                                                                                                              CODEC,
                                                                                                                                              config)
                                                                                                           .unwrap());
        var consumer = new AtomicReference<FrameworkStreamConsumer<ClusterEvent>>(SystemStreamFactories.<ClusterEvent> systemStreamConsumer(SystemStreams.CLUSTER_EVENTS,
                                                                                                                                           manager,
                                                                                                                                           CODEC,
                                                                                                                                           CODEC,
                                                                                                                                           config)
                                                                                                        .unwrap());

        return ClusterEventAggregator.clusterEventAggregator(publisher::get,
                                                             consumer::get,
                                                             () -> true,
                                                             SELF,
                                                             HlcClock.hlcClock(SELF),
                                                             () -> 1,
                                                             store::isReplaying,
                                                             () -> true);
    }
}
