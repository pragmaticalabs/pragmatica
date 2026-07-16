// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db.jdbc;

import java.sql.ResultSet;
import java.sql.SQLException;

import org.pragmatica.aether.resource.db.DatabaseConnectorError;
import org.pragmatica.aether.resource.db.RowMapper.RowAccessor;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;


final class JdbcRowAccessor implements RowAccessor {
    private final ResultSet rs;

    JdbcRowAccessor(ResultSet rs) {
        this.rs = rs;
    }

    @Override
    public Result<String> getString(String column) {
        try {
            var value = rs.getString(column);

            return rs.wasNull()
                   ? Result.success("")
                   : Result.success(option(value).or(""));
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getString(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Integer> getInt(String column) {
        try {
            var value = rs.getInt(column);

            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getInt(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getInt(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Long> getLong(String column) {
        try {
            var value = rs.getLong(column);

            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getLong(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getLong(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Double> getDouble(String column) {
        try {
            var value = rs.getDouble(column);

            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getDouble(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getDouble(" + column + ")", e).result();
        }
    }

    @Override
    public Result<Boolean> getBoolean(String column) {
        try {
            var value = rs.getBoolean(column);

            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", "Column value was NULL").result()
                   : Result.success(value);
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getBoolean(" + column + ")", e).result();
        }
    }

    @Override
    public Result<byte[]> getBytes(String column) {
        try {
            var value = rs.getBytes(column);

            return rs.wasNull()
                   ? Result.success(new byte[0])
                   : Result.success(option(value).or(new byte[0]));
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getBytes(" + column + ")", e).result();
        }
    }

    @Override
    public <V> Result<V> getObject(String column, Class<V> type) {
        try {
            var value = rs.getObject(column, type);

            return rs.wasNull()
                   ? DatabaseConnectorError.queryFailed("getObject(" + column + ", " + type.getSimpleName() + ")",
                                                        "Column value was NULL")
                                           .result()
                   : option(value).toResult(DatabaseConnectorError.queryFailed("getObject(" + column
                                                                              + ", " + type.getSimpleName()
                                                                              + ")",
                                                                               "Column value was NULL"));
        } catch (SQLException e) {
            return DatabaseConnectorError.queryFailed("getObject(" + column + ", " + type.getSimpleName() + ")",
                                                      e)
                                         .result();
        }
    }
}
