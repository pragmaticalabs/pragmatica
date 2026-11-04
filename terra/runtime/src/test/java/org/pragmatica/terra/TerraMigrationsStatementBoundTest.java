// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.terra;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.pragmatica.aether.resource.db.DatabaseConnectorConfig;
import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.DatabaseType;
import org.pragmatica.aether.resource.db.DatasourceConnectionProvider;
import org.pragmatica.aether.resource.db.PoolConfig;
import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.db.migration.MigrationScript;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// FDB-2a (#2591): Terra had no statement bound before the fence, so its migrations pass ZERO (= unbounded, sent as `0`
/// on PostgreSQL). Pinned at the seam: the `Duration` the migration runner hands to `SqlConnector#pinned`.
class TerraMigrationsStatementBoundTest {
    @Test
    void apply_pinsTheMigrationSessionWithAZeroStatementBound() {
        var pinnedWith = new AtomicReference<Duration>();
        var connector = new PinCapturing(pinnedWith);
        DatasourceConnectionProvider provider = new DatasourceConnectionProvider() {
            public Promise<SqlConnector> connector(String name) { return Promise.success(connector); }
            public Promise<Unit> release(String name) { return Promise.unitPromise(); }
            public Promise<Unit> releaseAll() { return Promise.unitPromise(); }
        };
        var scripts = Map.of("database", List.of(MigrationScript.migrationScript("V001__one.sql", "SELECT 1", 1)));

        TerraMigrations.terraMigrations("g:app", scripts, provider).unwrap().apply().await(timeSpan(5).seconds());

        assertThat(pinnedWith.get()).as("the runner reached SqlConnector#pinned").isNotNull();
        assertThat(pinnedWith.get()).isEqualTo(Duration.ZERO);
        assertThat(TerraMigrations.STATEMENT_TIMEOUT.duration()).isEqualTo(Duration.ZERO);
    }

    private record PinCapturing(AtomicReference<Duration> pinnedWith) implements SqlConnector {
        @Override
        public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
            pinnedWith.set(statementTimeout);
            return DatabaseConnectorError.PinnedSessionUnsupported.FACTORY.apply("captured; no session").promise();
        }

        @Override
        public <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params) {
            return DatabaseConnectorError.ResultNotFound.INSTANCE.promise();
        }

        @Override
        public <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(Option.none());
        }

        @Override
        public <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params) {
            return Promise.success(List.of());
        }

        @Override
        public Promise<Integer> update(String sql, Object... params) {
            return Promise.success(0);
        }

        @Override
        public Promise<int[]> batch(String sql, List<Object[]> paramsList) {
            return Promise.success(new int[0]);
        }

        @Override
        public <T> Promise<T> transactional(TransactionCallback<T> callback) {
            return callback.execute(this);
        }

        @Override
        public DatabaseConnectorConfig config() {
            return new DatabaseConnectorConfig(none(), some(DatabaseType.POSTGRESQL), some("localhost"), none(), some("test"), none(), none(),
                                               PoolConfig.DEFAULT, Map.of(), none(), none(), none());
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    }
}
