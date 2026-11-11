// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import org.pragmatica.lang.Promise;


/// A PostgreSQL [SqlConnector], the type the `@PgSql`-generated factories take.
///
/// **Transactions (#3014).** A generated factory needs a `PgSqlConnector`, but [SqlConnector#transactional] hands its
/// callback a plain [SqlConnector], so a generated adapter could not run inside a transaction without the application
/// wrapping the connector itself. [#transactional(PgTransactionCallback)] closes the gap: on a `PgSqlConnector` a
/// callback lambda receives a `PgSqlConnector` bound to the same transaction. It is an overload, not a change to the
/// base method, so every connector keeps its single `transactional` implementation; the overload only retypes the
/// connector the callback sees. Java picks it for every lambda written against a `PgSqlConnector`, because
/// [PgTransactionCallback] is a subtype of [SqlConnector.TransactionCallback].
public interface PgSqlConnector extends SqlConnector {
    /// Runs `callback` in one transaction, handing it the transaction-scoped connector as a [PgSqlConnector]. Commit on
    /// success, rollback on failure, exactly as [SqlConnector#transactional].
    default <T> Promise<T> transactional(PgTransactionCallback<T> callback) {
        return transactional((TransactionCallback<T>) callback);
    }

    /// A [SqlConnector.TransactionCallback] whose body sees a [PgSqlConnector]. The connector handed to the base
    /// `execute` is wrapped (or passed through when it already is a `PgSqlConnector`), so the callback also works with
    /// [SqlConnector#transactional] and [SqlConnector#pinned] of any connector.
    @FunctionalInterface
    interface PgTransactionCallback<T> extends TransactionCallback<T> {
        Promise<T> executePg(PgSqlConnector connector);

        @Override
        default Promise<T> execute(SqlConnector connector) {
            return executePg(PgSqlConnectorScope.pgSqlConnectorScope(connector));
        }
    }
}
