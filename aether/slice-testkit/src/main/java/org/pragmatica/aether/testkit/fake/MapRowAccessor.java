// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.testkit.fake;

import java.util.Map;

import org.pragmatica.aether.resource.db.RowMapper;
import org.pragmatica.aether.testkit.TestKitError;
import org.pragmatica.lang.Result;

import static org.pragmatica.lang.Option.option;


/// Feeds a scripted row (column name to value) to a slice's [RowMapper] so the slice's own mapping
/// code runs against the fake — matching the async driver's `RowAccessor` contract.
record MapRowAccessor(Map<String, Object> row) implements RowMapper.RowAccessor {
    @Override
    public Result<String> getString(String column) {
        return value(column, String.class);
    }

    @Override
    public Result<Integer> getInt(String column) {
        return value(column, Integer.class);
    }

    @Override
    public Result<Long> getLong(String column) {
        return value(column, Long.class);
    }

    @Override
    public Result<Double> getDouble(String column) {
        return value(column, Double.class);
    }

    @Override
    public Result<Boolean> getBoolean(String column) {
        return value(column, Boolean.class);
    }

    @Override
    public Result<byte[]> getBytes(String column) {
        return value(column, byte[].class);
    }

    @Override
    public <V> Result<V> getObject(String column, Class<V> type) {
        return value(column, type);
    }

    private <V> Result<V> value(String column, Class<V> type) {
        return option(row.get(column)).toResult(new TestKitError.UnscriptedInteraction("No scripted column '" + column
                                                                                      + "' in row: " + row))
                     .map(type::cast);
    }
}
