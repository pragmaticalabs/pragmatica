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
import org.pragmatica.aether.slice.blueprint.BlueprintId;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.aether.slice.kvstore.AetherKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaMigrationLockKey;
import org.pragmatica.aether.slice.kvstore.AetherKey.SchemaVersionKey;
import org.pragmatica.aether.slice.kvstore.AetherValue;
import org.pragmatica.aether.slice.kvstore.AetherValue.SchemaMigrationLockValue;
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

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.pragmatica.lang.io.TimeSpan.timeSpan;
import static org.assertj.core.api.Assertions.assertThat;


/// #766: `acquireLock` is check-then-act ACROSS NODES. The in-flight fence is per orchestrator
/// instance, and the KV lock is read (`isLockHeld`) and written (`Put`) in separate steps, so two
/// nodes that both observe the lock free before either write commits both proceed into the
/// migration and duplicate-key `aether_schema_history`.
///
/// The probe forces exactly that order: two orchestrators (two nodes) share one real [KVStore], the
/// cluster stub parks every lock write, both dispatch — both read the lock free — and only then is
/// the first write committed and its submitter let through into a migration that never completes,
/// after which the second write is committed and its submitter let through. Exactly one `migrate()`
/// must run. Committing the second write while the first holder is still migrating is what makes
/// the applier fence load-bearing: with both commits before either confirm, a last-writer-wins
/// store plus the re-read alone would also yield one winner.
@SuppressWarnings({"JBCT-EX-01", "JBCT-RET-03"})
class SchemaOrchestratorLockClaimRaceTest {
    private static final NodeId NODE_1 = new NodeId("node-1");
    private static final NodeId NODE_2 = new NodeId("node-2");
    private static final String DATASOURCE = "database.orders";
    private static final String COORDS = "org.example:my-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(COORDS).unwrap();
    private static final Cause NOT_IN_REPOSITORY = Causes.cause("Artifact not present in local repository");

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

    private InMemoryKvStore kvStore;
    private LockDeferringClusterNode cluster;
    private CountingSchemaManager schemaManager;

    @BeforeEach
    void setUp() {
        kvStore = new InMemoryKvStore(MessageRouter.mutable());
        cluster = new LockDeferringClusterNode(NODE_1, kvStore);
        schemaManager = new CountingSchemaManager();
    }

    @Test
    void twoNodesObservingLockAbsent_exactlyOneRunsTheMigration() {
        seedPendingSchema();
        assertExactlyOneClaimWins();
    }

    /// The takeover of an EXPIRED lock is the same race one step later: both nodes read the stale
    /// value, both treat it as free, both write. A lock that was released is absent; one whose holder
    /// died is present-and-expired, so this path is the one a crashed migrator leaves behind.
    @Test
    void twoNodesObservingLockExpired_exactlyOneRunsTheMigration() {
        seedPendingSchema();
        kvStore.put(SchemaMigrationLockKey.schemaMigrationLockKey(DATASOURCE),
                    new SchemaMigrationLockValue(DATASOURCE, new NodeId("node-9"), 1_000L, 2_000L, 3L));
        assertExactlyOneClaimWins();
    }

    private void assertExactlyOneClaimWins() {
        var first = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);
        var second = orchestrator(NODE_2).migrateIfNeeded(DATASOURCE);
        // Both dispatches read the lock free and are now parked inside their own lock write.
        assertThat(cluster.parkedLockWrites()).hasSize(2);
        assertThat(schemaManager.invocations).isEmpty();
        // First claim commits and its submitter runs into a migration that never completes: the
        // lock is held for the rest of the test.
        cluster.commitParkedLockWrite(0);
        assertThat(schemaManager.invocations).as("first claimant migrates").containsExactly(NODE_1.id());
        assertThat(first.isResolved()).as("first claimant still migrating").isFalse();
        // Second claim commits against the held lock, then its submitter sees the result.
        cluster.commitParkedLockWrite(1);
        var outcome = second.await(timeSpan(5).seconds());

        assertThat(schemaManager.invocations).as("exactly one node may run the migration").hasSize(1);
        outcome.onSuccess(_ -> Assertions.fail("second claimant must not acquire a held lock"))
               .onFailure(cause -> assertThat(cause).isInstanceOf(SchemaError.LockAcquisitionFailed.class));
    }

    private void seedPendingSchema() {
        kvStore.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                    SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                          3,
                                                          "V003__add_index.sql",
                                                          SchemaStatus.PENDING,
                                                          COORDS,
                                                          OWNER));
    }

    private SchemaOrchestratorService orchestrator(NodeId self) {
        Repository repository = _ -> NOT_IN_REPOSITORY.promise();

        return SchemaOrchestratorService.schemaOrchestratorService(cluster,
                                                                   kvStore,
                                                                   artifactStoreServing(),
                                                                   repository,
                                                                   schemaManager,
                                                                   stubConnectionProvider(),
                                                                   self);
    }

    /// Parks every batch carrying a lock write (the `Put` of a [SchemaMigrationLockValue]) and
    /// commits the rest immediately, so a test can hold two nodes' lock claims until both have
    /// passed their free-lock read. `commitParkedLockWrite` applies one parked batch through the real
    /// applier — as the consensus log would — and then resolves that submitter's promise.
    private static final class LockDeferringClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        private record Parked(List<KVCommand<AetherKey>> batch, Promise<List<Object>> promise) {}

        final NodeId self;
        final InMemoryKvStore kvStore;
        private final List<Parked> parked = Collections.synchronizedList(new ArrayList<>());

        LockDeferringClusterNode(NodeId self, InMemoryKvStore kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        @Override
        public NodeId self() {
            return self;
        }

        @Override
        public TopologyManager topologyManager() {
            return stubTopologyManager(self);
        }

        @Override
        public Promise<Unit> start() {
            return Promise.unitPromise();
        }

        @Override
        public Promise<Unit> stop() {
            return Promise.unitPromise();
        }

        @Override
        @SuppressWarnings("unchecked")
        public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            if (batch.stream().anyMatch(LockDeferringClusterNode::isLockWrite)) {
                var promise = Promise.<List<Object>> promise();

                parked.add(new Parked(batch, promise));

                return (Promise<List<R>>)(Promise<?>) promise;
            }

            kvStore.apply(batch);

            return Promise.success(Collections.emptyList());
        }

        List<List<KVCommand<AetherKey>>> parkedLockWrites() {
            return parked.stream()
                         .map(Parked::batch)
                         .toList();
        }

        void commitParkedLockWrite(int index) {
            var p = parked.get(index);

            kvStore.apply(p.batch());
            p.promise().succeed(List.of());
        }

        private static boolean isLockWrite(KVCommand<AetherKey> command) {
            return command instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof SchemaMigrationLockValue;
        }
    }

    private static final class CountingSchemaManager implements AetherSchemaManager {
        final List<String> invocations = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Promise<SchemaResult> migrate(String datasource,
                                             List<MigrationEntry> scripts,
                                             SqlConnector connector,
                                             String nodeId,
                                             BlueprintId owner) {
            invocations.add(nodeId);

            return Promise.promise();
        }

        @Override
        public Promise<SchemaResult> undo(String datasource,
                                          int targetVersion,
                                          List<MigrationEntry> scripts,
                                          SqlConnector connector,
                                          String nodeId,
                                          BlueprintId owner) {
            return Promise.success(SchemaResult.schemaResult(0, targetVersion, 1L));
        }

        @Override
        public Promise<SchemaResult> baseline(String datasource,
                                              int baselineVersion,
                                              List<MigrationEntry> scripts,
                                              SqlConnector connector,
                                              String nodeId,
                                              BlueprintId owner) {
            return Promise.success(SchemaResult.schemaResult(0, baselineVersion, 1L));
        }
    }

    private static final class InMemoryKvStore extends KVStore<AetherKey, AetherValue> {
        InMemoryKvStore(MessageRouter router) {
            super(router, stubSerializer(), stubDeserializer());
        }

        void put(AetherKey key, AetherValue value) {
            process(createBatch(List.of(new KVCommand.Put<>(key, value))));
        }

        void apply(List<KVCommand<AetherKey>> batch) {
            process(createBatch(batch));
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

    private static byte[] blueprintJar() {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", BLUEPRINT_TOML);
            writeEntry(zip, "schema/orders/V003__add_index.sql", "CREATE INDEX idx_orders ON orders(id);");
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
            @Override
            public Promise<DeployResult> deploy(Artifact artifact, byte[] content) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override
            public Promise<byte[]> resolve(Artifact artifact) {
                return Promise.success(jarBytes);
            }

            @Override
            public Promise<ResolvedArtifact> resolveWithMetadata(Artifact artifact) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override
            public Promise<Boolean> exists(Artifact artifact) {
                return Promise.success(true);
            }

            @Override
            public Promise<Option<ArtifactMetadata>> metadata(Artifact artifact) {
                return Promise.success(Option.none());
            }

            @Override
            public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<Unit> delete(Artifact artifact) {
                return Promise.unitPromise();
            }

            @Override
            public Metrics metrics() {
                return new Metrics(0, 0, 0L);
            }
        };
    }

    private static DatasourceConnectionProvider stubConnectionProvider() {
        return new DatasourceConnectionProvider() {
            @Override
            public Promise<SqlConnector> connector(String datasourceName) {
                return Promise.success(stubConnector());
            }

            @Override
            public Promise<Unit> release(String datasourceName) {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> releaseAll() {
                return Promise.unitPromise();
            }
        };
    }

    private static SqlConnector stubConnector() {
        return new SqlConnector() {
            @Override
            public DatabaseConnectorConfig config() {
                return STUB_CONFIG;
            }

            @Override
            public Promise<Boolean> isHealthy() {
                return Promise.success(true);
            }

            @Override
            public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override
            public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
                return Promise.success(Option.none());
            }

            @Override
            public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<Integer> update(String sql, Object... params) {
                return Promise.success(0);
            }

            @Override
            public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
                return Promise.success(new int[0]);
            }

            @Override
            public <T> Promise<T> transactional(TransactionCallback<T> callback) {
                return callback.execute(this);
            }
        };
    }

    private static TopologyManager stubTopologyManager(NodeId self) {
        return new TopologyManager() {
            @Override
            public NodeInfo self() {
                return NodeInfo.nodeInfo(self, new NodeAddress("localhost", 9000));
            }

            @Override
            public Option<NodeInfo> get(NodeId id) {
                return Option.some(NodeInfo.nodeInfo(id, new NodeAddress("localhost", 9000)));
            }

            @Override
            public int clusterSize() {
                return 1;
            }

            @Override
            public Option<NodeId> reverseLookup(SocketAddress socketAddress) {
                return Option.empty();
            }

            @Override
            public Promise<Unit> start() {
                return Promise.unitPromise();
            }

            @Override
            public Promise<Unit> stop() {
                return Promise.unitPromise();
            }

            @Override
            public TimeSpan pingInterval() {
                return timeSpan(5).seconds();
            }

            @Override
            public TimeSpan helloTimeout() {
                return timeSpan(5).seconds();
            }

            @Override
            public Option<NodeState> getState(NodeId id) {
                return Option.empty();
            }

            @Override
            public List<NodeId> topology() {
                return List.of(self);
            }
        };
    }
}
