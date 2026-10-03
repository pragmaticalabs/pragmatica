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
import org.pragmatica.aether.resource.artifact.ArtifactFile;
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
import org.pragmatica.lang.Result;
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


/// #806: the migration lock is a LEASE the holder renews while its attempt is in flight, and its release
/// is a fenced tombstone. Before, the lock was a fixed 5-minute claim around a migration bounded at 15
/// minutes, released by a bare `Remove`: the lock expired under a slow holder, a second node took it, and
/// the first holder's later release deleted the SECOND node's lock — a third migration then ran beside it.
///
/// Time is injected: the orchestrator reads a [FakeTime] clock and schedules its renewal ticks on it, so a
/// test steps time by hand and no assertion depends on a sleep. The only real waits are for EVENTS a test
/// cannot step — a parked renewal Put giving up after `RENEWAL_TIMEOUT_MS`, which is the path under test.
@SuppressWarnings({"JBCT-EX-01", "JBCT-RET-03"})
class SchemaOrchestratorLeaseTest {
    private static final long TTL_MS = 3_000L;
    private static final long TICK_MS = TTL_MS / 3;
    private static final long RENEWAL_TIMEOUT_MS = 50L;
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

    private static final SchemaMigrationLockKey LOCK_KEY = SchemaMigrationLockKey.schemaMigrationLockKey(DATASOURCE);

    private InMemoryKvStore kvStore;
    private RecordingClusterNode cluster;
    private ControlledSchemaManager schemaManager;
    private FakeTime time;

    @BeforeEach
    void setUp() {
        kvStore = new InMemoryKvStore(MessageRouter.mutable());
        cluster = new RecordingClusterNode(NODE_1, kvStore);
        schemaManager = new ControlledSchemaManager();
        time = new FakeTime(1_000_000L);
        seedPendingSchema();
    }

    @Test
    void migrationOutlivingTheLeaseTtl_keepsExclusivity_secondAcquirerRefused() {
        var first = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);

        assertThat(schemaManager.invocations).containsExactly(NODE_1.id());
        var firstExpiry = committedLock().expiresAt();

        // Run well past the moment the ORIGINAL claim would have expired, the migration still running.
        for (var i = 0; i < 6; i++) {
            time.advance(TICK_MS);
        }

        assertThat(time.now()).isGreaterThan(firstExpiry);
        assertThat(first.isResolved()).as("migration still running").isFalse();
        assertThat(committedLock().expiresAt()).as("renewed lease is live past the original expiry").isGreaterThan(time.now());
        assertThat(committedLock().heldBy()).isEqualTo(NODE_1);
        assertThat(committedLock().lockVersion()).as("lease was renewed, not merely held").isGreaterThan(1L);

        // The retry path re-marks the record PENDING while the first attempt is still running (#806 triage).
        seedPendingSchema();
        var second = orchestrator(NODE_2).migrateIfNeeded(DATASOURCE).await(timeSpan(5).seconds());

        second.onSuccess(_ -> Assertions.fail("a second node must not acquire a lock whose holder is still migrating"))
              .onFailure(cause -> assertThat(cause).isInstanceOf(SchemaError.LockAcquisitionFailed.class));
        assertThat(schemaManager.invocations).as("exactly one node ever ran the migration").containsExactly(NODE_1.id());
    }

    /// Control for the test above: without renewal the same elapsed time frees the lock, so the renewal
    /// is what the exclusivity rests on, not the length of the TTL.
    @Test
    void holderThatStopsRenewing_losesTheLockAfterTheTtl() {
        orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);
        var claim = committedLock();

        time.advanceWithoutFiring(TTL_MS + 1);
        seedPendingSchema();
        orchestrator(NODE_2).migrateIfNeeded(DATASOURCE);

        assertThat(committedLock().heldBy()).as("an unrenewed lock is taken over once expired").isEqualTo(NODE_2);
        assertThat(committedLock().lockVersion()).isEqualTo(claim.nextVersion());
    }

    @Test
    void completedMigration_afterRenewals_releasesByTombstone() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);

        time.advance(TICK_MS);
        time.advance(TICK_MS);
        var renewedVersion = committedLock().lockVersion();

        assertThat(renewedVersion).isGreaterThanOrEqualTo(3L);
        schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
        migration.await(timeSpan(5).seconds());

        assertThat(committedLock().expiresAt() <= time.now()).as("the release leaves an expired tombstone").isTrue();
        assertThat(committedLock().lockVersion()).as("the version chain continues; it never restarts").isEqualTo(renewedVersion + 1);
        assertThat(time.pending()).as("a released lease schedules no further tick").isZero();
    }

    /// A renewal Put still in flight when the migration finishes must settle BEFORE the release: otherwise
    /// it could land after the tombstone and leave a live lock nobody renews.
    @Test
    void releaseWaitsForAnInFlightRenewal() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);

        cluster.parkRenewals();
        time.advance(TICK_MS);
        assertThat(cluster.parkedRenewals()).isEqualTo(1);
        schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
        assertThat(migration.isResolved()).as("release is waiting for the renewal").isFalse();

        cluster.commitParkedRenewals();
        migration.await(timeSpan(5).seconds());

        assertThat(committedLock().expiresAt() <= time.now()).as("no live lock may outlive its holder's release").isTrue();
    }

    @Test
    void staleHolder_successResult_doesNotTouchSuccessorsLock() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);
        var successor = takeOver();

        schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
        migration.await(timeSpan(5).seconds());

        assertThat(committedLock()).as("A's release must not overwrite B's lock").isEqualTo(successor);
    }

    @Test
    void staleHolder_failureResult_doesNotTouchSuccessorsLock() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);
        var successor = takeOver();

        schemaManager.completeAll(Result.failure(Causes.cause("script failed")));
        migration.await(timeSpan(5).seconds());

        assertThat(committedLock()).as("A's finalize-time release must not overwrite B's lock").isEqualTo(successor);
    }

    @Test
    void supersededHolder_stopsRenewing() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);
        var successor = takeOver();
        var putsAtTakeover = cluster.lockPuts();

        time.advance(TICK_MS);
        time.advance(TICK_MS);

        assertThat(cluster.lockPuts()).as("a holder that lost the lock submits no further renewals").isEqualTo(putsAtTakeover);
        assertThat(time.pending()).as("and schedules no further tick").isZero();
        assertThat(committedLock()).isEqualTo(successor);
        assertThat(migration.isResolved()).isFalse();
    }

    /// v1860 S1: renewal R1 times out but is still pending; the next tick submits R2 for the SAME version
    /// (R1 never committed); R1 then lands and R2 is fenced out. The committed value is R1 — this holder's
    /// own write, nobody else claimed the key — so the lease must go on.
    @Test
    void lateLandingRenewal_afterANewerOneWasSubmitted_doesNotCostTheLease() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);

        cluster.parkRenewals();
        time.advance(TICK_MS);
        assertThat(cluster.parkedRenewals()).as("R1 in flight").isEqualTo(1);
        awaitEvent("R1 gave up and the next tick was scheduled", () -> time.pending() == 1);
        time.advance(TICK_MS);
        assertThat(cluster.parkedRenewals()).as("R2 submitted for the same version").isEqualTo(2);

        cluster.commitParkedRenewals();
        var landed = committedLock();

        assertThat(landed.heldBy()).isEqualTo(NODE_1);
        awaitEvent("R2's settlement scheduled the next tick", () -> time.pending() == 1);
        time.advance(TICK_MS);
        assertThat(committedLock().lockVersion()).as("the holder renews past its own late-landed value").isGreaterThan(landed.lockVersion());
        assertThat(migration.isResolved()).isFalse();
    }

    /// v1860 S2: renewal R1 times out but is still pending; the migration completes and releases; node 2
    /// claims the lock; R1 then lands. The release never restarts the version chain, so R1 is fenced out.
    @Test
    void renewalLandingAfterRelease_cannotClobberASuccessorsClaim() {
        var migration = orchestrator(NODE_1).migrateIfNeeded(DATASOURCE);

        cluster.parkRenewals();
        time.advance(TICK_MS);
        assertThat(cluster.parkedRenewals()).as("R1 in flight").isEqualTo(1);
        schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
        migration.await(timeSpan(5).seconds());

        seedPendingSchema();
        orchestrator(NODE_2).migrateIfNeeded(DATASOURCE);
        assertThat(committedLock().heldBy()).as("node 2 holds the lock").isEqualTo(NODE_2);
        var successor = committedLock();

        cluster.commitParkedRenewals();

        assertThat(committedLock()).as("node 1's stale renewal must not overwrite node 2's live claim").isEqualTo(successor);
    }

    /// Node 2 takes the lock the way a taker does once it believes the holder's lease expired: the next
    /// version in the chain, applied through the real applier.
    private SchemaMigrationLockValue takeOver() {
        var held = committedLock();
        var successor = new SchemaMigrationLockValue(DATASOURCE,
                                                     NODE_2,
                                                     time.now(),
                                                     time.now() + 10 * TTL_MS,
                                                     held.nextVersion());

        kvStore.put(LOCK_KEY, successor);
        assertThat(committedLock()).isEqualTo(successor);

        return successor;
    }

    private SchemaMigrationLockValue committedLock() {
        return (SchemaMigrationLockValue) kvStore.get(LOCK_KEY).or((AetherValue) null);
    }

    /// Waits for an EVENT the test cannot step (a real timeout elapsing), never for a duration.
    private static void awaitEvent(String what, java.util.function.BooleanSupplier condition) {
        var deadline = System.currentTimeMillis() + 10_000L;

        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                Assertions.fail("timed out waiting for: " + what);
            }

            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Assertions.fail("interrupted waiting for: " + what);
            }
        }
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

        return new SchemaOrchestratorServiceInstance(cluster,
                                                     kvStore,
                                                     artifactStoreServing(),
                                                     repository,
                                                     schemaManager,
                                                     stubConnectionProvider(),
                                                     self,
                                                     Option.<MessageRouter> none(),
                                                     new LeaseTiming(TTL_MS, RENEWAL_TIMEOUT_MS, time::now, time::schedule));
    }

    /// A hand-stepped clock and scheduler: `advance` moves time forward one tick at a time and runs each
    /// scheduled task whose moment has come, on the calling thread.
    private static final class FakeTime {
        private record Task(long at, Runnable body, boolean[] cancelled) {}

        private final java.util.concurrent.atomic.AtomicLong now;
        private final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());

        FakeTime(long start) {
            now = new java.util.concurrent.atomic.AtomicLong(start);
        }

        long now() {
            return now.get();
        }

        Runnable schedule(Runnable body, long delayMs) {
            var task = new Task(now.get() + delayMs, body, new boolean[1]);

            tasks.add(task);

            return () -> {
                task.cancelled()[0] = true;
                tasks.remove(task);
            };
        }

        int pending() {
            return tasks.size();
        }

        void advanceWithoutFiring(long ms) {
            now.addAndGet(ms);
        }

        void advance(long ms) {
            var target = now.get() + ms;

            now.set(target);
            List.copyOf(tasks).stream()
                .filter(t -> t.at() <= target)
                .sorted(java.util.Comparator.comparingLong(Task::at))
                .forEach(t -> {
                    if (tasks.remove(t) && !t.cancelled()[0]) {
                        t.body().run();
                    }
                });
        }
    }

    /// Applies every batch immediately through the real applier and counts the lock `Put`s submitted.
    private static final class RecordingClusterNode implements ClusterNode<KVCommand<AetherKey>> {
        final NodeId self;
        final InMemoryKvStore kvStore;
        private final java.util.concurrent.atomic.AtomicInteger lockPuts = new java.util.concurrent.atomic.AtomicInteger();

        RecordingClusterNode(NodeId self, InMemoryKvStore kvStore) {
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
            batch.stream()
                 .filter(c -> c instanceof KVCommand.Put<AetherKey, ?> put && put.value() instanceof SchemaMigrationLockValue)
                 .forEach(_ -> lockPuts.incrementAndGet());
            if (parking && isRenewal(batch)) {
                var promise = Promise.<List<Object>> promise();

                parked.add(new Parked(batch, promise));

                return (Promise<List<R>>)(Promise<?>) promise;
            }

            kvStore.apply(batch);

            return Promise.success(Collections.emptyList());
        }

        int lockPuts() {
            return lockPuts.get();
        }

        private volatile boolean parking;
        private final List<Parked> parked = Collections.synchronizedList(new ArrayList<>());

        private record Parked(List<KVCommand<AetherKey>> batch, Promise<List<Object>> promise) {}

        void parkRenewals() {
            parking = true;
        }

        int parkedRenewals() {
            return parked.size();
        }

        void commitParkedRenewals() {
            parking = false;
            var batches = List.copyOf(parked);

            parked.clear();
            batches.forEach(p -> {
                kvStore.apply(p.batch());
                p.promise().succeed(List.of());
            });
        }

        private static boolean isRenewal(List<KVCommand<AetherKey>> batch) {
            return batch.stream()
                        .anyMatch(c -> c instanceof KVCommand.Put<AetherKey, ?> put
                                       && put.value() instanceof SchemaMigrationLockValue lock
                                       && lock.heldBy().equals(NODE_1)
                                       && lock.expiresAt() > 0
                                       && lock.lockVersion() > SchemaMigrationLockValue.FIRST_VERSION);
        }
    }

    /// `migrate` parks until the test completes it; undo/baseline are not exercised.
    private static final class ControlledSchemaManager implements AetherSchemaManager {
        final List<String> invocations = Collections.synchronizedList(new ArrayList<>());
        private final List<Promise<SchemaResult>> pending = Collections.synchronizedList(new ArrayList<>());

        @Override
        public Promise<SchemaResult> migrate(String datasource,
                                             List<MigrationEntry> scripts,
                                             SqlConnector connector,
                                             String nodeId,
                                             BlueprintId owner) {
            var promise = Promise.<SchemaResult> promise();

            invocations.add(nodeId);
            pending.add(promise);

            return promise;
        }

        void completeAll(Result<SchemaResult> result) {
            pending.forEach(p -> p.resolve(result));
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
            public Promise<DeployResult> deploy(ArtifactFile file, byte[] content) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override
            public Promise<byte[]> resolve(ArtifactFile file) {
                return Promise.success(jarBytes);
            }

            @Override
            public Promise<ResolvedArtifact> resolveWithMetadata(ArtifactFile file) {
                return Causes.cause("Not supported in this stub").promise();
            }

            @Override
            public Promise<Boolean> exists(ArtifactFile file) {
                return Promise.success(true);
            }

            @Override
            public Promise<Option<ArtifactMetadata>> metadata(ArtifactFile file) {
                return Promise.success(Option.none());
            }

            @Override
            public Promise<List<Version>> versions(GroupId groupId, ArtifactId artifactId) {
                return Promise.success(List.of());
            }

            @Override
            public Promise<Unit> archive(Artifact artifact) {
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
