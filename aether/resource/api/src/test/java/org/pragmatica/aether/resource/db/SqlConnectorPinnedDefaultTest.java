// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.pragmatica.lang.Unit.unit;

/// FDB-1 (#2591): a connector that does not implement `pinned` must REFUSE with a typed cause, never fall back to
/// running unpinned (a session lock taken on a pooled connection would leak with it). The refusal must also not run
/// the body or the release, because neither can hold a session.
class SqlConnectorPinnedDefaultTest {
    private static final SqlConnector NON_PINNING = new SqlConnector() {
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
            throw new UnsupportedOperationException("not needed by the test");
        }

        @Override
        public Promise<Boolean> isHealthy() {
            return Promise.success(true);
        }
    };

    @Test
    void pinned_default_refusesWithTypedCause_andRunsNeitherBodyNorRelease() {
        var calls = new AtomicInteger();

        var result = NON_PINNING.<Unit> pinned(Duration.ZERO,
                                               _ -> countedStep(calls),
                                               _ -> countedStep(calls))
                                .await();

        assertThat(result.isFailure()).as("the default must refuse, not run unpinned").isTrue();
        result.onSuccess(_ -> {})
              .onFailure(cause -> assertThat(cause).isInstanceOf(DatabaseConnectorError.PinnedSessionUnsupported.class));
        assertThat(calls.get()).as("neither the body nor the release ran").isZero();
    }

    private static Promise<Unit> countedStep(AtomicInteger calls) {
        calls.incrementAndGet();

        return Promise.success(unit());
    }
}
