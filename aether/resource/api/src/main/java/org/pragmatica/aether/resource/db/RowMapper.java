// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Result;


@FunctionalInterface
public interface RowMapper<T> {
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
