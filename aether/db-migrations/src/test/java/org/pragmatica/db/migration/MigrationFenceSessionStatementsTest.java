// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.utils.Causes;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// FDB-2a (#2591) survivor tests: the exact session statements the fence sends (keepalives, `GET_LOCK` timeout, the
/// holder query's database filter) and the operator remedies in the error messages. Scripted, no database needed.
class MigrationFenceSessionStatementsTest {
    private static final List<List<String>> KEEPALIVES = List.of(List.of("tcp_keepalives_idle", "60"),
                                                                 List.of("tcp_keepalives_interval", "10"),
                                                                 List.of("tcp_keepalives_count", "3"),
                                                                 List.of("client_connection_check_interval", "10s"));

    /// Design §2.5, F11a/F11b/F15b/F14b: the four keepalive settings, with these names and values, session-wide
    /// (`is_local = false`, so they outlive the statement), each sent BEFORE the lock is attempted.
    @Test
    void acquire_postgres_setsTheFourKeepalivesSessionWideBeforeTakingTheLock() {
        var session = new Recording(DatabaseType.POSTGRESQL, _ -> false);

        assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();

        var keepaliveCalls = session.calls.stream().filter(call -> call.sql.contains("set_config(?, ?, false)")).toList();

        assertThat(keepaliveCalls.stream().map(call -> List.of(String.valueOf(call.args[0]), String.valueOf(call.args[1]))).toList())
            .containsExactlyElementsOf(KEEPALIVES);
        assertThat(session.calls.stream().filter(call -> call.sql.contains("set_config(")).allMatch(call -> call.sql.contains(", false)")))
            .as("application_name and every keepalive are session-wide, never is_local=true").isTrue();

        var lastKeepalive = session.indexOfLast("set_config(?, ?, false)");
        var lock = session.indexOfFirst("pg_try_advisory_lock");

        assertThat(lastKeepalive).isNotNegative().isLessThan(lock);
    }

    /// F12: a server that refuses a keepalive (managed service, older server) must not fail the migration: the lock is
    /// still taken, and the remaining keepalives are still attempted.
    @Test
    void acquire_postgresRefusingTcpKeepalivesIdle_isNotFatalAndTheRestAreStillSet() {
        var session = new Recording(DatabaseType.POSTGRESQL, call -> call.sql.contains("set_config(?, ?, false)") && "tcp_keepalives_idle".equals(call.args[0]));

        var result = MigrationFence.acquire(session, "ds", "node-1").await();

        assertThat(result.isSuccess()).as("a refused keepalive is logged, never fatal").isTrue();

        var attempted = session.calls.stream().filter(call -> call.sql.contains("set_config(?, ?, false)")).map(call -> String.valueOf(call.args[0])).toList();

        assertThat(attempted).containsExactly("tcp_keepalives_idle", "tcp_keepalives_interval", "tcp_keepalives_count", "client_connection_check_interval");
        assertThat(session.indexOfFirst("pg_try_advisory_lock")).as("the lock is still attempted").isNotNegative();
    }

    /// F23: `GET_LOCK` must be a no-wait try (timeout 0): a contender that waits holds its pinned connection for that
    /// long before it reports the fence as held.
    @Test
    void acquire_mysql_triesTheLockWithATimeoutOfZeroOnTheHashedName() {
        var session = new Recording(DatabaseType.MYSQL, _ -> false);

        assertThat(MigrationFence.acquire(session, "ds", "node-1").await().isSuccess()).isTrue();

        var lockCall = session.calls.stream().filter(call -> call.sql.contains("GET_LOCK(")).findFirst().orElseThrow();
        var expectedName = MigrationFence.MysqlFence.lockName("shop").unwrap();

        assertThat(lockCall.sql).contains("GET_LOCK(?, 0)").doesNotContain("GET_LOCK(?, 5)");
        assertThat(lockCall.args).containsExactly(expectedName, expectedName);
    }

    /// F9: the holder is looked up per database. An advisory lock of the same key held in ANOTHER database of the same
    /// server is not this fence's holder; `pg_locks.database` must equal this session's database. Scripted: the filter
    /// is in the statement and is evaluated against `current_database()`. [source: container variant not written: the
    /// build host's docker network pool is exhausted (T22), and a scripted assertion on the statement kills the mutant.]
    @Test
    void acquire_postgresHeldElsewhere_holderQueryIsFilteredToTheCurrentDatabase() {
        var session = new Recording(DatabaseType.POSTGRESQL, _ -> false).answering("pg_try_advisory_lock", Map.of("granted", 0))
                                                                         .answering("AS holder", Map.of("holder", "pid=7"));

        var cause = causeOf(MigrationFence.acquire(session, "ds", "node-1").await());

        var holderQuery = session.calls.stream().filter(call -> call.sql.contains("AS holder")).findFirst().orElseThrow();

        assertThat(cause).isInstanceOf(MigrationError.MigrationFencedInDatabase.class);
        assertThat(holderQuery.sql).contains("l.database = (SELECT oid FROM pg_database WHERE datname = current_database())");
    }

    /// E1: the INVALID-index message tells the operator how to clear it.
    @Test
    void invalidIndexLeftBehind_message_carriesTheDropIndexRemedy() {
        var message = MigrationError.InvalidIndexLeftBehind.invalidIndexLeftBehind("V2__idx.sql", "idx_orders").message();

        assertThat(message).contains("DROP INDEX CONCURRENTLY idx_orders").contains("V2__idx.sql").contains("retry");
    }

    /// E2: the fenced message names the holder and how to end its session on each engine.
    @Test
    void migrationFencedInDatabase_message_carriesTheTerminateRemedyForBothEngines() {
        var message = MigrationError.MigrationFencedInDatabase.migrationFencedInDatabase("ds", "pid=7, application_name=aether-mig:node-0").message();

        assertThat(message).contains("pid=7, application_name=aether-mig:node-0")
                           .contains("SELECT pg_terminate_backend(pid)")
                           .contains("KILL id")
                           .contains("Nothing was run");
    }

    private static Cause causeOf(Result<?> result) {
        return result.fold(cause -> cause, value -> {
            throw new AssertionError("expected a failure, got " + value);
        });
    }

    private record Call(String sql, Object[] args) {}

    /// Records every statement with its arguments; answers the fence's statements as an uncontended database would;
    /// refuses (fails) the queries `refuse` matches.
    private static final class Recording implements SqlConnector {
        private final DatabaseConnectorConfig config;
        private final Predicate<Call> refuse;
        private final Map<String, Map<String, Object>> overrides = new java.util.LinkedHashMap<>();
        private final List<Call> calls = new ArrayList<>();

        Recording(DatabaseType type, Predicate<Call> refuse) {
            this.config = new DatabaseConnectorConfig(none(), some(type), some("localhost"), none(), some("test"), none(), none(),
                                                      PoolConfig.DEFAULT, Map.of(), none(), none(), none());
            this.refuse = refuse;
        }

        Recording answering(String fragment, Map<String, Object> row) {
            overrides.put(fragment, row);
            return this;
        }

        int indexOfFirst(String fragment) {
            for (var i = 0; i < calls.size(); i++) {
                if (calls.get(i).sql.contains(fragment)) {
                    return i;
                }
            }
            return -1;
        }

        int indexOfLast(String fragment) {
            for (var i = calls.size() - 1; i >= 0; i--) {
                if (calls.get(i).sql.contains(fragment)) {
                    return i;
                }
            }
            return -1;
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... args) {
            return queryOptional(sql, mapper, args).flatMap(row -> row.async(Causes.cause("no row for " + sql)));
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... args) {
            var call = new Call(sql, args);

            calls.add(call);

            if (refuse.test(call)) {
                return Causes.cause("permission denied to set parameter").promise();
            }

            var values = answer(overrides, sql).orElse(answer(DEFAULTS, sql));

            return Promise.success(values.flatMap(row -> mapper.map(new MapRow(row)).option()));
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... args) {
            calls.add(new Call(sql, args));
            return Promise.success(List.of());
        }

        @Override
        public Promise<Integer> update(String sql, Object... args) {
            calls.add(new Call(sql, args));
            return Promise.success(0);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            calls.add(new Call(sql, new Object[0]));
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return config;
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        private static final Map<String, Map<String, Object>> DEFAULTS = Map.of("pg_try_advisory_lock", Map.of("granted", 1),
                                                                                "AS n FROM pg_locks", Map.of("n", 1),
                                                                                "version()", Map.of("v", "PostgreSQL 16.4"),
                                                                                "set_config", Map.of("v", "set"),
                                                                                "DATABASE()", Map.of("db", "shop"),
                                                                                "GET_LOCK(", Map.of("granted", 1),
                                                                                "CONNECTION_ID(), 0)", Map.of("n", 1));

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
