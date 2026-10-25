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

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;
import org.pragmatica.postgres.SqlError;
import org.pragmatica.postgres.net.SqlException;

import io.r2dbc.spi.R2dbcDataIntegrityViolationException;
import io.r2dbc.spi.R2dbcException;
import io.r2dbc.spi.R2dbcRollbackException;
import io.r2dbc.spi.R2dbcTransientResourceException;

import static org.pragmatica.lang.Option.none;
import static org.pragmatica.lang.Option.some;


/// Exception thrown at the R2DBC adapter boundary to convert
/// Pragmatica [org.pragmatica.lang.Cause] failures into R2DBC-compatible exceptions.
///
/// This exception exists solely at the adapter layer to bridge between
/// the promise-based error model and the reactive streams error model. The bridge keeps what the driver
/// reported: the SQLSTATE of a server error becomes the exception's `sqlState`, the exception class says whether
/// the failure is a constraint violation, a rolled-back transaction or otherwise transient (R2DBC consumers
/// classify by class), and the original cause travels as the exception's cause. Reducing the failure to its
/// message would hide all three from every R2DBC consumer.
final class R2dbcAdapterException extends R2dbcException {
    private R2dbcAdapterException(String message, String sqlState, Throwable origin) {
        super(message, sqlState, 0, origin);
    }

    /// Bridges a driver failure to the R2DBC exception that classifies it the way the driver did.
    static R2dbcException from(Cause cause) {
        var origin = new SqlException(cause);
        var sqlState = sqlState(cause).or((String) null);

        return switch (cause) {
            case SqlError.ServerErrorIntegrityConstraintViolation _ -> new R2dbcDataIntegrityViolationException(cause.message(),
                                                                                                                sqlState,
                                                                                                                0,
                                                                                                                origin);
            case SqlError.ServerErrorTransactionRollback _ -> new R2dbcRollbackException(cause.message(),
                                                                                         sqlState,
                                                                                         0,
                                                                                         origin);
            default -> cause.isTransient()
                       ? new R2dbcTransientResourceException(cause.message(), sqlState, 0, origin)
                       : new R2dbcAdapterException(cause.message(), sqlState, origin);
        };
    }

    private static Option<String> sqlState(Cause cause) {
        return cause instanceof SqlError.ServerError server
               ? some(server.response().code())
               : none();
    }
}
