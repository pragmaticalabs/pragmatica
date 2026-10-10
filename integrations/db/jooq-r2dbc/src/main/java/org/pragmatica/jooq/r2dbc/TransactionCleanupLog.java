package org.pragmatica.jooq.r2dbc;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Contract;


/// Where a failed cleanup step of a finished transaction is reported. The step's failure never replaces the
/// caller's primary failure (#1313), so the log is the only place it surfaces.
final class TransactionCleanupLog {
    private static final System.Logger log = System.getLogger(TransactionCleanupLog.class.getName());

    private TransactionCleanupLog() {}

    @Contract
    static void warnStepFailed(String step, Cause cause) {
        log.log(System.Logger.Level.WARNING, "Transaction cleanup step '" + step + "' failed: " + cause.message());
    }
}
