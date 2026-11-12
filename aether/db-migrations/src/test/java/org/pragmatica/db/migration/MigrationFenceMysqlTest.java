// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;
import org.pragmatica.db.migration.ParsedMigration.MigrationType;
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
import org.testcontainers.containers.MySQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration.appliedMigration;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a (#2591): the tier-1 MySQL migration fence (`GET_LOCK` on a 51-character name derived from the schema),
/// against a LIVE MySQL 8.0 through the PRODUCTION Hikari JDBC connector. Untagged: it runs in required CI (ruling Q4).
/// The lock name is computed HERE independently of the production code, so a wrong name in production is a red, not
/// a shared mistake. Self-skips without Docker unless `-Daether.requireDocker=true`.
@Timeout(value = 180, unit = TimeUnit.SECONDS)
class MigrationFenceMysqlTest {
    private static final String OWNER = "fence-test-blueprint";
    private static final String DATASOURCE = "fenced";
    private static final TimeSpan GENEROUS = timeSpan(60).seconds();
    private static final long WAIT_MS = 30_000;
    private static final AtomicInteger DATABASES = new AtomicInteger();
    private static MySQLContainer<?> mysql;

    private final List<SqlConnector> opened = new ArrayList<>();
    private final List<Connection> held = new ArrayList<>();
    private final ExecutorService runners = Executors.newCachedThreadPool();
    private String database;

    @BeforeAll
    static void startMysql() {
        assumeTrue(DockerRequirement.dockerAvailable(), "Docker is required for the live-MySQL migration fence");
        mysql = new MySQLContainer<>("mysql:8.0").withCommand("--skip-log-bin");
        mysql.start();
    }

    @AfterAll
    static void stopMysql() {
        if (mysql != null) {
            mysql.stop();
        }
    }

    @AfterEach
    void cleanUp() throws SQLException {
        runners.shutdownNow();
        held.forEach(MigrationFenceMysqlTest::closeQuietly);
        held.clear();
        opened.forEach(connector -> connector.stop().await());
        opened.clear();

        if (database != null) {
            try (var admin = root("")) {
                execute(admin, "DROP DATABASE IF EXISTS " + database);
            }
        }
    }

    /// T1 (MySQL): while another connection holds the schema's `GET_LOCK` fence, a migration returns
    /// `MigrationFencedInDatabase` naming that connection, runs nothing (a MyISAM counter ignores rollback) and creates
    /// no history table.
    @Test
    void migrate_whileAnotherSessionHoldsTheFence_returnsFencedAndRunsNothing() throws Exception {
        freshDatabase("CREATE TABLE entry_log (id INT) ENGINE = MyISAM");
        var holder = rootOnTestDb();
        var holderId = scalarOn(holder, "SELECT CONNECTION_ID()");

        assertThat(scalarOn(holder, "SELECT GET_LOCK('" + lockName() + "', 0)")).as("precondition: the test holds the fence").isEqualTo(1);

        var result = migrate(connector(2), "node-b", script(1, "t1", "INSERT INTO entry_log VALUES (1);\n"));

        assertThat(causeOf(result)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
        assertThat(causeOf(result).message()).contains("id=" + holderId);
        assertThat(scalar("SELECT count(*) FROM entry_log")).isZero();
        assertThat(scalar("SELECT count(*) FROM information_schema.tables WHERE table_schema = DATABASE() AND table_name = 'aether_schema_history'"))
            .isZero();
    }

    /// T5b (MySQL, H3): the DML statement and its checkpoint commit in ONE transaction. A resumes from a seeded
    /// checkpoint 0 and parks on its INSERT (the test holds the empty table's gap lock); the test then takes the history
    /// row FOR UPDATE and opens the gate, so A's INSERT lands and its checkpoint UPDATE blocks on that row; A is killed
    /// there; B re-runs the INSERT once: row count 1. (The history row cannot be locked before A starts: A's bootstrap
    /// `CREATE TABLE IF NOT EXISTS` needs the table's exclusive metadata lock, which an open FOR UPDATE would withhold.)
    @Test
    void migrate_holderDiesBetweenDmlAndItsCheckpoint_successorReRunsTheDmlOnce() throws Exception {
        freshDatabase("CREATE TABLE keyless (v INT) ENGINE = InnoDB");
        var connector = connector(2);
        var script = script(1, "t5b", "INSERT INTO keyless VALUES (1);\nCREATE TABLE t5b_done (id INT);\n");
        var repository = SchemaHistoryRepository.schemaHistoryRepository();

        repository.bootstrap(connector).await().unwrap();
        repository.recordInProgress(connector,
                                    appliedMigration(1, MigrationType.VERSIONED, "seed", script.filename(), script.checksum(), "seed", System.currentTimeMillis(), 0),
                                    2)
                  .await()
                  .unwrap();

        var gate = rootOnTestDb();

        gate.setAutoCommit(false);
        execute(gate, "SELECT * FROM keyless FOR UPDATE");

        var a = runners.submit(() -> migrate(connector, "node-a", script));

        awaitFenceHolderLockWait("INSERT INTO keyless", a);

        var rowLock = rootOnTestDb();

        rowLock.setAutoCommit(false);
        execute(rowLock, "SELECT * FROM aether_schema_history WHERE version = 1 FOR UPDATE");
        gate.commit();

        var aId = awaitFenceHolderLockWait("UPDATE aether_schema_history", a);

        try (var killer = rootOnTestDbScoped()) {
            execute(killer, "KILL " + aId);
        }

        assertThat(a.get(WAIT_MS, TimeUnit.MILLISECONDS).isFailure()).isTrue();
        awaitTrue("SELECT IS_USED_LOCK('" + lockName() + "') IS NULL");
        rowLock.rollback();

        var b = migrate(connector, "node-b", script);

        assertThat(b.isSuccess()).as("B completes: %s", b).isTrue();
        assertThat(scalar("SELECT count(*) FROM keyless")).as("the DML and its checkpoint commit atomically").isEqualTo(1);
    }

    /// T7 (MySQL): no fence outlives the run, on success and on a failing script; positive control: every history write
    /// came from the connection that held the fence at that moment.
    @Test
    void migrate_leavesNoFenceBehind_onSuccessAndFailure() throws Exception {
        freshDatabase("CREATE TABLE ok (id INT)");
        var connector = connector(2);

        SchemaHistoryRepository.schemaHistoryRepository().bootstrap(connector).await().unwrap();
        installHistoryWriterAudit();

        assertThat(migrate(connector, "node-a", script(1, "ok", "INSERT INTO ok VALUES (1);\n")).isSuccess()).isTrue();
        assertThat(scalar("SELECT IS_USED_LOCK('" + lockName() + "') IS NULL")).isEqualTo(1);

        assertThat(migrate(connector, "node-a", script(2, "bad", "INSERT INTO missing_table VALUES (1);\n")).isFailure()).isTrue();
        assertThat(scalar("SELECT IS_USED_LOCK('" + lockName() + "') IS NULL")).isEqualTo(1);

        assertThat(scalar("SELECT count(*) FROM history_writers")).as("positive control: history writes happened").isPositive();
        assertThat(scalar("SELECT count(*) FROM history_writers WHERE fenced = 0")).as("every history write ran under the fence").isZero();
    }

    /// T9 (MySQL): four runners on an EMPTY schema: exactly one meta row and one owner row; each runner succeeded or
    /// was fenced.
    @Test
    void migrate_concurrentFirstBootstrap_writesExactlyOneMetaAndOneOwnerRow() throws Exception {
        freshDatabase("CREATE TABLE entry_log (id INT) ENGINE = MyISAM");
        var connector = connector(8);
        var start = new CountDownLatch(1);
        var script = script(1, "t9", "INSERT INTO entry_log VALUES (1);\n");
        var futures = new ArrayList<Future<Result<SchemaResult>>>();

        for (var i = 0; i < 4; i++) {
            var node = "node-" + i;
            futures.add(runners.submit(() -> awaitThenMigrate(start, connector, node, script)));
        }

        start.countDown();

        for (var future : futures) {
            future.get(WAIT_MS, TimeUnit.MILLISECONDS)
                  .onFailure(cause -> assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class));
        }

        assertThat(scalar("SELECT count(*) FROM aether_schema_history_meta")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM aether_schema_owner")).isEqualTo(1);
        assertThat(scalar("SELECT count(*) FROM entry_log")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- fixtures

    /// The design's name (F6): `aether_mig:` + the first 40 hex of SHA-256(lower(schema)) = 51 characters.
    private String lockName() throws Exception {
        var digest = MessageDigest.getInstance("SHA-256").digest(database.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
        var name = "aether_mig:" + HexFormat.of().formatHex(digest).substring(0, 40);

        assertThat(name).hasSize(51);

        return name;
    }

    private static MigrationScript script(int version, String description, String sql) {
        return MigrationScript.migrationScript("V" + version + "__" + description + ".sql", sql, sql.hashCode());
    }

    private Result<SchemaResult> migrate(SqlConnector connector, String node, MigrationScript script) {
        return SchemaMigrations.schemaMigrations(GENEROUS).migrate(DATASOURCE, List.of(script), connector, node, OWNER).await();
    }

    private Result<SchemaResult> awaitThenMigrate(CountDownLatch start, SqlConnector connector, String node, MigrationScript script)
        throws InterruptedException {
        start.await();
        return migrate(connector, node, script);
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    private void freshDatabase(String... setup) throws SQLException {
        database = "fence_" + DATABASES.incrementAndGet();

        try (var admin = root("")) {
            execute(admin, "CREATE DATABASE " + database);
        }

        try (var conn = rootOnTestDbScoped()) {
            for (var statement : setup) {
                execute(conn, statement);
            }
        }
    }

    private SqlConnector connector(int poolSize) {
        var pool = PoolConfig.poolConfigBuilder().withMinConnections(0).withMaxConnections(poolSize).build().unwrap();
        var config = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                     .withJdbcUrl(jdbcUrl(database))
                                                     .withUsername("root")
                                                     .withPassword(mysql.getPassword())
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();
        var connector = new JdbcSqlConnectorFactory().provision(config).await().unwrap();

        opened.add(connector);

        return connector;
    }

    private void installHistoryWriterAudit() throws Exception {
        try (var conn = rootOnTestDbScoped()) {
            execute(conn, "CREATE TABLE history_writers (fenced INT)");

            for (var event : List.of("INSERT", "UPDATE", "DELETE")) {
                execute(conn, "CREATE TRIGGER history_audit_" + event.toLowerCase(Locale.ROOT) + " AFTER " + event
                              + " ON aether_schema_history FOR EACH ROW INSERT INTO history_writers VALUES "
                              + "(COALESCE(IS_USED_LOCK('" + lockName() + "') = CONNECTION_ID(), 0))");
            }
        }
    }

    /// Waits until the fence holder's connection is parked on a statement starting with `statement`. Each statement
    /// the test waits on is blocked by a lock the test holds, so seeing it as the connection's current statement means
    /// it is waiting there (a premature match could only make the test fail, never pass).
    private long awaitFenceHolderLockWait(String statement, Future<Result<SchemaResult>> runner) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;
        var sql = "SELECT p.ID FROM information_schema.PROCESSLIST p WHERE p.ID = IS_USED_LOCK('" + lockName() + "') AND p.INFO LIKE '"
                  + statement + "%'";

        while (System.currentTimeMillis() < deadline) {
            try (var conn = rootOnTestDbScoped(); var query = conn.createStatement(); var rs = query.executeQuery(sql)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }

            Thread.sleep(25);
        }

        throw new AssertionError("the fence holder never reached a lock wait on: " + statement + "; runner: "
                                 + (runner.isDone() ? runner.get() : "still running") + "; transactions: "
                                 + rows("SELECT CONCAT(trx_mysql_thread_id, ' ', trx_state, ' ', COALESCE(trx_query, '-')) FROM information_schema.innodb_trx")
                                 + "; fence holder: " + rows("SELECT COALESCE(IS_USED_LOCK('" + lockName() + "'), -1)")
                                 + "; processes: " + rows("SELECT CONCAT(ID, ' ', COALESCE(STATE, '-'), ' ', COALESCE(INFO, '-')) FROM information_schema.PROCESSLIST"));
    }

    private void awaitTrue(String sql) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (scalar(sql) == 1) {
                return;
            }

            Thread.sleep(25);
        }

        throw new AssertionError("condition never held: " + sql);
    }

    private List<String> rows(String sql) throws SQLException {
        try (var conn = rootOnTestDbScoped(); var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            var out = new ArrayList<String>();

            while (rs.next()) {
                out.add(rs.getString(1));
            }

            return out;
        }
    }

    private long scalar(String sql) throws SQLException {
        try (var conn = rootOnTestDbScoped()) {
            return scalarOn(conn, sql);
        }
    }

    private static long scalarOn(Connection conn, String sql) throws SQLException {
        try (var statement = conn.createStatement(); var rs = statement.executeQuery(sql)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private Connection rootOnTestDb() throws SQLException {
        var conn = rootOnTestDbScoped();

        held.add(conn);

        return conn;
    }

    private Connection rootOnTestDbScoped() throws SQLException {
        return root(database);
    }

    private static Connection root(String db) throws SQLException {
        return DriverManager.getConnection(jdbcUrl(db), "root", mysql.getPassword());
    }

    private static String jdbcUrl(String db) {
        return "jdbc:mysql://" + mysql.getHost() + ":" + mysql.getMappedPort(3306) + "/" + db;
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
