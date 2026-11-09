// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.io.TimeSpan;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.io.TimeSpan.timeSpan;

/// T-VIEW (#2973 D2.7): a holder's view forwards every method of its interface, default methods included, and declares
/// its own `stop()` and `close()`; after the release nothing reaches the pool.
class SqlConnectorViewTest {
    private static final TimeSpan TIMEOUT = timeSpan(5).seconds();

    /// Every public method of `api` (inherited ones and defaults included) resolves to a method declared by a view
    /// class, never to the interface's own default.
    static List<String> undeclared(Class<?> api, Class<?> view) {
        return Arrays.stream(api.getMethods())
                     .filter(method -> !Modifier.isStatic(method.getModifiers()))
                     .filter(method -> !isDeclaredByView(view, method))
                     .map(Method::getName)
                     .toList();
    }

    private static boolean isDeclaredByView(Class<?> view, Method method) {
        try {
            return !view.getMethod(method.getName(), method.getParameterTypes())
                        .getDeclaringClass()
                        .isInterface();
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    @Test
    void sqlConnectorView_declaresEveryMethod_includingStopCloseAndPinned() {
        assertThat(undeclared(SqlConnector.class, SqlConnectorView.class)).isEmpty();
        assertThat(undeclared(PgSqlConnector.class, PgSqlConnectorView.class)).isEmpty();
    }

    @Test
    void pinnedThroughAView_reachesTheUnderlyingInstance() {
        var pool = new PinnedPool();
        var view = PgSqlConnectorView.pgSqlConnectorView(pool, Promise::unitPromise);

        var result = view.pinned(Duration.ZERO, _ -> Promise.success("pinned"), _ -> Promise.unitPromise())
                         .await(TIMEOUT);

        assertThat(result.unwrap()).isEqualTo("pinned");
        assertThat(pool.pinnedCalls.get()).isEqualTo(1);
    }

    @Test
    void afterRelease_everyOperationFails_andStopIsIdempotent() {
        var pool = new PinnedPool();
        var releases = new AtomicInteger();
        var view = SqlConnectorView.sqlConnectorView(pool,
                                                     () -> Promise.success(Unit.unit())
                                                                  .onSuccess(_ -> releases.incrementAndGet()));

        view.close()
            .await(TIMEOUT);
        view.stop()
            .await(TIMEOUT);

        assertThat(view.isHealthy()
                       .await(TIMEOUT)
                       .isFailure()).isTrue();
        assertThat(view.pinned(Duration.ZERO, _ -> Promise.success("x"), _ -> Promise.unitPromise())
                       .await(TIMEOUT)
                       .isFailure()).isTrue();
        assertThat(pool.pinnedCalls.get()).isZero();
        assertThat(pool.stops.get()).as("the view never stops the pool").isZero();
        assertThat(releases.get()).isEqualTo(1);
    }

    /// A pool that implements `pinned` itself, so a view inheriting the default would be told apart.
    static final class PinnedPool implements PgSqlConnector {
        final AtomicInteger pinnedCalls = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public <T> Promise<T> pinned(Duration statementTimeout, TransactionCallback<T> body, TransactionCallback<Unit> release) {
            pinnedCalls.incrementAndGet();

            return body.execute(this);
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
            return DatabaseConnectorConfig.databaseConnectorConfigBuilder()
                                          .withAsyncUrl("postgresql://h:5432/db")
                                          .build()
                                          .unwrap();
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }

        @Override
        public Promise<Unit> stop() {
            stops.incrementAndGet();

            return Promise.unitPromise();
        }
    }
}
