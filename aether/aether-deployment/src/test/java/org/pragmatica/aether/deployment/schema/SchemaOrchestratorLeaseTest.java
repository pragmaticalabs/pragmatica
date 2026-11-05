// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.time.Duration;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.LongStream;
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
import org.pragmatica.db.migration.MigrationError;
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
import org.pragmatica.utility.warning.OperatorWarning;
import org.pragmatica.utility.warning.OperatorWarningCode;
import org.pragmatica.utility.warning.OperatorWarningSink;

import io.netty.buffer.ByteBuf;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
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
    private static final Cause NOT_IN_REPOSITORY = (org.pragmatica.aether.slice.repository.Repository.Absent) () -> "Artifact not present in local repository";

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

    private static final String OTHER_DATASOURCE = "database.billing";
    private static final String SENTINEL = "test-sentinel";
    private static final String POSTGRES_VERSION = "PostgreSQL 16.4 (stub)";
    private static final String COCKROACH_VERSION = "CockroachDB CCL v23.1.11 (x86_64-pc-linux-gnu)";
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
        var migration = orchestrator(NODE_1, 60_000L).migrateIfNeeded(DATASOURCE);

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
    /// FDB-2a ruling (A6): a migration fenced in the database (another session holds the DB fence) does not consume an
    /// attempt. Before, `MAX_RETRIES` Helds in a row (15 s + 45 s) made the schema FAILED, while a holder lives minutes
    /// to hours. Re-dispatches run on the injected scheduler, so each backoff is read from the delay it was scheduled at.
    @Nested
    class HeldInTheDatabase {
        private final Cause held = MigrationError.MigrationFencedInDatabase.migrationFencedInDatabase(DATASOURCE,
                                                                                                         "pid=7, application_name=aether-mig:node-2");
        private final List<OperatorWarning> warnings = Collections.synchronizedList(new ArrayList<>());
        private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);
        private SchemaOrchestratorService orchestrator;

        @Test
        void consecutiveHelds_neverFail_keepTheAttemptCount_backOffCappedAt45s_warnFromTheThird() {
            start();

            for (var attempt = 1; attempt <= 4; attempt++) {
                assertThat(fail(attempt, held, attempt < 4)).as("status while waiting out Held %d", attempt).isEqualTo(SchemaStatus.PENDING);
            }

            assertThat(redispatchDelays()).containsExactly(15_000L, 45_000L, 45_000L, 45_000L);
            assertThat(current().attemptCount()).as("a Held consumes no attempt").isZero();
            assertThat(deliveredWarnings()).extracting(OperatorWarning::code)
                                           .as("raised on the 3rd and the 4th consecutive Held only")
                                           .containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD,
                                                            OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD);
            assertThat(deliveredWarnings().getFirst().message()).contains("pid=7");
        }

        /// The count is of CONSECUTIVE Helds: a counted failure in between (a transient outage, which does consume an
        /// attempt) restarts it, so two more Helds neither warn nor reach the capped backoff early.
        @Test
        void aCountedFailureBetweenHelds_restartsTheConsecutiveCount() {
            start();
            fail(1, held, true);
            fail(2, held, true);
            fail(3, SchemaError.DatasourceUnreachable.datasourceUnreachable(DATASOURCE, "connection refused"), true);
            fail(4, held, true);
            fail(5, held, false);

            assertThat(redispatchDelays()).containsExactly(15_000L, 45_000L, 15_000L, 15_000L, 45_000L);
            assertThat(current().attemptCount()).as("only the outage consumed an attempt").isEqualTo(1);
            assertThat(deliveredWarnings()).as("never three consecutive Helds").isEmpty();
        }

        @Test
        void heldBackoff_isCappedAt45s_andPositive_forAnyCount() {
            var stats = LongStream.rangeClosed(1, 100_000)
                                  .map(n -> SchemaOrchestratorServiceInstance.heldBackoff((int) n))
                                  .summaryStatistics();

            assertThat(stats.getMax()).isEqualTo(SchemaOrchestratorService.HELD_BACKOFF_CAP_MS);
            assertThat(stats.getMin()).as("no overflow to zero or below").isEqualTo(15_000L);
        }

        /// A success resets the consecutive count: two Helds, a success, then two more Helds are never three in a row, so
        /// nothing warns and the backoff restarts at 15 s. Without the reset the second run's first Held would be the 3rd.
        @Test
        void aSuccessBetweenHelds_restartsTheConsecutiveCount() {
            start();
            fail(1, held, true);
            fail(2, held, true);
            succeed(3);
            fail(4, held, true);
            fail(5, held, false);

            assertThat(redispatchDelays()).containsExactly(15_000L, 45_000L, 15_000L, 45_000L);
            assertThat(deliveredWarnings()).as("never three consecutive Helds, and no warning was open to recover").isEmpty();
        }

        /// The recovery follows a published fence-held warning, once, for the same datasource.
        @Test
        void aSuccessAfterAPublishedFenceHeld_raisesTheRecoveryOnce() {
            start();
            fail(1, held, true);
            fail(2, held, true);
            fail(3, held, true);
            succeed(4);
            succeed(5);

            var delivered = deliveredWarnings();

            assertThat(delivered).extracting(OperatorWarning::code)
                                 .containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD,
                                                  OperatorWarningCode.SCHEMA_MIGRATION_FENCE_RELEASED);
            assertThat(delivered).extracting(OperatorWarning::subject).containsOnly(DATASOURCE);
        }

        /// Control: Helds below the threshold publish no warning, so the success that ends them has none to close.
        @Test
        void aSuccessAfterHeldsBelowTheThreshold_raisesNoRecovery() {
            start();
            fail(1, held, true);
            fail(2, held, true);
            succeed(3);

            assertThat(deliveredWarnings()).isEmpty();
        }

        /// The recovery is per datasource: another datasource completing while this one's warning is open closes nothing,
        /// and this one's own success then closes it.
        @Test
        void anotherDatasourcesSuccess_doesNotCloseThisOnesFenceHeld() {
            start();
            fail(1, held, true);
            fail(2, held, true);
            fail(3, held, false);
            seedPendingSchema(OTHER_DATASOURCE);
            orchestrator.migrateIfNeeded(OTHER_DATASOURCE);
            awaitEvent("the other datasource reached the schema manager", () -> schemaManager.invocations.size() == 4);
            schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
            awaitEvent("the other datasource completed and released its lock",
                       () -> statusOf(OTHER_DATASOURCE) == SchemaStatus.COMPLETED && lockOf(OTHER_DATASOURCE).expiresAt() <= time.now());

            assertThat(deliveredWarnings()).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD);

            time.advance(redispatchDelays().getLast());
            awaitEvent("this datasource's next attempt dispatched", () -> schemaManager.invocations.size() == 5);
            schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
            awaitEvent("this datasource completed and released its lock", () -> statusOf(DATASOURCE) == SchemaStatus.COMPLETED && committedLock().expiresAt() <= time.now());

            assertThat(deliveredWarnings()).extracting(OperatorWarning::code)
                                           .containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_FENCE_HELD,
                                                            OperatorWarningCode.SCHEMA_MIGRATION_FENCE_RELEASED);
            assertThat(deliveredWarnings().getLast().subject()).isEqualTo(DATASOURCE);
        }

        private void start() {
            orchestrator = orchestrator(NODE_1);
            orchestrator.setOperatorWarningSink(sink);
            orchestrator.migrateIfNeeded(DATASOURCE);
        }

        /// Completes attempt `attempt` successfully, waits until its schema record is COMPLETED, then re-arms the schema
        /// as PENDING and dispatches the next run (a later migration on the same datasource).
        private void succeed(int attempt) {
            awaitEvent("attempt " + attempt + " dispatched", () -> schemaManager.invocations.size() == attempt);
            schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
            awaitEvent("attempt " + attempt + " completed", () -> current().status() == SchemaStatus.COMPLETED && committedLock().expiresAt() <= time.now());
            dispatchWhenFinalized(orchestrator, DATASOURCE, () -> seedPendingSchema(DATASOURCE));
        }

        /// Fails attempt `attempt` with `cause`, waits until its re-dispatch is scheduled and the attempt has let go of
        /// its lock, then (when `redispatch`) steps time to fire it. Returns the status read while the backoff was pending.
        private SchemaStatus fail(int attempt, Cause cause, boolean redispatch) {
            var scheduledBefore = redispatchDelays().size();

            awaitEvent("attempt " + attempt + " dispatched", () -> schemaManager.invocations.size() == attempt);
            schemaManager.completeAll(cause.result());
            awaitEvent("attempt " + attempt + "'s re-dispatch scheduled", () -> redispatchDelays().size() == scheduledBefore + 1);
            awaitEvent("attempt " + attempt + " released its lock", () -> committedLock().expiresAt() <= time.now() && time.pending() == 1);

            var waiting = current().status();

            if (redispatch) {
                time.advance(redispatchDelays().getLast());
            }
            return waiting;
        }

        private List<OperatorWarning> deliveredWarnings() {
            return delivered(sink, warnings);
        }
    }

    /// FDB-2a ruling: CockroachDB configured as POSTGRESQL has no tier-1 fence and runs unfenced in FDB-2a, but never
    /// silently: each schema operation raises an operator warning (and the runner logs one).
    @Nested
    class UnfencedCockroach {
        private final List<OperatorWarning> warnings = Collections.synchronizedList(new ArrayList<>());
        private final OperatorWarningSink sink = OperatorWarningSink.handingOffTo(warnings::add);

        @Test
        void migrate_onCockroachConfiguredAsPostgres_raisesTheUnfencedWarning() {
            migrateOn(COCKROACH_VERSION);

            var delivered = delivered(sink, warnings);

            assertThat(delivered).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_UNFENCED);
            assertThat(delivered).extracting(OperatorWarning::subject).containsExactly(DATASOURCE);
        }

        /// Control: the same flow on PostgreSQL raises nothing, so the warning above comes from the engine probe.
        @Test
        void migrate_onPostgres_raisesNoUnfencedWarning() {
            migrateOn(POSTGRES_VERSION);

            assertThat(delivered(sink, warnings)).isEmpty();
        }

        /// The recovery follows a published unfenced warning, once, for the same datasource.
        @Test
        void migrate_onceTheEngineRunsFenced_raisesTheFencedRecoveryOnce() {
            var engines = new ConcurrentHashMap<String, String>();
            var orchestrator = orchestrator(NODE_1, RENEWAL_TIMEOUT_MS, engines::get);

            engines.put(DATASOURCE, COCKROACH_VERSION);
            orchestrator.setOperatorWarningSink(sink);
            migrateAgain(orchestrator, DATASOURCE, 1);
            engines.put(DATASOURCE, POSTGRES_VERSION);
            migrateAgain(orchestrator, DATASOURCE, 2);
            migrateAgain(orchestrator, DATASOURCE, 3);

            var delivered = delivered(sink, warnings);

            assertThat(delivered).extracting(OperatorWarning::code)
                                 .containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_UNFENCED, OperatorWarningCode.SCHEMA_MIGRATION_FENCED);
            assertThat(delivered).extracting(OperatorWarning::subject).containsOnly(DATASOURCE);
        }

        /// The recovery is per datasource: another datasource running fenced closes nothing, and this one's own fenced run
        /// then closes it.
        @Test
        void migrate_onAnotherDatasourceRunningFenced_doesNotCloseThisOnesUnfenced() {
            var engines = new ConcurrentHashMap<String, String>();
            var orchestrator = orchestrator(NODE_1, RENEWAL_TIMEOUT_MS, engines::get);

            engines.put(DATASOURCE, COCKROACH_VERSION);
            engines.put(OTHER_DATASOURCE, POSTGRES_VERSION);
            orchestrator.setOperatorWarningSink(sink);
            migrateAgain(orchestrator, DATASOURCE, 1);
            migrateAgain(orchestrator, OTHER_DATASOURCE, 2);

            assertThat(delivered(sink, warnings)).extracting(OperatorWarning::code).containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_UNFENCED);

            engines.put(DATASOURCE, POSTGRES_VERSION);
            migrateAgain(orchestrator, DATASOURCE, 3);

            assertThat(delivered(sink, warnings)).extracting(OperatorWarning::code)
                                                 .containsExactly(OperatorWarningCode.SCHEMA_MIGRATION_UNFENCED, OperatorWarningCode.SCHEMA_MIGRATION_FENCED);
            assertThat(delivered(sink, warnings).getLast().subject()).isEqualTo(DATASOURCE);
        }

        /// Dispatches the `run`th migration of `datasource` and completes it. The seed puts the schema back to PENDING.
        private void migrateAgain(SchemaOrchestratorService orchestrator, String datasource, int run) {
            dispatchWhenFinalized(orchestrator, datasource, () -> seedPendingSchema(datasource));
            awaitEvent("run " + run + " reached the schema manager", () -> schemaManager.invocations.size() == run);
            schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
            awaitEvent("run " + run + " completed and released its lock", () -> statusOf(datasource) == SchemaStatus.COMPLETED && lockOf(datasource).expiresAt() <= time.now());
        }

        private void migrateOn(String serverVersion) {
            var orchestrator = orchestrator(NODE_1, RENEWAL_TIMEOUT_MS, serverVersion);

            orchestrator.setOperatorWarningSink(sink);
            orchestrator.migrateIfNeeded(DATASOURCE);
            awaitEvent("the migration reached the schema manager", () -> schemaManager.invocations.size() == 1);
            schemaManager.completeAll(Result.success(AetherSchemaManager.SchemaResult.schemaResult(1, 4, 1L)));
        }
    }

    /// The warnings delivered through `sink` so far. The hand-off delivers in order on one thread, so once a sentinel
    /// offered now has arrived, every warning raised before it has too.
    private static List<OperatorWarning> delivered(OperatorWarningSink sink, List<OperatorWarning> received) {
        var sentinel = OperatorWarning.operatorWarning(OperatorWarningCode.DEPLOY_WARNING, SENTINEL, "sentinel " + System.nanoTime());

        sink.accept(sentinel);
        awaitEvent("the sentinel warning was delivered", () -> received.contains(sentinel));

        return List.copyOf(received).stream().filter(warning -> !SENTINEL.equals(warning.subject())).toList();
    }

    private List<Long> redispatchDelays() {
        return List.copyOf(time.delays).stream().filter(delay -> delay != TICK_MS).toList();
    }

    private SchemaVersionValue current() {
        return (SchemaVersionValue) kvStore.get(SchemaVersionKey.schemaVersionKey(DATASOURCE)).or((AetherValue) null);
    }

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
        return lockOf(DATASOURCE);
    }

    private SchemaStatus statusOf(String datasource) {
        return ((SchemaVersionValue) kvStore.get(SchemaVersionKey.schemaVersionKey(datasource)).or((AetherValue) null)).status();
    }

    /// Re-arms `datasource` as PENDING and dispatches a migration, retrying while the previous attempt has not yet let go
    /// of its single-flight fence (the dispatch is then refused at once, with no side effect beyond the re-arm).
    private static void dispatchWhenFinalized(SchemaOrchestratorService orchestrator, String datasource, Runnable rearm) {
        awaitEvent("the dispatch of " + datasource + " was accepted", () -> accepted(orchestrator, datasource, rearm));
    }

    private static boolean accepted(SchemaOrchestratorService orchestrator, String datasource, Runnable rearm) {
        rearm.run();
        var run = orchestrator.migrateIfNeeded(datasource);

        return !(run.isResolved() && run.await().isFailure());
    }

    private SchemaMigrationLockValue lockOf(String datasource) {
        return (SchemaMigrationLockValue) kvStore.get(SchemaMigrationLockKey.schemaMigrationLockKey(datasource)).or((AetherValue) null);
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
        seedPendingSchema(DATASOURCE);
    }

    private void seedPendingSchema(String datasource) {
        kvStore.put(SchemaVersionKey.schemaVersionKey(datasource),
                    SchemaVersionValue.schemaVersionValue(datasource,
                                                          3,
                                                          "V003__add_index.sql",
                                                          SchemaStatus.PENDING,
                                                          COORDS,
                                                          OWNER));
    }

    private SchemaOrchestratorService orchestrator(NodeId self) {
        return orchestrator(self, RENEWAL_TIMEOUT_MS);
    }

    /// Only the tests of a renewal that GIVES UP use the short real timeout; a test that holds a renewal in
    /// flight across assertions must not race it, so it passes a timeout no real run reaches.
    private SchemaOrchestratorService orchestrator(NodeId self, long renewalTimeoutMs) {
        return orchestrator(self, renewalTimeoutMs, POSTGRES_VERSION);
    }

    private SchemaOrchestratorService orchestrator(NodeId self, long renewalTimeoutMs, String serverVersion) {
        return orchestrator(self, renewalTimeoutMs, _ -> serverVersion);
    }

    /// `engineOf` answers, per datasource and per call, the server version the fence-tier probe reads, so a test can
    /// change a datasource's engine between two operations of one orchestrator.
    private SchemaOrchestratorService orchestrator(NodeId self, long renewalTimeoutMs, Function<String, String> engineOf) {
        Repository repository = _ -> NOT_IN_REPOSITORY.promise();

        return new SchemaOrchestratorServiceInstance(cluster,
                                                     kvStore,
                                                     artifactStoreServing(),
                                                     repository,
                                                     schemaManager,
                                                     stubConnectionProvider(engineOf),
                                                     self,
                                                     Option.<MessageRouter> none(),
                                                     new LeaseTiming(TTL_MS, renewalTimeoutMs, time::now, time::schedule));
    }

    /// A hand-stepped clock and scheduler: `advance` moves time forward one tick at a time and runs each
    /// scheduled task whose moment has come, on the calling thread.
    private static final class FakeTime {
        private record Task(long at, Runnable body, boolean[] cancelled) {}

        private final java.util.concurrent.atomic.AtomicLong now;
        private final List<Task> tasks = Collections.synchronizedList(new ArrayList<>());
        /// Every delay ever scheduled, in order: lease renewal ticks ([#TICK_MS]) and re-dispatches.
        private final List<Long> delays = Collections.synchronizedList(new ArrayList<>());

        FakeTime(long start) {
            now = new java.util.concurrent.atomic.AtomicLong(start);
        }

        long now() {
            return now.get();
        }

        Runnable schedule(Runnable body, long delayMs) {
            var task = new Task(now.get() + delayMs, body, new boolean[1]);

            delays.add(delayMs);
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
            writeEntry(zip, "schema/billing/V003__add_index.sql", "CREATE INDEX idx_billing ON billing(id);");
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

    private static DatasourceConnectionProvider stubConnectionProvider(Function<String, String> engineOf) {
        return new DatasourceConnectionProvider() {
            @Override
            public Promise<SqlConnector> connector(String datasourceName) {
                return Promise.success(stubConnector(engineOf.apply(datasourceName)));
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

    /// Answers only `SELECT version()` (with `serverVersion`, the orchestrator's fence-tier probe); the schema manager
    /// is a double, so nothing else reaches this connector.
    private static SqlConnector stubConnector(String serverVersion) {
        return new SqlConnector() {
            /// FDB-2a (#2591): one logical session; `body` runs on this double, then `release`; the result is `body`'s.
            @Override
            public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
                return body.execute(this).fold(bodyResult -> release.execute(this).fold(_ -> Promise.resolved(bodyResult)));
            }

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
                return sql.contains("version()")
                       ? FakeMigrationFence.rowOf(Map.of("v", serverVersion), mapper)
                       : Causes.cause("Not supported in this stub").promise();
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
