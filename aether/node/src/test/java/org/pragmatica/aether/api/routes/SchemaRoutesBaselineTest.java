// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.lang.reflect.Proxy;
import java.util.List;

import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;

/// #542 adjacent defect — `POST /api/schema/{ds}/baseline` used the coordinates-less
/// `SchemaVersionValue` factory, which reset `artifactCoords` to `""`. That silently broke
/// `SchemaOrchestratorService.resolveAndParseMigrations` on any later migrate (the declared scripts
/// could no longer be located) and, once ownership became a record component, would have detached
/// the record from the blueprint whose activation gate consults it. Baselining now rewrites only the
/// version, the marker name and the status.
class SchemaRoutesBaselineTest {
    private static final String DATASOURCE = "database.orders";
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(COORDS).unwrap();
    private static final String LAST_MIGRATION = "V003__add_index.sql";

    private InMemoryKvStore store;
    private SchemaRoutes routes;

    @BeforeEach
    void setUp() {
        store = new InMemoryKvStore(MessageRouter.mutable());
        routes = SchemaRoutes.schemaRoutes(() -> nodeOver(store));
    }

    @Nested
    class Preservation {
        @Test
        void baselineDatasource_preservesArtifactCoords_forExistingRecord() {
            seed(SchemaStatus.COMPLETED);

            routes.baselineDatasource(DATASOURCE, Option.some("7"))
                  .await()
                  .onFailure(SchemaRoutesBaselineTest::failOnUnexpectedFailure);

            assertThat(recorded().artifactCoords()).as("dropping the coordinates orphans the declared migration set")
                                                   .isEqualTo(COORDS);
        }

        @Test
        void baselineDatasource_preservesOwningBlueprint_forExistingRecord() {
            seed(SchemaStatus.COMPLETED);

            routes.baselineDatasource(DATASOURCE, Option.some("7"))
                  .await()
                  .onFailure(SchemaRoutesBaselineTest::failOnUnexpectedFailure);

            assertThat(recorded().owningBlueprint()).as("dropping the owner detaches the record from its activation gate")
                                                    .isEqualTo(OWNER);
        }

        @Test
        void baselineDatasource_rewritesVersionAndStatus_forExistingRecord() {
            seed(SchemaStatus.FAILED);

            routes.baselineDatasource(DATASOURCE, Option.some("7"))
                  .await()
                  .onFailure(SchemaRoutesBaselineTest::failOnUnexpectedFailure);

            assertThat(recorded().currentVersion()).isEqualTo(7);
            assertThat(recorded().status()).isEqualTo(SchemaStatus.COMPLETED);
            assertThat(recorded().lastMigration()).isEqualTo("V007__baseline");
        }
    }

    @Nested
    class MissingRecord {
        @Test
        void baselineDatasource_fails_whenDatasourceHasNoRecord() {
            routes.baselineDatasource(DATASOURCE, Option.some("7"))
                  .await()
                  .onSuccess(_ -> Assertions.fail("Baselining a datasource with no record must not fabricate an unowned one"))
                  .onFailure(cause -> assertThat(cause.message()).contains("Schema status not found"));
        }

        @Test
        void baselineDatasource_writesNothing_whenDatasourceHasNoRecord() {
            routes.baselineDatasource(DATASOURCE, Option.some("7")).await();

            assertThat(store.get(SchemaVersionKey.schemaVersionKey(DATASOURCE)).isPresent()).isFalse();
        }
    }

    // --- helpers ---

    private static void failOnUnexpectedFailure(Cause cause) {
        Assertions.fail("Unexpected baseline failure: " + cause.message());
    }

    private void seed(SchemaStatus status) {
        store.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                  SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                        3,
                                                        LAST_MIGRATION,
                                                        status,
                                                        COORDS,
                                                        OWNER));
    }

    private SchemaVersionValue recorded() {
        return store.get(SchemaVersionKey.schemaVersionKey(DATASOURCE))
                    .filter(SchemaVersionValue.class::isInstance)
                    .map(SchemaVersionValue.class::cast)
                    .or(SchemaRoutesBaselineTest::noRecord);
    }

    private static SchemaVersionValue noRecord() {
        return Assertions.fail("No schema version record was written");
    }

    private ManageableNode nodeOver(InMemoryKvStore kvStore) {
        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           case "apply" -> applyBatch(args);
                                                           default -> throw new UnsupportedOperationException("Not implemented in test proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    @SuppressWarnings("unchecked")
    private Promise<List<Long>> applyBatch(Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(store::apply);

        return Promise.success(List.of());
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            apply(new KVCommand.Put<>(key, value));
        }

        void apply(KVCommand<AetherKey> command) {
            process(createBatch(List.of(command)));
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
