// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;

import java.util.List;


/// Raw SQL connector for database operations.
///
/// Extends DatabaseConnector with query, update, batch, and transactional operations.
/// Transport (JDBC or R2DBC) is selected at runtime based on configuration URL.
///
/// Example usage:
/// ```{@code
/// connector.queryOne("SELECT * FROM users WHERE id = ?", userMapper, userId)
///          .flatMap(user -> connector.update(
///              "UPDATE users SET last_login = ? WHERE id = ?",
///              Instant.now(), user.id()))
/// }```
public interface SqlConnector extends DatabaseConnector {
    <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params);
    Promise<Integer> update(String sql, Object... params);
    Promise<int[]> batch(String sql, List<Object[]> paramsList);
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    @FunctionalInterface interface TransactionCallback<T> {
        Promise<T> execute(SqlConnector connector);
    }
}
