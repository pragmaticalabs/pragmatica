// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementApiResponses.EnrichedNodeInfo;
import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.http.AppHttpServer;
import org.pragmatica.aether.metrics.ClusterSyncCollector;
import org.pragmatica.aether.node.ManageableNode;
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
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import static org.assertj.core.api.Assertions.assertThat;

/// #3411: `GET /api/nodes` lists every node that hosts a slice, read through the engine's per-node slice rows. Under FSM2
/// those are `SliceObservedKey` rows ([SliceRowSource#observed]); the route used to scan `SliceNodeKey`, which FSM2 never
/// writes, so a node known only by the slices it hosts (not the local node, not a connected peer, no metrics yet) was dropped.
class StatusRoutesNodesListTest {
    private static final NodeId SELF = new NodeId("node-a");
    private static final NodeId SLICE_HOST = new NodeId("node-c");
    private static final Artifact ARTIFACT = Artifact.artifact("org.example:orders-worker:1.0.0").unwrap();

    @Test
    void nodesList_listsNodeHostingOnlySlices_fromFsm2ObservedRows() {
        var store = new InMemoryKvStore();

        store.put(SliceObservedKey.sliceObservedKey(SLICE_HOST, ARTIFACT), observedRow(SliceObservedState.ACTIVE));

        assertThat(listedNodeIds(store)).containsExactly(SELF.id(), SLICE_HOST.id());
    }

    @Test
    void nodesList_omitsNode_whenItsOnlyRowIsTheUnloadedTombstone() {
        var store = new InMemoryKvStore();

        store.put(SliceObservedKey.sliceObservedKey(SLICE_HOST, ARTIFACT), observedRow(SliceObservedState.UNLOADED));

        assertThat(listedNodeIds(store)).containsExactly(SELF.id());
    }

    private static List<String> listedNodeIds(KVStore<AetherKey, AetherValue> store) {
        return StatusRoutes.statusRoutes(() -> nodeOver(store), StatusRoutesNodesListTest::noAppHttpServer)
                           .buildNodesResponse()
                           .nodes()
                           .stream()
                           .map(EnrichedNodeInfo::nodeId)
                           .toList();
    }

    /// The row the FSM2 node commits (`SliceObservedKey(self, artifact)` -> `SliceObservedValue`), with an unknown boot token
    /// (0), which the shared read takes as current.
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

    private static ManageableNode nodeOver(KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> switch (method.getName()) {
            case "self" -> SELF;
            case "connectedPeerIds" -> Set.of();
            case "metricsCollector" -> noMetrics();
            case "topologyManager" -> StatusRoutesLiveNodesTest.TestTopologyManager.with(Map.of());
            case "leader" -> Option.none();
            case "kvStore" -> store;
            case "sliceRows" -> SliceRowSource.observed(_ -> 0L);
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method.getName());
        });
    }

    private static ClusterSyncCollector noMetrics() {
        return (ClusterSyncCollector) Proxy.newProxyInstance(ClusterSyncCollector.class.getClassLoader(),
                                                             new Class[]{ClusterSyncCollector.class},
                                                             (_, method, _) -> switch (method.getName()) {
            case "allMetrics" -> Map.of();
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method.getName());
        });
    }

    private static AppHttpServer noAppHttpServer() {
        throw new UnsupportedOperationException("The nodes list never reads the app HTTP server");
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
