// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a (#2591): the fence's decisions that need no live database: `verifyHeld` refuses a lock that acquired
/// nothing (M2), the engine probe, the §2.6 topology refusals, the MySQL lock name, the history read happening only
/// after the fence is verified (M3), the scope of tier 1, the per-write verification, and the autocommit statement
/// classification (H3, F8). The live-database properties are in [MigrationFencePgTest] and [MigrationFenceMysqlTest].
class MigrationFenceTest {
    @Nested
    class Acquire {
        /// M2: a lock call that returns "granted" while this session holds nothing (CockroachDB's no-op advisory lock,
        /// a transaction pooler that swapped the session) is refused before the first write.
        @Test
        void acquire_postgresLockGrantedButNotHeldByThisSession_isFenceUnavailable() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("pg_backend_pid", row("n", 0)));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
        }

        @Test
        void acquire_postgresLockGrantedAndHeld_succeedsAndNamesTheSession() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());

            assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();
            assertThat(session.params).contains("aether-mig:node-1");
        }

        @Test
        void acquire_postgresLockNotGranted_isFencedNamingTheHolder() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL,
                                              Map.of("pg_try_advisory_lock", row("granted", false),
                                                     "AS holder", row("holder", "pid=7, application_name=aether-mig:node-0")));

            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
            assertThat(cause.message()).contains("pid=7");
        }

        /// The engine is probed, not trusted: CockroachDB configured as PostgreSQL gets no tier-1 lock (FDB-2b gives it
        /// the tier-3 one).
        @Test
        void acquire_cockroachBehindAPostgresConfig_takesNoAdvisoryLock() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of("version()", row("v", "CockroachDB CCL v23.1.11")));

            assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();
            assertThat(session.statements).noneMatch(sql -> sql.contains("pg_try_advisory_lock"));
        }

        @Test
        void acquire_mysqlGalera_isRefused() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of(), List.of(variable("wsrep_on", "ON")));

            assertRefused(session, "Galera");
        }

        @Test
        void acquire_mysqlMultiPrimaryGroupReplication_isRefused() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of(), List.of(variable("group_replication_single_primary_mode", "OFF")));

            assertRefused(session, "multi-primary");
        }

        @Test
        void acquire_mysqlSinglePrimaryGroupReplication_isFenced() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of(), List.of(variable("group_replication_single_primary_mode", "ON")));

            assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();
        }

        @Test
        void acquire_mysqlWithoutADefaultSchema_isRefused() {
            var session = new ScriptedSession(DatabaseType.MYSQL, Map.of("DATABASE()", row("db", "")));

            assertRefused(session, "no default schema");
        }

        private static void assertRefused(ScriptedSession session, String reason) {
            var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

            assertThat(cause).isInstanceOf(MigrationError.FenceUnavailable.class);
            assertThat(cause.message()).contains(reason);
            assertThat(session.statements).noneMatch(sql -> sql.contains("GET_LOCK("));
        }

        @Test
        void mysqlLockName_is51CharactersAndCaseInsensitive() {
            var name = MigrationFence.MysqlFence.lockName("Shop_DB").unwrap();

            assertThat(name).hasSize(51).startsWith("aether_mig:");
            assertThat(name).isEqualTo(MigrationFence.MysqlFence.lockName("shop_db").unwrap());
        }
    }

    @Nested
    class Runner {
        /// M3: on a tier-1 engine nothing touches the history tables (bootstrap included) before the fence is taken
        /// and verified on the pinned session.
        @Test
        void migrate_touchesHistoryOnlyAfterTheFenceIsVerified() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());

            SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                            .migrate("ds", List.of(MigrationScript.migrationScript("V1__a.sql", "CREATE TABLE a (id INT);", 1L)), session, "node-1", "bp")
                            .await();

            var verified = indexOf(session.statements, "pg_backend_pid");
            var firstHistory = indexOf(session.statements, "aether_schema");

            assertThat(session.pinnedCalls.get()).isEqualTo(1);
            assertThat(verified).isNotNegative();
            assertThat(firstHistory).as("history touched only after the fence is verified").isGreaterThan(verified);
        }

        /// FDB-2a scope: an engine without a tier-1 fence keeps today's path and never asks for a pinned session.
        @Test
        void migrate_onAnEngineWithoutATierOneFence_neverPins() {
            var session = new ScriptedSession(DatabaseType.SQLITE, Map.of());

            SchemaMigrations.schemaMigrations(timeSpan(1).minutes())
                            .migrate("ds", List.of(MigrationScript.migrationScript("V1__a.sql", "CREATE TABLE a (id INT);", 1L)), session, "node-1", "bp")
                            .await();

            assertThat(session.pinnedCalls.get()).isZero();
            assertThat(session.statements).noneMatch(sql -> sql.contains("pg_try_advisory_lock"));
        }

        /// Every write on a fenced session is preceded by `verifyHeld` (the `pg_backend_pid` lock check); reads are not.
        @Test
        void fencedSession_verifiesBeforeEveryWriteAndNeverBeforeAQuery() {
            var session = new ScriptedSession(DatabaseType.POSTGRESQL, Map.of());
            var fenced = new FencedSession(session, new MigrationFence.PgFence("ds"));

            fenced.queryList("SELECT 1", MigrationFence.IGNORED).await();
            assertThat(verifications(session)).isZero();

            fenced.update("INSERT INTO t VALUES (1)").await();
            fenced.batch("INSERT INTO t VALUES (?)", List.<Object[]>of(new Object[]{1})).await();
            fenced.transactional(tx -> tx.update("INSERT INTO t VALUES (2)")).await();
            assertThat(verifications(session)).isEqualTo(3);
        }

        private static long verifications(ScriptedSession session) {
            return session.statements.stream().filter(sql -> sql.contains("pg_backend_pid")).count();
        }

        private static int indexOf(List<String> statements, String fragment) {
            for (var i = 0; i < statements.size(); i++) {
                if (statements.get(i).contains(fragment)) {
                    return i;
                }
            }

            return -1;
        }
    }

    @Nested
    class AutocommitStatements {
        @Test
        void concurrentIndex_namedUnquoted_foldsToLowerCase() {
            var statement = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY IF NOT EXISTS Orders_Idx ON orders (id)",
                                                                    MigrationDialects.POSTGRESQL);

            assertThat(statement.concurrentIndex()).isEqualTo(some("orders_idx"));
            assertThat(statement.transactional()).isFalse();
        }

        @Test
        void concurrentIndex_uniqueAndQuoted_keepsTheQuotedName() {
            var statement = AutocommitStatement.autocommitStatement("create unique index concurrently \"Big\"\"Idx\" on t (c)",
                                                                    MigrationDialects.POSTGRESQL);

            assertThat(statement.concurrentIndex()).isEqualTo(some("Big\"Idx"));
        }

        @Test
        void concurrentIndex_unnamed_cannotBeChecked() {
            var statement = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY ON t (c)", MigrationDialects.POSTGRESQL);

            assertThat(statement.concurrentIndex()).isEqualTo(none());
        }

        @Test
        void dml_isTransactional_andNamesNoIndex() {
            var statement = AutocommitStatement.autocommitStatement("INSERT INTO t VALUES (1)", MigrationDialects.POSTGRESQL);

            assertThat(statement.transactional()).isTrue();
            assertThat(statement.concurrentIndex()).isEqualTo(none());
        }

        @Test
        void mysql_neverNamesAConcurrentIndex() {
            var statement = AutocommitStatement.autocommitStatement("CREATE INDEX CONCURRENTLY i ON t (c)", MigrationDialects.MYSQL);

            assertThat(statement.concurrentIndex()).isEqualTo(none());
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    private static Map<String, Object> row(String column, Object value) {
        return Map.of(column, value);
    }

    private static Map<String, Object> variable(String name, String value) {
        return Map.of("Variable_name", name, "Value", value);
    }

    /// An in-memory session that records every statement and answers the fence's statements as an uncontended
    /// database would, unless an override (matched by SQL fragment) says otherwise.
    private static final class ScriptedSession implements SqlConnector {
        private static final Map<String, Map<String, Object>> DEFAULTS = Map.of("pg_try_advisory_lock", row("granted", true),
                                                                                "pg_backend_pid", row("n", 1),
                                                                                "version()", row("v", "PostgreSQL 16.4"),
                                                                                "set_config", row("v", "set"),
                                                                                "DATABASE()", row("db", "shop"),
                                                                                "GET_LOCK(", row("granted", 1),
                                                                                "IS_USED_LOCK", row("n", 1));
        private final DatabaseConnectorConfig config;
        private final Map<String, Map<String, Object>> overrides;
        private final List<Map<String, Object>> variables;
        private final List<String> statements = new ArrayList<>();
        private final List<Object> params = new ArrayList<>();
        private final AtomicInteger pinnedCalls = new AtomicInteger();

        ScriptedSession(DatabaseType type, Map<String, Map<String, Object>> overrides) {
            this(type, overrides, List.of());
        }

        ScriptedSession(DatabaseType type, Map<String, Map<String, Object>> overrides, List<Map<String, Object>> variables) {
            this.config = new DatabaseConnectorConfig(none(), some(type), some("localhost"), none(), some("test"), none(), none(),
                                                      PoolConfig.DEFAULT, Map.of(), none(), none(), none());
            this.overrides = overrides;
            this.variables = variables;
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            return queryOptional(sql, mapper, args).flatMap(row -> row.async(Causes.cause("no row for " + sql)));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
            record(sql, args);
            return Promise.success(answer(overrides, sql).orElse(answer(DEFAULTS, sql))
                                                         .flatMap(values -> mapper.map(new MapRow(values)).option()));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... args) {
            record(sql, args);
            var rows = sql.contains("SHOW VARIABLES") ? variables : List.<Map<String, Object>>of();

            return Promise.success(rows.stream().map(values -> mapper.map(new MapRow(values)).unwrap()).toList());
        }

        @Override
        public Promise<Integer> update(String sql, Object... args) {
            record(sql, args);
            return Promise.success(0);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            record(sql, new Object[0]);
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
            pinnedCalls.incrementAndGet();
            return body.execute(this).fold(result -> release.execute(this).fold(_ -> Promise.resolved(result)));
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        private void record(String sql, Object[] args) {
            statements.add(sql);
            params.addAll(List.of(args));
        }

        private static Option<Map<String, Object>> answer(Map<String, Map<String, Object>> table, String sql) {
            return Option.from(table.entrySet().stream().filter(entry -> sql.contains(entry.getKey())).map(Map.Entry::getValue).findFirst());
        }
    }

    private record MapRow(Map<String, Object> values) implements RowMapper.RowAccessor {
        @Override
        public Result<String> getString(String column) {
            return value(column, String.class);
        }

        @Override
        public Result<Integer> getInt(String column) {
            return value(column, Integer.class);
        }

        @Override
        public Result<Long> getLong(String column) {
            return value(column, Integer.class).map(Integer::longValue);
        }

        @Override
        public Result<Double> getDouble(String column) {
            return value(column, Double.class);
        }

        @Override
        public Result<Boolean> getBoolean(String column) {
            return value(column, Boolean.class);
        }

        @Override
        public Result<byte[]> getBytes(String column) {
            return value(column, byte[].class);
        }

        @Override
        public <V> Result<V> getObject(String column, Class<V> type) {
            return value(column, type);
        }

        private <V> Result<V> value(String column, Class<V> type) {
            return Option.option(values.get(column)).filter(type::isInstance).map(type::cast).toResult(Causes.cause("no " + column));
        }
    }
}
