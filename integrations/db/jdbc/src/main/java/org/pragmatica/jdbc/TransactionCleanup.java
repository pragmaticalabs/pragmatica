package org.pragmatica.jdbc;

import java.sql.Connection;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Result;


/// Releases the connection of a finished transaction, in order: rollback (only when the transaction failed),
/// auto-commit restoration, close. Each step is attempted whatever the previous one did, so a connection is
/// never left open because a rollback failed.
///
/// A step that fails is logged at WARNING and does not change the outcome: the caller's primary failure is
/// kept, and after a committed transaction a failed close does not turn committed work into a failure.
final class TransactionCleanup {
    private static final System.Logger log = System.getLogger(TransactionCleanup.class.getName());

    private TransactionCleanup() {}

    static void release(Connection conn, boolean rollbackFirst) {
        if (rollbackFirst) {
            report("rollback",
                   Result.lift(JdbcError::fromException,
                               () -> {
                                   conn.rollback();
                               }));
        }

        report("auto-commit restoration",
               Result.lift(JdbcError::fromException,
                           () -> {
                               conn.setAutoCommit(true);
                           }));
        report("close",
               Result.lift(JdbcError::fromException,
                           () -> {
                               conn.close();
                           }));
    }

    private static void report(String step, Result<?> outcome) {
        outcome.onFailure(cause -> logFailure(step, cause));
    }

    private static void logFailure(String step, Cause cause) {
        log.log(System.Logger.Level.WARNING, "Transaction cleanup step '" + step + "' failed: " + cause.message());
    }
}
