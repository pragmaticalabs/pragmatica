// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;
import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.pragmatica.testing.DockerRequirement;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.images.builder.Transferable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a fix round 1 (#2591, verifier finding A1; CTO ruling: transaction-mode poolers are UNSUPPORTED and refused):
/// the migration fence through a transaction-mode PgBouncer with ONE server connection, so every client shares one
/// server session. Both fence locks are re-entrant: before the fix a second runner on the holder's server session was
/// granted the fence again, its `verifyHeld` agreed, and a DML statement of a script ran twice. The runner now refuses a
/// server session that already holds the fence (`FenceUnavailable`), and a refused runner never runs the courtesy
/// unlock, which on the shared session would drop the holder's lock.
///
/// The holder is a plain JDBC session through the pooler that takes the fence with the runner's own grant
/// (`pg_try_advisory_lock(0x41455448, 1)`), or a real runner. Lock state is read on the server directly (`pg_locks`).
/// Self-skips without Docker unless `CI_REQUIRE_DOCKER=1`.
@Timeout(value = 300, unit = TimeUnit.SECONDS)
class MigrationFencePoolerTest {
    private static final String BOUNCER_IMAGE = "edoburu/pgbouncer:v1.26.1-p0";
    private static final int FENCE_CLASS = 0x41455448;
    private static final int FENCE_OBJECT = 1;
    private static final String RUNNER_ROLE = "migrator";
    private static final String RUNNER_PASSWORD = "migrator";
    private static final String OWNER = "pooler-test-blueprint";
    private static final String DATASOURCE = "pooled";
    private static final TimeSpan GENEROUS = timeSpan(60).seconds();
    private static final long WAIT_MS = 60_000;
    private static final String FENCE_LOCKS = "SELECT count(*) FROM pg_locks WHERE locktype = 'advisory' AND classid = " + FENCE_CLASS
                                              + " AND objid = " + FENCE_OBJECT + " AND granted";
    private static final String ENTRY = "INSERT INTO entry_log VALUES (nextval('entries'));\n";
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static PostgreSQLContainer<?> postgres;
    private static GenericContainer<?> bouncer;

    private final List<SqlConnector> opened = new ArrayList<>();
    private final List<Connection> held = new ArrayList<>();
    private final ExecutorService runners = Executors.newCachedThreadPool();
    private String database;

    @BeforeAll
    static void startPostgresBehindAPooler() throws SQLException {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the PgBouncer migration fence tests");
        postgres = new PostgreSQLContainer<>("postgres:16");
        postgres.start();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE ROLE " + RUNNER_ROLE + " LOGIN PASSWORD '" + RUNNER_PASSWORD + "'");
        }

        var ini = """
            [databases]
            * = host=%s port=5432
            [pgbouncer]
            listen_addr = 0.0.0.0
            listen_port = 6432
            auth_type = scram-sha-256
            auth_file = /etc/pgbouncer/userlist.txt
            pool_mode = transaction
            default_pool_size = 1
            max_client_conn = 200
            ignore_startup_parameters = extra_float_digits,options,search_path,statement_timeout
            """.formatted(postgres.getContainerInfo().getNetworkSettings().getIpAddress());

        bouncer = new GenericContainer<>(BOUNCER_IMAGE).withExposedPorts(6432)
                                                       .withCopyToContainer(Transferable.of(ini), "/etc/pgbouncer/pgbouncer.ini")
                                                       .withCopyToContainer(Transferable.of("\"" + RUNNER_ROLE + "\" \"" + RUNNER_PASSWORD + "\"\n"),
                                                                            "/etc/pgbouncer/userlist.txt");
        bouncer.start();
    }

    @AfterAll
    static void stopContainers() {
        if (bouncer != null) {
            bouncer.stop();
        }

        if (postgres != null) {
            postgres.stop();
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        runners.shutdownNow();
        held.forEach(MigrationFencePoolerTest::closeQuietly);
        held.clear();
        opened.forEach(connector -> connector.stop().await());
        opened.clear();

        if (database != null) {
            try (var admin = superuser(postgres.getDatabaseName())) {
                execute(admin, "DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            }
        }
    }

    /// A1b: a session holds the fence through the pooler and sits idle between statements (as a runner does between
    /// its autocommit statements). A runner through the same pooler lands on the holder's server session. It is
    /// refused as a shared server session and runs nothing.
    @Test
    void migrate_onTheHoldersServerSessionThroughATransactionPooler_isRefusedAndRunsNothing() throws Exception {
        freshDatabase("CREATE SEQUENCE entries", "CREATE TABLE entry_log (id BIGINT)");
        holdTheFenceThroughThePooler();

        var result = migrate(pooledConnector(), "node-b", script(1, "b", ENTRY));

        assertThat(causeOf(result)).isInstanceOf(MigrationError.FenceUnavailable.class);
        assertThat(causeOf(result).message()).contains("already held the fence before taking it");
        assertThat(entries()).as("the refused runner ran nothing").isZero();
    }

    /// CTO ruling (fix round 1): the refused runner must NOT run `pg_advisory_unlock_all()` on the shared server
    /// session, which would drop the holder's lock and let a third runner in between the holder's writes. After the
    /// refusal the holder's lock is still granted, on the same backend.
    @Test
    void migrate_refusedOnTheHoldersServerSession_leavesTheHoldersLockHeld() throws Exception {
        freshDatabase("CREATE SEQUENCE entries", "CREATE TABLE entry_log (id BIGINT)");
        holdTheFenceThroughThePooler();
        var holderPid = scalar("SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid = " + FENCE_CLASS
                               + " AND objid = " + FENCE_OBJECT + " AND granted");

        var refused = migrate(pooledConnector(), "node-b", script(1, "b", ENTRY));

        assertThat(causeOf(refused)).isInstanceOf(MigrationError.FenceUnavailable.class);
        assertThat(scalar(FENCE_LOCKS)).as("the holder's lock survives the refused runner").isEqualTo(1);
        assertThat(scalar("SELECT pid FROM pg_locks WHERE locktype = 'advisory' AND classid = " + FENCE_CLASS
                          + " AND objid = " + FENCE_OBJECT + " AND granted"))
            .as("on the same server session").isEqualTo(holderPid);
    }

    /// A1c: two REAL runners through the same pooler; B starts once A's first INSERT has committed. B is refused, A is
    /// unharmed (its lock was never dropped under it) and completes, and no statement of the 6-INSERT script ran twice.
    @Test
    void migrate_twoRunnersThroughATransactionPooler_secondIsRefusedFirstCompletesNothingRunsTwice() throws Exception {
        freshDatabase("CREATE SEQUENCE entries", "CREATE TABLE entry_log (id BIGINT)", "CREATE TABLE t (c INT)");
        var sql = new StringBuilder("CREATE INDEX CONCURRENTLY IF NOT EXISTS t_c_idx ON t (c);\n");

        for (var i = 0; i < 6; i++) {
            sql.append(ENTRY).append("DO $$ BEGIN PERFORM pg_sleep(0.3); END $$;\n");
        }

        var script = script(1, "two", sql.toString());
        var connector = pooledConnector();
        var first = runners.submit(() -> migrate(connector, "node-a", script));

        awaitTrue("SELECT CASE WHEN is_called THEN last_value ELSE 0 END >= 1 FROM entries");

        var second = runners.submit(() -> migrate(connector, "node-b", script)).get(WAIT_MS, TimeUnit.MILLISECONDS);
        var firstResult = first.get(WAIT_MS, TimeUnit.MILLISECONDS);

        assertThat(causeOf(second)).isInstanceOf(MigrationError.FenceUnavailable.class);
        assertThat(firstResult.isSuccess()).as("the holder completes: %s", firstResult).isTrue();
        assertThat(entries()).as("each of the 6 INSERTs entered exactly once").isEqualTo(6);
    }

    // ---------------------------------------------------------------- fixtures

    private void holdTheFenceThroughThePooler() throws Exception {
        var holder = DriverManager.getConnection(bouncerJdbcUrl(), RUNNER_ROLE, RUNNER_PASSWORD);

        held.add(holder);

        try (var statement = holder.createStatement(); var rs = statement.executeQuery("SELECT pg_try_advisory_lock(" + FENCE_CLASS + ", " + FENCE_OBJECT + ")")) {
            rs.next();
            assertThat(rs.getBoolean(1)).as("the holder took the fence").isTrue();
        }

        assertThat(scalar(FENCE_LOCKS)).as("control: the fence is held on the server").isEqualTo(1);
    }

    private static MigrationScript script(int version, String description, String sql) {
        return MigrationScript.migrationScript("V" + version + "__" + description + ".sql", sql, sql.hashCode());
    }

    private Result<SchemaResult> migrate(SqlConnector connector, String node, MigrationScript script) {
        return SchemaMigrations.schemaMigrations(GENEROUS).migrate(DATASOURCE, List.of(script), connector, node, OWNER).await();
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    private void freshDatabase(String... setup) throws SQLException {
        database = "pooler_" + DATABASES.incrementAndGet();

        try (var admin = superuser(postgres.getDatabaseName())) {
            execute(admin, "CREATE DATABASE " + database + " OWNER " + RUNNER_ROLE);
        }

        try (var owner = DriverManager.getConnection(directJdbcUrl(database), RUNNER_ROLE, RUNNER_PASSWORD)) {
            for (var statement : setup) {
                execute(owner, statement);
            }
        }
    }

    private SqlConnector pooledConnector() {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(2).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                     .withUsername(RUNNER_ROLE)
                                                     .withPassword(RUNNER_PASSWORD)
                                                     .withPoolConfig(pool)
                                                     .withJdbcUrl(bouncerJdbcUrl())
                                                     .build()
                                                     .unwrap();
        var connector = new JdbcSqlConnectorFactory().provision(config).await().unwrap();

        opened.add(connector);

        return connector;
    }

    private String bouncerJdbcUrl() {
        return "jdbc:postgresql://" + bouncer.getHost() + ":" + bouncer.getMappedPort(6432) + "/" + database + "?prepareThreshold=0";
    }

    private long entries() throws SQLException {
        return scalar("SELECT CASE WHEN is_called THEN last_value ELSE 0 END FROM entries");
    }

    private void awaitTrue(String sql) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (scalar("SELECT CASE WHEN (" + sql + ") THEN 1 ELSE 0 END") == 1) {
                return;
            }

            Thread.sleep(25);
        }

        throw new AssertionError("condition never held: " + sql);
    }

    private long scalar(String sql) throws SQLException {
        try (var conn = superuser(database); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private static Connection superuser(String db) throws SQLException {
        return DriverManager.getConnection(directJdbcUrl(db), postgres.getUsername(), postgres.getPassword());
    }

    private static String directJdbcUrl(String db) {
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
