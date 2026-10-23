// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Option;
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

        /// Reads a nullable column: SQL NULL is an empty option, any other value is read as [#getObject] reads
        /// it. A generated row mapper reads an `Option<T>` record component through it.
        ///
        /// The default refuses: an accessor that cannot tell NULL from a failed read would otherwise turn one
        /// into the other. The PostgreSQL async connector and the slice test kit's fake implement it.
        default <V> Result<Option<V>> getOptional(String column, Class<V> type) {
            return DatabaseConnectorError.queryFailed("getOptional(" + column + ")",
                                                      getClass().getName() + " does not support nullable reads")
                                         .result();
        }
    }
}
