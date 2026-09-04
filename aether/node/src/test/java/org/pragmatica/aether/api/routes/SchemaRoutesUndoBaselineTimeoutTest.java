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
import java.util.concurrent.atomic.AtomicReference;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.pragmatica.aether.artifact.Artifact;
import org.pragmatica.aether.artifact.ArtifactId;
import org.pragmatica.aether.artifact.GroupId;
import org.pragmatica.aether.artifact.Version;
import org.pragmatica.aether.deployment.schema.AetherSchemaManager;
import org.pragmatica.aether.deployment.schema.SchemaError;
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
import org.pragmatica.http.ContentType;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.http.server.ResponseWriter;
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

/// #543 / PR #832 review round 1 BLOCKING 3: `SchemaOrchestratorService.provisionAndRun` used to
/// call the manager's `undo`/`baseline` with no bound at all — `migrate` already bounded its own
/// manager call (`resolveAndParseMigrations(...).timeout(schemaManager.policy().migrationTimeout())`,
/// `SchemaOrchestratorService.java:389-403`), but undo/baseline shared the same in-process fence
/// ([SchemaOrchestratorService#inFlightMigrations]) and the same KV migration lock without sharing
/// that bound. A manager call that never settled — a wedged connection, a runaway down/base script
/// holding a lock forever — held BOTH forever: the KV lock's own TTL eventually expires, but
/// nothing ever releases the in-process fence, since `finalizeAttempt` (the only release site) is
/// chained onto a promise that never resolves. The CLI would time out
/// (`AetherCli.java:575`) while the server kept the datasource wedged for every future call.
///
/// [NeverSettlingSchemaManager] returns a `Promise` from `undo`/`baseline` that is constructed and
/// deliberately never resolved, with a short `policy().migrationTimeout()` (150ms, not the 15-minute
/// production default) so the test runs fast. Each test below:
///
///   1. Calls the route once, bounded by a generous 2-second OUTER `Promise.await(TimeSpan)` safety
///      net so a regression can never hang the build — then asserts the call actually settled in
///      well under a second, which is only true if `provisionAndRun`'s OWN 150ms bound fired (the
///      outer 2-second net is a backstop, not the thing under test), and that the failure surfaced
///      is the named `UndoTimedOut`/`BaselineTimedOut` (not a bare `CoreError.Timeout`).
///   2. Asserts the KV record was written `FAILED` — not left at its pre-attempt `COMPLETED` status
///      with no sign the attempt ever ran.
///   3. Calls the route a SECOND time and asserts it is NOT rejected instantly with the typed
///      `SchemaError.LockAcquisitionFailed` (a near-zero-time rejection — this PR typed
///      `acquireLock`'s failure sites; it was a bare `Cause` before) — proving both the in-process
///      fence and the KV migration lock were released after the first attempt's timeout, not just
///      the KV lock's TTL eventually expiring.
///
/// Red-before (recorded per the review's Process section): removing ONLY the
/// `.timeout(schemaManager.policy().migrationTimeout())` call `provisionAndRun` adds to
/// `terminalOp.apply(connector, scripts, owner)` — restoring the pre-fix body exactly — makes the
/// manager's never-settling promise propagate unbounded. Both tests below then fail every assertion
/// in step 1 (elapsed time balloons to the 2-second OUTER net, and the surfaced cause becomes a bare
/// `CoreError.Timeout`, never the named `SchemaError`) and step 3 (the second call returns near-
/// instantly with the typed `LockAcquisitionFailed`, since the first attempt's fence is still held).
/// Restoring the original `.timeout(...)` call turns both green again. See PR #832 for the verified
/// revert transcript.
class SchemaRoutesUndoBaselineTimeoutTest {
    private static final String DATASOURCE = "database.orders";
    private static final String COORDS = "org.example:orders-app:1.0.0";
    private static final BlueprintId OWNER = BlueprintId.blueprintId(COORDS).unwrap();
    private static final NodeId SELF = new NodeId("node-a");
    private static final TimeSpan MANAGER_TIMEOUT = timeSpan(150).millis();
    private static final TimeSpan TEST_SAFETY_NET = timeSpan(2).seconds();
    private static final long PRODUCTION_TIMEOUT_CEILING_MS = 1000L;
    // #832 review round 4 item B: probe the real HTTP status a timed-out attempt puts on the wire,
    // not only the cause's Java type — via the same ProblemResponses.writeProblem funnel
    // ManagementRouter.writeError uses, following SchemaRouteStatusTest's established pattern.
    private static final String INSTANCE = "/api/v1/schema/undo/" + DATASOURCE;
    private static final String REQUEST_ID = "req-1";

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

    private InMemoryKvStore store;
    private SchemaRoutes routes;

    @BeforeEach
    void setUp() {
        store = new InMemoryKvStore(MessageRouter.mutable());
        routes = SchemaRoutes.schemaRoutes(() -> nodeOver(store));
        store.put(SchemaVersionKey.schemaVersionKey(DATASOURCE),
                  SchemaVersionValue.schemaVersionValue(DATASOURCE,
                                                        5,
                                                        "V005__prior.sql",
                                                        SchemaStatus.COMPLETED,
                                                        COORDS,
                                                        OWNER));
    }

    @Test
    void undoToVersion_boundsTheManagerCall_releasesTheFence_andMarksFailed_onTimeout() {
        var start1 = System.nanoTime();
        var result1 = routes.undoToVersion(DATASOURCE, 2).await(TEST_SAFETY_NET);
        var elapsed1Ms = elapsedMs(start1);

        assertThat(result1.isFailure()).as("undo must fail once the manager call never settles").isTrue();
        assertThat(elapsed1Ms).as("the manager call must be bounded by SchemaPolicy#migrationTimeout (150ms), "
                                  + "not merely by this test's own 2-second safety net — a large elapsed time here "
                                  + "means provisionAndRun's timeout bound did not fire")
                              .isLessThan(PRODUCTION_TIMEOUT_CEILING_MS);
        result1.onFailure(cause -> assertThat(cause).as("the surfaced cause must be the named UndoTimedOut, "
                                                         + "not a bare CoreError.Timeout")
                                                     .isInstanceOf(SchemaError.UndoTimedOut.class));
        result1.onFailure(SchemaRoutesUndoBaselineTimeoutTest::assertWritesGatewayTimeout);

        assertThat(recorded().status()).as("a timed-out attempt must leave a FAILED record, not the pre-attempt "
                                            + "COMPLETED status with no sign this attempt ever ran")
                                       .isEqualTo(SchemaStatus.FAILED);

        var start2 = System.nanoTime();
        var result2 = routes.undoToVersion(DATASOURCE, 2).await(TEST_SAFETY_NET);
        var elapsed2Ms = elapsedMs(start2);

        assertThat(result2.isFailure()).isTrue();
        assertThat(elapsed2Ms).as("a second call must NOT be refused instantly as a typed LockAcquisitionFailed — "
                                  + "near-zero elapsed time here means the first attempt's fence/lock were never "
                                  + "released")
                              .isGreaterThanOrEqualTo(MANAGER_TIMEOUT.millis() / 2);
        result2.onFailure(cause -> assertThat(cause).as("a released fence lets the second attempt reach the "
                                                         + "manager again and time out on its own terms, not "
                                                         + "get rejected as a duplicate")
                                                     .isInstanceOf(SchemaError.UndoTimedOut.class));
    }

    @Test
    void baselineDatasource_boundsTheManagerCall_releasesTheFence_andMarksFailed_onTimeout() {
        var start1 = System.nanoTime();
        var result1 = routes.baselineDatasource(DATASOURCE, Option.some("7")).await(TEST_SAFETY_NET);
        var elapsed1Ms = elapsedMs(start1);

        assertThat(result1.isFailure()).as("baseline must fail once the manager call never settles").isTrue();
        assertThat(elapsed1Ms).as("the manager call must be bounded by SchemaPolicy#migrationTimeout (150ms), "
                                  + "not merely by this test's own 2-second safety net")
                              .isLessThan(PRODUCTION_TIMEOUT_CEILING_MS);
        result1.onFailure(cause -> assertThat(cause).as("the surfaced cause must be the named BaselineTimedOut, "
                                                         + "not a bare CoreError.Timeout")
                                                     .isInstanceOf(SchemaError.BaselineTimedOut.class));
        result1.onFailure(SchemaRoutesUndoBaselineTimeoutTest::assertWritesGatewayTimeout);

        assertThat(recorded().status()).as("a timed-out attempt must leave a FAILED record")
                                       .isEqualTo(SchemaStatus.FAILED);

        var start2 = System.nanoTime();
        var result2 = routes.baselineDatasource(DATASOURCE, Option.some("7")).await(TEST_SAFETY_NET);
        var elapsed2Ms = elapsedMs(start2);

        assertThat(result2.isFailure()).isTrue();
        assertThat(elapsed2Ms).as("a second call must NOT be refused instantly as a typed LockAcquisitionFailed")
                              .isGreaterThanOrEqualTo(MANAGER_TIMEOUT.millis() / 2);
        result2.onFailure(cause -> assertThat(cause).isInstanceOf(SchemaError.BaselineTimedOut.class));
    }

    // --- helpers ---

    private static long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    // #832 review round 4 item B: prove the 504 through the real route-level funnel, not only the
    // cause's Java type — same idiom as SchemaRouteStatusTest's RecordingResponseWriter, feeding the
    // cause through the exact ProblemResponses.writeProblem call ManagementRouter.writeError makes.
    private static void assertWritesGatewayTimeout(Cause cause) {
        var recorder = new RecordingResponseWriter();
        ProblemResponses.writeProblem(recorder, cause, INSTANCE, REQUEST_ID);
        assertThat(recorder.status()).as("a timed-out undo/baseline must reach the caller as 504 GATEWAY_TIMEOUT "
                                          + "through the real ProblemResponses.writeProblem funnel, not only carry "
                                          + "the right Java type")
                                     .isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
    }

    private SchemaVersionValue recorded() {
        return store.get(SchemaVersionKey.schemaVersionKey(DATASOURCE))
                    .filter(SchemaVersionValue.class::isInstance)
                    .map(SchemaVersionValue.class::cast)
                    .or(SchemaRoutesUndoBaselineTimeoutTest::noRecord);
    }

    private static SchemaVersionValue noRecord() {
        return Assertions.fail("No schema version record was written");
    }

    private ManageableNode nodeOver(InMemoryKvStore kvStore) {
        var orchestrator = SchemaOrchestratorService.schemaOrchestratorService(new PlainClusterNode(SELF, kvStore),
                                                                               kvStore,
                                                                               artifactStoreServing(),
                                                                               noLocalRepository(),
                                                                               new NeverSettlingSchemaManager(),
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

    private static Repository noLocalRepository() {
        return _ -> Causes.cause("Artifact not present in local repository").promise();
    }

    private static byte[] blueprintJar() {
        var bytes = new ByteArrayOutputStream();

        try (var zip = new ZipOutputStream(bytes)) {
            writeEntry(zip, "META-INF/blueprint.toml", """
                    id = "org.example:orders-app:1.0.0"

                    [[slices]]
                    artifact = "org.example:orders-service:1.0.0"
                    """);
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

    /// `undo`/`baseline` each construct and return a fresh, never-completed `Promise` — the test
    /// double for "a runaway script/wedged connection that never reports back." `policy()` is
    /// overridden to a 150ms `migrationTimeout` so the test exercises the real bound quickly instead
    /// of waiting out the 15-minute production default.
    private static final class NeverSettlingSchemaManager implements AetherSchemaManager {
        @Override
        public Promise<SchemaResult> migrate(String datasource, List<MigrationEntry> scripts, SqlConnector connector, String nodeId, BlueprintId owner) {
            return Causes.cause("migrate() not exercised by SchemaRoutesUndoBaselineTimeoutTest").promise();
        }

        @Override
        public Promise<SchemaResult> undo(String datasource, int targetVersion, List<MigrationEntry> scripts, SqlConnector connector, String nodeId, BlueprintId owner) {
            return Promise.promise();
        }

        @Override
        public Promise<SchemaResult> baseline(String datasource, int baselineVersion, List<MigrationEntry> scripts, SqlConnector connector, String nodeId, BlueprintId owner) {
            return Promise.promise();
        }

        @Override
        public SchemaPolicy policy() {
            return SchemaPolicy.schemaPolicy(SchemaPolicy.FailureMode.LEAVE_PARTIAL,
                                             SchemaPolicy.FailoverMode.AUTO_RESUME,
                                             MANAGER_TIMEOUT);
        }
    }

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

    private static final class RecordingResponseWriter implements ResponseWriter {
        private final AtomicReference<HttpStatus> status = new AtomicReference<>();
        private final AtomicReference<byte[]> body = new AtomicReference<>(new byte[0]);

        @Override
        public void write(HttpStatus status, byte[] body, ContentType contentType) {
            this.status.set(status);
            this.body.set(body);
        }

        @Override
        public ResponseWriter header(String name, String value) {
            return this;
        }

        HttpStatus status() {
            return status.get();
        }
    }
}
