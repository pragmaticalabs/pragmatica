// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.db.migration;

import java.util.List;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


/// The pinned session a migration runs on once it holds the fence (FDB-2a, #2591; design §2.3). Every WRITE, a
/// migration statement, a checkpoint, a history row or a bootstrap write, is preceded by [MigrationFence#verifyHeld] on
/// the same session; reads are not. A `transactional(...)` is verified once, before it begins, and runs entirely on the
/// pinned connection. This is a superset of the design's "before each script, and before every statement on the
/// autocommit path": a lost lock (a pooler that swapped the session) is caught before the next write, whatever it is.
///
/// Nested `pinned` keeps the interface's typed refusal: a fenced session is already the pinned one.
record FencedSession(SqlConnector session, MigrationFence fence) implements SqlConnector {
    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return session.queryOne(sql, mapper, params);
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return session.queryOptional(sql, mapper, params);
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return session.queryList(sql, mapper, params);
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return fence.verifyHeld(session)
                    .flatMap(_ -> session.update(sql, params));
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return fence.verifyHeld(session)
                    .flatMap(_ -> session.batch(sql, paramsList));
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return fence.verifyHeld(session)
                    .flatMap(_ -> session.transactional(callback));
    }

    @Override
    public DatabaseConnectorConfig config() {
        return session.config();
    }

    @Override
    public DatabaseType databaseType() {
        return session.databaseType();
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return session.isHealthy();
    }
}
