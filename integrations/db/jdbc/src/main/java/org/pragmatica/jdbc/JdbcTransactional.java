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
package org.pragmatica.jdbc;

import javax.sql.DataSource;
import java.sql.Connection;

import org.pragmatica.lang.Functions.Fn1;
import org.pragmatica.lang.Promise;


/// Transaction aspect for JDBC operations.
/// Provides transactional boundaries with automatic commit/rollback.
public interface JdbcTransactional {
    /// Executes an operation within a transaction.
    /// Automatically commits on success and rolls back on failure.
    ///
    /// @param dataSource DataSource for connection acquisition
    /// @param operation Operation to execute with the connection
    /// @param <R> Result type
    ///
    /// @return Promise with operation result
    static <R> Promise<R> withTransaction(DataSource dataSource, Fn1<Promise<R>, Connection> operation) {
        return withTransaction(dataSource, JdbcError::fromException, operation);
    }

    /// Executes an operation within a transaction with custom error mapping.
    /// Automatically commits on success and rolls back on failure.
    ///
    /// Every step is composed into the returned Promise, so it settles only after the transaction has: the
    /// connection is released after rollback (or commit) on every path, including a failed configuration and
    /// a synchronous throw from `operation`. The primary failure is what the caller sees; a rollback, restore
    /// or close failure that follows it is logged (see [TransactionCleanup]) and never replaces it. (#1313:
    /// rollback and close used to be independent observers, so cleanup could run before rollback, and the
    /// returned Promise did not wait for either.)
    ///
    /// @param dataSource DataSource for connection acquisition
    /// @param errorMapper Function to map exceptions to errors
    /// @param operation Operation to execute with the connection
    /// @param <R> Result type
    ///
    /// @return Promise with operation result
    static <R> Promise<R> withTransaction(DataSource dataSource,
                                          Fn1<JdbcError, Throwable> errorMapper,
                                          Fn1<Promise<R>, Connection> operation) {
        return acquireConnection(dataSource, errorMapper).flatMap(conn -> transact(conn, errorMapper, operation));
    }

    private static <R> Promise<R> transact(Connection conn,
                                           Fn1<JdbcError, Throwable> errorMapper,
                                           Fn1<Promise<R>, Connection> operation) {
        var attempt = Promise.lift(errorMapper,
                                   () -> operation.apply(conn))
                             .flatMap(pending -> pending)
                             .flatMap(result -> commit(conn, errorMapper, result));

        return releaseAfterSettlement(attempt, conn);
    }

    private static Promise<Connection> acquireConnection(DataSource dataSource, Fn1<JdbcError, Throwable> errorMapper) {
        return Promise.lift(errorMapper,
                            () -> {
                                return dataSource.getConnection();
                            })
                      .flatMap(conn -> configure(conn, errorMapper));
    }

    /// A connection that cannot be configured is released at once, without a rollback: nothing ran on it.
    private static Promise<Connection> configure(Connection conn, Fn1<JdbcError, Throwable> errorMapper) {
        var configured = Promise.lift(errorMapper,
                                      () -> {
                                          conn.setAutoCommit(false);

                                          return conn;
                                      });

        return releaseIfFailed(configured, conn);
    }

    private static <R> Promise<R> commit(Connection conn, Fn1<JdbcError, Throwable> errorMapper, R result) {
        return Promise.lift(errorMapper,
                            () -> {
                                conn.commit();

                                return result;
                            });
    }

    /// Creates a transactional wrapper function.
    /// The returned function executes operations in a transaction.
    ///
    /// @param dataSource DataSource for connection acquisition
    /// @param <R> Result type
    ///
    /// @return Function that wraps operations in transactions
    static <R> Fn1<Promise<R>, Fn1<Promise<R>, Connection>> transactional(DataSource dataSource) {
        return operation -> withTransaction(dataSource, operation);
    }

    /// Settles the returned Promise only after the connection has been released. The release runs inside the
    /// one callback that observes the attempt's result, strictly in order (rollback when the attempt failed,
    /// then auto-commit restoration, then close), and only then is the result handed on.
    private static <R> Promise<R> releaseAfterSettlement(Promise<R> attempt, Connection conn) {
        var settled = Promise.<R> promise();

        attempt.onResult(result -> {
            TransactionCleanup.release(conn, result.isFailure());
            settled.resolve(result);
        });

        return settled;
    }

    private static Promise<Connection> releaseIfFailed(Promise<Connection> attempt, Connection conn) {
        var settled = Promise.<Connection> promise();

        attempt.onResult(result -> {
            if (result.isFailure()) {
                TransactionCleanup.release(conn, false);
            }

            settled.resolve(result);
        });

        return settled;
    }
}
