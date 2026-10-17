// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.pragmatica.aether.api.ClusterEvent.CommunityMinted;
import org.pragmatica.aether.api.ClusterEvent.CommunityStateChanged;
import org.pragmatica.aether.node.NodeCodecs;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.CommunityKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.GovernorAnnouncementKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ActivationDirectiveValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.CommunityValue;
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
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.FrameworkCodecs;
import org.pragmatica.serialization.SliceCodec;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// #1652: community lifecycle events from a REAL committed write to a published, codec-encoded event.
///
/// The applier's own `ValuePut` (with its `oldValue`) drives the aggregator, and the event travels the
/// production `system:cluster-events` stream through the node codec — so a missing wire-tag pin fails
/// here. Dissolution is driven exactly as `CommunityPlacementReconciler.markDissolved` commits it (a
/// leader transaction setting DISSOLVED, target 0 and `dissolvedAt`), because Ember cannot retire a
/// placement policy; the Ember test covers the edges a live cluster can drive.
class CommunityLifecycleAggregationTest {
    private static final NodeId SELF = new NodeId("core-1");
    private static final SliceCodec CODEC = NodeCodecs.nodeCodecs(FrameworkCodecs.frameworkCodecs());
    private static final LeaderValue LEADER_VALUE = LeaderValue.leaderValue(SELF, 1L);
    private static final String COMMUNITY = "default:local:0";

    private KVStore<AetherKey, AetherValue> kvStore;
    private ClusterEventAggregator aggregator;

    @BeforeEach
    void setUp() {
        aggregator = aggregator();
        var router = MessageRouter.mutable();

        router.addRoute(ValuePut.class, this::dispatch);
        kvStore = new KVStore<>(router, CODEC, CODEC);
        commitLeader();
    }

    @Test
    void dissolution_publishesAStateChangeIntoDissolved_fromTheCommittedWrite() {
        var community = CommunityKey.communityKey(COMMUNITY);

        commit(community, none(), community(CommunityState.ACTIVE, 3, none()));
        commit(community, kvStore.get(community), community(CommunityState.DISSOLVED, 0, some(1_700_000_000_000L)));

        assertThat(events()).filteredOn(CommunityStateChanged.class::isInstance)
                            .singleElement()
                            .satisfies(event -> assertThat(event.details()).containsEntry("from", "ACTIVE")
                                                                           .containsEntry("to", "DISSOLVED")
                                                                           .containsEntry("targetSize", "0"));
    }

    @Test
    void mint_publishesCommunityMinted_andNoStateChange() {
        commit(CommunityKey.communityKey(COMMUNITY), none(), community(CommunityState.FORMING, 3, none()));

        assertThat(events()).singleElement()
                            .isInstanceOf(CommunityMinted.class);
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

    private static CommunityValue community(CommunityState state, int targetSize, Option<Long> dissolvedAt) {
        return CommunityValue.communityValue("", ActivationDirectiveValue.WORKER, targetSize, state, 1L, dissolvedAt);
    }

    private static ClusterEventAggregator aggregator() {
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
                                                             () -> false,
                                                             () -> true);
    }
}
