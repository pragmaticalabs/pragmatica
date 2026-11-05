// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.time.Duration;
import java.util.List;

import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


public interface SqlConnector extends DatabaseConnector {
    <T> Promise<T> queryOne(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<Option<T>> queryOptional(String sql, RowMapper<T> mapper, Object... params);
    <T> Promise<List<T>> queryList(String sql, RowMapper<T> mapper, Object... params);
    Promise<Integer> update(String sql, Object... params);
    Promise<int[]> batch(String sql, List<Object[]> paramsList);
    <T> Promise<T> transactional(TransactionCallback<T> callback);

    /// Runs `body` on ONE dedicated physical connection, opened for this call OUTSIDE any pool, in autocommit mode.
    /// Every call the `body` makes on the connector it receives runs on that connection (same backend, same session
    /// state), and a `transactional(...)` it opens begins and commits on that same connection. This is the primitive a
    /// session-scoped database lock needs: such a lock lives and dies with the connection it was taken on.
    ///
    /// **Never borrowed.** The connection is never taken from the connector's pool, so a held session lock cannot leak
    /// back into one, and the pool's sizing, reset and mode do not matter. Cost: one extra server connection per
    /// running pinned scope.
    ///
    /// **Close is the release.** When `body` completes, success or failure, `release` runs on the same connection as a
    /// courtesy (an explicit unlock), and then the physical connection is CLOSED, which ends the server session and
    /// with it every session-scoped lock. The outcome of `release` changes nothing. The result is `body`'s result.
    /// The close is chained to `body`'s own promise, never to a caller's timeout on it.
    ///
    /// **Contract.** No statement is retried on a connection error: a retry could silently land on another session
    /// (fail-fast). Statements are bounded by `statementTimeout`: client-side on JDBC; on R2DBC and pg-async, which have
    /// no client-side bound, by the server-side `statement_timeout` and `lock_timeout` set first on a PostgreSQL session
    /// ([PinnedSessionBound]). `Duration.ZERO` means unbounded.
    ///
    /// **Default.** Fails with [DatabaseConnectorError.PinnedSessionUnsupported]: a transport that does not implement
    /// this method cannot hold a session, and must never fall back to running unpinned.
    default <T> Promise<T> pinned(Duration statementTimeout,
                                  TransactionCallback<T> body,
                                  TransactionCallback<Unit> release) {
        return DatabaseConnectorError.PinnedSessionUnsupported.FACTORY.apply("this connector does not implement pinned sessions").promise();
    }

    @FunctionalInterface
    interface TransactionCallback<T> {
        Promise<T> execute(SqlConnector connector);
    }
}
