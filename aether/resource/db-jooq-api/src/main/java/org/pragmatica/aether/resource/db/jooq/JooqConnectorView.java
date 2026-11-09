// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db.jooq;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.lang.Functions.Fn0;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.jooq.DSLContext;
import org.jooq.Query;
import org.jooq.Record;
import org.jooq.ResultQuery;


/// One slice's handle on a node-shared [JooqConnector] pool (#2973).
///
/// `stop()` and `close()` are both declared here, never inherited: the first call releases THIS holder only (the pool
/// closes when its last holder releases it), and every later call succeeds without doing anything. Neither ever reaches
/// the pool's own `stop()`. After the release every operation fails with [DatabaseConnectorError.HandleReleased] and
/// never reaches the pool. `config()`, `databaseType()` and `dsl()` keep answering: they describe the pool and touch no
/// connection (`dsl()` is detached from the pool).
public final class JooqConnectorView implements JooqConnector {
    private final JooqConnector pool;
    private final Fn0<Promise<Unit>> release;
    private final AtomicBoolean released = new AtomicBoolean();

    private JooqConnectorView(JooqConnector pool, Fn0<Promise<Unit>> release) {
        this.pool = pool;
        this.release = release;
    }

    public static JooqConnector jooqConnectorView(JooqConnector pool, Fn0<Promise<Unit>> release) {
        return new JooqConnectorView(pool, release);
    }

    @Override
    public DSLContext dsl() {
        return pool.dsl();
    }

    @Override
    public <R extends Record> Promise<R> fetchOne(ResultQuery<R> query) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.fetchOne(query);
    }

    @Override
    public <R extends Record> Promise<Option<R>> fetchOptional(ResultQuery<R> query) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.fetchOptional(query);
    }

    @Override
    public <R extends Record> Promise<List<R>> fetch(ResultQuery<R> query) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.fetch(query);
    }

    @Override
    public Promise<Integer> execute(Query query) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.execute(query);
    }

    @Override
    public <T> Promise<T> transactional(TransactionCallback<T> callback) {
        return released.get()
               ? DatabaseConnectorError.HandleReleased.INSTANCE.promise()
               : pool.transactional(callback);
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
