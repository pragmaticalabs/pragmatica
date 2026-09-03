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
import java.util.concurrent.TimeUnit;
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
import static org.awaitility.Awaitility.await;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// #760/#724 review, BLOCKING 2: a manual re-dispatch of a PENDING migration (the retry route, or any
/// external re-dispatch) must cancel the still-live scheduled backoff timer from the prior failed
/// attempt — otherwise that stale timer and the manual attempt both eventually acquire the single-flight
/// fence in sequence, running the migration scripts a second, uncoordinated time (the 23505 duplicate-key
/// defect the reverted sweep hit).
///
/// The pinning assertion has to cross the REAL backoff floor: `SharedScheduler` is a hardcoded singleton
/// with no injectable clock, and `calculateBackoff(1) == 15_000`ms is the smallest possible first-retry
/// delay in production — a shorter synthetic wait cannot go red on unfixed code, since even an uncancelled
/// timer genuinely cannot fire before its real deadline. Widen the ceiling below (never shorten the
/// backoff) if real-time scheduling ever makes this flaky, matching the precedent in
/// `LeaderReconcilerTest#awaitDead`.
class SchemaOrchestratorRetrySingleFlightTest {
    private static final NodeId SELF = new NodeId("node-1");
    private static final String DATASOURCE = "database.orders";
    private static final String COORDS = "org.example:my-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(COORDS).unwrap();
    private static final String LAST_MIGRATION = "V003__add_index.sql";
    private static final int DECLARED_VERSION = 3;
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

    private RecordingClusterNode cluster;
    private InMemoryKvStore kvStore;
    private FlakySchemaManager schemaManager;

    @BeforeEach
    void setUp() {
        kvStore = new InMemoryKvStore(MessageRouter.mutable());
        cluster = new RecordingClusterNode(SELF, kvStore);
        schemaManager = new FlakySchemaManager();
    }

    @Test
    void manualRetryDuringBackoffWindow_cancelsStaleScheduledRetry_preventingDoubleDispatch() {
        seedSchemaVersion(DECLARED_VERSION, LAST_MIGRATION, COORDS);

        var orchestrator = orchestrator();

        // Attempt 1 fails (transient) — schedules a real backoff timer ~15s out and leaves status PENDING.
        orchestrator.migrateIfNeeded(DATASOURCE)
                    .await()
                    .onSuccess(_ -> Assertions.fail("Expected attempt 1 to fail (fake datasource is unreachable)"))
                    .onFailure(SchemaOrchestratorRetrySingleFlightTest::assertTransientDatasourceFailure);

        assertThat(schemaManager.invocations).hasSize(1);
        assertThat(statusesWrittenToKv()).contains(SchemaStatus.PENDING);

        // The manual retry lands inside the backoff window, before attempt 1's timer would fire.
        // It must cancel that stale timer as part of claiming the single-flight fence. This attempt
        // also fails (transient), leaving status PENDING again with its OWN retry scheduled ~45s out —
        // this is what makes the assertion below discriminating: if attempt 1's timer were merely
        // harmless (status no longer PENDING by the time it fires), an unfixed no-op would pass too.
        orchestrator.migrateIfNeeded(DATASOURCE)
                    .await()
                    .onSuccess(_ -> Assertions.fail("Expected the manual retry to also fail (fake datasource is still unreachable)"))
                    .onFailure(SchemaOrchestratorRetrySingleFlightTest::assertTransientDatasourceFailure);

        assertThat(schemaManager.invocations).hasSize(2);
        assertThat(statusesWrittenToKv()).contains(SchemaStatus.PENDING);

        // Cross attempt 1's original ~15s deadline. Fixed code: that timer was cancelled above, so it
        // never fires and the invocation count never grows past 2. Unfixed code: the stale timer fires,
        // finds status still PENDING, and re-dispatches — a third, uncoordinated migrate() call.
        await().pollDelay(16, TimeUnit.SECONDS)
               .atMost(19, TimeUnit.SECONDS)
               .untilAsserted(() -> assertThat(schemaManager.invocations).hasSize(2));

        // Cleanup: let the datasource succeed so attempt 2's own (legitimate) ~45s-out retry is
        // cancelled too, leaving no scheduled task live past this test.
        orchestrator.migrateIfNeeded(DATASOURCE)
                    .await()
                    .onFailure(SchemaOrchestratorRetrySingleFlightTest::failOnUnexpectedFailure);

        assertThat(schemaManager.invocations).hasSize(3);
        assertThat(statusesWrittenToKv()).contains(SchemaStatus.COMPLETED);
    }

    private static void assertTransientDatasourceFailure(Cause cause) {
        assertThat(cause).isInstanceOf(SchemaError.DatasourceUnreachable.class);
    }

    private static void failOnUnexpectedFailure(Cause cause) {
        Assertions.fail("Unexpected migration failure: " + cause.message());
    }

    private SchemaOrchestratorService orchestrator() {
        Repository repository = _ -> NOT_IN_REPOSITORY.promise();

        return SchemaOrchestratorService.schemaOrchestratorService(cluster,
                                                                   kvStore,
                                                                   artifactStoreServing(),
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
                                                          artifactCoords,
                                                          OWNER));
    }

    private List<SchemaStatus> statusesWrittenToKv() {
        return cluster.schemaVersionPuts()
                      .stream()
                      .map(SchemaVersionValue::status)
                      .toList();
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

    /// Fails the first two `migrate()` calls with a TRANSIENT-classified cause (driving two real
    /// scheduled retries), then succeeds — used only to let the test clean up its own scheduled task.
    private static final class FlakySchemaManager implements AetherSchemaManager {
        final List<String> invocations = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Promise<SchemaResult> migrate(String datasource,
                                             List<MigrationEntry> scripts,
                                             SqlConnector connector,
                                             String nodeId,
                                             BlueprintId owner) {
            invocations.add(datasource);

            return invocations.size() < 3
                   ? SchemaError.DatasourceUnreachable.datasourceUnreachable(datasource, "connection refused").promise()
                   : Promise.success(SchemaResult.schemaResult(scripts.size(), DECLARED_VERSION, 1L));
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

    /// #760/#724 review BLOCKING 2 pinning-test fix: production `cluster.apply()` commits through
    /// consensus and the committed value becomes visible to the NEXT `kvStore.get()` — that
    /// read-your-write property is what lets `attemptCount` escalate across retries, which is what
    /// makes the backoff for attempt 2 differ from attempt 1's. The original double recorded commands
    /// for assertion but never applied them to `kvStore`, so every `migrateIfNeeded` call re-read the
    /// SAME seeded `attemptCount=0` value forever — both attempts computed `attemptNumber=1` and
    /// scheduled the SAME 15s backoff, so attempt 2's own (miscomputed) timer coincidentally landed
    /// inside the test's assertion window regardless of whether the stale-timer cancellation worked.
    /// Applying the batch to `kvStore` here restores read-your-write parity with production, so the
    /// test actually discriminates "stale timer cancelled" from "stale timer fires".
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final InMemoryKvStore kvStore;
        final List<KVCommand<AetherKey>> commands = Collections.synchronizedList(new ArrayList<>());

        RecordingClusterNode(NodeId self, InMemoryKvStore kvStore) {
            this.self = self;
            this.kvStore = kvStore;
        }

        @Override public NodeId self() {return self;}

        @Override public TopologyManager topologyManager() {return stubTopologyManager(self);}

        @Override public Promise<Unit> start() {return Promise.unitPromise();}

        @Override public Promise<Unit> stop() {return Promise.unitPromise();}

        @Override public <R> Promise<List<R>> apply(List<KVCommand<AetherKey>> batch) {
            commands.addAll(batch);
            kvStore.apply(batch);

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

        /// Mirrors production: a batch that commits through consensus becomes visible to the next
        /// `get()`. See [RecordingClusterNode#apply] for why this parity matters to the pinning test.
        void apply(List<KVCommand<AetherKey>> batch) {
            process(createBatch(batch));
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
