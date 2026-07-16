// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.List;
import java.util.Map;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// In-memory, statement-recording [SqlConnector] fake for `@Sql` slices (spec §3.3). Scripts rows
/// per SQL substring and records executed statements for assertions. Runs no SQL — use the
/// container path (`Containers.postgres()`) for real SQL/migrations.
public final class InMemorySqlConnector implements SqlConnector {
    private final StatementRecorder recorder = new StatementRecorder();

    private InMemorySqlConnector() {}

    public static InMemorySqlConnector scripted() {
        return new InMemorySqlConnector();
    }

    /// Script a single row returned by any query whose SQL contains `sqlSubstring`.
    public InMemorySqlConnector onRow(String sqlSubstring, Map<String, Object> row) {
        recorder.scriptRows(sqlSubstring, List.of(row));

        return this;
    }

    /// Script a result set returned by any query whose SQL contains `sqlSubstring`.
    public InMemorySqlConnector onRows(String sqlSubstring, List<Map<String, Object>> rows) {
        recorder.scriptRows(sqlSubstring, rows);

        return this;
    }

    /// Script the affected-row count returned by any update whose SQL contains `sqlSubstring`.
    public InMemorySqlConnector onUpdate(String sqlSubstring, int affected) {
        recorder.scriptUpdate(sqlSubstring, affected);

        return this;
    }

    /// Statements executed against this connector so far, in execution order.
    public List<RecordedStatement> statements() {
        return recorder.recorded();
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return recorder.queryOne(sql, mapper, params);
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return recorder.queryOptional(sql, mapper, params);
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return recorder.queryList(sql, mapper, params);
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return recorder.update(sql, params);
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return recorder.batch(sql, paramsList);
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return callback.execute(this);
    }

    @Override
    public DatabaseConnectorConfig config() {
        return recorder.config();
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return Promise.success(true);
    }

    @Override
    public Promise<Unit> stop() {
        return Promise.unitPromise();
    }
}
