// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;


public interface SqlConnector extends DatabaseConnector {
    <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params);
    Promise<Integer> update(String sql, Object... params);
    Promise<int[]> batch(String sql, List<Object[]> paramsList);
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    @FunctionalInterface
    interface TransactionCallback<T> {
        Promise<T> execute(SqlConnector connector);
    }
}
