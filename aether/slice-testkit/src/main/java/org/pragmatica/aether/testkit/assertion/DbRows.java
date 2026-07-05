// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.assertion;

import org.pragmatica.aether.resource.db.SqlConnector;
import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Result;
import org.pragmatica.lang.TerminalOperation;
import org.pragmatica.lang.io.TimeSpan;

import java.util.List;
import java.util.function.Predicate;


/// A lazily-executed query against a real (container-backed) connector. Each terminal method runs
/// the query and maps rows through the caller's function while the driver row is live.
public final class DbRows {
    private static final TimeSpan TIMEOUT = TimeSpan.timeSpan(10).seconds();

    private final SqlConnector connector;
    private final String sql;
    private final Object[] params;

    private DbRows(SqlConnector connector, String sql, Object[] params) {
        this.connector = connector;
        this.sql = sql;
        this.params = params;
    }

    static DbRows dbRows(SqlConnector connector, String sql, Object[] params) {
        return new DbRows(connector, sql, params);
    }

    /// Map each row to a value with the caller's function (evaluated while the row is live) and
    /// return the list for AssertJ/JUnit assertions.
    @TerminalOperation
    public <T> List<T> map(Fn1<T, DbRow> mapper) {
        return connector.queryList(sql,
                                   accessor -> Result.success(mapper.apply(new LiveDbRow(accessor))),
                                   params)
                        .await(TIMEOUT)
                        .or(List.of());
    }

    /// Number of rows returned.
    @TerminalOperation
    public int count() {
        return map(row -> Boolean.TRUE).size();
    }

    /// True if at least one row satisfies the predicate.
    @TerminalOperation
    public boolean anyMatch(Predicate<DbRow> predicate) {
        return map(predicate::test).stream()
                  .anyMatch(Boolean::booleanValue);
    }

    /// True if every returned row satisfies the predicate (vacuously true for no rows).
    @TerminalOperation
    public boolean allMatch(Predicate<DbRow> predicate) {
        return map(predicate::test).stream()
                  .allMatch(Boolean::booleanValue);
    }
}
