// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.stream.DeclarationDefaults;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.json.JsonMapper;

import java.lang.reflect.Proxy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// T-14 and the plan-added declared-body arm (#3334, G36): a stream created through the Management API gets the
/// declaration defaults a TOML stream gets (`max_age` 24 h AND `max_bytes = max(1 GiB, P x 2 x seal_merge_bytes)`),
/// and a stream declared in the request body carries what the body declared, on BOTH create routes (the legacy
/// body-carried `POST /streams` and the path-addressed one). The evidence is the config the node ENFORCES for the
/// stream, which is what `createStream` commits as the `StreamConfigKey` row (an in-JVM manager has no consensus;
/// [unverified: the KV row itself, no cluster here]).
class StreamApiRoutesDeclarationDefaultsTest {
    private static final String NAMESPACE = "com.example.app";
    private static final String STREAM = "orders";
    private static final String VERSION = "1.0.0";
    private static final String ADDRESS = NAMESPACE + ":" + STREAM + ":" + VERSION;
    private static final long DAY_MS = 24 * 60 * 60 * 1000L;
    private static final RetentionDimensions AGE_AND_BYTES = RetentionDimensions.retentionDimensions(false, true, true);
    private static final RetentionDimensions ALL = RetentionDimensions.retentionDimensions(true, true, true);

    private static ManageableNode nodeWith(StreamPartitionManager manager) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager));
    }

    /// #1564: the management create path reads the committed cluster config for its replication defaults; an empty
    /// store is the "no committed config yet" case — the built-in RF 3 / CF 2.
    private static final org.pragmatica.cluster.state.kvstore.KVStore<org.pragmatica.aether.slice.kvstore.AetherKey, org.pragmatica.aether.slice.kvstore.AetherValue> EMPTY_KV =
        new org.pragmatica.cluster.state.kvstore.KVStore<>(org.pragmatica.messaging.MessageRouter.mutable(),
                                                           new org.pragmatica.serialization.Serializer() {
                                                               @Override
                                                               public <T> void write(io.netty.buffer.ByteBuf byteBuf, T object) {}
                                                           },
                                                           new org.pragmatica.serialization.Deserializer() {
                                                               @Override
                                                               public <T> T read(io.netty.buffer.ByteBuf byteBuf) {
                                                                   return null;
                                                               }
                                                           });

    private static Object stubbed(String method, StreamPartitionManager manager) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> EMPTY_KV;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static StreamApiRoutes routesFor(StreamPartitionManager manager, StreamNamespacesService namespacesService) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager), namespacesService, null, null);
    }

    private static RetentionPolicy retentionAfter(StreamPartitionManager manager) {
        var config = manager.enforcedConfig(ADDRESS);

        assertThat(config.isPresent()).as("the stream must be enforced after create").isTrue();
        return config.map(c -> c.retention()).or(RetentionPolicy.retentionPolicy());
    }

    @Test
    void pathCreate_noDeclaration_getsTheDeclarationDefaults() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                  .onFailure(_ -> fail("stream create must succeed"));

            var retention = retentionAfter(manager);

            assertThat(retention.maxAgeMs()).isEqualTo(DAY_MS);
            assertThat(retention.maxBytes()).isEqualTo(DeclarationDefaults.defaultMaxBytes(4));
            assertThat(retention.declared()).isEqualTo(AGE_AND_BYTES);
        } finally {
            manager.close();
        }
    }

    @Test
    void legacyCreate_noDeclaration_getsTheDeclarationDefaults() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(new StreamApiRoutes.StreamCreateRequest(ADDRESS, 1))
                  .onFailure(_ -> fail("legacy stream create must succeed"));

            var retention = retentionAfter(manager);

            assertThat(retention.maxAgeMs()).isEqualTo(DAY_MS);
            assertThat(retention.maxBytes()).isEqualTo(DeclarationDefaults.defaultMaxBytes(1));
            assertThat(retention.declared()).isEqualTo(AGE_AND_BYTES);
        } finally {
            manager.close();
        }
    }

    @Test
    void pathCreate_manyPartitions_byteDefaultCarriesThePartitionTerm() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(1024))
                  .onFailure(_ -> fail("stream create must succeed"));

            assertThat(retentionAfter(manager).maxBytes()).isEqualTo(8L * 1024 * 1024 * 1024);
        } finally {
            manager.close();
        }
    }

    @Test
    void pathCreate_declaredBody_reachesTheRow() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(2, "2h", 123_456_789L, 777L))
                  .onFailure(_ -> fail("declared stream create must succeed"));

            assertDeclared(retentionAfter(manager));
        } finally {
            manager.close();
        }
    }

    @Test
    void legacyCreate_declaredBody_reachesTheRow() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(new StreamApiRoutes.StreamCreateRequest(ADDRESS, 2, "2h", 123_456_789L, 777L))
                  .onFailure(_ -> fail("declared legacy stream create must succeed"));

            assertDeclared(retentionAfter(manager));
        } finally {
            manager.close();
        }
    }

    @Test
    void pathCreate_malformedMaxAge_isRefusedAndMintsNothing() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());

            assertThat(routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null, "soon", null, null))
                             .isFailure()).isTrue();
            assertThat(manager.enforcedConfig(ADDRESS).isPresent()).isFalse();
        } finally {
            manager.close();
        }
    }

    /// The request bodies bind from JSON through the real binder: the declaration fields by name, and the
    /// pre-#3334 body (partitions only, name and partitions only) still binds with every declaration field absent.
    @Test
    void requestBodies_bindFromJson() {
        var mapper = JsonMapper.defaultJsonMapper();

        mapper.readString("{\"partitions\":2,\"maxAge\":\"2h\",\"maxBytes\":123456789,\"maxCount\":777}",
                          StreamApiRoutes.CreateRequest.class)
              .onFailure(_ -> fail("declared CreateRequest must bind"))
              .onSuccess(request -> assertThat(request).isEqualTo(new StreamApiRoutes.CreateRequest(2, "2h", 123_456_789L, 777L)));
        mapper.readString("{\"partitions\":2}", StreamApiRoutes.CreateRequest.class)
              .onFailure(_ -> fail("legacy CreateRequest must bind"))
              .onSuccess(request -> assertThat(request).isEqualTo(new StreamApiRoutes.CreateRequest(2)));
        mapper.readString("{\"name\":\"" + ADDRESS + "\",\"maxAge\":\"2h\",\"maxBytes\":5}",
                          StreamApiRoutes.StreamCreateRequest.class)
              .onFailure(_ -> fail("declared StreamCreateRequest must bind"))
              .onSuccess(request -> assertThat(request).isEqualTo(new StreamApiRoutes.StreamCreateRequest(ADDRESS, null, "2h", 5L, null)));
        mapper.readString("{\"name\":\"" + ADDRESS + "\",\"partitions\":3}", StreamApiRoutes.StreamCreateRequest.class)
              .onFailure(_ -> fail("legacy StreamCreateRequest must bind"))
              .onSuccess(request -> assertThat(request).isEqualTo(new StreamApiRoutes.StreamCreateRequest(ADDRESS, 3)));
    }

    private static void assertDeclared(RetentionPolicy retention) {
        assertThat(retention.maxAgeMs()).isEqualTo(2 * 60 * 60 * 1000L);
        assertThat(retention.maxBytes()).isEqualTo(123_456_789L);
        assertThat(retention.maxCount()).isEqualTo(777L);
        assertThat(retention.declared()).isEqualTo(ALL);
    }
}
