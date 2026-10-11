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
package org.pragmatica.jooq.r2dbc;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Functions.Fn2;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.r2dbc.R2dbcError;
import org.pragmatica.r2dbc.ReactiveOperations;

import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.reactivestreams.Publisher;


/// Transaction aspect for JOOQ R2DBC operations.
/// Provides transactional boundaries with automatic commit/rollback.
public interface JooqR2dbcTransactional {
    /// Executes an operation within a transaction.
    /// Automatically commits on success and rolls back on failure.
    ///
    /// @param connectionFactory ConnectionFactory for connection acquisition
    /// @param dialect SQL dialect
    /// @param operation Operation to execute with DSLContext
    /// @param <R> Result type
    ///
    /// @return Promise with operation result
    static <R> Promise<R> withTransaction(ConnectionFactory connectionFactory,
                                          SQLDialect dialect,
                                          Fn2<Promise<R>, DSLContext, Connection> operation) {
        return withTransaction(connectionFactory, dialect, R2dbcError::fromException, operation);
    }

    /// Executes an operation within a transaction with custom error mapping.
    /// Automatically commits on success and rolls back on failure.
    ///
    /// @param connectionFactory ConnectionFactory for connection acquisition
    /// @param dialect SQL dialect
    /// @param errorMapper Function to map exceptions to errors
    /// @param operation Operation to execute with DSLContext
    /// @param <R> Result type
    ///
    /// @return Promise with operation result
    static <R> Promise<R> withTransaction(ConnectionFactory connectionFactory,
                                          SQLDialect dialect,
                                          Fn1<R2dbcError, Throwable> errorMapper,
                                          Fn2<Promise<R>, DSLContext, Connection> operation) {
        return acquireConnection(connectionFactory, errorMapper).flatMap(conn -> executeWithConnection(conn,
                                                                                                       dialect,
                                                                                                       errorMapper,
                                                                                                       operation));
    }

    private static Promise<Connection> acquireConnection(ConnectionFactory factory,
                                                         Fn1<R2dbcError, Throwable> errorMapper) {
        return ReactiveOperations.fromPublisher(factory.create(), errorMapper);
    }

    private static <R> Promise<R> executeWithConnection(Connection conn,
                                                        SQLDialect dialect,
                                                        Fn1<R2dbcError, Throwable> errorMapper,
                                                        Fn2<Promise<R>, DSLContext, Connection> operation) {
        var attempt = beginTransaction(conn, errorMapper).flatMap(_ -> executeOperation(conn, dialect, operation))
                                      .flatMap(result -> commitAndReturn(conn, errorMapper, result));

        return releaseAfterSettlement(attempt, conn);
    }

    /// Rollback (on failure) and close are steps of the returned Promise, in that order, and the result is
    /// handed on only after both have completed (#1313). They used to be independent `onFailure` / `onResult`
    /// observers that each blocked on `await()`, so nothing ordered the close after the rollback and the
    /// returned Promise settled without waiting for either. The primary failure is preserved: a failing
    /// rollback or close is logged and never replaces it, and close is attempted whatever the rollback did.
    private static <R> Promise<R> releaseAfterSettlement(Promise<R> attempt, Connection conn) {
        var settled = Promise.<R> promise();

        attempt.onResult(result -> rollbackWhenFailed(conn,
                                                      result.isFailure()).flatMap(_ -> closeConnection(conn))
                                                     .onResult(_ -> settled.resolve(result)));

        return settled;
    }

    private static <R> Promise<R> executeOperation(Connection conn,
                                                   SQLDialect dialect,
                                                   Fn2<Promise<R>, DSLContext, Connection> operation) {
        var dsl = DSL.using(conn, dialect);

        return operation.apply(dsl, conn);
    }

    private static <R> Promise<R> commitAndReturn(Connection conn, Fn1<R2dbcError, Throwable> errorMapper, R result) {
        return commitTransaction(conn, errorMapper).map(_ -> result);
    }

    /// Lifted, so a driver that throws instead of returning a failing publisher is a failed step: the attempt
    /// then exists and its settlement releases the connection. Unlifted, a throw from `begin` escaped before
    /// the attempt was built and the connection was never closed.
    private static Promise<Unit> beginTransaction(Connection conn, Fn1<R2dbcError, Throwable> errorMapper) {
        return lifted(errorMapper, conn::beginTransaction);
    }

    private static Promise<Unit> commitTransaction(Connection conn, Fn1<R2dbcError, Throwable> errorMapper) {
        return lifted(errorMapper, conn::commitTransaction);
    }

    private static Promise<Unit> lifted(Fn1<R2dbcError, Throwable> errorMapper,
                                        java.util.function.Supplier<Publisher<Void>> publisher) {
        return Promise.<Publisher<Void>> lift(errorMapper, publisher::get).flatMap(created -> ReactiveOperations.fromVoidPublisher(created,
                                                                                                                                   errorMapper));
    }

    private static Promise<Unit> rollbackWhenFailed(Connection conn, boolean failed) {
        return failed
               ? loggingFailure("rollback", step(conn::rollbackTransaction))
               : Promise.success(Unit.unit());
    }

    private static Promise<Unit> closeConnection(Connection conn) {
        return loggingFailure("close", step(conn::close));
    }

    /// A driver may refuse a cleanup step by throwing instead of returning a failing publisher (a connection
    /// already closed underneath). Lifted, the throw is a failed step that is logged like any other; unlifted it
    /// escaped the callback that settles the returned Promise, which then never settled and never closed.
    private static Promise<Unit> step(java.util.function.Supplier<Publisher<Void>> publisher) {
        return Promise.<Publisher<Void>> lift(R2dbcError::fromException, publisher::get).flatMap(ReactiveOperations::fromVoidPublisher);
    }

    private static Promise<Unit> loggingFailure(String step, Promise<Unit> stepResult) {
        return stepResult.fold(outcome -> {
            outcome.onFailure(cause -> TransactionCleanupLog.warnStepFailed(step, cause));

            return Promise.success(Unit.unit());
        });
    }
}
