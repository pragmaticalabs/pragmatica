// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ConsistencyMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamCompression;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Option;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import java.lang.reflect.Proxy;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// The Management-API publish path (`POST /api/streams/publish/{name}`) auto-creates a missing stream
/// via {@link StreamRoutes#ensureStreamExists}. Before the fix it ALWAYS committed a fabricated
/// `replicas=1/min-sync=0` default, discarding an app/blueprint stream's committed replication knobs —
/// disarming the sync barrier and collapsing the replica set to RF=1. It must now (a) leave an already-
/// materialized stream's config intact, (b) adopt the committed `StreamConfig` from applied KV state
/// when the stream is not yet locally materialized (the slice-activation race), and (c) fall back to the
/// management default only for a genuinely new management-only stream with no committed entry.
class StreamRoutesEnsureStreamExistsTest {
    private static final RetentionPolicy RETENTION = RetentionPolicy.retentionPolicy(1000, 1024 * 1024, 60_000);

    private static ManageableNode nodeWith(StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager, store));
    }

    private static Object stubbed(String method, StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> store;
            default -> throw new UnsupportedOperationException("Not stubbed in test proxy: " + method);
        };
    }

    private static StreamRoutes routesFor(StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return StreamRoutes.streamRoutes(() -> nodeWith(manager, store), null, null);
    }

    private static KVStore<AetherKey, AetherValue> emptyStore() {
        return new KVStore<>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
    }

    private static StreamConfig appConfig(String name) {
        return StreamConfig.streamConfig(name,
                                         4,
                                         RETENTION,
                                         "latest",
                                         1_048_576L,
                                         ConsistencyMode.EVENTUAL,
                                         2,
                                         2,
                                         StreamCompression.NONE,
                                         Option.none());
    }

    private static void seed(KVStore<AetherKey, AetherValue> store, AetherKey key, AetherValue value) {
        store.process(store.createBatch(List.of(new Put<>(key, value))));
    }

    @Test
    void ensureStreamExists_existingAppStream_preservesReplicationConfig() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            manager.createStream(appConfig("app-stream")).onFailure(_ -> fail("app stream create must succeed"));
            assertThat(manager.minSyncReplicasFor("app-stream")).isEqualTo(2);

            routesFor(manager, emptyStore()).ensureStreamExists("app-stream")
                              .onFailure(_ -> fail("ensureStreamExists must succeed for an existing stream"));

            assertThat(manager.minSyncReplicasFor("app-stream"))
                .as("a management publish must NOT downgrade an app stream's min-sync-replicas")
                .isEqualTo(2);
            manager.streamInfo("app-stream")
                   .onPresent(si -> assertThat(si.partitions()).isEqualTo(4));
        } finally {
            manager.close();
        }
    }

    @Test
    void ensureStreamExists_committedConfigNotYetMaterialized_adoptsCommittedReplication() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var store = emptyStore();
            seed(store,
                 StreamConfigKey.streamConfigKey("repl-failover-events"),
                 StreamConfigValue.streamConfigValue(appConfig("repl-failover-events")));

            assertThat(manager.streamInfo("repl-failover-events").isEmpty())
                .as("stream must NOT be locally materialized yet — simulates a publish racing slice activation")
                .isTrue();

            routesFor(manager, store).ensureStreamExists("repl-failover-events")
                              .onFailure(_ -> fail("ensureStreamExists must materialize the committed config"));

            assertThat(manager.minSyncReplicasFor("repl-failover-events"))
                .as("auto-create must adopt the committed min-sync-replicas, NOT the RF=1 management default")
                .isEqualTo(2);
        } finally {
            manager.close();
        }
    }

    @Test
    void ensureStreamExists_newManagementStream_createsWithDefault() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager, emptyStore()).ensureStreamExists("mgmt-stream")
                              .onFailure(_ -> fail("ensureStreamExists must auto-create a new management stream"));

            assertThat(manager.streamInfo("mgmt-stream").isPresent())
                .as("a genuinely new management stream must be auto-created")
                .isTrue();
            assertThat(manager.minSyncReplicasFor("mgmt-stream")).isEqualTo(0);
        } finally {
            manager.close();
        }
    }

    private static Serializer stubSerializer() {
        return new Serializer() {
            @Override public <T> void write(ByteBuf byteBuf, T object) {}
        };
    }

    private static Deserializer stubDeserializer() {
        return new Deserializer() {
            @Override public <T> T read(ByteBuf byteBuf) {
                return null;
            }
        };
    }
}
