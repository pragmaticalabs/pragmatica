/*
 *  Copyright (c) 2025 Sergiy Yevtushenko.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 *
 */

package org.pragmatica.postgres.r2dbc;

import io.r2dbc.spi.ColumnMetadata;
import io.r2dbc.spi.RowMetadata;

import org.pragmatica.postgres.PgColumn;

import java.util.Collection;
import java.util.List;

/// R2DBC [RowMetadata] backed by a list of postgres-async [PgColumn] descriptors.
///
/// Provides column lookup by both index and case-insensitive name.
public final class PgAsyncRowMetadata implements RowMetadata {
    private final List<PgAsyncColumnMetadata> columns;

    private PgAsyncRowMetadata(List<PgAsyncColumnMetadata> columns) {
        this.columns = columns;
    }

    /// Creates row metadata from a list of postgres-async column descriptors.
    ///
    /// @param pgColumns ordered list of column descriptors
    ///
    /// @return a new [PgAsyncRowMetadata]
    static PgAsyncRowMetadata pgAsyncRowMetadata(List<PgColumn> pgColumns) {
        var cols = pgColumns.stream()
                            .map(PgAsyncColumnMetadata::pgAsyncColumnMetadata)
                            .toList();
        return new PgAsyncRowMetadata(cols);
    }

    @Override
    public ColumnMetadata getColumnMetadata(int index) {
        return columns.get(index);
    }

    @Override
    public ColumnMetadata getColumnMetadata(String name) {
        for (var col : columns) {
            if (col.getName().equalsIgnoreCase(name)) {
                return col;
            }
        }
        throw new IllegalArgumentException("Unknown column: " + name);
    }

    @Override
    public List<? extends ColumnMetadata> getColumnMetadatas() {
        return columns;
    }

    @Override
    public boolean contains(String columnName) {
        for (var col : columns) {
            if (col.getName().equalsIgnoreCase(columnName)) {
                return true;
            }
        }
        return false;
    }

    public Collection<String> getColumnNames() {
        return columns.stream()
                      .map(PgAsyncColumnMetadata::getName)
                      .toList();
    }
}
