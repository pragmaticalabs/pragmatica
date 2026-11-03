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

    /// Runs `body` on ONE physical connection, borrowed in autocommit mode and held for the whole call. Every call the
    /// `body` makes on the connector it receives runs on that connection (same backend, same session state), and a
    /// `transactional(...)` it opens begins and commits on that same connection. This is the primitive a
    /// session-scoped database lock needs: such a lock lives and dies with the connection it was taken on.
    ///
    /// **Exit.** When `body` completes, success or failure, `release` runs on the same connection. Only if `release`
    /// succeeded is the connection handed back to its pool. If `release` fails, the physical connection is destroyed
    /// (aborted, and removed from the pool rather than returned to it) so that a lock the failed unlock left behind
    /// dies with the session. A pinned connection is never returned to the pool while `release` has not succeeded.
    /// The result is `body`'s result; a `release` failure does not replace it.
    ///
    /// **Contract.** No statement is retried on a connection error: a retry could silently land on another session
    /// (fail-fast). Statements are bounded by `statementTimeout` where the transport can bound them (JDBC); a transport
    /// that cannot, leaves the bound to the caller's own promise timeout.
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
