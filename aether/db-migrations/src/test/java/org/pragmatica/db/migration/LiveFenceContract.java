// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.io.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2b fix round (#2591, review A1): the tier-2 migration fence against a LIVE engine through the PRODUCTION Hikari
/// JDBC connector. One contract, run by [MigrationFenceSqlServerTest] and [MigrationFenceOracleTest]; each subclass
/// supplies the engine, an independent raw statement that takes the SAME session-owned lock (written in the test, so a
/// wrong lock name or mode in production is a red, not a shared mistake), and a blocked-session probe. Tagged
/// `heavy-db` in the subclasses: excluded from the default build, run with
/// `-Dsurefire.excludedGroups= -Dgroups=heavy-db`.
@Timeout(value = 600, unit = TimeUnit.SECONDS)
abstract class LiveFenceContract {
    static final String OWNER = "fence-live-blueprint";
    static final String DATASOURCE = "fenced";
    static final TimeSpan GENEROUS = timeSpan(120).seconds();
    static final long WAIT_MS = 120_000;
    static final String APPLY_SCRIPT = "CREATE TABLE entries (id INT);\nINSERT INTO entries VALUES (1);\n";

    private final List<SqlConnector> opened = new ArrayList<>();
    private final List<Connection> held = new ArrayList<>();
    private final ExecutorService runners = Executors.newCachedThreadPool();

    /// Creates a fresh, isolated schema (database or user) for this test; every later call addresses it.
    abstract void freshSchema() throws SQLException;

    abstract SqlConnector newConnector(int poolSize);

    /// A raw connection to the fresh schema, as the migration user. The contract closes it.
    abstract Connection rawConnection() throws SQLException;

    /// Takes the migration fence's lock on `session` with the engine's own raw statement; false when someone else holds it.
    abstract boolean tryHoldFence(Connection session) throws SQLException;

    /// How many sessions the engine currently reports as blocked on another session's lock.
    abstract long blockedSessions() throws SQLException;

    /// Statement text counting the rows of `table` in the fresh schema (used through [#rawConnection]).
    String countOf(String table) {
        return "SELECT COUNT(*) FROM " + table;
    }

    @AfterEach
    void cleanUpContract() {
        runners.shutdownNow();
        held.forEach(LiveFenceContract::closeQuietly);
        held.clear();
        opened.forEach(connector -> connector.stop().await());
        opened.clear();
    }

    /// While another session holds the fence, a migration returns `MigrationFencedInDatabase` and runs nothing.
    @Test
    void migrate_whileAnotherSessionHoldsTheFence_returnsFencedAndRunsNothing() throws Exception {
        freshSchema();
        var holder = held(rawConnection());

        assertThat(tryHoldFence(holder)).as("precondition: the test holds the fence").isTrue();

        var result = migrate(connector(2), "node-b", script(1, "t1", APPLY_SCRIPT));

        assertThat(causeOf(result)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);

        try (var probe = rawConnection()) {
            assertThat(tableExists(probe, "entries")).as("the fenced run created nothing").isFalse();
        }
    }

    /// No fence outlives the run: after a success and after a failing script another session can take it.
    @Test
    void migrate_leavesNoFenceBehind_onSuccessAndOnFailure() throws Exception {
        freshSchema();
        var connector = connector(2);

        assertThat(migrate(connector, "node-a", script(1, "ok", APPLY_SCRIPT)).isSuccess()).isTrue();
        assertThat(canTakeAndDropFence()).as("fence free after a successful run").isTrue();

        assertThat(migrate(connector, "node-a", script(2, "bad", "INSERT INTO missing_table VALUES (1);\n")).isFailure()).isTrue();
        assertThat(canTakeAndDropFence()).as("fence free after a failing run").isTrue();
    }

    /// Two concurrent migrators on a fresh schema: the script is applied exactly once; the other runner is either
    /// fenced or, arriving after the first finished, skips what is applied. Never a double apply, never another failure.
    @Test
    void migrate_twoConcurrentRunners_applyTheScriptExactlyOnce() throws Exception {
        freshSchema();
        var first = connector(2);
        var second = connector(2);
        var start = new CountDownLatch(1);
        var script = script(1, "t3", APPLY_SCRIPT);
        var a = runners.submit(() -> awaitThenMigrate(start, first, "node-a", script));
        var b = runners.submit(() -> awaitThenMigrate(start, second, "node-b", script));

        start.countDown();

        var results = List.of(a.get(WAIT_MS, TimeUnit.MILLISECONDS), b.get(WAIT_MS, TimeUnit.MILLISECONDS));

        results.forEach(result -> result.onFailure(cause -> assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class)));
        assertThat(results.stream().filter(Result::isSuccess).count()).as("at least one runner completed: %s", results).isPositive();

        try (var probe = rawConnection()) {
            assertThat(count(probe, "entries")).as("the script ran once").isEqualTo(1);
            assertThat(count(probe, "aether_schema_history")).as("one history row").isEqualTo(1);
        }
    }

    /// The fence survives the runner's own commits (Oracle `release_on_commit => FALSE`, SQL Server `@LockOwner =
    /// 'Session'`): a runner parked inside its script, after the bootstrap and history commits, still excludes a
    /// competitor. The test parks it on a row lock it holds itself.
    @Test
    void migrate_fenceSurvivesTheRunnersOwnCommits_competitorIsFencedMidRun() throws Exception {
        freshSchema();

        var blocker = held(rawConnection());

        blocker.setAutoCommit(true);
        execute(blocker, "CREATE TABLE gate (v INT)");
        execute(blocker, "INSERT INTO gate VALUES (1)");
        blocker.setAutoCommit(false);
        execute(blocker, "UPDATE gate SET v = 1");

        var a = runners.submit(() -> migrate(connector(2), "node-a", script(1, "t4", "UPDATE gate SET v = 2;\n")));

        awaitBlocked(a);

        var competitor = migrate(connector(2), "node-b", script(1, "t4", "UPDATE gate SET v = 2;\n"));

        assertThat(causeOf(competitor)).as("a competitor mid-run").isInstanceOf(MigrationError.MigrationFencedInDatabase.class);

        blocker.rollback();

        var finished = a.get(WAIT_MS, TimeUnit.MILLISECONDS);

        assertThat(finished.isSuccess()).as("the parked runner completes: %s", finished).isTrue();
        assertThat(canTakeAndDropFence()).as("fence free after the parked run").isTrue();
    }

    private boolean canTakeAndDropFence() throws SQLException {
        try (var session = rawConnection()) {
            return tryHoldFence(session);
        }
    }

    private void awaitBlocked(Future<Result<SchemaResult>> runner) throws Exception {
        var deadline = System.currentTimeMillis() + WAIT_MS;

        while (System.currentTimeMillis() < deadline) {
            if (blockedSessions() > 0) {
                return;
            }

            Thread.sleep(100);
        }

        throw new AssertionError("the runner never blocked on the test's row lock; runner: " + (runner.isDone()
                                                                                              ? runner.get()
                                                                                              : "still running"));
    }

    final SqlConnector connector(int poolSize) {
        var connector = newConnector(poolSize);

        opened.add(connector);

        return connector;
    }

    final Connection held(Connection connection) {
        held.add(connection);
        return connection;
    }

    static MigrationScript script(int version, String description, String sql) {
        return MigrationScript.migrationScript("V" + version + "__" + description + ".sql", sql, sql.hashCode());
    }

    static Result<SchemaResult> migrate(SqlConnector connector, String node, MigrationScript script) {
        return SchemaMigrations.schemaMigrations(GENEROUS).migrate(DATASOURCE, List.of(script), connector, node, OWNER).await();
    }

    private static Result<SchemaResult> awaitThenMigrate(CountDownLatch start, SqlConnector connector, String node, MigrationScript script)
        throws InterruptedException {
        start.await();
        return migrate(connector, node, script);
    }

    static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    boolean tableExists(Connection conn, String table) {
        try {
            count(conn, table);
            return true;
        } catch (SQLException missing) {
            return false;
        }
    }

    long count(Connection conn, String table) throws SQLException {
        try (var statement = conn.createStatement(); var rs = statement.executeQuery(countOf(table))) {
            rs.next();
            return rs.getLong(1);
        }
    }

    static void execute(Connection conn, String sql) throws SQLException {
        try (var statement = conn.createStatement()) {
            statement.execute(sql);
        }
    }

    static void closeQuietly(Connection conn) {
        try {
            conn.close();
        } catch (SQLException ignored) {
            // the test's own holder connection; closing it is cleanup only
        }
    }
}
