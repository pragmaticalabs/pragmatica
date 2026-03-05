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

import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;

import org.pragmatica.postgres.PgRow;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;

/// R2DBC [Row] backed by a postgres-async [PgRow].
///
/// Delegates type-specific accessors to the corresponding [PgRow] methods
/// for optimal conversion, falling back to the generic `get(index, type)`
/// for unrecognized types.
public final class PgAsyncRow implements Row {
    private final PgRow pgRow;
    private final RowMetadata metadata;

    PgAsyncRow(PgRow pgRow, RowMetadata metadata) {
        this.pgRow = pgRow;
        this.metadata = metadata;
    }

    @Override
    public RowMetadata getMetadata() {
        return metadata;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(int index, Class<T> type) {
        return (T) getByIndex(index, type);
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(String name, Class<T> type) {
        return (T) getByName(name, type);
    }

    private Object getByIndex(int index, Class<?> type) {
        if (type == String.class) {
            return pgRow.getString(index);
        }
        if (type == Integer.class) {
            return pgRow.getInt(index);
        }
        if (type == Long.class) {
            return pgRow.getLong(index);
        }
        if (type == Double.class) {
            return pgRow.getDouble(index);
        }
        if (type == Boolean.class) {
            return pgRow.getBoolean(index);
        }
        if (type == byte[].class) {
            return pgRow.getBytes(index);
        }
        if (type == Short.class) {
            return pgRow.getShort(index);
        }
        if (type == Byte.class) {
            return pgRow.getByte(index);
        }
        if (type == BigDecimal.class) {
            return pgRow.getBigDecimal(index);
        }
        if (type == BigInteger.class) {
            return pgRow.getBigInteger(index);
        }
        if (type == LocalDate.class) {
            return pgRow.getLocalDate(index);
        }
        if (type == LocalTime.class) {
            return pgRow.getLocalTime(index);
        }
        if (type == LocalDateTime.class) {
            return pgRow.getLocalDateTime(index);
        }
        if (type == Instant.class) {
            return pgRow.getInstant(index);
        }
        if (type == Character.class) {
            return pgRow.getChar(index);
        }
        return pgRow.get(index, type);
    }

    private Object getByName(String name, Class<?> type) {
        if (type == String.class) {
            return pgRow.getString(name);
        }
        if (type == Integer.class) {
            return pgRow.getInt(name);
        }
        if (type == Long.class) {
            return pgRow.getLong(name);
        }
        if (type == Double.class) {
            return pgRow.getDouble(name);
        }
        if (type == Boolean.class) {
            return pgRow.getBoolean(name);
        }
        if (type == byte[].class) {
            return pgRow.getBytes(name);
        }
        if (type == Short.class) {
            return pgRow.getShort(name);
        }
        if (type == Byte.class) {
            return pgRow.getByte(name);
        }
        if (type == BigDecimal.class) {
            return pgRow.getBigDecimal(name);
        }
        if (type == BigInteger.class) {
            return pgRow.getBigInteger(name);
        }
        if (type == LocalDate.class) {
            return pgRow.getLocalDate(name);
        }
        if (type == LocalTime.class) {
            return pgRow.getLocalTime(name);
        }
        if (type == LocalDateTime.class) {
            return pgRow.getLocalDateTime(name);
        }
        if (type == Instant.class) {
            return pgRow.getInstant(name);
        }
        if (type == Character.class) {
            return pgRow.getChar(name);
        }
        return pgRow.get(name, type);
    }
}
