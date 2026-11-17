// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.deployment.schema;

import java.util.Map;

import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.utils.Causes;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;

/// FDB-2a/2b (#2591): lets an in-memory connector double that drives the REAL migration runner on a session-fenced
/// engine (PostgreSQL, MySQL, SQL Server, Oracle) answer the runner's database-fence statements as an uncontended database would: the fence is
/// granted and held, the engine is not CockroachDB, no topology is refused, no index is invalid. Any other statement is
/// left to the double. A double that answered these with nulls or "unsupported" would fail every migration at the fence.
final class FakeMigrationFence {
    private FakeMigrationFence() {}

    /// The single-row answer to a fence statement, or none for any other statement.
    static <T> Option<Promise<T>> answer(String sql, RowMapper<T> mapper) {
        return row(sql).map(values -> mapper.map(new MapRow(values)).async());
    }

    /// A single-row answer carrying exactly `values`, for a double that answers one statement itself.
    static <T> Promise<T> rowOf(Map<String, Object> values, RowMapper<T> mapper) {
        return mapper.map(new MapRow(values)).async();
    }

    /// The fence's list statements (topology probe, courtesy unlock) return no rows.
    static boolean isFenceListQuery(String sql) {
        return sql.contains("SHOW VARIABLES") || sql.contains("pg_advisory_unlock_all") || sql.contains("RELEASE_ALL_LOCKS");
    }

    /// The tier-2 fence statements issued as updates (Oracle `DBMS_LOCK` take/verify/release, SQL Server unlock): the
    /// double answers them as an uncontended database and does not count them as migration statements.
    static boolean isFenceUpdate(String sql) {
        return sql.contains("DBMS_LOCK") || sql.contains("sp_releaseapplock");
    }

    /// The double's single logical session: `body` runs on it, then `release`; the result is `body`'s.
    static <T> Promise<T> pinnedOn(SqlConnector session,
                                   SqlConnector.TransactionCallback<T> body,
                                   SqlConnector.TransactionCallback<Unit> release) {
        return body.execute(session)
                   .fold(bodyResult -> release.execute(session)
                                              .fold(_ -> Promise.resolved(bodyResult)));
    }

    private static Option<Map<String, Object>> row(String sql) {
        if (sql.contains("pg_try_advisory_lock")) {
            return some(Map.of("granted", 1));
        }
        if (sql.contains("GET_LOCK(")) {
            return some(Map.of("granted", 1));
        }
        if (sql.contains("sp_getapplock")) {
            return some(Map.of("granted", 0));
        }
        if (sql.contains("APPLOCK_MODE")) {
            return some(Map.of("n", 1));
        }
        if (sql.contains("indisvalid")) {
            return some(Map.of("n", 0));
        }
        if (sql.contains("pg_locks") || sql.contains("IS_USED_LOCK")) {
            return some(Map.of("n", 1));
        }
        if (sql.contains("version()")) {
            return some(Map.of("v", "PostgreSQL 16 (fake)"));
        }
        if (sql.contains("set_config")) {
            return some(Map.of("v", "set"));
        }
        if (sql.contains("DATABASE()")) {
            return some(Map.of("db", "fake"));
        }
        return none();
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
            return Option.option(values.get(column))
                         .filter(type::isInstance)
                         .map(type::cast)
                         .toResult(Causes.cause("fake fence row has no " + type.getSimpleName() + " column " + column));
        }
    }
}
