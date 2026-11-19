// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.metrics.topology;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SliceObservedKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.Generation;
import org.pragmatica.aether.slice.kvstore.AetherValue.ProcessIncarnation;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedState;
import org.pragmatica.aether.slice.kvstore.AetherValue.SliceObservedValue;
import org.pragmatica.aether.slice.kvstore.SliceRowSource;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #3411: the topology snapshot counts the slice distribution from the row source it is given (under FSM2 the current
/// `SliceObservedKey` rows, [SliceRowSource#observed]), not from `SliceNodeKey` rows, which no engine writes.
class TopologyCollectorSliceRowsTest {
    private static final NodeId SELF = new NodeId("node-a");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-worker:1.0.0").unwrap();

    @Test
    void snapshot_countsSliceDistribution_fromFsm2ObservedRows() {
        var store = new InMemoryKvStore();

        store.put(SliceObservedKey.sliceObservedKey(new NodeId("node-b"), ARTIFACT), observedRow(SliceObservedState.ACTIVE));
        store.put(SliceObservedKey.sliceObservedKey(new NodeId("node-c"), ARTIFACT), observedRow(SliceObservedState.ACTIVATING));
        store.put(SliceObservedKey.sliceObservedKey(new NodeId("node-d"), ARTIFACT), observedRow(SliceObservedState.UNLOADED));

        var slices = snapshotOver(store).slices();

        assertThat(slices).containsOnlyKeys(ARTIFACT.asString());
        assertThat(slices.get(ARTIFACT.asString()).nodeDistribution()).isEqualTo(Map.of("node-b", 1, "node-c", 1));
    }

    private static ClusterTopology snapshotOver(KVStore<AetherKey, AetherValue> store) {
        var collector = TopologyCollector.topologyCollector();

        return collector.setTopologyManager(topologyManager())
                        .flatMap(_ -> collector.setKVStore(store, SliceRowSource.observed(_ -> 0L)))
                        .map(_ -> collector.snapshot())
                        .unwrap();
    }

    /// The row the FSM2 node commits (`SliceObservedKey(self, artifact)` -> `SliceObservedValue`), with an unknown boot token
    /// (0), which the shared read takes as current; UNLOADED is the tombstone it reads as absent.
    private static SliceObservedValue observedRow(SliceObservedState state) {
        return new SliceObservedValue(state,
                                      1L,
                                      new Generation(1L, 1L),
                                      new ProcessIncarnation(1L, 0L),
                                      Option.none(),
                                      false,
                                      0,
                                      List.of(),
                                      0L);
    }

    private static TopologyManager topologyManager() {
        var self = NodeInfo.nodeInfo(SELF, NodeAddress.nodeAddress("10.0.0.1", 7100).unwrap());

        return (TopologyManager) Proxy.newProxyInstance(TopologyManager.class.getClassLoader(),
                                                        new Class[]{TopologyManager.class},
                                                        (_, method, _) -> switch (method.getName()) {
            case "self" -> self;
            case "quorumSize" -> 1;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method.getName());
        });
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore() {
            super(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }
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
