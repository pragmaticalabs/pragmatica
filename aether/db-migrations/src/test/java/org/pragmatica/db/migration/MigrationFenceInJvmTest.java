// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.resource.db.jdbc.JdbcSqlConnectorFactory;
import org.pragmatica.db.migration.ParsedMigration.MigrationType;
import org.pragmatica.db.migration.SchemaHistoryRepository.AppliedMigration;
import org.pragmatica.db.migration.SchemaMigrations.SchemaResult;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.parse.TimeSpan;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.aether.resource.db.DatabaseConnectorConfig.databaseConnectorConfigBuilder;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2b (#2591): the in-JVM proofs of the tiers that need no container. T12 pins H2's decision (DDL commits
/// implicitly, so its dialect is autocommit; `SET EXCLUSIVE 1` makes a second connect fail, so it is tier 1). T6, T8
/// and T8b run tier 3 on a REAL SQLite file (the lock, the in-transaction re-read, the non-transactional refusal, the
/// history-row collision) and the CockroachDB path on a recording wrapper over H2 (CockroachDB and DB2 themselves are
/// nightly heavy-db `[unverified engine]`).
@Timeout(120)
class MigrationFenceInJvmTest {
    private static final String DATASOURCE = "ds";
    private static final String OWNER = "bp";
    private static final String MARK_SCRIPT = "CREATE TABLE applied_marks (n INTEGER); INSERT INTO applied_marks VALUES (1);";

    @TempDir
    Path dir;

    private final List<SqlConnector> opened = new ArrayList<>();

    @AfterEach
    void stopConnectors() {
        opened.forEach(connector -> connector.stop().await());
    }

    // ---------------------------------------------------------------- T12 (H2)

    /// T12a: `CREATE TABLE` then `ROLLBACK` leaves the table, so H2's DDL is not transactional (dialect flag false).
    @Test
    void t12_h2CreateTableThenRollback_leavesTheTable() throws SQLException {
        try (var conn = DriverManager.getConnection("jdbc:h2:mem:t12a");
             var statement = conn.createStatement()) {
            conn.setAutoCommit(false);
            statement.execute("CREATE TABLE t12 (id INT)");
            conn.rollback();

            try (var rows = statement.executeQuery("SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'T12'")) {
                rows.next();
                assertThat(rows.getInt(1)).isEqualTo(1);
            }
        }

        assertThat(MigrationDialects.H2.ddlTransactional()).isFalse();
    }

    /// T12b: while a runner holds H2's exclusive mode a competing runner's connect is rejected (90135) and read as the
    /// fence being held; after the holder's session closes the competitor runs.
    @Test
    void t12_h2ExclusiveMode_rejectsTheCompetingConnectAsHeldThenReleases() {
        var url = "jdbc:h2:mem:t12b;DB_CLOSE_DELAY=-1";
        var holder = h2(url);
        var competitor = h2(url);

        var duringHold = holder.pinned(Duration.ofMinutes(1),
                                       session -> MigrationFence.acquire(session, DATASOURCE, "holder")
                                                                .flatMap(_ -> migrate(competitor, MARK_SCRIPT)),
                                       _ -> Promise.unitPromise())
                               .await();

        assertThat(causeOf(duringHold)).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
        assertThat(causeOf(duringHold).message()).contains("exclusive mode");
        assertThat(migrate(competitor, MARK_SCRIPT).await().isSuccess()).isTrue();
        assertThat(scalar(url, "SELECT COUNT(*) FROM applied_marks")).isEqualTo(1);
    }

    /// H2 runs its script on the fenced session in autocommit (checkpointed) and records its history row.
    @Test
    void t12_h2Migrate_runsFencedAndRecordsTheHistoryRow() {
        var url = "jdbc:h2:mem:t12c;DB_CLOSE_DELAY=-1";

        var result = migrate(h2(url), MARK_SCRIPT).await();

        assertThat(result.isSuccess()).isTrue();
        assertThat(scalar(url, "SELECT COUNT(*) FROM aether_schema_history WHERE status = 'SUCCESS'")).isEqualTo(1);
    }

    // ---------------------------------------------------------------- T6

    /// T6 (SQLite): a script that cannot run in a transaction is refused before its first statement.
    @Test
    void t6_sqliteScriptWithVacuum_isRefusedBeforeAnyStatement() {
        var file = dir.resolve("t6.db");
        var statements = new CopyOnWriteArrayList<String>();
        var probe = Probe.over(sqlite(file), DatabaseType.SQLITE, statements);

        var cause = causeOf(migrate(probe, "VACUUM;").await());

        assertThat(cause).isInstanceOf(MigrationError.NonTransactionalScriptUnfenced.class);
        assertThat(cause.message()).contains("SQLite").contains("VACUUM").contains("No statement was run");
        assertThat(statements).noneMatch(sql -> sql.startsWith("VACUUM"));
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM aether_schema_history")).isZero();
    }

    /// T6 (CockroachDB fake): `CREATE INDEX CONCURRENTLY` cannot run in a transaction, so it is refused, nothing run.
    @Test
    void t6_cockroachScriptWithConcurrentIndex_isRefusedBeforeAnyStatement() {
        var url = "jdbc:h2:mem:t6crdb;DB_CLOSE_DELAY=-1";
        var statements = new CopyOnWriteArrayList<String>();
        var probe = Probe.over(h2(url), DatabaseType.COCKROACHDB, statements);

        var cause = causeOf(migrate(probe, "CREATE INDEX CONCURRENTLY idx_a ON t_a (a);").await());

        assertThat(cause).isInstanceOf(MigrationError.NonTransactionalScriptUnfenced.class);
        assertThat(cause.message()).contains("CockroachDB").contains("CONCURRENTLY");
        assertThat(statements).noneMatch(sql -> sql.contains("CONCURRENTLY"));
    }

    // ---------------------------------------------------------------- T8

    /// T8 (SQLite): the second runner has already read an empty history when the first finishes; under its script lock
    /// it reads the row again and skips. Both complete, and the script ran once (M9: without the re-read the second
    /// run would repeat the script and fail).
    @Test
    void t8_sqliteSerializedPair_secondRunnerReReadsUnderTheLockAndSkips() throws Exception {
        var file = dir.resolve("t8.db");
        var reached = new CountDownLatch(1);
        var gate = new CountDownLatch(1);
        var second = Probe.over(sqlite(file),
                                DatabaseType.SQLITE,
                                new CopyOnWriteArrayList<>()).afterHistoryRead(() -> pause(reached, gate));
        var executor = Executors.newSingleThreadExecutor();

        try {
            Future<Result<SchemaResult>> secondRun = executor.submit(() -> migrate(second, MARK_SCRIPT).await());

            assertThat(reached.await(30, TimeUnit.SECONDS)).isTrue();
            var firstRun = migrate(sqlite(file), MARK_SCRIPT).await();
            gate.countDown();

            assertThat(firstRun.isSuccess()).isTrue();
            assertThat(secondRun.get(60, TimeUnit.SECONDS).isSuccess()).isTrue();
            assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM applied_marks")).isEqualTo(1);
            assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM aether_schema_history WHERE status = 'SUCCESS'")).isEqualTo(1);
        } finally {
            gate.countDown();
            executor.shutdownNow();
        }
    }

    /// T8 (order): the lock is the FIRST statement of the script's transaction, before the history re-read and before
    /// the script (mutant: lock removed or moved).
    @Test
    void t8_sqliteLock_isTheFirstStatementOfTheScriptTransaction() {
        var statements = new CopyOnWriteArrayList<String>();
        var probe = Probe.over(sqlite(dir.resolve("t8order.db")), DatabaseType.SQLITE, statements);

        assertThat(migrate(probe, MARK_SCRIPT).await().isSuccess()).isTrue();

        var lock = indexOf(statements, "UPDATE aether_schema_owner SET blueprint_base = blueprint_base");
        var reread = indexOf(statements, "SELECT status, statements_completed, checksum FROM aether_schema_history");
        var script = indexOf(statements, "CREATE TABLE applied_marks");

        assertThat(lock).isNotNegative();
        assertThat(reread).isGreaterThan(lock);
        assertThat(script).isGreaterThan(reread);
    }

    /// T8 (CockroachDB fake): the lock is `SELECT ... FOR UPDATE` on the owner row, first in the transaction.
    @Test
    void t8_cockroachLock_isForUpdateOnTheOwnerRowBeforeTheScript() {
        var statements = new CopyOnWriteArrayList<String>();
        var probe = Probe.over(h2("jdbc:h2:mem:t8crdb;DB_CLOSE_DELAY=-1"), DatabaseType.COCKROACHDB, statements);

        assertThat(migrate(probe, MARK_SCRIPT).await().isSuccess()).isTrue();

        var lock = indexOf(statements, "SELECT 1 FROM aether_schema_owner FOR UPDATE");

        assertThat(lock).isNotNegative();
        assertThat(indexOf(statements, "CREATE TABLE applied_marks")).isGreaterThan(lock);
    }

    /// T8 (bootstrap): on tier 3 the meta and owner inserts are `INSERT ... SELECT ... WHERE NOT EXISTS`.
    @Test
    void t8_tierThreeBootstrap_insertsOnlyWhenAbsent() {
        var statements = new CopyOnWriteArrayList<String>();
        var probe = Probe.over(sqlite(dir.resolve("t8boot.db")), DatabaseType.SQLITE, statements);

        assertThat(migrate(probe, MARK_SCRIPT).await().isSuccess()).isTrue();

        assertThat(statements).anyMatch(sql -> sql.startsWith("INSERT INTO aether_schema_history_meta") && sql.contains("WHERE NOT EXISTS"));
        assertThat(statements).anyMatch(sql -> sql.startsWith("INSERT INTO aether_schema_owner") && sql.contains("WHERE NOT EXISTS"));
    }

    /// T8 (failure): a statement failure inside a tier-3 script ends FAILED and rolls the script back; nothing
    /// converts it to a completion.
    @Test
    void t8_sqliteStatementFailure_endsFailedAndLeavesNoHistoryRow() {
        var file = dir.resolve("t8fail.db");

        var result = migrate(sqlite(file), "CREATE TABLE applied_marks (n INTEGER); INSERT INTO missing_table VALUES (1);").await();

        assertThat(result.isFailure()).isTrue();
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM aether_schema_history")).isZero();
    }

    // ---------------------------------------------------------------- T8b

    /// T8b (SQLite): a history row the applied-read does not show (`FAILED`) meets the script's insert: the run ends
    /// FAILED with [MigrationError.HistoryRowCollision] and the script's work is rolled back (M5: collision to success).
    @Test
    void t8b_sqliteHistoryRowCollision_endsFailedAndRollsTheScriptBack() throws SQLException {
        var file = dir.resolve("t8b.db");
        var connector = sqlite(file);
        SchemaHistoryRepository.schemaHistoryRepository().bootstrap(connector).await().unwrap();
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = conn.createStatement()) {
            statement.execute("INSERT INTO aether_schema_history (version, type, description, script, checksum, applied_by, applied_at, execution_ms, status, statements_completed)"
                              + " VALUES (1, 'VERSIONED', 'a', 'V1__a.sql', 1, 'other', 0, 0, 'FAILED', 0)");
        }

        var cause = causeOf(migrate(connector, MARK_SCRIPT).await());

        assertThat(cause).isInstanceOf(MigrationError.HistoryRowCollision.class);
        assertThat(cause.message()).contains("version 1").contains("VERSIONED");
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'applied_marks'")).isZero();
    }

    /// T8b (tier 1 repository path): a real primary-key violation from the engine is a collision, never a success.
    @Test
    void t8b_historyInsertMeetingAnExistingRow_isHistoryRowCollision() {
        var connector = h2("jdbc:h2:mem:t8btier1;DB_CLOSE_DELAY=-1");
        var repository = SchemaHistoryRepository.schemaHistoryRepository();
        var row = AppliedMigration.appliedMigration(1, MigrationType.VERSIONED, "a", "V1__a.sql", 1L, "node", 0L, 0);
        repository.bootstrap(connector).await().unwrap();
        repository.recordMigration(connector, row).await().unwrap();

        var again = repository.recordMigration(connector, row).await();

        assertThat(causeOf(again)).isInstanceOf(MigrationError.HistoryRowCollision.class);
    }

    // ---------------------------------------------------------------- survivors of the first mutation run

    /// M9b: a serialized runner that finds a row for ITS script with ANOTHER checksum does not skip: the insert meets
    /// the row and the run ends [MigrationError.HistoryRowCollision], the script rolled back.
    @Test
    void t8_serializedRunnerFindingARowWithAnotherChecksum_isCollisionNotSkip() {
        var file = dir.resolve("t8sum.db");
        var connector = sqlite(file);
        SchemaHistoryRepository.schemaHistoryRepository().bootstrap(connector).await().unwrap();
        var probe = Probe.over(connector, DatabaseType.SQLITE, new CopyOnWriteArrayList<>())
                         .afterHistoryRead(() -> execute(file, "INSERT INTO aether_schema_history (version, type, description, script, checksum, applied_by, applied_at, execution_ms, status, statements_completed)"
                                                              + " VALUES (1, 'VERSIONED', 'a', 'V1__a.sql', 999, 'other', 0, 0, 'SUCCESS', 1)"));

        var cause = causeOf(migrate(probe, MARK_SCRIPT).await());

        assertThat(cause).isInstanceOf(MigrationError.HistoryRowCollision.class);
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'applied_marks'")).isZero();
    }

    /// B2: a tier-3 bootstrap whose owner insert lost to a competitor (the NOT EXISTS insert wrote nothing) reads the
    /// owner again and compares it: another owner is a conflict, not a silent success.
    @Test
    void t8_tierThreeOwnerInsertLosingToAnotherOwner_isOwnershipConflict() {
        var file = dir.resolve("t8owner.db");
        var raced = new AtomicBoolean();
        var probe = Probe.over(sqlite(file), DatabaseType.SQLITE, new CopyOnWriteArrayList<>())
                         .afterOwnerRead(() -> insertOtherOwnerOnce(file, raced));

        var cause = causeOf(migrate(probe, MARK_SCRIPT).await());

        assertThat(cause).isInstanceOf(MigrationError.PhysicalDatasourceOwnershipConflict.class);
    }

    /// U2: a serialized second undo finds its history row already gone under the lock and skips: it neither reruns
    /// the undo script nor fails.
    @Test
    void undo_secondRunAfterTheRowIsGone_skipsTheScript() {
        var file = dir.resolve("undoTwice.db");
        var connector = sqlite(file);
        var scripts = List.of(MigrationScript.migrationScript("V1__a.sql", MARK_SCRIPT, 1L),
                              MigrationScript.migrationScript("U1__a.sql", "DROP TABLE applied_marks", 2L));
        var migrations = SchemaMigrations.schemaMigrations(timeSpan(1).minutes());

        assertThat(migrate(connector, MARK_SCRIPT).await().isSuccess()).isTrue();
        assertThat(migrations.undo(DATASOURCE, 0, scripts, connector, "node", OWNER).await().isSuccess()).isTrue();

        assertThat(migrations.undo(DATASOURCE, 0, scripts, connector, "node", OWNER).await().isSuccess()).isTrue();
    }

    private static void insertOtherOwnerOnce(Path file, AtomicBoolean raced) {
        if (raced.compareAndSet(false, true)) {
            execute(file, "INSERT INTO aether_schema_owner (blueprint_base) VALUES ('other')");
        }
    }

    private static void execute(Path file, String sql) {
        try (var conn = DriverManager.getConnection("jdbc:sqlite:" + file);
             var statement = conn.createStatement()) {
            statement.execute(sql);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- atomic undo

    /// Undo and the removal of its history row commit as one unit: a failing undo script leaves the row.
    @Test
    void undo_failingScript_keepsTheHistoryRow() {
        var file = dir.resolve("undo.db");
        var connector = sqlite(file);

        assertThat(migrate(connector, MARK_SCRIPT).await().isSuccess()).isTrue();
        var undo = SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                                   .undo(DATASOURCE,
                                         0,
                                         List.of(MigrationScript.migrationScript("V1__a.sql", MARK_SCRIPT, 1L),
                                                 MigrationScript.migrationScript("U1__a.sql", "DROP TABLE no_such_table", 2L)),
                                         connector,
                                         "node",
                                         OWNER)
                                   .await();

        assertThat(undo.isFailure()).isTrue();
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM aether_schema_history WHERE version = 1")).isEqualTo(1);
    }

    @Test
    void undo_succeedingScript_dropsTheWorkAndTheHistoryRowTogether() {
        var file = dir.resolve("undoOk.db");
        var connector = sqlite(file);

        assertThat(migrate(connector, MARK_SCRIPT).await().isSuccess()).isTrue();
        var undo = SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                                   .undo(DATASOURCE,
                                         0,
                                         List.of(MigrationScript.migrationScript("V1__a.sql", MARK_SCRIPT, 1L),
                                                 MigrationScript.migrationScript("U1__a.sql", "DROP TABLE applied_marks", 2L)),
                                         connector,
                                         "node",
                                         OWNER)
                                   .await();

        assertThat(undo.isSuccess()).isTrue();
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM aether_schema_history WHERE version = 1")).isZero();
        assertThat(scalar("jdbc:sqlite:" + file, "SELECT COUNT(*) FROM sqlite_master WHERE name = 'applied_marks'")).isZero();
    }

    // ---------------------------------------------------------------- helpers

    private static Promise<SchemaResult> migrate(SqlConnector connector, String sql) {
        return SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                               .migrate(DATASOURCE,
                                        List.of(MigrationScript.migrationScript("V1__a.sql", sql, 1L)),
                                        connector,
                                        "node",
                                        OWNER);
    }

    private SqlConnector sqlite(Path file) {
        return connector(DatabaseType.SQLITE, "jdbc:sqlite:" + file, 2);
    }

    private SqlConnector h2(String url) {
        return connector(DatabaseType.H2, url, 4);
    }

    private SqlConnector connector(DatabaseType type, String url, int poolSize) {
        var pool = PoolConfig.poolConfigBuilder()
                             .withMinConnections(0)
                             .withMaxConnections(poolSize)
                             .withConnectionTimeout(TimeSpan.timeSpan("1s").unwrap())
                             .build()
                             .unwrap();
        var config = databaseConnectorConfigBuilder().withName(DATASOURCE)
                                                     .withType(type)
                                                     .withJdbcUrl(url)
                                                     .withPoolConfig(pool)
                                                     .build()
                                                     .unwrap();
        var connector = new JdbcSqlConnectorFactory().provision(config).await().unwrap();

        opened.add(connector);

        return connector;
    }

    private static int scalar(String url, String sql) {
        try (var conn = DriverManager.getConnection(url);
             var statement = conn.createStatement();
             var rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getInt(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void pause(CountDownLatch reached, CountDownLatch gate) {
        reached.countDown();

        try {
            gate.await(60, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static int indexOf(List<String> statements, String prefix) {
        for (var i = 0; i < statements.size(); i++) {
            if (statements.get(i).startsWith(prefix)) {
                return i;
            }
        }

        return -1;
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, _ -> {
            throw new AssertionError("expected a failure");
        });
    }

    /// A connector over a real one that reports another engine type and records every statement, including those of
    /// the script transactions it begins. It can run a hook after the applied-history read.
    private static final class Probe implements SqlConnector {
        private final SqlConnector delegate;
        private final DatabaseType type;
        private final List<String> statements;
        private final Predicate<String> historyRead = sql -> sql.contains("status = 'SUCCESS'");
        private Runnable afterHistoryRead = () -> {};
        private Runnable afterOwnerRead = () -> {};

        private Probe(SqlConnector delegate, DatabaseType type, List<String> statements) {
            this.delegate = delegate;
            this.type = type;
            this.statements = statements;
        }

        static Probe over(SqlConnector delegate, DatabaseType type, List<String> statements) {
            return new Probe(delegate, type, statements);
        }

        Probe afterHistoryRead(Runnable hook) {
            afterHistoryRead = hook;
            return this;
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            statements.add(sql);
            return delegate.queryOne(sql, mapper, params);
        }

        Probe afterOwnerRead(Runnable hook) {
            afterOwnerRead = hook;
            return this;
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            statements.add(sql);
            return delegate.queryOptional(sql, mapper, params)
                           .map(row -> hookedOwner(sql, row));
        }

        private <T> Option<T> hookedOwner(String sql, Option<T> row) {
            if (sql.startsWith("SELECT blueprint_base")) {
                afterOwnerRead.run();
            }

            return row;
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            statements.add(sql);
            return delegate.queryList(sql, mapper, params)
                           .map(rows -> hooked(sql, rows));
        }

        private <T> List<T> hooked(String sql, List<T> rows) {
            if (historyRead.test(sql)) {
                afterHistoryRead.run();
            }

            return rows;
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            statements.add(sql);
            return delegate.update(sql, params);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            statements.add(sql);
            return delegate.batch(sql, paramsList);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return delegate.transactional(tx -> callback.execute(new Probe(tx, type, statements)));
        }

        @Override
        public DatabaseConnectorConfig config() {
            return delegate.config();
        }

        @Override
        public DatabaseType databaseType() {
            return type;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return delegate.isHealthy();
        }
    }
}
