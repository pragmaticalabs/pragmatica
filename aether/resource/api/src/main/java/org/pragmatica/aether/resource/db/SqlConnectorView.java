// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// One slice's handle on a node-shared [SqlConnector] pool (#2973).
///
/// `stop()` and `close()` are both declared here, never inherited: the first call releases THIS holder only (the pool
/// closes when its last holder releases it), and every later call succeeds without doing anything. Neither ever reaches
/// the pool's own `stop()`. After the release every operation fails with [DatabaseConnectorError.HandleReleased] and
/// never reaches the pool. `config()` and `databaseType()` keep answering: they describe the pool and touch no
/// connection.
///
/// Every other method of the interface is forwarded, default methods included: an inherited `pinned` would fail with
/// `PinnedSessionUnsupported` and silently break session locks.
public class SqlConnectorView implements SqlConnector {
    private final SqlConnector pool;
    private final Fn0<Promise<Unit>> release;
    private final AtomicBoolean released = new AtomicBoolean();

    SqlConnectorView(SqlConnector pool, Fn0<Promise<Unit>> release) {
        this.pool = pool;
        this.release = release;
    }

    public static SqlConnector sqlConnectorView(SqlConnector pool, Fn0<Promise<Unit>> release) {
        return new SqlConnectorView(pool, release);
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.queryOne(sql, mapper, params);
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.queryOptional(sql, mapper, params);
    }

    @Override
    public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.queryList(sql, mapper, params);
    }

    @Override
    public Promise<Integer> update(String sql, Object... params) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.update(sql, params);
    }

    @Override
    public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.batch(sql, paramsList);
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.transactional(callback);
    }

    @Override
    public <T> Promise<T> pinned(Duration statementTimeout,
                                 TransactionCallback<T> body,
                                 TransactionCallback<Unit> release) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.pinned(statementTimeout, body, release);
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.isHealthy();
    }

    @Override
    public DatabaseConnectorConfig config() {
        return pool.config();
    }

    @Override
    public DatabaseType databaseType() {
        return pool.databaseType();
    }

    @Override
    public Promise<Unit> stop() {
        return released.compareAndSet(false, true)
               ? release.apply()
               : Promise.unitPromise();
    }

    @Override
    public Promise<Unit> close() {
        return stop();
    }
}
