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
import io.r2dbc.spi.R2dbcType;
import io.r2dbc.spi.Type;

import org.pragmatica.postgres.Oid;
import org.pragmatica.postgres.PgColumn;

/// R2DBC [ColumnMetadata] backed by a postgres-async [PgColumn].
///
/// Maps PostgreSQL OID types to their corresponding R2DBC type descriptors.
public final class PgAsyncColumnMetadata implements ColumnMetadata {
    private final PgColumn pgColumn;

    private PgAsyncColumnMetadata(PgColumn pgColumn) {
        this.pgColumn = pgColumn;
    }

    /// Creates column metadata from a postgres-async column descriptor.
    ///
    /// @param pgColumn the source column descriptor
    ///
    /// @return a new [PgAsyncColumnMetadata]
    static PgAsyncColumnMetadata pgAsyncColumnMetadata(PgColumn pgColumn) {
        return new PgAsyncColumnMetadata(pgColumn);
    }

    @Override
    public String getName() {
        return pgColumn.name();
    }

    @Override
    public Type getType() {
        return mapOidToType(pgColumn.type());
    }

    private static Type mapOidToType(Oid oid) {
        return switch (oid) {
            case INT2, INT4 -> R2dbcType.INTEGER;
            case INT8 -> R2dbcType.BIGINT;
            case FLOAT4 -> R2dbcType.FLOAT;
            case FLOAT8 -> R2dbcType.DOUBLE;
            case NUMERIC -> R2dbcType.DECIMAL;
            case BOOL -> R2dbcType.BOOLEAN;
            case TEXT, VARCHAR, BPCHAR, NAME -> R2dbcType.VARCHAR;
            case BYTEA -> R2dbcType.VARBINARY;
            case DATE -> R2dbcType.DATE;
            case TIME, TIMETZ -> R2dbcType.TIME;
            case TIMESTAMP, TIMESTAMPTZ -> R2dbcType.TIMESTAMP;
            default -> R2dbcType.VARCHAR;
        };
    }
}
