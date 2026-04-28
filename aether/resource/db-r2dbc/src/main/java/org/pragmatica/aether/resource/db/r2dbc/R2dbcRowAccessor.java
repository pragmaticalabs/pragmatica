// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db.r2dbc;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Result;

import io.r2dbc.spi.Row;


final class R2dbcRowAccessor implements RowAccessor {
    private final Row row;

    R2dbcRowAccessor(Row row) {
        this.row = row;
    }

    @Override public Result<String> getString(String column) {
        return Result.lift(e -> queryError("getString", column, e),
                           () -> row.get(column, String.class))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getString", column)));
    }

    @Override public Result<Integer> getInt(String column) {
        return Result.lift(e -> queryError("getInt", column, e),
                           () -> row.get(column, Integer.class))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getInt", column)));
    }

    @Override public Result<Long> getLong(String column) {
        return Result.lift(e -> queryError("getLong", column, e),
                           () -> row.get(column, Long.class))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getLong", column)));
    }

    @Override public Result<Double> getDouble(String column) {
        return Result.lift(e -> queryError("getDouble", column, e),
                           () -> row.get(column, Double.class))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getDouble", column)));
    }

    @Override public Result<Boolean> getBoolean(String column) {
        return Result.lift(e -> queryError("getBoolean", column, e),
                           () -> row.get(column, Boolean.class))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getBoolean", column)));
    }

    @Override public Result<byte[]> getBytes(String column) {
        return Result.lift(e -> queryError("getBytes", column, e),
                           () -> row.get(column, byte[].class))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getBytes", column)));
    }

    @Override public <V> Result<V> getObject(String column, Class<V> type) {
        return Result.lift(e -> queryError("getObject", column, e),
                           () -> row.get(column, type))
        .flatMap(v -> Option.option(v).toResult(nullColumnError("getObject", column)));
    }

    private static DatabaseConnectorError queryError(String method, String column, Throwable e) {
        return DatabaseConnectorError.queryFailed(method + "(" + column + ")", e);
    }

    private static DatabaseConnectorError nullColumnError(String method, String column) {
        return DatabaseConnectorError.queryFailed(method + "(" + column + ")", "Column value was NULL");
    }
}
