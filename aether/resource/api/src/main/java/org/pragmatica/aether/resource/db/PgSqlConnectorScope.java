// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.time.Duration;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// A [PgSqlConnector] view of the plain [SqlConnector] a transaction (or a pinned session) hands its callback (#3014).
///
/// [PgSqlConnector] adds no operation to [SqlConnector], so every method, `pinned` and the release pair included, is
/// forwarded to `delegate`: the scope IS the transaction's connector under the type the generated factories ask for.
/// A connector that already is a `PgSqlConnector` is used as it is.
record PgSqlConnectorScope(SqlConnector delegate) implements PgSqlConnector {
    static PgSqlConnector pgSqlConnectorScope(SqlConnector connector) {
        return connector instanceof PgSqlConnector pg
               ? pg
               : new PgSqlConnectorScope(connector);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return delegate.queryOne(sql, mapper, params);
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return delegate.queryOptional(sql, mapper, params);
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return delegate.queryList(sql, mapper, params);
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return delegate.update(sql, params);
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return delegate.batch(sql, paramsList);
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return delegate.transactional(callback);
    }

    @Override
    public <T> Promise<T> pinned(Duration statementTimeout,
                                 TransactionCallback<T> body,
                                 TransactionCallback<Unit> release) {
        return delegate.pinned(statementTimeout, body, release);
    }

    @Override
    public DatabaseConnectorConfig config() {
        return delegate.config();
    }

    @Override
    public DatabaseType databaseType() {
        return delegate.databaseType();
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return delegate.isHealthy();
    }

    @Override
    public Promise<Unit> stop() {
        return delegate.stop();
    }

    @Override
    public Promise<Unit> close() {
        return delegate.close();
    }
}
