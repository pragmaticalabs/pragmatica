// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.async.AsyncSqlConnectorFactory;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;
import org.pragmatica.aether.resource.db.r2dbc.R2dbcSqlConnectorFactory;
import org.pragmatica.db.migration.ParsedMigration.MigrationType;
import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration.appliedMigration;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a (#2591): the tier-1 PostgreSQL migration fence, against a LIVE PostgreSQL 16, through each PRODUCTION
/// transport (Hikari JDBC, R2DBC, pg-async), never a hand-rolled connector double. Every test gets a fresh database
/// owned by a NON-superuser role `migrator` (the runner's identity), so the fence is scoped exactly as in production
/// (per database) and a privilege can be revoked from the runner. Ordering is forced by database locks the test holds
/// (a `gate` table held ACCESS EXCLUSIVE, a history row held FOR UPDATE), and the test waits for the runner's own
/// session (`application_name` `aether-mig:<node>`) to be lock-waiting: no sleeps decide an interleaving. A
/// rollback-proof counter (`nextval`) counts entries into a script. Self-skips without Docker unless
/// `-Daether.requireDocker=true`.
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MigrationFencePgTest {
    private static final int FENCE_CLASS = 0x41455448;
    private static final int FENCE_OBJECT = 1;
    private static final String RUNNER_ROLE = "migrator";
    private static final String RUNNER_PASSWORD = "migrator";
    private static final String OWNER = "fence-test-blueprint";
    private static final String DATASOURCE = "fenced";
    private static final TimeSpan GENEROUS = timeSpan(60).seconds();
    private static final long WAIT_MS = 30_000;
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;

    private final List<SqlConnector> opened = new ArrayList<>();
    private final List<Connection> held = new ArrayList<>();
    private final ExecutorService runners = Executors.newCachedThreadPool();
    private String database;

    enum Transport { JDBC, R2DBC, ASYNC }

    @BeforeAll
    static void startPostgres() throws SQLException {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-PostgreSQL migration fence");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE ROLE " + RUNNER_ROLE + " LOGIN PASSWORD '" + RUNNER_PASSWORD + "'");
        }
    }

    @AfterAll
    static void stopPostgres() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        runners.shutdownNow();
        held.forEach(MigrationFencePgTest::closeQuietly);
        held.clear();
        opened.forEach(connector -> connector.stop().await());
        opened.clear();

        if (database != null) {
            try (var admin = superuser(postgres.getDatabaseName())) {
                execute(admin, "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    // ---------------------------------------------------------------- T1

    /// T1: while another session holds the fence, a migration returns `MigrationFencedInDatabase` naming that holder,
    /// runs NOTHING (the rollback-proof entry counter is unchanged) and writes NOTHING (not even the history table:
    /// bootstrap runs only under the fence).
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_whileAnotherSessionHoldsTheFence_returnsFencedAndRunsNothing(Transport transport) throws Exception {
        freshDatabase("CREATE SEQUENCE entries", "CREATE TABLE entry_log (id BIGINT)");
        var holder = superuserOnTestDb();

        execute(holder, "SET application_name = 'holder-A'");
        execute(holder, "SELECT pg_advisory_lock(" + FENCE_CLASS + ", " + FENCE_OBJECT + ")");

        var result = migrate(connector(transport, 2), GENEROUS, "node-b", script(1, "t1", ENTRY));

        assertThat(causeOf(result)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
        assertThat(causeOf(result).message()).contains("holder-A");
        assertThat(entries()).as("no statement of the script may run while fenced").isZero();
        assertThat(scalar("SELECT count(*) FROM pg_class WHERE relname = 'aether_schema_history'"))
            .as("no history write (bootstrap included) may run while fenced").isZero();
    }

    // ---------------------------------------------------------------- T4

    /// T4: holder death on a transactional script. A is parked mid-script on the gate; B is fenced while A's session
    /// lives; A is terminated, its transaction rolls back; only once A's session is gone does B acquire, and the
    /// script's effects commit exactly once.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_holderDiesMidTransactionalScript_successorAcquiresOnlyAfterItsSessionIsGone_committedOnce(Transport transport) throws Exception {
        freshDatabase("CREATE SEQUENCE entries", "CREATE TABLE entry_log (id BIGINT)", "CREATE TABLE gate (id INT)");
        var connector = connector(transport, 2);
        var script = script(1, "t4", ENTRY + "INSERT INTO gate VALUES (1);\nCREATE TABLE t4_done (id INT);\n");
        var gate = holdGate();

        var a = runAsync(connector, GENEROUS, "node-a", script);
        var aPid = awaitRunnerWaitingOnLock("node-a");

        var b = migrate(connector, GENEROUS, "node-b", script);
        assertThat(causeOf(b)).as("B is fenced while A's session lives").isInstanceOf(MigrationError.MigrationFencedInDatabase.class);

        terminate(aPid);
        assertThat(a.get(WAIT_MS, TimeUnit.MILLISECONDS).isFailure()).as("A dies with its session").isTrue();
        awaitSessionGone(aPid);
        gate.rollback();

        assertThat(migrate(connector, GENEROUS, "node-b", script).isSuccess()).isTrue();
        assertThat(scalar("SELECT count(*) FROM entry_log")).as("the script committed exactly once").isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM pg_class WHERE relname = 't4_done'")).isEqualTo(1);
        assertThat(historyRows(1)).isEqualTo(1);
        assertThat(entries()).as("A entered once (rolled back), B once").isEqualTo(2);
    }

    // ---------------------------------------------------------------- T5

    /// T5: holder death on a NON-transactional script (a `CREATE INDEX CONCURRENTLY` forces the autocommit path).
    /// A completes statement 1, parks on statement 2, and is terminated. B resumes at checkpoint 1: statement 1 is not
    /// re-entered. Every history write, A's and B's, came from a fenced runner session (`aether-mig:*`), never from a
    /// pooled connection (M8: a write on the base connector would carry the pool's application name).
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_holderDiesMidAutocommitScript_successorResumesAtTheCheckpoint_statementOneNotReentered(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE s1_log (id INT)", "CREATE TABLE gate (id INT)", "CREATE TABLE t5 (c INT)");
        var connector = connector(transport, 2);
        installHistoryWriterAudit(connector);
        var script = script(1, "t5", """
            INSERT INTO s1_log VALUES (1);
            INSERT INTO gate VALUES (2);
            CREATE INDEX CONCURRENTLY IF NOT EXISTS t5_c_idx ON t5 (c);
            """);
        var gate = holdGate();

        var a = runAsync(connector, GENEROUS, "node-a", script);
        var aPid = awaitRunnerWaitingOnLock("node-a");

        terminate(aPid);
        assertThat(a.get(WAIT_MS, TimeUnit.MILLISECONDS).isFailure()).isTrue();
        awaitSessionGone(aPid);
        gate.rollback();

        var b = migrate(connector, GENEROUS, "node-b", script);

        assertThat(b.isSuccess()).as("B resumes and completes: %s", b).isTrue();
        assertThat(scalar("SELECT count(*) FROM s1_log")).as("statement 1 must not be re-entered").isEqualTo(1);
        assertThat(scalar("SELECT statements_completed FROM aether_schema_history WHERE version = 1")).isEqualTo(3);
        assertThat(scalar("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 't5_c_idx' AND i.indisvalid"))
            .isEqualTo(1);
        assertHistoryWritesCameFromFencedSessions();
    }

    // ---------------------------------------------------------------- T5b

    /// T5b (H3): a DML statement on the autocommit path commits in ONE transaction with its checkpoint. A resumes from
    /// a seeded `IN_PROGRESS` checkpoint 0, inserts into a keyless table, and its checkpoint UPDATE blocks on the
    /// history row the test holds FOR UPDATE; A is terminated there. Its INSERT rolls back with the checkpoint, so B
    /// re-runs it once: row count 1. Split from its checkpoint (M10), A's INSERT would have committed: count 2.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_holderDiesBetweenDmlAndItsCheckpoint_successorReRunsTheDmlOnce(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE keyless (v INT)", "CREATE TABLE t5b (c INT)");
        var connector = connector(transport, 2);
        var script = script(1, "t5b", """
            INSERT INTO keyless VALUES (1);
            CREATE INDEX CONCURRENTLY IF NOT EXISTS t5b_c_idx ON t5b (c);
            """);
        seedInProgress(connector, script, 2);
        var rowLock = superuserOnTestDb();

        rowLock.setAutoCommit(false);
        execute(rowLock, "SELECT * FROM aether_schema_history WHERE version = 1 FOR UPDATE");

        var a = runAsync(connector, GENEROUS, "node-a", script);
        var aPid = awaitRunnerWaitingOnLock("node-a");

        terminate(aPid);
        assertThat(a.get(WAIT_MS, TimeUnit.MILLISECONDS).isFailure()).isTrue();
        awaitSessionGone(aPid);
        rowLock.rollback();

        var b = migrate(connector, GENEROUS, "node-b", script);

        assertThat(b.isSuccess()).as("B completes: %s", b).isTrue();
        assertThat(scalar("SELECT count(*) FROM keyless")).as("the DML and its checkpoint commit atomically").isEqualTo(1);
    }

    // ---------------------------------------------------------------- T7

    /// T7: no fence lock outlives the run, on success, on a failing script, and when the courtesy unlock itself fails
    /// (EXECUTE on `pg_advisory_unlock_all` revoked from the runner): the runner's dedicated session is gone and NO
    /// session (pooled ones included) holds the fence. Positive control: the run's history writes came from a fenced
    /// runner session, so a fence really existed to leak.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_leavesNoFenceBehind_onSuccessFailureAndAFailedUnlock(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE ok (id INT)");
        var connector = connector(transport, 2);
        installHistoryWriterAudit(connector);

        assertThat(migrate(connector, GENEROUS, "node-a", script(1, "ok", "INSERT INTO ok VALUES (1);\n")).isSuccess()).isTrue();
        assertNoFenceRemains(connector);

        assertThat(migrate(connector, GENEROUS, "node-a", script(2, "bad", "INSERT INTO missing_table VALUES (1);\n")).isFailure()).isTrue();
        assertNoFenceRemains(connector);

        try (var admin = superuserOnTestDbScoped()) {
            execute(admin, "REVOKE EXECUTE ON FUNCTION pg_advisory_unlock_all() FROM PUBLIC");
        }

        assertThat(migrate(connector, GENEROUS, "node-a", script(2, "fixed", "INSERT INTO ok VALUES (2);\n")).isSuccess()).isTrue();
        assertNoFenceRemains(connector);
        assertHistoryWritesCameFromFencedSessions();
    }

    // ---------------------------------------------------------------- T9

    /// T9: four runners started together on an EMPTY database: bootstrap runs only under the fence, so exactly one
    /// meta row and one owner row exist, the script ran once, and every runner either succeeded or was fenced.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_concurrentFirstBootstrap_writesExactlyOneMetaAndOneOwnerRow(Transport transport) throws Exception {
        freshDatabase("CREATE SEQUENCE entries", "CREATE TABLE entry_log (id BIGINT)");
        var connector = connector(transport, 8);
        var start = new CountDownLatch(1);
        var script = script(1, "t9", ENTRY);
        var futures = new ArrayList<Future<Result<SchemaResult>>>();

        for (var i = 0; i < 4; i++) {
            var node = "node-" + i;
            futures.add(runners.submit(() -> awaitThenMigrate(start, connector, node, script)));
        }

        start.countDown();

        for (var future : futures) {
            var result = future.get(WAIT_MS, TimeUnit.MILLISECONDS);

            result.onFailure(cause -> assertThat(cause).as("a losing runner is fenced, nothing else")
                                                       .isInstanceOf(MigrationError.MigrationFencedInDatabase.class));
        }

        assertThat(scalar("SELECT count(*) FROM aether_schema_history_meta")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM aether_schema_owner")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM entry_log")).isEqualTo(1);
        assertThat(historyRows(1)).isEqualTo(1);
    }

    // ---------------------------------------------------------------- T11

    /// T11 (F8): a `CREATE INDEX CONCURRENTLY` cancelled by the statement timeout leaves an INVALID index, and the
    /// resumed `IF NOT EXISTS` passes over it silently; the runner checks `pg_index.indisvalid` after the statement and
    /// fails with `InvalidIndexLeftBehind` naming the index. The cancel comes from the pinned session's own statement
    /// bound: client-side on JDBC, server-side `statement_timeout` on R2DBC and pg-async.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_cicCancelledByTheStatementTimeout_resumeFailsWithInvalidIndexLeftBehind(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE t11 (c INT)", "INSERT INTO t11 VALUES (1)");
        var connector = connector(transport, 2);
        var script = script(1, "t11", "CREATE INDEX CONCURRENTLY IF NOT EXISTS t11_c_idx ON t11 (c);\n");
        var writer = superuserOnTestDb();

        writer.setAutoCommit(false);
        execute(writer, "INSERT INTO t11 VALUES (2)");

        var first = runAsync(connector, timeSpan(2).seconds(), "node-a", script).get(WAIT_MS, TimeUnit.MILLISECONDS);

        assertThat(first.isFailure()).as("the CIC is cancelled by the statement bound").isTrue();
        writer.rollback();
        assertThat(scalar("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 't11_c_idx' AND NOT i.indisvalid"))
            .as("precondition: the cancelled CIC left an INVALID index").isEqualTo(1);

        var resumed = migrate(connector, GENEROUS, "node-a", script);

        assertThat(causeOf(resumed)).isInstanceOf(MigrationError.InvalidIndexLeftBehind.class);
        assertThat(((MigrationError.InvalidIndexLeftBehind) causeOf(resumed)).index()).isEqualTo("t11_c_idx");
    }

    /// T11b (F8, fix round 1 A3): a `CREATE INDEX CONCURRENTLY` WITHOUT `IF NOT EXISTS`, cancelled by the bound, then
    /// resumed. The resumed build FAILS ("already exists", 42P07) instead of passing over the INVALID leftover; the
    /// runner explains the failed build, so the resume names the index (`InvalidIndexLeftBehind`), not the raw error.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_cicWithoutIfNotExistsCancelledByTheBound_resumeFailsWithInvalidIndexLeftBehind(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE t11 (c INT)", "INSERT INTO t11 VALUES (1)");
        var connector = connector(transport, 2);
        var script = script(1, "t11b", "CREATE INDEX CONCURRENTLY t11_c_idx ON t11 (c);\n");
        var writer = superuserOnTestDb();

        writer.setAutoCommit(false);
        execute(writer, "INSERT INTO t11 VALUES (2)");

        var first = runAsync(connector, timeSpan(2).seconds(), "node-a", script).get(WAIT_MS, TimeUnit.MILLISECONDS);

        writer.rollback();
        assertThat(first.isFailure()).as("the CIC is cancelled by the statement bound").isTrue();
        assertThat(scalar("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = 't11_c_idx' AND NOT i.indisvalid"))
            .as("precondition: the cancelled CIC left an INVALID index").isEqualTo(1);

        var resumed = migrate(connector, GENEROUS, "node-a", script);

        assertThat(causeOf(resumed)).isInstanceOf(MigrationError.InvalidIndexLeftBehind.class);
        assertThat(((MigrationError.InvalidIndexLeftBehind) causeOf(resumed)).index()).isEqualTo("t11_c_idx");
    }

    /// T11c (F8 scope, fix round 1 A3): index names are unique per SCHEMA. An INVALID `other.dup_idx` (a cancelled
    /// build in another schema) must not fail a VALID `public.dup_idx` build: the check looks only at the build's table.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_sameNameInvalidIndexInAnotherSchema_doesNotFailThisSchemasValidBuild(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE t13 (c INT)", "CREATE SCHEMA other", "CREATE TABLE other.t13 (c INT)", "INSERT INTO other.t13 VALUES (1)");
        leaveInvalidIndex("other.t13", "dup_idx");

        var result = migrate(connector(transport, 2), GENEROUS, "node-a",
                             script(1, "t13", "CREATE INDEX CONCURRENTLY IF NOT EXISTS dup_idx ON public.t13 (c);\n"));

        assertThat(result.isSuccess()).as("this build's index is valid: %s", result).isTrue();
        assertThat(scalar("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid JOIN pg_namespace n ON n.oid = c.relnamespace"
                          + " WHERE c.relname = 'dup_idx' AND n.nspname = 'public' AND i.indisvalid"))
            .as("positive control: the public index exists and is valid").isEqualTo(1);
    }

    // ---------------------------------------------------------------- T12: server-side bounds

    /// T12a (fix round 1 A4, CTO ruling): a positive bound governs lock waits on EVERY transport, a role default
    /// `lock_timeout` notwithstanding (JDBC used to keep the role default). The CIC waits on an open writer for longer
    /// than the role default (1 s); with that default in force it would be cancelled (`55P03`). The 2.5 s wait is a
    /// duration the test must exceed, not an interleaving: the runner is first seen lock-waiting.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void pinned_roleDefaultLockTimeout_isOverriddenByTheBound(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE t14 (c INT)", "INSERT INTO t14 VALUES (1)");
        setRoleDefault("lock_timeout", "1s");
        var connector = connector(transport, 2);

        assertThat(shownOnPinnedSession(connector, Duration.ofSeconds(60), "lock_timeout")).isEqualTo("1min");

        var writer = superuserOnTestDb();

        writer.setAutoCommit(false);
        execute(writer, "INSERT INTO t14 VALUES (2)");

        var run = runAsync(connector, GENEROUS, "node-a", script(1, "t14", "CREATE INDEX CONCURRENTLY IF NOT EXISTS t14_c_idx ON t14 (c);\n"));

        awaitRunnerWaitingOnLock("node-a");
        Thread.sleep(2_500);
        assertThat(run.isDone()).as("the role default (1 s) did not cancel the lock wait").isFalse();
        writer.rollback();
        assertThat(run.get(WAIT_MS, TimeUnit.MILLISECONDS).isSuccess()).isTrue();
    }

    /// T12b (fix round 1 A4, CTO ruling): a ZERO bound (Terra's) is sent to the server explicitly and means unbounded,
    /// overriding a role default `statement_timeout`; before, nothing was set and the role default cancelled the run.
    @ParameterizedTest
    @EnumSource(names = {"JDBC", "ASYNC"})
    void migrate_zeroBound_isUnboundedEvenUnderARoleDefaultStatementTimeout(Transport transport) throws Exception {
        freshDatabase("CREATE TABLE ok (id INT)");
        setRoleDefault("statement_timeout", "1s");
        var connector = connector(transport, 2);

        assertThat(shownOnPinnedSession(connector, Duration.ZERO, "statement_timeout")).isEqualTo("0");

        var run = migrate(connector, timeSpan(0).nanos(), "node-a",
                          script(1, "t12b", "DO $$ BEGIN PERFORM pg_sleep(2.5); END $$;\nINSERT INTO ok VALUES (1);\n"));

        assertThat(run.isSuccess()).as("a zero-bound migration outlives the 1 s role default: %s", run).isTrue();
    }

    // ---------------------------------------------------------------- R2DBC tripwire

    /// TRIPWIRE, asserting CURRENT behaviour: no migration runs through the R2DBC transport at all, fence or not. The
    /// history repository's statements bind `?` placeholders and r2dbc-postgresql accepts only `$n`, so the first
    /// parameterised statement fails ("Binding index 0 when only 0 parameters are expected"). The fence tests above
    /// therefore run on JDBC and pg-async only. When this goes red, R2DBC can run migrations: add `R2DBC` to every
    /// `@EnumSource` in this class and delete this test.
    @Test
    void tripwire_r2dbcCannotRunAMigration_enableTheR2dbcRowsWhenThisGoesRed() throws Exception {
        freshDatabase("CREATE TABLE ok (id INT)");

        var result = migrate(connector(Transport.R2DBC, 2), GENEROUS, "node-a", script(1, "ok", "INSERT INTO ok VALUES (1);\n"));

        assertThat(result.isFailure()).as("R2DBC now runs migrations: enable the R2DBC rows of MigrationFencePgTest").isTrue();
        assertThat(causeOf(result).message()).contains("Binding index");
    }

    // ---------------------------------------------------------------- fixtures

    private static final String ENTRY = "INSERT INTO entry_log VALUES (nextval('entries'));\n";

    private static MigrationScript script(int version, String description, String sql) {
        return MigrationScript.migrationScript("V" + version + "__" + description + ".sql", sql, sql.hashCode());
    }

    private Result<SchemaResult> migrate(SqlConnector connector, TimeSpan bound, String node, MigrationScript... scripts) {
        return SchemaMigrations.schemaMigrations(bound).migrate(DATASOURCE, List.of(scripts), connector, node, OWNER).await();
    }

    private Future<Result<SchemaResult>> runAsync(SqlConnector connector, TimeSpan bound, String node, MigrationScript script) {
        return runners.submit(() -> migrate(connector, bound, node, script));
    }

    private Result<SchemaResult> awaitThenMigrate(CountDownLatch start, SqlConnector connector, String node, MigrationScript script)
        throws InterruptedException {
        start.await();
        return migrate(connector, GENEROUS, node, script);
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    private void freshDatabase(String... setup) throws SQLException {
        database = "fence_" + DATABASES.incrementAndGet();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE DATABASE " + database + " OWNER " + RUNNER_ROLE);
        }

        try (var owner = DriverManager.getConnection(jdbcUrl(database), RUNNER_ROLE, RUNNER_PASSWORD)) {
            for (var statement : setup) {
                execute(owner, statement);
            }
        }
    }

    private SqlConnector connector(Transport transport, int poolSize) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(poolSize).build().unwrap();
        var builder = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                      .withUsername(RUNNER_ROLE)
                                                      .withPassword(RUNNER_PASSWORD)
                                                      .withPoolConfig(pool);
        var hostPort = postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + database;
        var connector = switch (transport) {
            case JDBC -> new JdbcSqlConnectorFactory().provision(builder.withJdbcUrl(jdbcUrl(database)).build().unwrap()).await().unwrap();
            case R2DBC -> new R2dbcSqlConnectorFactory().provision(builder.withR2dbcUrl("r2dbc:postgresql://" + hostPort).build().unwrap())
                                                        .await()
                                                        .unwrap();
            case ASYNC -> new AsyncSqlConnectorFactory().provision(builder.withAsyncUrl("postgresql://" + hostPort).build().unwrap())
                                                        .await()
                                                        .unwrap();
        };

        opened.add(connector);

        return connector;
    }

    private void seedInProgress(SqlConnector connector, MigrationScript script, int statements) {
        var repository = SchemaHistoryRepository.schemaHistoryRepository();
        var seed = appliedMigration(1, MigrationType.VERSIONED, "seed", script.filename(), script.checksum(), "seed", System.currentTimeMillis(), 0);

        repository.bootstrap(connector).await().unwrap();
        repository.recordInProgress(connector, seed, statements).await().unwrap();
    }

    /// Records the `application_name` of every session that writes a history row, so a test can prove the writes
    /// came from the fenced runner session and never from a pooled connection.
    private void installHistoryWriterAudit(SqlConnector connector) throws SQLException {
        SchemaHistoryRepository.schemaHistoryRepository().bootstrap(connector).await().unwrap();

        try (var owner = DriverManager.getConnection(jdbcUrl(database), RUNNER_ROLE, RUNNER_PASSWORD)) {
            execute(owner, "CREATE TABLE history_writers (app TEXT)");
            execute(owner, """
                CREATE FUNCTION audit_history_writer() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN
                    INSERT INTO history_writers VALUES (current_setting('application_name'));
                    RETURN NULL;
                END $$""");
            execute(owner, "CREATE TRIGGER history_writer_audit AFTER INSERT OR UPDATE OR DELETE ON aether_schema_history "
                           + "FOR EACH ROW EXECUTE FUNCTION audit_history_writer()");
        }
    }

    private void assertHistoryWritesCameFromFencedSessions() throws SQLException {
        assertThat(scalar("SELECT count(*) FROM history_writers")).as("positive control: history writes happened").isPositive();
        assertThat(scalar("SELECT count(*) FROM history_writers WHERE app NOT LIKE 'aether-mig:%'"))
            .as("every history write runs on the fenced runner session").isZero();
    }

    private void assertNoFenceRemains(SqlConnector connector) throws Exception {
        awaitTrue("SELECT count(*) = 0 FROM pg_stat_activity WHERE datname = current_database() AND application_name LIKE 'aether-mig:%'");
        assertThat(scalar("SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = " + FENCE_CLASS + " AND objid = " + FENCE_OBJECT))
            .as("no session, pooled or not, may still hold the fence").isZero();
        assertThat(connector.queryOne("SELECT count(*)::int AS n FROM pg_locks WHERE locktype = 'advisory' AND pid = pg_backend_pid()",
                                      row -> row.getInt("n"))
                            .await()
                            .unwrap()).as("a pooled connection holds no advisory lock").isZero();
    }

    /// Leaves an INVALID index `index` on `table`: its concurrent build waits on an open writer and is cancelled.
    private void leaveInvalidIndex(String table, String index) throws SQLException {
        var writer = superuserOnTestDb();
        var builder = superuserOnTestDb();

        writer.setAutoCommit(false);
        execute(writer, "INSERT INTO " + table + " VALUES (2)");
        execute(builder, "SET statement_timeout = 1000");

        try {
            execute(builder, "CREATE INDEX CONCURRENTLY " + index + " ON " + table + " (c)");
        } catch (SQLException expected) {
            // cancelled by the 1 s statement_timeout, which is what leaves the index INVALID
        }

        writer.rollback();
        assertThat(scalar("SELECT count(*) FROM pg_index i JOIN pg_class c ON c.oid = i.indexrelid WHERE c.relname = '" + index + "' AND NOT i.indisvalid"))
            .as("precondition: an INVALID " + index + " exists").isEqualTo(1);
    }

    private void setRoleDefault(String setting, String value) throws SQLException {
        try (var admin = superuserOnTestDbScoped()) {
            execute(admin, "ALTER ROLE " + RUNNER_ROLE + " IN DATABASE " + database + " SET " + setting + " = '" + value + "'");
        }
    }

    private static String shownOnPinnedSession(SqlConnector connector, Duration bound, String setting) {
        return connector.pinned(bound,
                                session -> session.queryOne("SHOW " + setting, row -> row.getString(setting)),
                                _ -> Promise.unitPromise())
                        .await()
                        .unwrap();
    }

    private Connection holdGate() throws SQLException {
        var gate = superuserOnTestDb();

        gate.setAutoCommit(false);
        execute(gate, "LOCK TABLE gate IN ACCESS EXCLUSIVE MODE");

        return gate;
    }

    private int awaitRunnerWaitingOnLock(String node) throws Exception {
        var sql = "SELECT pid FROM pg_stat_activity WHERE datname = current_database() AND application_name = 'aether-mig:" + node
                  + "' AND wait_event_type = 'Lock'";
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            var pids = pids(sql);

            if (!pids.isEmpty()) {
                return pids.getFirst();
            }

            Thread.sleep(25);
        }

        throw new AssertionError("runner " + node + " never reached the lock wait");
    }

    private void terminate(int pid) throws SQLException {
        try (var admin = superuserOnTestDbScoped()) {
            execute(admin, "SELECT pg_terminate_backend(" + pid + ")");
        }
    }

    private void awaitSessionGone(int pid) throws Exception {
        awaitTrue("SELECT count(*) = 0 FROM pg_stat_activity WHERE pid = " + pid);
    }

    private void awaitTrue(String sql) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (bool(sql)) {
                return;
            }

            Thread.sleep(25);
        }

        throw new AssertionError("condition never held: " + sql);
    }

    private long entries() throws SQLException {
        return scalar("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM entries");
    }

    private long historyRows(int version) throws SQLException {
        return scalar("SELECT count(*) FROM aether_schema_history WHERE version = " + version);
    }

    private long scalar(String sql) throws SQLException {
        try (var conn = superuserOnTestDbScoped(); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private boolean bool(String sql) throws SQLException {
        try (var conn = superuserOnTestDbScoped(); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getBoolean(1);
        }
    }

    private List<Integer> pids(String sql) throws SQLException {
        try (var conn = superuserOnTestDbScoped(); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            var out = new ArrayList<Integer>();

            while (rs.next()) {
                out.add(rs.getInt(1));
            }

            return out;
        }
    }

    private Connection superuserOnTestDb() throws SQLException {
        var conn = superuserOnTestDbScoped();

        held.add(conn);

        return conn;
    }

    private Connection superuserOnTestDbScoped() throws SQLException {
        return superuser(database);
    }

    private static Connection superuser(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static String jdbcUrl(String db) {
        return "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/" + db;
    }

    private static void execute(Connection conn, String sql) throws SQLException {
        try (var statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // the test's own holder connection; closing it is cleanup only
        }
    }
}
