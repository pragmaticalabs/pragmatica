// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.

package org.pragmatica.aether.deployment.schema;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
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

/// Issue #518: a datasource whose declared migration set cannot be resolved must NOT report a
/// successful migration. The two cases are separated structurally, not by log level:
///
/// - "declares no migrations" — no `SchemaVersionValue` record is written at all
///   (`BlueprintService.buildAllCommands` skips the schema commands when the artifact carries
///   none), so `migrateIfNeeded` short-circuits quietly.
/// - "declares migrations but they cannot be resolved" — the record exists and names a version /
///   last-migration, yet either the artifact coordinates are absent or the resolved artifact holds
///   no scripts for the datasource. Both propagate a typed `SchemaError` to the caller and land the
///   record in `FAILED`.
class SchemaMigrationResolutionTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final String DATASOURCE = "database.orders";
    private static final String COORDS = "org.example:my-app:1.0.0";
    private static final String LAST_MIGRATION = "V003__add_index.sql";
    private static final int DECLARED_VERSION = 3;
    private static final Cause NOT_IN_REPOSITORY = Causes.cause("Artifact not present in local repository");
    private static final Cause NOT_IN_STORE = Causes.cause("Artifact not present in artifact store");
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

    private static final String BLUEPRINT_TOML = """
            id = "org.example:my-app:1.0.0"

            [[slices]]
            artifact = "org.example:order-service:1.0.0"
            """;

    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private RecordingSchemaManager schemaManager;

    @BeforeEach
    void setUp() {
        cluster = new RecordingClusterNode(SELF);
        kvStore = new InMemoryKvStore(MessageRouter.mutable());
        schemaManager = new RecordingSchemaManager();
    }

    @Nested
    class DeclaredNoMigrations {
        @Test
        void migrateIfNeeded_succeedsQuietly_whenNoSchemaVersionRecordExists() {
            orchestrator(unreachableArtifactStore()).migrateIfNeeded(DATASOURCE)
                                                    .await()
                                                    .onFailure(SchemaMigrationResolutionTest::failOnUnexpectedFailure);

            assertThat(cluster.commands).isEmpty();
            assertThat(schemaManager.invocations).isEmpty();
        }

        @Test
        void migrateIfNeeded_succeedsQuietly_whenRecordDeclaresEmptyMigrationSet() {
            seedSchemaVersion(0, "", "");

            orchestrator(unreachableArtifactStore()).migrateIfNeeded(DATASOURCE)
                                                    .await()
                                                    .onFailure(SchemaMigrationResolutionTest::failOnUnexpectedFailure);

            assertThat(statusesWrittenToKv()).contains(SchemaStatus.COMPLETED)
                                             .doesNotContain(SchemaStatus.FAILED);
            assertThat(schemaManager.invocations).isEmpty();
        }
    }

    @Nested
    class DeclaredButUnresolvable {
        @Test
        void migrateIfNeeded_fails_whenDeclaredMigrationsHaveNoArtifactCoords() {
            seedSchemaVersion(DECLARED_VERSION, LAST_MIGRATION, "");

            orchestrator(unreachableArtifactStore()).migrateIfNeeded(DATASOURCE)
                                                    .await()
                                                    .onSuccess(_ -> Assertions.fail("Migration reported success while the artifact coordinates were missing"))
                                                    .onFailure(SchemaMigrationResolutionTest::assertUnresolvedCoords);

            assertThat(statusesWrittenToKv()).contains(SchemaStatus.FAILED)
                                             .doesNotContain(SchemaStatus.COMPLETED);
            assertThat(schemaManager.invocations).isEmpty();
        }

        @Test
        void migrateIfNeeded_fails_whenResolvedArtifactHasNoScriptsForDatasource() {
            seedSchemaVersion(DECLARED_VERSION, LAST_MIGRATION, COORDS);

            orchestrator(artifactStoreServing(blueprintJar(Option.none()))).migrateIfNeeded(DATASOURCE)
                                                                          .await()
                                                                          .onSuccess(_ -> Assertions.fail("Migration reported success while the resolved artifact held no scripts"))
                                                                          .onFailure(SchemaMigrationResolutionTest::assertUnavailableMigrationSet);

            assertThat(statusesWrittenToKv()).contains(SchemaStatus.FAILED)
                                             .doesNotContain(SchemaStatus.COMPLETED);
            assertThat(schemaManager.invocations).isEmpty();
        }

        @Test
        void classifyFailure_permanent_forMigrationArtifactUnresolved() {
            var cause = SchemaError.MigrationArtifactUnresolved.migrationArtifactUnresolved(DATASOURCE,
                                                                                             DECLARED_VERSION,
                                                                                             LAST_MIGRATION);

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(FailureClassification.PERMANENT);
        }

        @Test
        void classifyFailure_permanent_forMigrationSetUnavailable() {
            var cause = SchemaError.MigrationSetUnavailable.migrationSetUnavailable(DATASOURCE,
                                                                                     COORDS,
                                                                                     DECLARED_VERSION);

            assertThat(SchemaOrchestratorServiceInstance.classifyFailure(cause)).isEqualTo(FailureClassification.PERMANENT);
        }
    }

    /// The false-positive direction guard: a datasource whose declared scripts DO resolve must still
    /// deploy, reaching the schema manager and completing.
    @Nested
    class DeclaredAndResolvable {
        @Test
        void migrateIfNeeded_appliesScripts_whenResolvedArtifactHasScriptsForDatasource() {
            seedSchemaVersion(DECLARED_VERSION, LAST_MIGRATION, COORDS);

            orchestrator(artifactStoreServing(blueprintJar(Option.some("schema/orders/V003__add_index.sql")))).migrateIfNeeded(DATASOURCE)
                                                                                                             .await()
                                                                                                             .onFailure(SchemaMigrationResolutionTest::failOnUnexpectedFailure);

            assertThat(schemaManager.invocations).containsExactly(DATASOURCE);
            assertThat(statusesWrittenToKv()).contains(SchemaStatus.COMPLETED)
                                             .doesNotContain(SchemaStatus.FAILED);
        }
    }

    private static void assertUnresolvedCoords(Cause cause) {
        assertThat(cause).isInstanceOf(SchemaError.MigrationArtifactUnresolved.class);
        assertThat(cause.message()).contains(DATASOURCE)
                                   .contains(LAST_MIGRATION)
                                   .contains("no blueprint artifact coordinates");
    }

    private static void assertUnavailableMigrationSet(Cause cause) {
        assertThat(cause).isInstanceOf(SchemaError.MigrationSetUnavailable.class);
        assertThat(cause.message()).contains(DATASOURCE)
                                   .contains(COORDS)
                                   .contains("no migration scripts");
    }

    private static void failOnUnexpectedFailure(Cause cause) {
        Assertions.fail("Unexpected migration failure: " + cause.message());
    }

    private SchemaOrchestratorService orchestrator(ArtifactStore artifactStore) {
        Repository repository = _ -> NOT_IN_REPOSITORY.promise();

        return SchemaOrchestratorService.schemaOrchestratorService(cluster,
                                                                   kvStore,
                                                                   artifactStore,
                                                                   repository,
                                                                   schemaManager,
                                                                   stubConnectionProvider(),
                                                                   SELF);
    }

    private void seedSchemaVersion(int currentVersion, String lastMigration, String artifactCoords) {
        kvStore.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                    SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                          currentVersion,
                                                          lastMigration,
                                                          SchemaStatus.PENDING,
                                                          artifactCoords));
    }

    private List<SchemaStatus> statusesWrittenToKv() {
        return cluster.schemaVersionPuts()
                      .stream()
                      .map(SchemaVersionValue::status)
                      .toList();
    }

    /// A blueprint jar carrying `META-INF/blueprint.toml` and, when requested, one migration script.
    private static byte[] blueprintJar(Option<String> migrationEntryPath) {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", BLUEPRINT_TOML);
            migrationEntryPath.onPresent(path -> writeMigration(zip, path));
        } catch (IOException e) {
            throw new IllegalStateException("Failed to build test blueprint jar", e);
        }

        return bytes.toByteArray();
    }

    private static void writeMigration(ZipOutputStream zip, String path) {
        try {
            writeEntry(zip, path, "CREATE INDEX idx_orders ON orders(id);");
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write test migration entry", e);
        }
    }

    private static void writeEntry(ZipOutputStream zip, String name, String content) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(content.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static ArtifactStore unreachableArtifactStore() {
        return artifactStore(NOT_IN_STORE.promise());
    }

    private static ArtifactStore artifactStoreServing(byte[] jarBytes) {
        return artifactStore(Promise.success(jarBytes));
    }

    private static ArtifactStore artifactStore(Promise<byte[]> resolution) {
        return new ArtifactStore() {
            @Override public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<byte[]> resolve(Artifact artifact) {
                return resolution;
            }

            @Override public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
                return NOT_IN_STORE.promise();
            }

            @Override public Promise<Boolean> exists(Artifact artifact) {
                return Promise.success(false);
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

    /// The schema manager stub never touches the connector, so every statement path is inert.
    private static SqlConnector stubConnector() {
        return new SqlConnector() {
            @Override public DatabaseConnectorConfig config() {
                return STUB_CONFIG;
            }

            @Override public Promise<Boolean> isHealthy() {
                return Promise.success(true);
            }

            @Override public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
                return NOT_IN_STORE.promise();
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

    private static final class RecordingSchemaManager implements AetherSchemaManager {
        final List<String> invocations = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Promise<SchemaResult> migrate(String datasource,
                                             List<MigrationEntry> scripts,
                                             SqlConnector connector,
                                             String nodeId) {
            invocations.add(datasource);

            return Promise.success(SchemaResult.schemaResult(scripts.size(), DECLARED_VERSION, 1L));
        }

        @Override
        public Promise<SchemaResult> undo(String datasource,
                                          int targetVersion,
                                          List<MigrationEntry> scripts,
                                          SqlConnector connector,
                                          String nodeId) {
            return Promise.success(SchemaResult.schemaResult(0, targetVersion, 1L));
        }

        @Override
        public Promise<SchemaResult> baseline(String datasource,
                                              int baselineVersion,
                                              List<MigrationEntry> scripts,
                                              SqlConnector connector,
                                              String nodeId) {
            return Promise.success(SchemaResult.schemaResult(0, baselineVersion, 1L));
        }
    }

    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self) {this.self = self;}

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);

            return Promise.success(Collections.emptyList());
        }

        List<SchemaVersionValue> schemaVersionPuts() {
            synchronized (commands) {
                return commands.stream()
                               .map(RecordingClusterNode::schemaVersionOf)
                               .flatMap(Option::stream)
                               .toList();
            }
        }

        private static Option<SchemaVersionValue> schemaVersionOf(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof SchemaVersionValue value
                   ? Option.some(value)
                   : Option.none();
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
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
}
