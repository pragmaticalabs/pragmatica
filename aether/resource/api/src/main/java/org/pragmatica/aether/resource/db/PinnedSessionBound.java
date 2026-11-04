// SPDX-License-Identifier: Apache-2.0
// Copyright (c) 2025 Pragmatica Labs - Sergiy Yevtushenko
// Licensed under the Apache License, Version 2.0. See LICENSE-APACHE-2.0 in the repository root for full terms.
package org.pragmatica.aether.resource.db;

import java.time.Duration;

import org.pragmatica.aether.resource.db.SqlConnector.TransactionCallback;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;


/// Server-side statement bound for a pinned session on a transport with no client-side one (FDB-2a, #2591; the
/// v2639 addendum). R2DBC and pg-async cannot cancel a statement themselves, and `pinned` chains its close to the body,
/// never to a caller's timeout: a hung statement would hold the dedicated connection, and every session lock on it,
/// until it ends. So, on PostgreSQL, `statement_timeout` and `lock_timeout` are set as the FIRST statements of the
/// session, both to the pinned `statementTimeout`.
///
/// **Why both equal the bound.** A shorter `lock_timeout` would cancel the lock waits a `CREATE INDEX CONCURRENTLY`
/// makes for older transactions, which is the common way to leave an INVALID index behind (design §2.4, F8); the
/// whole statement budget is the right budget for a lock wait too. Setting `lock_timeout` explicitly still matters: a
/// role or database default (managed PostgreSQL often sets one) would otherwise apply to the migration session.
///
/// **A failed `SET` fails the session** before the body runs: a pinned scope with no bound is exactly what this
/// prevents. `Duration.ZERO` (or negative) means "no bound": nothing is set and the server defaults stay.
///
/// Other engines on R2DBC are not bounded here [unverified: no non-PostgreSQL R2DBC driver ships in this build];
/// CockroachDB is left to FDB-2b's tier-3 path [unverified: `lock_timeout` on CockroachDB before v21.2].
public sealed interface PinnedSessionBound {
    record unused() implements PinnedSessionBound {}

    /// Wraps `body` so that, on PostgreSQL, the server-side bounds are set on the session before it runs.
    static <T> TransactionCallback<T> serverBounded(Duration statementTimeout, TransactionCallback<T> body) {
        return session -> applyBounds(session, statementTimeout).flatMap(_ -> body.execute(session));
    }

    private static Promise<Unit> applyBounds(SqlConnector session, Duration statementTimeout) {
        return isBounded(session, statementTimeout)
               ? setBounds(session, statementTimeout.toMillis())
               : Promise.unitPromise();
    }

    private static boolean isBounded(SqlConnector session, Duration statementTimeout) {
        return session.databaseType() == DatabaseType.POSTGRESQL && statementTimeout.isPositive();
    }

    private static Promise<Unit> setBounds(SqlConnector session, long millis) {
        return session.update("SET statement_timeout = " + millis)
                      .flatMap(_ -> session.update("SET lock_timeout = " + millis))
                      .mapToUnit();
    }
}
