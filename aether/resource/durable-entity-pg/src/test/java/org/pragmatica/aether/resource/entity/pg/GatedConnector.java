// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.entity.pg;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

/// Test instrument: strips the row lock (` FOR UPDATE`) from every versioned read, then holds the result of each of
/// those reads until `parties` of them have been read, and releases all together. That forces N updates to have read
/// the same row version before any of them writes, so the version compare-and-set is the only thing separating them.
/// Non-blocking: the gate is a promise, no event-loop thread waits.
final class GatedConnector implements SqlConnector {
    private final SqlConnector delegate;
    private final int parties;
    private final AtomicInteger arrived;
    private final Promise<Unit> open;

    private GatedConnector(SqlConnector delegate, int parties, AtomicInteger arrived, Promise<Unit> open) {
        this.delegate = delegate;
        this.parties = parties;
        this.arrived = arrived;
        this.open = open;
    }

    static GatedConnector unlocked(SqlConnector delegate, int parties) {
        return new GatedConnector(delegate, parties, new AtomicInteger(), Promise.promise());
    }

    private static String withoutLock(String sql) {
        return sql.replace(" FOR UPDATE", "");
    }

    private <T> Promise<T> gate(String sql, Promise<T> read) {
        return sql.startsWith("SELECT version")
               ? read.flatMap(value -> arrive().map(_ -> value))
               : read;
    }

    private Promise<Unit> arrive() {
        if (arrived.incrementAndGet() == parties) {
            open.succeed(Unit.unit());
        }
        return open;
    }

    @Override
    public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
        var unlocked = withoutLock(sql);
        return gate(unlocked, delegate.queryOne(unlocked, mapper, params));
    }

    @Override
    public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
        var unlocked = withoutLock(sql);
        return gate(unlocked, delegate.queryOptional(unlocked, mapper, params));
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
        return delegate.transactional(inner -> callback.execute(new GatedConnector(inner, parties, arrived, open)));
    }

    @Override
    public DatabaseConnectorConfig config() {
        return delegate.config();
    }

    @Override
    public Promise<Boolean> isHealthy() {
        return delegate.isHealthy();
    }
}
