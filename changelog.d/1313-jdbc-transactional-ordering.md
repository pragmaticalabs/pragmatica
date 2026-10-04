### Fixed (2026-10-04 — #1313: `JdbcTransactional` cleanup could run before the rollback)
- **`withTransaction` attached rollback with `onFailure` and cleanup with `onResult`**: independent observers, so
  auto-commit restoration and `close()` could run before the rollback (restoring auto-commit with a transaction
  pending commits it), and the returned Promise settled without waiting for either. Acquisition,
  configuration, the operation (a synchronous throw from it is now a failed Promise, not an escape), commit or
  rollback and release are composed into the returned Promise: release runs strictly after the transaction has
  settled, in the order rollback (failure only), auto-commit restoration, close, and the Promise stays pending
  until it is done. The primary failure is preserved; a failing rollback, restoration or close step is logged at
  WARNING and does not replace it, and after a successful commit a failed close does not turn committed work
  into a failure. A connection that cannot be configured is released without a rollback.
- **`JooqR2dbcTransactional` had the identical defect** (`onFailure(rollback)` + `onResult(close)`, each blocking on
  `await()`) and gets the same treatment: rollback, then close, composed into the returned Promise, primary failure
  preserved, a failing step logged.
