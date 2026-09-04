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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.ParsedMigration.MigrationType;
import org.pragmatica.aether.deployment.schema.SchemaHistoryRepository.AppliedMigration;
import org.pragmatica.aether.deployment.schema.SchemaOrchestratorService;
import org.pragmatica.aether.deployment.schema.SchemaPolicy;
import org.pragmatica.aether.node.ManageableNode;
import org.pragmatica.aether.resource.artifact.ArtifactStore;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.BlueprintId;
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
import org.junit.jupiter.api.Test;

import io.netty.buffer.ByteBuf;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #543 / PR #832 review round 1 BLOCKING 1: every prior undo test substituted a stub
/// orchestrator (`SchemaRouteStatusTest`'s `orchestratorFailingUndo`/`RecordingOrchestrator`,
/// `ClusterDeploymentStateActiveTest`'s `RecordingSchemaOrchestrator`) or a stub schema manager
/// (`SchemaRoutesBaselineTest`'s `BaselineOnlySchemaManager`, which throws if `undo()` is ever
/// called) — nothing ever drove a real `SchemaOrchestratorServiceInstance` into a real
/// `AetherSchemaManager` for undo. This file wires the SAME real-route → real-orchestrator
/// pattern `SchemaRoutesBaselineTest` established for baseline, but swaps in the REAL
/// `DefaultAetherSchemaManager` (via [AetherSchemaManager#aetherSchemaManager]) against a fake
/// [SqlConnector] that records every statement it executes and every history row it deletes —
/// the only boundary genuinely external to this fix. Proves both halves of the review's ask:
/// the `U<n>__` undo scripts run in descending (reverse) version order, and
/// `DELETE FROM aether_schema_history` fires once per undone version, in the same order.
///
/// Red-before (recorded per the review's Process section): reverting `SchemaOrchestratorService
/// .undoTo` to a no-op stub (`return Promise.unitPromise();`, skipping the call into
/// `schemaManager.undo`) fails this test — `executedSql()` and `deletedVersions()` are both
/// empty because nothing ever reaches the connector. Restoring the real body turns it green
/// again. See PR #832 for the verified revert transcript.
class SchemaRoutesUndoRealManagerTest {
    private static final String DATASOURCE = "database.orders";
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(COORDS).unwrap();
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

    /// Mirrors `SchemaHistoryRepository`'s private `QUERY_APPLIED_SQL`/`DELETE_SQL` literals —
    /// duplicated here (rather than referencing the package-private constants) because the fake
    /// connector must recognize the exact statements the real manager issues without reaching
    /// into that class's internals.
    private static final String QUERY_APPLIED_SQL = "SELECT version, type, description, script, checksum, applied_by, applied_at, execution_ms "
                                                   + "FROM aether_schema_history WHERE status = 'SUCCESS' ORDER BY version";
    private static final String DELETE_SQL = "DELETE FROM aether_schema_history WHERE version = ? AND type = ?";

    private static final String UNDO_V3_SQL = "DROP INDEX idx_orders_v3;";
    private static final String UNDO_V2_SQL = "DROP INDEX idx_orders_v2;";
    private static final String UNDO_V1_SQL = "DROP INDEX idx_orders_v1;";

    private InMemoryKvStore store;
    private SchemaRoutes routes;
    private RecordingConnector connector;

    @BeforeEach
    void setUp() {
        store = new InMemoryKvStore(MessageRouter.mutable());
        connector = new RecordingConnector();
        routes = SchemaRoutes.schemaRoutes(() -> nodeOver(store, connector));
        store.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                  SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                        3,
                                                        "V003__add_index.sql",
                                                        SchemaStatus.COMPLETED,
                                                        COORDS,
                                                        OWNER));
    }

    @Test
    void undoToVersion_runsUndoScriptsInReverseOrder_againstTheRealManager() {
        routes.undoToVersion(DATASOURCE, 0)
              .await()
              .onFailure(SchemaRoutesUndoRealManagerTest::failOnUnexpectedFailure);

        var undoStatements = connector.executedSql.stream()
                                                   .filter(sql -> sql.equals(UNDO_V3_SQL) || sql.equals(UNDO_V2_SQL) || sql.equals(UNDO_V1_SQL))
                                                   .toList();

        assertThat(undoStatements).as("all three undo scripts must run, most recent version first")
                                  .containsExactly(UNDO_V3_SQL, UNDO_V2_SQL, UNDO_V1_SQL);
    }

    @Test
    void undoToVersion_removesHistoryRowsInReverseOrder_forEveryUndoneVersion() {
        routes.undoToVersion(DATASOURCE, 0)
              .await()
              .onFailure(SchemaRoutesUndoRealManagerTest::failOnUnexpectedFailure);

        assertThat(connector.deletedVersions).as("one DELETE per undone version, descending — mirrors the undo script order")
                                             .containsExactly(3, 2, 1);
    }

    // --- helpers ---

    private static void failOnUnexpectedFailure(Cause cause) {
        Assertions.fail("Unexpected undo failure: " + cause.message());
    }

    /// Same wiring `SchemaRoutesBaselineTest.nodeOver` uses — a real `SchemaOrchestratorService`
    /// behind a `ManageableNode` proxy — except the schema manager is the REAL
    /// `DefaultAetherSchemaManager` and the connector is [RecordingConnector], not a stub that
    /// always returns empty.
    private ManageableNode nodeOver(InMemoryKvStore kvStore, RecordingConnector recordingConnector) {
        var orchestrator = SchemaOrchestratorService.schemaOrchestratorService(new PlainClusterNode(SELF, kvStore),
                                                                               kvStore,
                                                                               artifactStoreServing(),
                                                                               noLocalRepository(),
                                                                               AetherSchemaManager.aetherSchemaManager(SchemaPolicy.schemaPolicy()),
                                                                               stubConnectionProvider(recordingConnector),
                                                                               SELF);

        return (ManageableNode) Proxy.newProxyInstance(ManageableNode.class.getClassLoader(),
                                                       new Class[]{ManageableNode.class},
                                                       (_, method, args) -> switch (method.getName()) {
                                                           case "kvStore" -> kvStore;
                                                           case "apply" -> applyBatch(kvStore, args);
                                                           case "isLeader" -> true;
                                                           case "leader" -> Option.none();
                                                           case "schemaOrchestrator" -> orchestrator;
                                                           default -> throw new UnsupportedOperationException("Not implemented in test proxy: "
                                                                                                              + method.getName());
                                                       });
    }

    @SuppressWarnings("unchecked")
    private static Promise<List<Long>> applyBatch(InMemoryKvStore kvStore, Object[] args) {
        ((List<KVCommand<AetherKey>>) args[0]).forEach(kvStore::apply);

        return Promise.success(List.of());
    }

    private static Repository noLocalRepository() {
        return _ -> Causes.cause("Artifact not present in local repository").promise();
    }

    /// `schema/orders/U<n>__*.sql` — the layout `BlueprintArtifactParser` maps to the
    /// `"database.orders"` key this file undoes, same convention `SchemaRoutesBaselineTest`
    /// pins for `V001__init.sql`. Three down-scripts, U3 through U1, so undoing to version 0
    /// exercises the full descending sequence.
    private static byte[] blueprintJar() {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", """
                    id = "org.example:orders-app:1.0.0"

                    [[slices]]
                    artifact = "org.example:orders-service:1.0.0"
                    """);
            writeEntry(zip, "schema/orders/U003__drop_index_v3.sql", UNDO_V3_SQL);
            writeEntry(zip, "schema/orders/U002__drop_index_v2.sql", UNDO_V2_SQL);
            writeEntry(zip, "schema/orders/U001__drop_index_v1.sql", UNDO_V1_SQL);
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

    private static DatasourceConnectionProvider stubConnectionProvider(RecordingConnector recordingConnector) {
        return new DatasourceConnectionProvider() {
            @Override public Promise<SqlConnector> connector(String datasourceName) {
                return Promise.success(recordingConnector);
            }

            @Override public Promise<Unit> release(String datasourceName) {
                return Promise.unitPromise();
            }

            @Override public Promise<Unit> releaseAll() {
                return Promise.unitPromise();
            }
        };
    }

    /// Records every `update` statement in execution order (`executedSql`) and every
    /// `DELETE FROM aether_schema_history` call's version parameter, in order
    /// (`deletedVersions`) — the two observables review round 1 BLOCKING 1 requires. `queryList`
    /// answers `QUERY_APPLIED_SQL` with three already-applied VERSIONED migrations (versions
    /// 1-3), matching the KV record seeded at version 3 in [#setUp]; every other query returns
    /// empty, so `claimOwnership`/`bootstrap` take their fresh-database branch (insert, not
    /// match/evolve) without needing a fake `RowAccessor` for those paths. `transactional` runs
    /// the callback against `this`, so a statement issued inside a transaction is recorded by the
    /// same `update` override as one issued directly — exactly how `SchemaRoutesBaselineTest
    /// .stubConnector` already behaves.
    private static final class RecordingConnector implements SqlConnector {
        private final List<String> executedSql = new ArrayList<>();
        private final List<Integer> deletedVersions = new ArrayList<>();

        private static List<AppliedMigration> seededApplied() {
            return List.of(AppliedMigration.appliedMigration(1, MigrationType.VERSIONED, "init", "V001__init.sql", 1L, "node-a", 1L, 1),
                           AppliedMigration.appliedMigration(2, MigrationType.VERSIONED, "add_col", "V002__add_col.sql", 2L, "node-a", 2L, 1),
                           AppliedMigration.appliedMigration(3, MigrationType.VERSIONED, "add_index", "V003__add_index.sql", 3L, "node-a", 3L, 1));
        }

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

        @SuppressWarnings("unchecked")
        @Override public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return sql.equals(QUERY_APPLIED_SQL)
                   ? Promise.success((List<T>) seededApplied())
                   : Promise.success(List.of());
        }

        @Override public Promise<Integer> update(String sql, Object... params) {
            executedSql.add(sql);

            if (sql.equals(DELETE_SQL)) {
                deletedVersions.add((Integer) params[0]);
            }

            return Promise.success(1);
        }

        @Override public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.success(new int[0]);
        }

        @Override public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }
    }

    /// Applies commands straight to the test's own `InMemoryKvStore` — copied from
    /// `SchemaRoutesBaselineTest`'s identical helper.
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
