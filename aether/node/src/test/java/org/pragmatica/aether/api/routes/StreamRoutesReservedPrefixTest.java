// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.time.Instant;
import java.util.List;

import org.pragmatica.aether.stream.consumer.ConsumerGroupCoordinator;
import org.pragmatica.aether.stream.consumer.ConsumerGroupRegistry;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.api.routes.StreamRoutes.StreamCreateRequest;
import org.pragmatica.aether.dht.EntityPartitionArc;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.node.StreamEntityLogSubstrate;
import org.pragmatica.aether.resource.DurableTopicSpec;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.StreamConfig;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.StreamConfigKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.StreamConfigValue;
import org.pragmatica.aether.slice.resource.ResourceAddress;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamRegistryEntry;
import org.pragmatica.aether.slice.stream.SystemStreams;
import org.pragmatica.aether.stream.EvictionListener;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.aether.stream.StreamWriteRouter;
import org.pragmatica.aether.stream.topic.DurableTopicSubstrate;
import org.pragmatica.cluster.state.kvstore.KVCommand.Put;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import io.netty.buffer.ByteBuf;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.stream.StreamPartitionManager.streamPartitionManager;

/// #1282: stream KIND is carried by the engine-name prefix — `entity:` (entity keyspace logs,
/// `EntityPartitionArc`), `topic:` (durable topics and their DLQs, `DurableTopicNames`) and `system:`
/// (system streams) — and runtime rules key off it (#1233's fail-on-drop rule, the system budget
/// bypass). The Management API must never mint a stream under one of those prefixes with an
/// operator-chosen config: the real resource would later find it already present. Every external mint
/// site is covered — the legacy body-carried create, the catalog create (whose `topic`/`entity`
/// namespaces yield `topic:…`/`entity:…` engine keys), and both publish auto-create fallbacks. Internal
/// provisioning calls `StreamPartitionManager` directly and is unaffected.
class StreamRoutesReservedPrefixTest {

    @Test
    void createStream_topicPrefixedName_isRefusedAndNothingIsMinted() {
        assertLegacyCreateRefused("topic:foo", "topic:");
    }

    @Test
    void createStream_entityPrefixedName_isRefusedAndNothingIsMinted() {
        assertLegacyCreateRefused("entity:ledger", "entity:");
    }

    /// The pre-existing guard refuses only the ENUMERATED system streams (`SystemStreams.ALL`); any
    /// other `system:` name still minted a stream that then took the system budget bypass.
    @Test
    void createStream_nonEnumeratedSystemPrefixedName_isRefusedAndNothingIsMinted() {
        assertLegacyCreateRefused("system:foo:1.0.0", "system:");
        assertLegacyCreateRefused("system:bare", "system:");
    }

    @Test
    void catalogCreate_topicNamespace_isRefusedAndNothingIsMintedOrRegistered() {
        assertCatalogCreateRefused("topic", "topic:foo:1.0.0", "topic:");
    }

    @Test
    void catalogCreate_entityNamespace_isRefusedAndNothingIsMintedOrRegistered() {
        assertCatalogCreateRefused("entity", "entity:foo:1.0.0", "entity:");
    }

    /// Publish auto-create with NO committed config would fabricate a management default under the
    /// reserved name — the exact collision the refusal exists to prevent.
    @Test
    void ensureStreamExists_reservedNameWithoutCommittedConfig_isRefusedAndNothingIsMinted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            legacyRoutes(manager).ensureStreamExists("topic:foo")
                                 .onSuccess(_ -> fail("publish auto-create must not mint 'topic:foo' with a management default"))
                                 .onFailure(cause -> assertReserved(cause, "topic:foo", "topic:"));

            assertThat(manager.streamInfo("topic:foo").isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    @Test
    void catalogPublish_reservedNamespaceWithoutCommittedConfig_isRefusedAndNothingIsMinted() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            catalogRoutes(manager, StreamNamespacesService.inMemory())
                .publishEvent("topic", "foo", "1.0.0", new StreamApiRoutes.PublishRequest("payload", null))
                .await()
                .onSuccess(_ -> fail("catalog publish auto-create must not mint 'topic:foo:1.0.0'"))
                .onFailure(cause -> assertReserved(cause, "topic:foo:1.0.0", "topic:"));

            assertThat(manager.streamInfo("topic:foo:1.0.0").isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// The refusal is a Management-API boundary, not a manager-level one: internal durable-topic
    /// provisioning still creates `topic:<address>` (and its DLQ) on the same manager.
    @Test
    void durableTopicProvisioning_stillCreatesTopicPrefixedStreams() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            var spec = DurableTopicSpec.durableTopicSpec(1, 2, 2, DurableTopicSpec.DEFAULT_RETENTION).unwrap();

            DurableTopicSubstrate.durableTopicSubstrate(manager)
                                 .activateTopic("foo", spec)
                                 .onFailure(cause -> fail("internal topic provisioning must succeed: " + cause.message()));

            assertThat(manager.streamInfo("topic:foo").isPresent()).isTrue();
            assertThat(manager.streamInfo("topic:foo.dlq").isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// Review F2: a batch publish fans out per event and aggregates with `Result.allOf`, whose composite
    /// cause is not `HttpStatusAware` — the reserved-name refusal left the wire as 500. It must be the same
    /// 400 `ReservedStreamName` the single publish answers.
    @Test
    void catalogPublishBatch_reservedNamespaceWithoutCommittedConfig_isRefusedWith400() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            catalogRoutes(manager, StreamNamespacesService.inMemory())
                .publishBatch("topic",
                              "foo",
                              "1.0.0",
                              "publish-batch",
                              new StreamApiRoutes.PublishRequest[]{new StreamApiRoutes.PublishRequest("a", null),
                                                                   new StreamApiRoutes.PublishRequest("b", null)})
                .await()
                .onSuccess(_ -> fail("a batch publish must not mint 'topic:foo:1.0.0'"))
                .onFailure(cause -> assertReserved(cause, "topic:foo:1.0.0", "topic:"));

            assertThat(manager.streamInfo("topic:foo:1.0.0").isEmpty()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// Review round 3: the up-front ensure added for F2 must not turn an EMPTY batch into a stream
    /// creation — a batch with no events publishes nothing and must mint nothing.
    @Test
    void catalogPublishBatch_emptyBatch_mintsNoStream() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            catalogRoutes(manager, StreamNamespacesService.inMemory())
                .publishBatch("com.example.app", "orders", "1.0.0", "publish-batch", new StreamApiRoutes.PublishRequest[0])
                .await()
                .onFailure(cause -> fail("an empty batch must succeed: " + cause.message()))
                .onSuccess(response -> assertThat(response.published()).isZero());

            assertThat(manager.streamInfo("com.example.app:orders:1.0.0").isEmpty())
                .as("an empty batch must not create the stream")
                .isTrue();
        } finally {
            manager.close();
        }
    }

    /// Review F3/F4: answering `200 "exists"` for a reserved name that already exists is an existence
    /// oracle for internally provisioned streams. The refusal runs BEFORE the existence check.
    @Test
    void createStream_existingReservedName_isRefusedRatherThanReportedAsExisting() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            manager.createStream(StreamConfig.streamConfig("topic:foo"))
                   .onFailure(cause -> fail("internal creation must succeed: " + cause.message()));

            legacyRoutes(manager).createStream(new StreamCreateRequest("topic:foo", 4))
                                 .onSuccess(response -> fail("an existing reserved name must be refused, not reported as '"
                                                             + response.status() + "'"))
                                 .onFailure(cause -> assertReserved(cause, "topic:foo", "topic:"));
        } finally {
            manager.close();
        }
    }

    @Test
    void catalogCreate_existingReservedAddress_isRefusedRatherThanReportedAsExisting() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();
        var address = ResourceAddress.resourceAddress("topic", "foo", "1.0.0").unwrap();

        try {
            namespacesService.registry()
                             .register(StreamRegistryEntry.operator(address, RetentionPolicy.retentionPolicy(), Instant.now()))
                             .onFailure(cause -> fail("seeding the catalog must succeed: " + cause.message()));

            catalogRoutes(manager, namespacesService)
                .createStream("topic", "foo", "1.0.0", new StreamApiRoutes.CreateRequest(null))
                .onSuccess(response -> fail("an existing reserved address must be refused, not reported as '"
                                            + response.status() + "'"))
                .onFailure(cause -> assertReserved(cause, "topic:foo:1.0.0", "topic:"));
        } finally {
            manager.close();
        }
    }

    /// Internal entity-keyspace provisioning (`StreamEntityLogSubstrate.ensureLog`) still creates the
    /// `entity:<keyspace>` log on the same manager the Management API refuses to mint it on.
    @Test
    void entityKeyspaceProvisioning_stillCreatesEntityPrefixedStream() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var substrate = StreamEntityLogSubstrate.streamEntityLogSubstrate(manager,
                                                                          (_, _) -> new StreamPartitionManager.ReplicaCatchupSource.CatchupView(0,
                                                                                                                                                false),
                                                                          null,
                                                                          null,
                                                                          EvictionListener.NOOP,
                                                                          null,
                                                                          null,
                                                                          null);

        try {
            substrate.ensureLog("ledger", 2, 1, 1)
                     .onFailure(cause -> fail("internal entity provisioning must succeed: " + cause.message()));

            assertThat(manager.streamInfo(EntityPartitionArc.arcName("ledger")).isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// Internal system-stream provisioning creates its stream straight through
    /// `StreamPartitionManager.createStream` (as `AetherNode` does for `SystemStreams.CLUSTER_EVENTS`); the
    /// refusal is a Management-API boundary and does not reach it.
    @Test
    void systemStreamProvisioning_stillCreatesSystemPrefixedStream() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var name = SystemStreams.CLUSTER_EVENTS.asString();

        try {
            manager.createStream(StreamConfig.streamConfig(name))
                   .onFailure(cause -> fail("internal system-stream provisioning must succeed: " + cause.message()));

            assertThat(manager.streamInfo(name).isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// Over-refusal guard: a `system`-namespace catalog address reduces to its bare engine name — the flat
    /// operator-stream spelling — so it is NOT reserved and still mints.
    @Test
    void catalogCreate_systemNamespaceFlatStream_stillSucceeds() {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            catalogRoutes(manager, StreamNamespacesService.inMemory())
                .createStream("system", "diagnostics", "1.0.0", new StreamApiRoutes.CreateRequest(null))
                .onFailure(cause -> fail("a flat operator stream must still be creatable: " + cause.message()));

            assertThat(manager.streamInfo("diagnostics").isPresent()).isTrue();
        } finally {
            manager.close();
        }
    }

    /// Over-refusal guard: a publish racing the real resource's local materialization finds its COMMITTED
    /// config in KV and adopts it — that is the real resource, not an operator-side fabrication.
    @Test
    void ensureStreamExists_reservedNameWithCommittedConfig_adoptsTheCommittedConfig() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var store = new KVStore<AetherKey, AetherValue>(MessageRouter.mutable(), stubSerializer(), stubDeserializer());
        var committed = DurableTopicSubstrate.topicStreamConfig("foo",
                                                                DurableTopicSpec.durableTopicSpec(1, 2, 2, DurableTopicSpec.DEFAULT_RETENTION)
                                                                                .unwrap());

        try {
            store.process(store.createBatch(List.of(new Put<>(StreamConfigKey.streamConfigKey(committed.name()),
                                                              StreamConfigValue.streamConfigValue(committed)))));

            legacyRoutes(manager, store).ensureStreamExists(committed.name())
                                        .onFailure(cause -> fail("a committed real-resource config must be adopted: " + cause.message()));

            assertThat(manager.minSyncReplicasFor(committed.name())).isEqualTo(2);
        } finally {
            manager.close();
        }
    }

    // === helpers ===

    private static void assertLegacyCreateRefused(String name, String prefix) {
        var manager = streamPartitionManager(Long.MAX_VALUE);

        try {
            legacyRoutes(manager).createStream(new StreamCreateRequest(name, 4))
                                 .onSuccess(_ -> fail("a create under a reserved prefix must be refused: " + name))
                                 .onFailure(cause -> assertReserved(cause, name, prefix));

            assertThat(manager.streamInfo(name).isEmpty()).as("nothing minted under " + name).isTrue();
        } finally {
            manager.close();
        }
    }

    private static void assertCatalogCreateRefused(String namespace, String engineKey, String prefix) {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        var namespacesService = StreamNamespacesService.inMemory();

        try {
            catalogRoutes(manager, namespacesService)
                .createStream(namespace, "foo", "1.0.0", new StreamApiRoutes.CreateRequest(null))
                .onSuccess(_ -> fail("a catalog create yielding engine key " + engineKey + " must be refused"))
                .onFailure(cause -> assertReserved(cause, engineKey, prefix));

            assertThat(manager.streamInfo(engineKey).isEmpty()).as("nothing minted under " + engineKey).isTrue();
            assertThat(namespacesService.snapshot()).as("nothing registered for " + engineKey).isEmpty();
        } finally {
            manager.close();
        }
    }

    private static void assertReserved(Cause cause, String streamName, String prefix) {
        assertThat(cause).isEqualTo(new ManagementServerError.ReservedStreamName(streamName, prefix));
        assertThat(((ManagementServerError) cause).httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private static StreamRoutes legacyRoutes(StreamPartitionManager manager) {
        return legacyRoutes(manager, new KVStore<>(null, null, null));
    }

    private static StreamRoutes legacyRoutes(StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return StreamRoutes.streamRoutes(() -> nodeWith(manager, store), null, null);
    }

    private static StreamApiRoutes catalogRoutes(StreamPartitionManager manager, StreamNamespacesService namespacesService) {
        return StreamApiRoutes.streamApiRoutes(() -> nodeWith(manager, new KVStore<>(null, null, null)),
                                               namespacesService,
                                               ConsumerGroupCoordinator.noOp(),
                                               ConsumerGroupRegistry.consumerGroupRegistry());
    }

    private static ManageableNode nodeWith(StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, _) -> stubbed(method.getName(), manager, store));
    }

    private static Object stubbed(String method, StreamPartitionManager manager, KVStore<AetherKey, AetherValue> store) {
        return switch (method) {
            case "streamPartitionManager" -> manager;
            case "kvStore" -> store;
            case "streamWriteRouter" -> StreamWriteRouter.localOnly(manager);
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
