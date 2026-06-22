// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.aether.slice.blueprint.MigrationEntry;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;
import static org.pragmatica.aether.slice.blueprint.MigrationEntry.migrationEntry;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.option;
import static org.pragmatica.lang.Option.some;

/// Deterministic, fake-connector proof of Increment B2 (#338): autocommit-dialect migrations
/// (MySQL/Oracle) are resumable after a partial failure via the B1 `status`/`statements_completed`
/// columns. The connector is a pure in-memory model of the `aether_schema_history` table that
/// understands exactly the SQL the repository emits, so the per-statement checkpoint lifecycle and
/// the resume gate can be observed across separate `migrate(...)` invocations without a real DB.
///
/// - [FailThenResume] is the core correctness proof: a MySQL migration of N statements whose
///   statement K+1 is injected to FAIL leaves a `FAILED` row at `statements_completed = K`; the
///   SAME migration re-run RESUMES (skips the first K, executes K+1..N) and finishes `SUCCESS` with
///   `statements_completed = N`, re-executing only the boundary statement.
/// - [HappyPath] proves an `IN_PROGRESS` checkpoint appears and is finalized to `SUCCESS` with
///   `statements_completed = N`, the statements running in order.
/// - [TransactionalUnchanged] proves a PostgreSQL (transactional) migration NEVER writes an
///   `IN_PROGRESS` row — its Increment A all-or-nothing semantics are untouched.
class AetherSchemaManagerResumeTest {
    private static final SchemaPolicy POLICY = SchemaPolicy.schemaPolicy();
    private static final String NODE_ID = "node-1";
    private static final long CHECKSUM = 7L;

    private static final String THREE_STATEMENTS = """
        CREATE TABLE a (id INT);
        CREATE TABLE b (id INT);
        CREATE TABLE c (id INT);
        """;

    @Nested
    class FailThenResume {

        /// Statement K+1 (here the 2nd of 3, index 1) fails: the partial row is `FAILED` at
        /// `statements_completed = 1` (only the 1st statement durably committed), and the 1st
        /// statement ran exactly once.
        @Test
        void migrate_marksFailedAtCheckpoint_whenStatementFails() {
            var connector = new FakeHistoryConnector(DatabaseType.MYSQL);
            connector.failOnMigrationStatement("CREATE TABLE b (id INT)");

            migrateExpectingFailure(connector, "V1__three.sql", THREE_STATEMENTS);

            var row = connector.historyRow(1);
            assertThat(row.status).isEqualTo("FAILED");
            assertThat(row.statementsCompleted).isEqualTo(1);
            assertThat(connector.migrationExecutions).containsExactly("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)");
        }

        /// Re-running the SAME migration after the partial failure RESUMES: it skips the 1st
        /// already-applied statement and runs only the boundary statement (2nd, which now succeeds)
        /// and the 3rd, finishing `SUCCESS` at `statements_completed = 3`.
        @Test
        void migrate_resumesFromCheckpoint_onReRunAfterFailure() {
            var connector = new FakeHistoryConnector(DatabaseType.MYSQL);
            connector.failOnMigrationStatement("CREATE TABLE b (id INT)");
            migrateExpectingFailure(connector, "V1__three.sql", THREE_STATEMENTS);
            connector.clearInjectedFailure();
            connector.migrationExecutions.clear();

            migrate(connector, "V1__three.sql", THREE_STATEMENTS);

            var row = connector.historyRow(1);
            assertThat(row.status).isEqualTo("SUCCESS");
            assertThat(row.statementsCompleted).isEqualTo(3);
            assertThat(connector.migrationExecutions).containsExactly("CREATE TABLE b (id INT)", "CREATE TABLE c (id INT)");
        }
    }

    @Nested
    class HappyPath {

        /// A clean MySQL migration: an `IN_PROGRESS` row is written first (observed via the recorded
        /// statuses), every statement runs in order, and the row is finalized `SUCCESS` at
        /// `statements_completed = N`.
        @Test
        void migrate_recordsInProgressThenSuccess_forCleanMysqlRun() {
            var connector = new FakeHistoryConnector(DatabaseType.MYSQL);

            migrate(connector, "V1__three.sql", THREE_STATEMENTS);

            var row = connector.historyRow(1);
            assertThat(row.status).isEqualTo("SUCCESS");
            assertThat(row.statementsCompleted).isEqualTo(3);
            assertThat(connector.migrationExecutions).containsExactly("CREATE TABLE a (id INT)", "CREATE TABLE b (id INT)", "CREATE TABLE c (id INT)");
            assertThat(connector.statusHistory(1)).containsExactly("IN_PROGRESS", "SUCCESS");
        }
    }

    @Nested
    class TransactionalUnchanged {

        /// A PostgreSQL (transactional) migration NEVER writes an `IN_PROGRESS` checkpoint row — the
        /// single in-transaction INSERT lands the row already `SUCCESS` — so the autocommit
        /// checkpoint lifecycle is not exercised and Increment A semantics are unchanged.
        @Test
        void migrate_neverWritesInProgressRow_forTransactionalPostgres() {
            var connector = new FakeHistoryConnector(DatabaseType.POSTGRESQL);

            migrate(connector, "V1__three.sql", THREE_STATEMENTS);

            assertThat(connector.everInProgress).isFalse();
            var row = connector.historyRow(1);
            assertThat(row.status).isEqualTo("SUCCESS");
            assertThat(connector.statusHistory(1)).containsExactly("SUCCESS");
        }
    }

    private AetherSchemaManager schemaManager() {
        return AetherSchemaManager.aetherSchemaManager(POLICY);
    }

    private void migrate(FakeHistoryConnector connector, String filename, String sql) {
        schemaManager().migrate("ds", List.of(migrationEntry(filename, sql, CHECKSUM)), connector, NODE_ID)
                       .await()
                       .onFailure(cause -> fail("Migration failed: " + cause.message()));
    }

    private void migrateExpectingFailure(FakeHistoryConnector connector, String filename, String sql) {
        schemaManager().migrate("ds", List.of(migrationEntry(filename, sql, CHECKSUM)), connector, NODE_ID)
                       .await()
                       .onSuccess(_ -> fail("Expected migration to fail at the injected statement"));
    }

    /// In-memory model of `aether_schema_history` for the autocommit dialect (MySQL) and the
    /// transactional dialect (PostgreSQL). It interprets the exact SQL the repository and manager
    /// emit — bootstrap DDL (meta + history, treated as no-ops that simply track the schema version),
    /// the history INSERT / progress-UPDATE / status-UPDATE / finalize-UPDATE / progress-SELECT /
    /// applied-SELECT bookkeeping, and the migration-body statements — so checkpoint state survives
    /// across separate `migrate(...)` calls and the resume gate can be exercised end-to-end.
    private static final class FakeHistoryConnector implements SqlConnector {
        private static final String HISTORY_TABLE = "aether_schema_history";
        private static final String META_TABLE = "aether_schema_history_meta";

        private final DatabaseConnectorConfig config;
        private final Map<Integer, HistoryRow> historyRows = new HashMap<>();
        private final Map<Integer, List<String>> statusHistory = new HashMap<>();
        private final List<String> migrationExecutions = new ArrayList<>();
        private Option<String> failingStatement = none();
        private boolean everInProgress;
        private int metaVersion;

        private FakeHistoryConnector(DatabaseType type) {
            this.config = new DatabaseConnectorConfig(none(),
                                                      some(type),
                                                      some("localhost"),
                                                      none(),
                                                      some("test"),
                                                      none(),
                                                      none(),
                                                      PoolConfig.DEFAULT,
                                                      Map.of(),
                                                      none(),
                                                      none(),
                                                      none());
        }

        private void failOnMigrationStatement(String statement) {
            this.failingStatement = some(statement);
        }

        private void clearInjectedFailure() {
            this.failingStatement = none();
        }

        private HistoryRow historyRow(int version) {
            return Optional.ofNullable(historyRows.get(version))
                           .orElseThrow(() -> new IllegalStateException("no history row for version " + version));
        }

        private List<String> statusHistory(int version) {
            return statusHistory.getOrDefault(version, List.of());
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return applyUpdate(sql.strip(), params);
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return queryOptional(sql, mapper, params).flatMap(opt -> opt.async(Causes.cause("no row")));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return queryList(sql, mapper, params).map(rows -> rows.isEmpty() ? none() : some(rows.getFirst()));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return mapRows(selectRows(sql.strip(), params), mapper).async();
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        private Promise<Integer> applyUpdate(String sql, Object[] params) {
            var upper = sql.toUpperCase();

            if (sql.contains(META_TABLE)) {
                return applyMetaUpdate(upper, params);
            }

            if (sql.contains(HISTORY_TABLE)) {
                return applyHistoryUpdate(upper, params);
            }

            return applyMigrationStatement(sql);
        }

        private Promise<Integer> applyMetaUpdate(String upper, Object[] params) {
            if (upper.startsWith("INSERT") || upper.startsWith("UPDATE")) {
                metaVersion = (int) params[0];
            }

            return Promise.success(1);
        }

        private Promise<Integer> applyHistoryUpdate(String upper, Object[] params) {
            if (upper.startsWith("CREATE") || upper.startsWith("ALTER")) {
                return Promise.success(0);
            }

            if (upper.startsWith("INSERT")) {
                return applyHistoryInsert(upper, params);
            }

            if (upper.startsWith("DELETE")) {
                historyRows.remove((int) params[0]);
                return Promise.success(1);
            }

            return applyHistoryStatusUpdate(upper, params);
        }

        private Promise<Integer> applyHistoryInsert(String upper, Object[] params) {
            var version = (int) params[0];
            var checksumIndex = 4;
            var checksum = ((Number) params[checksumIndex]).longValue();
            var status = upper.contains("STATEMENTS_COMPLETED") ? (String) params[7] : "SUCCESS";
            putRow(version, new HistoryRow(status, 0, checksum));

            return Promise.success(1);
        }

        private Promise<Integer> applyHistoryStatusUpdate(String upper, Object[] params) {
            if (upper.startsWith("UPDATE") && upper.contains("SET STATEMENTS_COMPLETED")) {
                return updateRow(params, (row, p) -> row.withStatementsCompleted((int) p[0]), 1, 2);
            }

            if (upper.startsWith("UPDATE") && upper.contains("SET STATUS = ?,")) {
                return finalizeRow(params);
            }

            return updateRow(params, (row, p) -> row.withStatus((String) p[0]), 1, 2);
        }

        private Promise<Integer> finalizeRow(Object[] params) {
            var version = (int) params[5];
            var status = (String) params[0];
            var checksum = ((Number) params[1]).longValue();
            var statementsCompleted = (int) params[4];
            putRow(version, new HistoryRow(status, statementsCompleted, checksum));

            return Promise.success(1);
        }

        private Promise<Integer> updateRow(Object[] params, RowUpdate update, int versionIndex, int unusedTypeIndex) {
            var version = (int) params[versionIndex];
            var current = historyRows.get(version);

            if (current != null) {
                putRow(version, update.apply(current, params));
            }

            return Promise.success(1);
        }

        private void putRow(int version, HistoryRow row) {
            var previous = historyRows.put(version, row);

            if ("IN_PROGRESS".equals(row.status)) {
                everInProgress = true;
            }

            if (previous == null || !previous.status.equals(row.status)) {
                statusHistory.computeIfAbsent(version, _ -> new ArrayList<>()).add(row.status);
            }
        }

        private Promise<Integer> applyMigrationStatement(String sql) {
            migrationExecutions.add(sql);

            return failingStatement.filter(sql::equals)
                                   .fold(() -> Promise.success(1),
                                         _ -> Causes.cause("injected failure at: " + sql).promise());
        }

        private List<Map<String, Object>> selectRows(String sql, Object[] params) {
            var upper = sql.toUpperCase();

            if (sql.contains(META_TABLE)) {
                return metaVersion == 0 ? List.of() : List.of(Map.of("schema_version", metaVersion));
            }

            if (upper.contains("WHERE TYPE = 'REPEATABLE'")) {
                return List.of();
            }

            if (upper.contains("STATEMENTS_COMPLETED, CHECKSUM")) {
                return progressRows((int) params[0]);
            }

            return appliedRows();
        }

        private List<Map<String, Object>> progressRows(int version) {
            var row = historyRows.get(version);

            return row == null
                   ? List.of()
                   : List.of(Map.of("status", row.status, "statements_completed", row.statementsCompleted, "checksum", row.checksum));
        }

        private List<Map<String, Object>> appliedRows() {
            return historyRows.entrySet().stream()
                              .filter(e -> "SUCCESS".equals(e.getValue().status))
                              .<Map<String, Object>>map(e -> Map.of("version", e.getKey(),
                                                                    "type", "VERSIONED",
                                                                    "description", "d",
                                                                    "script", "s",
                                                                    "checksum", e.getValue().checksum,
                                                                    "applied_by", NODE_ID,
                                                                    "applied_at", 0L,
                                                                    "execution_ms", 0))
                              .toList();
        }

        private static <T> Result<List<T>> mapRows(List<Map<String, Object>> rows, RowMapper<T> mapper) {
            return Result.allOf(rows.stream().map(row -> mapper.map(new MapRow(row))).toList());
        }

        @FunctionalInterface
        private interface RowUpdate {
            HistoryRow apply(HistoryRow row, Object[] params);
        }
    }

    private static final class HistoryRow {
        private final String status;
        private final int statementsCompleted;
        private final long checksum;

        private HistoryRow(String status, int statementsCompleted, long checksum) {
            this.status = status;
            this.statementsCompleted = statementsCompleted;
            this.checksum = checksum;
        }

        private HistoryRow withStatus(String newStatus) {
            return new HistoryRow(newStatus, statementsCompleted, checksum);
        }

        private HistoryRow withStatementsCompleted(int completed) {
            return new HistoryRow(status, completed, checksum);
        }
    }

    private record MapRow(Map<String, Object> values) implements RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return option((String) values.get(column)).toResult(Causes.cause("no column " + column));
        }

        @Override
        public Result<Integer> getInt(String column) {
            return option((Number) values.get(column)).map(Number::intValue).toResult(Causes.cause("no column " + column));
        }

        @Override
        public Result<Long> getLong(String column) {
            return option((Number) values.get(column)).map(Number::longValue).toResult(Causes.cause("no column " + column));
        }

        @Override
        public Result<Double> getDouble(String column) {
            return option((Number) values.get(column)).map(Number::doubleValue).toResult(Causes.cause("no column " + column));
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return option((Boolean) values.get(column)).toResult(Causes.cause("no column " + column));
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return option((byte[]) values.get(column)).toResult(Causes.cause("no column " + column));
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return option(type.cast(values.get(column))).toResult(Causes.cause("no column " + column));
        }
    }
}
