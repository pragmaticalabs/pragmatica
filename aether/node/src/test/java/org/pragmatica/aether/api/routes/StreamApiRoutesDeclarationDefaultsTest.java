// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.api.ManagementServerError;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.ReplicationFactors;
import org.pragmatica.aether.slice.RetentionDimensions;
import org.pragmatica.aether.slice.RetentionMode;
import org.pragmatica.aether.slice.RetentionPolicy;
import org.pragmatica.aether.slice.blueprint.StreamConfigParser;
import org.pragmatica.aether.slice.stream.DeclarationDefaults;
import org.pragmatica.aether.slice.stream.StreamNamespacesService;
import org.pragmatica.aether.slice.stream.StreamResource;
import org.pragmatica.aether.stream.StreamPartitionManager;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.HttpStatusAware;
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
            assertThat(retention.mode()).as("the default mode is ANY (ST 2.2)").isEqualTo(RetentionMode.ANY);
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

    // ---- #3491 fix round: F1-F7 and the lane-b survivors ----

    private static RetentionPolicy enforcedAfter(StreamApiRoutes.CreateRequest request) {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager, StreamNamespacesService.inMemory()).createStream(NAMESPACE, STREAM, VERSION, request)
                                                                  .onFailure(cause -> fail("stream create must succeed: " + cause.message()));
            return retentionAfter(manager);
        } finally {
            manager.close();
        }
    }

    private static RetentionPolicy tomlRetention(String retentionLines) {
        var toml = "[streams.orders]\nversion = \"1.0.0\"\n" + retentionLines + "\n";
        var owned = (StreamResource.Owned) StreamConfigParser.parseResources(toml, ReplicationFactors.BUILT_IN).unwrap().get("orders");

        return owned.config().retention();
    }

    /// F1: a body naming some retention fields declares ONLY those, as the same declaration in TOML does; the omitted
    /// dimension is undeclared at the ring's sizing default. The enforced retention through the API equals the one the
    /// TOML parser produces for the same declaration. The mutant that declares age AND bytes whatever the body names
    /// fails the declared-dimensions equality.
    @Test
    void partialDeclaration_throughTheApi_equalsTheSameDeclarationThroughToml() {
        var bytesOnly = tomlRetention("retention = \"compound\"\nmax_bytes = \"8MB\"");
        var ageOnly = tomlRetention("retention = \"compound\"\nmax_age = \"30d\"");
        var countOnly = tomlRetention("retention = \"compound\"\nmax_count = \"500\"");

        assertThat(bytesOnly.declared()).isEqualTo(RetentionDimensions.BYTES);
        assertThat(enforcedAfter(new StreamApiRoutes.CreateRequest(null, null, bytesOnly.maxBytes(), null))).isEqualTo(bytesOnly);
        assertThat(enforcedAfter(new StreamApiRoutes.CreateRequest(null, "30d", null, null))).isEqualTo(ageOnly);
        assertThat(enforcedAfter(new StreamApiRoutes.CreateRequest(null, null, null, countOnly.maxCount()))).isEqualTo(countOnly);
    }

    /// F1: the legacy body-carried route resolves a partial declaration the same way.
    @Test
    void legacyCreate_maxBytesOnly_declaresOnlyTheBytes() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager, StreamNamespacesService.inMemory()).createStream(new StreamApiRoutes.StreamCreateRequest(ADDRESS, 1, null, 200L * 1024 * 1024 * 1024, null))
                                                                  .onFailure(cause -> fail("legacy create must succeed: " + cause.message()));

            var retention = retentionAfter(manager);

            assertThat(retention.declared()).isEqualTo(RetentionDimensions.BYTES);
            assertThat(retention.maxBytes()).isEqualTo(200L * 1024 * 1024 * 1024);
            assertThat(retention.maxAgeMs()).isEqualTo(RetentionPolicy.retentionPolicy().maxAgeMs());
        } finally {
            manager.close();
        }
    }

    /// F2: GET metadata reports the retention the node enforces, never a constant: the default after a plain create,
    /// and the declared values after a declared one.
    @Test
    void metadata_afterCreate_reportsTheEnforcedRetention() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                  .onFailure(_ -> fail("stream create must succeed"));

            var metadata = routes.streamMetadata(NAMESPACE, STREAM, VERSION).unwrap();

            assertThat(metadata.maxAgeMs()).isEqualTo(DAY_MS);
            assertThat(metadata.maxBytes()).isEqualTo(DeclarationDefaults.defaultMaxBytes(4));
        } finally {
            manager.close();
        }
    }

    @Test
    void metadata_afterDeclaredCreate_reportsTheDeclaredValues() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(2, "2h", 123_456_789L, 777L))
                  .onFailure(_ -> fail("declared stream create must succeed"));

            var metadata = routes.streamMetadata(NAMESPACE, STREAM, VERSION).unwrap();

            assertThat(metadata.maxAgeMs()).isEqualTo(2 * 60 * 60 * 1000L);
            assertThat(metadata.maxBytes()).isEqualTo(123_456_789L);
            assertThat(metadata.maxEvents()).isEqualTo(777L);
        } finally {
            manager.close();
        }
    }

    /// F2, the already-materialized arm: a stream a publish auto-created keeps the retention the engine holds, and the
    /// catalog entry (so GET metadata) reports that enforced retention, not the retention this create declared.
    @Test
    void createAfterPublishAutoCreate_registersTheEnforcedRetention() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.ensureStreamExists(ADDRESS).onFailure(cause -> fail("auto-create must succeed: " + cause.message()));
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null, "2h", null, null))
                  .onFailure(cause -> fail("create must succeed: " + cause.message()));

            assertThat(routes.streamMetadata(NAMESPACE, STREAM, VERSION).unwrap().maxAgeMs()).isEqualTo(DAY_MS);
            assertThat(retentionAfter(manager).maxAgeMs()).isEqualTo(DAY_MS);
        } finally {
            manager.close();
        }
    }

    /// F3: publish auto-create mints the declaration default on BOTH routes (it minted a 1 h / 4 MiB policy that
    /// declared nothing).
    @Test
    void publishAutoCreate_enforcesTheDeclarationDefault_onBothRoutes() {
        var viaApi = streamPartitionManager(Long.MAX_VALUE);
        var viaLegacy = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(viaApi, StreamNamespacesService.inMemory()).ensureStreamExists("auto-api")
                                                                 .onFailure(cause -> fail("auto-create must succeed: " + cause.message()));
            StreamRoutes.streamRoutes(() -> nodeWith(viaLegacy), null, null)
                        .ensureStreamExists("auto-legacy")
                        .onFailure(cause -> fail("auto-create must succeed: " + cause.message()));

            var expected = StreamApiRoutes.defaultRetention(4);

            assertThat(viaApi.enforcedConfig("auto-api").map(c -> c.retention()).or(RetentionPolicy.retentionPolicy())).isEqualTo(expected);
            assertThat(viaLegacy.enforcedConfig("auto-legacy").map(c -> c.retention()).or(RetentionPolicy.retentionPolicy())).isEqualTo(expected);
            assertThat(expected.declared()).isEqualTo(AGE_AND_BYTES);
        } finally {
            viaApi.close();
            viaLegacy.close();
        }
    }

    /// F5: caller input the engine or the parser refuses answers 400 with the error's own message, on both routes.
    @Test
    void malformedRetentionInput_answers400WithTheErrorsMessage() {
        assertRefusedAs400(new StreamApiRoutes.CreateRequest(null, "soon", null, null));
        assertRefusedAs400(new StreamApiRoutes.CreateRequest(null, null, 0L, null));
        assertRefusedAs400(new StreamApiRoutes.CreateRequest(null, null, -5L, null));
        assertRefusedAs400(new StreamApiRoutes.CreateRequest(null, null, null, 0L));
        assertRefusedAs400(new StreamApiRoutes.CreateRequest(null, null, null, -1L));
    }

    private static void assertRefusedAs400(StreamApiRoutes.CreateRequest request) {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager, StreamNamespacesService.inMemory()).createStream(NAMESPACE, STREAM, VERSION, request)
                                                                  .onSuccess(_ -> fail("must be refused: " + request))
                                                                  .onFailure(cause -> assertBadRequest(request, cause));
        } finally {
            manager.close();
        }
    }

    private static void assertBadRequest(StreamApiRoutes.CreateRequest request, org.pragmatica.lang.Cause cause) {
        assertThat(cause).as("a status-carrying cause for " + request).isInstanceOf(HttpStatusAware.class);
        assertThat(((HttpStatusAware) cause).httpStatus()).as("status for " + request).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(cause.message()).isNotBlank();
    }

    /// F6: an identical repeat create answers "exists"; a repeat declaring a DIFFERENT retention is refused with 409
    /// naming both; a repeat declaring nothing changes nothing and answers "exists".
    @Test
    void repeatCreate_identicalDeclaration_exists_differingDeclaration_isRefusedWith409() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var routes = routesFor(manager, StreamNamespacesService.inMemory());
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null, "2h", null, null))
                  .onFailure(_ -> fail("first create must succeed"));

            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null, "2h", null, null))
                  .onFailure(cause -> fail("identical repeat must answer exists: " + cause.message()))
                  .onSuccess(response -> assertThat(response.status()).isEqualTo("exists"));
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null))
                  .onFailure(cause -> fail("a repeat declaring nothing must answer exists: " + cause.message()))
                  .onSuccess(response -> assertThat(response.status()).isEqualTo("exists"));
            routes.createStream(NAMESPACE, STREAM, VERSION, new StreamApiRoutes.CreateRequest(null, "3h", null, null))
                  .onSuccess(_ -> fail("a differing declaration must be refused"))
                  .onFailure(cause -> assertConflictNamingBoth(cause));
            assertThat(retentionAfter(manager).maxAgeMs()).as("the refused repeat changed nothing").isEqualTo(2 * 60 * 60 * 1000L);
        } finally {
            manager.close();
        }
    }

    private static void assertConflictNamingBoth(org.pragmatica.lang.Cause cause) {
        assertThat(cause).isInstanceOf(ManagementServerError.Conflict.class);
        assertThat(((HttpStatusAware) cause).httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(cause.message()).contains("maxAgeMs=7200000").contains("maxAgeMs=10800000");
    }

    /// N8: the legacy body-carried create passes its `partitions` to the declaration default: with 1024 partitions and
    /// no `maxBytes` the byte default carries the partition term (8 GiB). The mutant that drops the partitions gets 1 GiB.
    @Test
    void legacyCreate_manyPartitions_byteDefaultCarriesThePartitionTerm() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            routesFor(manager, StreamNamespacesService.inMemory()).createStream(new StreamApiRoutes.StreamCreateRequest(ADDRESS, 1024))
                                                                  .onFailure(cause -> fail("legacy create must succeed: " + cause.message()));

            assertThat(retentionAfter(manager).maxBytes()).isEqualTo(8L * 1024 * 1024 * 1024);
        } finally {
            manager.close();
        }
    }

    /// N8: the legacy body-carried create with NO `partitions` mints the default 4-partition ring and reports 4; the
    /// mutant that reads the count from elsewhere reports another number.
    @Test
    void legacyCreate_nullPartitions_mintsTheDefaultFourPartitions() {
        var manager = streamPartitionManager(Long.MAX_VALUE);
        try {
            var response = routesFor(manager, StreamNamespacesService.inMemory()).createStream(new StreamApiRoutes.StreamCreateRequest(ADDRESS, null)).unwrap();

            assertThat(response.partitions()).isEqualTo(4);
            assertThat(response.status()).isEqualTo("created");
            assertThat(manager.enforcedConfig(ADDRESS).map(c -> c.partitions()).or(-1)).isEqualTo(4);
            assertThat(retentionAfter(manager).maxBytes()).isEqualTo(DeclarationDefaults.defaultMaxBytes(4));
        } finally {
            manager.close();
        }
    }

    private static void assertDeclared(RetentionPolicy retention) {
        assertThat(retention.maxAgeMs()).isEqualTo(2 * 60 * 60 * 1000L);
        assertThat(retention.maxBytes()).isEqualTo(123_456_789L);
        assertThat(retention.maxCount()).isEqualTo(777L);
        assertThat(retention.declared()).isEqualTo(ALL);
    }
}
