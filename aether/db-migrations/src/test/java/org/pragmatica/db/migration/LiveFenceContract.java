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
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;
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
    final ExecutorService runners = Executors.newCachedThreadPool();

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

    /// The session-owned lock survives the holder's own commits and excludes a contender for as long as the holder's
    /// session lives (Oracle `release_on_commit => FALSE`, SQL Server `@LockOwner = 'Session'`): the holder commits a
    /// row after taking the fence, and a second session is STILL refused; once the holder's scope ends, it is free.
    @Test
    void fence_excludesAContenderAcrossTheHoldersCommits_andFreesWhenTheScopeEnds() throws Exception {
        freshSchema();

        try (var setup = rawConnection()) {
            execute(setup, "CREATE TABLE commit_probe (id INT)");
        }

        var hold = Promise.<Unit>promise();
        var acquired = new CountDownLatch(1);
        var holder = runners.submit(() -> holdTheFence(connector(1), "node-a", hold, acquired).await());

        assertThat(acquired.await(WAIT_MS, TimeUnit.MILLISECONDS)).as("the holder took the fence and committed").isTrue();

        var contender = tryTheFence(connector(1), "node-b").await();

        assertThat(causeOf(contender)).as("a contender while the holder lives").isInstanceOf(MigrationError.MigrationFencedInDatabase.class);

        hold.succeed(Unit.unit());

        assertThat(holder.get(WAIT_MS, TimeUnit.MILLISECONDS).isSuccess()).isTrue();
        assertThat(tryTheFence(connector(1), "node-b").await().isSuccess()).as("free after the holder's scope ended").isTrue();
    }

    /// A holder whose body FAILS releases the fence all the same (the close is the release).
    @Test
    void fence_isFreedWhenTheHoldersBodyFails() {
        freshSchemaQuietly();

        var failed = connector(1).pinned(GENEROUS.duration(),
                                         session -> MigrationFence.acquire(session, DATASOURCE, "node-a")
                                                                  .flatMap(_ -> Causes.cause("boom").<Unit>promise()),
                                         session -> Promise.unitPromise())
                                 .await();

        assertThat(failed.isFailure()).isTrue();
        assertThat(tryTheFence(connector(1), "node-b").await().isSuccess()).as("free after a failing holder").isTrue();
    }

    private static Promise<Unit> holdTheFence(SqlConnector connector, String node, Promise<Unit> hold, CountDownLatch acquired) {
        return connector.pinned(GENEROUS.duration(),
                                session -> MigrationFence.acquire(session, DATASOURCE, node)
                                                         .flatMap(_ -> session.update("INSERT INTO commit_probe VALUES (1)"))
                                                         .flatMap(_ -> signalThenWait(acquired, hold)),
                                session -> Promise.unitPromise());
    }

    private static Promise<Unit> signalThenWait(CountDownLatch acquired, Promise<Unit> hold) {
        acquired.countDown();
        return hold;
    }

    private static Promise<Unit> tryTheFence(SqlConnector connector, String node) {
        return connector.pinned(GENEROUS.duration(),
                                session -> MigrationFence.acquire(session, DATASOURCE, node).mapToUnit(),
                                session -> Promise.unitPromise());
    }

    private void freshSchemaQuietly() {
        try {
            freshSchema();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
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

    static Result<SchemaResult> awaitThenMigrate(CountDownLatch start, SqlConnector connector, String node, MigrationScript script)
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
