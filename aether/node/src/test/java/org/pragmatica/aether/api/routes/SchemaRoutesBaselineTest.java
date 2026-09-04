// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.api.routes;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaStatus;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaVersionValue;
import org.pragmatica.aether.slice.repository.Repository;
import org.pragmatica.cluster.node.ClusterNode;
import org.pragmatica.cluster.state.kvstore.KVCommand;
import org.pragmatica.cluster.state.kvstore.KVStore;
import org.pragmatica.consensus.NodeId;
import org.pragmatica.consensus.net.NodeInfo;
import org.pragmatica.consensus.topology.NodeState;
import org.pragmatica.consensus.topology.TopologyManager;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.messaging.MessageRouter;
import org.pragmatica.net.tcp.NodeAddress;
import org.pragmatica.serialization.Deserializer;
import org.pragmatica.serialization.Serializer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

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
    private static final NodeId SELF = new NodeId("node-a");

    private static final DatabaseConnectorConfig STUB_CONFIG = new DatabaseConnectorConfig(Option.none(),
                                                                                            Option.some(DatabaseType.POSTGRESQL),
                                                                                            Option.some("localhost"),
                                                                                            Option.none(),
                                                                                            Option.some("test"),
                                                                                            Option.none(),
                                                                                            Option.none(),
                                                                                            PoolConfig.DEFAULT,
                                                                                            Map.of(),
                                                                                            Option.none(),
                                                                                            Option.none(),
                                                                                            Option.none());

    /// `schema/orders/*.sql` is the layout `BlueprintArtifactParser` maps to the exact key
    /// `"database.orders"` this file baselines — folder name becomes the `"database."`-prefixed
    /// datasource key. The script CONTENT never runs: [BaselineOnlySchemaManager] fakes the actual
    /// SQL execution, so only `scripts.isEmpty() == false` matters for resolution to succeed.
    private static final String BLUEPRINT_TOML = """
            id = "org.example:orders-app:1.0.0"

            [[slices]]
            artifact = "org.example:orders-service:1.0.0"
            """;

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

    /// #543: `SchemaRoutes.baselineAtVersion` now calls `requireLeader` before delegating to
    /// `ManageableNode.schemaOrchestrator()`, so the proxy must answer both — and, per the brief's
    /// "real handler, not fixture-rebuilding helpers" requirement, `schemaOrchestrator()` hands back a
    /// REAL `SchemaOrchestratorService` (the same production factory `AetherNode` wires up), not a
    /// fake that just echoes what the test wants. Only the boundary collaborators genuinely external
    /// to this fix — the artifact store, the DB connector, and [BaselineOnlySchemaManager]'s actual SQL
    /// execution — are stubbed. Every assertion in [Preservation] is therefore produced by the real
    /// route → orchestrator → KV-write path, condition 3's evidence included (the written
    /// `currentVersion` comes from `SchemaResult.currentVersion()`, not the raw request parameter).
    private ManageableNode nodeOver(InMemoryKvStore kvStore) {
        var orchestrator = SchemaOrchestratorService.schemaOrchestratorService(new PlainClusterNode(SELF, kvStore),
                                                                               kvStore,
                                                                               artifactStoreServing(),
                                                                               noLocalRepository(),
                                                                               new BaselineOnlySchemaManager(),
                                                                               stubConnectionProvider(),
                                                                               SELF);

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           case "apply" -> applyBatch(args);
                                                           case "isLeader" -> true;
                                                           case "leader" -> Option.none();
                                                           case "schemaOrchestrator" -> orchestrator;
                                                           default -> throw new UnsupportedOperationException("Not implemented in test proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    @SuppressWarnings("unchecked")
    private Promise<List<Long>> applyBatch(Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(store::apply);

        return Promise.success(List.of());
    }

    /// Mirrors `SchemaOrchestratorRetrySingleFlightTest`'s `Repository repository = _ ->
    /// NOT_IN_REPOSITORY.promise();` — a repository miss is the common case, forcing resolution to
    /// fall back to [#artifactStoreServing].
    private static Repository noLocalRepository() {
        return _ -> Causes.cause("Artifact not present in local repository").promise();
    }

    private static byte[] blueprintJar() {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", BLUEPRINT_TOML);
            writeEntry(zip, "schema/orders/V001__init.sql", "CREATE TABLE orders(id INT);");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build test blueprint jar", e);
        }

        return bytes.toByteArray();
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static ArtifactStore artifactStoreServing() {
        var jarBytes = blueprintJar();

        return new ArtifactStore() {
            @Override public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override public Promise<byte[]> resolve(Artifact artifact) {
                return Promise.success(jarBytes);
            }

            @Override public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override public Promise<Boolean> exists(Artifact artifact) {
                return Promise.success(true);
            }

            @Override public Promise<Option<ArtifactMetadata>> metadata(Artifact artifact) {
                return Promise.success(Option.none());
            }

            @Override public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override public Promise<Unit> delete(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override public Metrics metrics() {
                return new Metrics(0, 0, 0L);
            }
        };
    }

    private static DatasourceConnectionProvider stubConnectionProvider() {
        return new DatasourceConnectionProvider() {
            @Override public Promise<SqlConnector> connector(String datasourceName) {
                return Promise.success(stubConnector());
            }

            @Override public Promise<Unit> release(String datasourceName) {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> releaseAll() {
                return Promise.unitPromise();
            }
        };
    }

    private static SqlConnector stubConnector() {
        return new SqlConnector() {
            @Override public DatabaseConnectorConfig config() {
                return STUB_CONFIG;
            }

            @Override public Promise<Boolean> isHealthy() {
                return Promise.success(true);
            }

            @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
                return Promise.success(Option.none());
            }

            @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
                return Promise.success(List.of());
            }

            @Override public Promise<Integer> update(String sql, Object... params) {
                return Promise.success(0);
            }

            @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
                return Promise.success(new int[0]);
            }

            @Override public <T> Promise<T> transactional(TransactionCallback<T> callback) {
                return callback.execute(this);
            }
        };
    }

    /// Echoes the requested version back as `SchemaResult.currentVersion()` — exactly what a real
    /// baseline implementation does (baseline sets the current version to the requested one). `undo`
    /// and `migrate` are never exercised by this file's tests and fail loudly if they ever are, so a
    /// wiring mistake shows up as a test failure instead of a silently wrong result.
    private static final class BaselineOnlySchemaManager implements AetherSchemaManager {
        @Override
        public Promise<SchemaResult> migrate(String datasource, List<MigrationEntry> scripts, SqlConnector connector, String nodeId, BlueprintId owner) {
            return Causes.cause("migrate() not exercised by SchemaRoutesBaselineTest").promise();
        }

        @Override
        public Promise<SchemaResult> undo(String datasource, int targetVersion, List<MigrationEntry> scripts, SqlConnector connector, String nodeId, BlueprintId owner) {
            return Causes.cause("undo() not exercised by SchemaRoutesBaselineTest").promise();
        }

        @Override
        public Promise<SchemaResult> baseline(String datasource, int baselineVersion, List<MigrationEntry> scripts, SqlConnector connector, String nodeId, BlueprintId owner) {
            return Promise.success(SchemaResult.schemaResult(scripts.size(), baselineVersion, 1L));
        }
    }

    /// Applies commands straight to the test's own `InMemoryKvStore` — no blocking, no dispatch
    /// recording, unlike `SchemaOrchestratorRetrySingleFlightTest`'s `RecordingClusterNode`, since no
    /// test here needs either.
    private static final class PlainClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private final NodeId self;
        private final InMemoryKvStore kvStore;

        PlainClusterNode(NodeId self, InMemoryKvStore kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            batch.forEach(kvStore::apply);

            return Promise.success(List.of());
        }
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override public int clusterSize() {
                return 1;
            }

            @Override public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override public List<NodeId> topology() {
                return List.of(self);
            }
        };
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
