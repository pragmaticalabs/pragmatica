// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.stream.consumer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.generation.Epoch;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.ConsumerGroupKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupTombstone;
import org.pragmatica.aether.slice.kvstore.AetherValue.ConsumerGroupValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValuePut;
import org.pragmatica.cluster.state.kvstore.KVStoreNotification.ValueRemove;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.leader.LeaderNotification.LeaderChange;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// #3041: the tombstones of a deleted stream, and of partitions a group no longer has, are reclaimed; the guard of the
/// stream's life keeps the reclaim sound. The coordinator drives a REAL [KVStore] that commits every batch at once.
///
/// Expected at the guard package (i3041b): (f) and (g) are green; (a), (b) and the bound are RED until the reclaim
/// triggers land (i3041c), which wires [#reclaimTrigger] to them.
class ConsumerGroupStreamLifeReclaimTest {
    private static final NodeId SELF = new NodeId("leader-node");
    private static final NodeId NODE_A = new NodeId("node-a");
    private static final int PARTITIONS = 4;

    private KVStore<AetherKey, AetherValue> store;
    private ConsumerGroupRegistry registry;
    private ConsumerGroupCoordinator coordinator;
    private long life = 10;

    @BeforeEach
    void setUp() {
        var router = MessageRouter.mutable();

        store = new KVStore<>(router, stubSerializer(), stubDeserializer());
        registry = ConsumerGroupRegistry.consumerGroupRegistry();
        router.addRoute(ValuePut.class, this::route);
        router.addRoute(ValueRemove.class, this::routeRemove);
        coordinator = newCoordinator();
    }

    @SuppressWarnings("unchecked")
    private void route(ValuePut<?, ?> event) {
        if (event.cause().key() instanceof ConsumerGroupKey) {
            registry.onConsumerGroupPut((ValuePut<ConsumerGroupKey, AetherValue>) event);
        }
    }

    @SuppressWarnings("unchecked")
    private void routeRemove(ValueRemove<?, ?> event) {
        if (event.cause().key() instanceof ConsumerGroupKey) {
            registry.onConsumerGroupRemove((ValueRemove<ConsumerGroupKey, ConsumerGroupValue>) event);
        }
        if (event.cause().key() instanceof StreamConfigKey configKey) {
            reclaimTrigger(configKey.streamName());
        }
    }

    /// i3041c wires the reclaim here: the node-local reaction to a committed removal of a stream's config. At base
    /// nothing reclaims, which is what makes (a) and the bound red.
    private void reclaimTrigger(String streamName) {}

    private ConsumerGroupCoordinator newCoordinator() {
        var created = ConsumerGroupCoordinator.consumerGroupCoordinator(immediateClusterNode(),
                                                                        registry,
                                                                        () -> Epoch.epoch(1L, 1L, 0L));

        created.onLeaderChange(new LeaderChange(Option.some(SELF), true));

        return created;
    }

    private ClusterNode<KVCommand<AetherKey>> immediateClusterNode() {
        return new ClusterNode<>() {
            @Override
            public NodeId self() {
                return SELF;
            }

            @Override
            public TopologyManager topologyManager() {
                return null;
            }

            @Override
            public Promise<Unit> start() {
                return Promise.success(Unit.unit());
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.success(Unit.unit());
            }

            @Override
            @SuppressWarnings("unchecked")
            public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> commands) {
                store.process(store.createBatch(commands));
                return Promise.success((List<R>) List.of());
            }
        };
    }

    @Test
    void joinThenLeave_onLiveStream_writesTheTombstones_g() {
        createStream("events");

        assertThat(coordinator.joinGroup("g", "events", PARTITIONS, "c1", NODE_A).isSuccess()).isTrue();
        assertThat(coordinator.leaveGroup("g", "events", "c1").isSuccess()).isTrue();

        assertThat(tombstonesOf("events")).as("the last leave tombstones every partition, as at base")
                                          .hasSize(PARTITIONS);
    }

    @Test
    void join_forStreamNeverCreated_isRefused_f() {
        var joined = coordinator.joinGroup("g", "ghost", PARTITIONS, "c1", NODE_A);

        assertThat(joined.isFailure()).as("a join for a stream with no committed config is refused").isTrue();
        assertThat(recordsOf("ghost")).as("and nothing is committed for it").isEmpty();
    }

    @Test
    void join_forStreamDeleted_isRefused_f() {
        createStream("events");
        deleteStream("events");

        var joined = coordinator.joinGroup("g", "events", PARTITIONS, "c1", NODE_A);

        assertThat(joined.isFailure()).as("a join for a deleted stream is refused").isTrue();
        assertThat(recordsOf("events")).isEmpty();
    }

    @Test
    void streamDeleted_withTombstonedGroups_leavesNoTombstoneOfThatStream_a() {
        createStream("events");
        createStream("other");
        coordinator.joinGroup("g1", "events", PARTITIONS, "c1", NODE_A);
        coordinator.leaveGroup("g1", "events", "c1");
        coordinator.joinGroup("g2", "events", PARTITIONS, "c2", NODE_A);
        coordinator.leaveGroup("g2", "events", "c2");
        coordinator.joinGroup("g1", "other", PARTITIONS, "c1", NODE_A);
        coordinator.leaveGroup("g1", "other", "c1");

        deleteStream("events");

        assertThat(tombstonesOf("events")).as("no tombstone of the deleted stream remains").isEmpty();
        assertThat(tombstonesOf("other")).as("the live stream keeps its own").hasSize(PARTITIONS);
    }

    @Test
    void rejoinWithFewerPartitions_leavesNoStaleTombstoneBehindTheNewEpoch_b() {
        createStream("events");
        coordinator.joinGroup("g", "events", PARTITIONS, "c1", NODE_A);
        coordinator.leaveGroup("g", "events", "c1");
        coordinator.joinGroup("g", "events", 2, "c1", NODE_A);

        var rejoinEpoch = assignmentEpoch("events", 0);

        for (var partition = 2; partition < PARTITIONS; partition++) {
            var key = ConsumerGroupKey.consumerGroupKey("g", "events", partition);

            assertThat(store.get(key)
                            .map(ConsumerGroupStreamLifeReclaimTest::epochOf)
                            .or(rejoinEpoch))
                    .as("partition %d: gone, or superseded by an epoch not older than the rejoin", partition)
                    .isGreaterThanOrEqualTo(rejoinEpoch);
        }
    }

    @Test
    void churn_boundsTheTombstones_byGroupsTimesPartitionsOfLiveStreams_bound() {
        var streams = List.of("s0", "s1", "s2", "s3", "s4");
        var groups = List.of("g1", "g2");

        for (var stream : streams) {
            createStream(stream);
            for (var group : groups) {
                coordinator.joinGroup(group, stream, PARTITIONS, "c", NODE_A);
                coordinator.leaveGroup(group, stream, "c");
            }
        }
        deleteStream("s0");
        deleteStream("s1");
        deleteStream("s2");

        var liveStreams = 2;
        var bound = groups.size() * PARTITIONS * liveStreams;
        var tombstones = store.snapshot().values().stream().filter(ConsumerGroupTombstone.class::isInstance).count();

        assertThat(tombstones).as("tombstones <= groups x partitions of live streams").isLessThanOrEqualTo(bound);
    }

    private void createStream(String name) {
        life++;
        store.process(store.createBatch(List.of(new KVCommand.Put<AetherKey, AetherValue>(StreamConfigKey.streamConfigKey(name),
                                                                                           StreamConfigValue.streamConfigValue(StreamConfig.streamConfig(name)
                                                                                                                                           .withIncarnation(life))))));
    }

    private void deleteStream(String name) {
        store.process(store.createBatch(List.of(new KVCommand.Remove<AetherKey>(StreamConfigKey.streamConfigKey(name)))));
    }

    private List<Map.Entry<AetherKey, AetherValue>> recordsOf(String streamName) {
        return store.snapshot()
                    .entrySet()
                    .stream()
                    .filter(entry -> entry.getKey() instanceof ConsumerGroupKey key && key.streamName().equals(streamName))
                    .toList();
    }

    private List<Map.Entry<AetherKey, AetherValue>> tombstonesOf(String streamName) {
        return recordsOf(streamName).stream()
                                    .filter(entry -> entry.getValue() instanceof ConsumerGroupTombstone)
                                    .toList();
    }

    private Epoch assignmentEpoch(String streamName, int partition) {
        return store.get(ConsumerGroupKey.consumerGroupKey("g", streamName, partition))
                    .map(ConsumerGroupStreamLifeReclaimTest::epochOf)
                    .or(Epoch.epoch(0L, 0L, 0L));
    }

    private static Epoch epochOf(AetherValue value) {
        return switch (value) {
            case ConsumerGroupValue assignment -> assignment.epoch();
            case ConsumerGroupTombstone tombstone -> tombstone.epoch();
            default -> Epoch.epoch(0L, 0L, 0L);
        };
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override
            public <T> void write(ByteBuf buffer, T value) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override
            public <T> T read(ByteBuf buffer) {
                return null;
            }
        };
    }
}
