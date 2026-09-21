// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;

import org.pragmatica.aether.api.routes.StreamApiRoutes.StreamCreateRequest;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;

import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;


/// `POST /streams` (`StreamApiRoutes#createStream(StreamCreateRequest)`, moved from `StreamRoutes` by
/// #968) must refuse to mint a stream under a reserved
/// [org.pragmatica.aether.slice.stream.SystemStreams] name — the target name is body-carried, so
/// `ManagementServer`'s pre-auth write-gate structurally cannot see it (gate condition 1: reuse the
/// dispatch path's route-match, no parallel body parser). The guard therefore lives in
/// `#createStreamWithConfig`, the sole call site that ever mints a stream, unconditionally, before the
/// mint — closing the window where a create racing ahead of `SystemStreamBootstrap` would otherwise
/// find `streamInfo(name)` empty and mint a caller-controlled config under a reserved name. This
/// harness's `streamPartitionManager` is never bootstrapped, so it exercises exactly that window.
class StreamRoutesCreateSystemStreamTest {
    private static ManageableNode nodeWith(StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager, store));
    }

    private static Object stubbed(String method,
                                  StreamPartitionManager manager,
                                  KVStore<AetherKey, AetherValue> store) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> store;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return routesFor(manager, store, StreamNamespacesService.inMemory());
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager,
                                             KVStore<AetherKey, AetherValue> store,
                                             StreamNamespacesService namespacesService) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager, store), namespacesService, null, null);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    @Test
    void createStream_reservedSystemStreamName_isRejectedAndNothingIsMinted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            assertThat(manager.streamInfo("cluster-events").isEmpty()).as("harness must not have bootstrapped system streams — this is the pre-bootstrap race window")
                      .isTrue();
            var result = routesFor(manager, emptyStore()).createStream(new StreamCreateRequest("cluster-events", 4));

            result.onSuccess(_ -> fail("a create targeting a reserved system stream name must be rejected"));
            assertThat(result.isFailure()).isTrue();
            assertThat(manager.streamInfo("cluster-events").isEmpty()).as("the guard must run before the mint — no stream may be created under the reserved name")
                      .isTrue();
        } finally {
            manager.close();
        }
    }

    /// #742 review SF-2, same shape here: the catalog spelling reduces to the reserved engine key and
    /// must be refused the same way the bare key is — before it would mint an app stream under a
    /// `system:`-prefixed engine key.
    @Test
    void createStream_catalogSpellingOfASystemStream_isRejectedAndNothingIsMinted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            var result = routesFor(manager, emptyStore()).createStream(new StreamCreateRequest("system:cluster-events:1.0.0",
                                                                                               4));

            result.onSuccess(_ -> fail("the catalog spelling of a reserved system stream must be rejected"));
            assertThat(manager.streamInfo("system:cluster-events:1.0.0").isEmpty()).isTrue();
            assertThat(manager.streamInfo("cluster-events").isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// #968: the pre-fix version of this test passed the bare name `orders` and asserted only the
    /// engine map — it specified the half-write the ticket names (rings minted, catalog untouched).
    /// The name is now a catalog address and the stream must be readable back from the catalog.
    @Test
    void createStream_ordinaryAppStreamAddress_stillSucceedsAndIsCatalogued() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();

        try {
            var result = routesFor(manager, emptyStore(), namespacesService).createStream(new StreamCreateRequest("com.example.app:orders:1.0.0",
                                                                                                                  4));

            result.onFailure(_ -> fail("an ordinary application stream address must not be affected by the guard"));
            assertThat(manager.streamInfo("com.example.app:orders:1.0.0").isPresent()).isTrue();
            assertThat(namespacesService.lookup(ResourceAddress.resourceAddress("com.example.app:orders:1.0.0").unwrap())
                                        .isPresent()).as("the created stream must be readable back from the catalog")
                      .isTrue();
        } finally {
            manager.close();
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
