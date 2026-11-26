// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamReadRouter;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.routing.Route;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import static org.junit.jupiter.api.Assertions.fail;

/// #1753, #1963: the stream routes resolve a stream through the CLUSTER catalog (the registry entry and the committed
/// [StreamConfig]) and ask the partition owner for the data, so a test of a route needs a node that has both a catalog and a read
/// router. This is that node, built once, so each test says only what its catalog and its engine hold.
final class StreamRouteFixtures {
    private StreamRouteFixtures() {}

    /// The KV store of a node whose catalog has not committed any stream.
    static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    /// The KV store of a node whose catalog has committed `config` under `engineKey`.
    static KVStore<AetherKey, AetherValue> storeCommitting(String engineKey, StreamConfig config) {
        var store = emptyStore();

        store.process(store.createBatch(List.of(new Put<>(StreamConfigKey.streamConfigKey(engineKey),
                                                          StreamConfigValue.streamConfigValue(config)))));
        return store;
    }

    /// A registry that holds the operator-declared stream `address`.
    static StreamNamespacesService registryWith(ResourceAddress address, RetentionPolicy retention) {
        var namespaces = StreamNamespacesService.inMemory();

        namespaces.registry()
                  .register(StreamRegistryEntry.operator(address, retention, Instant.now()))
                  .onFailure(cause -> fail("registration must succeed: " + cause.message()));
        return namespaces;
    }

    /// Both route classes over one node: the catalog routes ([StreamApiRoutes]) and the flat ones ([StreamRoutes]).
    static Stream<Route<?>> routes(StreamPartitionManager manager,
                                   StreamReadRouter readRouter,
                                   KVStore<AetherKey, AetherValue> store,
                                   StreamNamespacesService namespaces) {
        return routes(manager, readRouter, StreamWriteRouter.localOnly(manager), store, namespaces);
    }

    /// The same two route classes over a node whose writes go through `writeRouter`: a publish at a node that is not the
    /// stream's owner reaches the owner through it, which is the path a publish takes in production.
    static Stream<Route<?>> routes(StreamPartitionManager manager,
                                   StreamReadRouter readRouter,
                                   StreamWriteRouter writeRouter,
                                   KVStore<AetherKey, AetherValue> store,
                                   StreamNamespacesService namespaces) {
        var node = node(manager, readRouter, writeRouter, store);
        var api = StreamApiRoutes.streamApiRoutes(() -> node,
                                                  namespaces,
                                                  ConsumerGroupCoordinator.noOp(),
                                                  ConsumerGroupRegistry.consumerGroupRegistry());
        var flat = StreamRoutes.streamRoutes(() -> node,
                                             ConsumerGroupCoordinator.noOp(),
                                             ConsumerGroupRegistry.consumerGroupRegistry());

        return Stream.concat(api.routes(), flat.routes());
    }

    static ManageableNode node(StreamPartitionManager manager, StreamReadRouter readRouter, KVStore<AetherKey, AetherValue> store) {
        return node(manager, readRouter, StreamWriteRouter.localOnly(manager), store);
    }

    static ManageableNode node(StreamPartitionManager manager,
                               StreamReadRouter readRouter,
                               StreamWriteRouter writeRouter,
                               KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager, readRouter, writeRouter, store));
    }

    private static Object stubbed(String method,
                                  StreamPartitionManager manager,
                                  StreamReadRouter readRouter,
                                  StreamWriteRouter writeRouter,
                                  KVStore<AetherKey, AetherValue> store) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "streamReadRouter" -> readRouter;
            case "streamWriteRouter" -> writeRouter;
            case "kvStore" -> store;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
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
