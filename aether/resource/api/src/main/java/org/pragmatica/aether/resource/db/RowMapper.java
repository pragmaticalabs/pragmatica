// SPDX-License-Identifier: BUSL-1.1
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under Business Source License 1.1. Change Date: 2030-01-01. Change License: Apache-2.0.
// See LICENSE in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Result;


@FunctionalInterface public interface RowMapper<T> {
    Result<T> map(RowAccessor row);

    interface RowAccessor {
        Result<String> getString(String column);
        Result<Integer> getInt(String column);
        Result<Long> getLong(String column);
        Result<Double> getDouble(String column);
        Result<Boolean> getBoolean(String column);
        Result<byte[]> getBytes(String column);
        <V> Result<V> getObject(String column, Class<V> type);
    }
}
