### Fixed (2026-10-13 — #2181: postgres-async swapped SQLSTATE and severity in every server error)

- **`ErrorResponse.asServerResponse()` passed severity where `SqlError.ServerResponse` expects the
  SQLSTATE.** The record is `(code, level, message)`; it was built as `(level, code, message)`, so
  `response().code()` returned `ERROR`/`FATAL` and `level()` returned the SQLSTATE for every error
  that arrived from the wire. Three consumers read the wrong field:
  the prepared-statement recovery in `PgConnection` (keyed on `42P05`/`26000`) never ran;
  `ServerConnectionException` and `ServerErrorOperatorIntervention` never classified a server-sent
  error as transient, so the retry policy never retried `08001`/`08004`/`08006` or `57P01`–`57P03`;
  and `ServerError.message()` printed `23505: SQLSTATE=ERROR`. The choice of error class was not
  affected: `PgProtocolStream.toSqlError` keys its mapper on `ErrorResponse.code()`, which the wire
  decoder fills correctly (`S` → severity, `C` → SQLSTATE).
- **Pinned through the wire mapping.** `ErrorResponseWireMappingTest` decodes ErrorResponse bytes with
  the real `ErrorResponseDecoder`, then checks `code`/`level`/`message`, the rendered message, transient
  classification for `08006` and `57P01`, and the stale-statement predicate for `42P05`/`26000` (and
  not for `23505`); all five tests are red with the old argument order. `ServerErrorWireTest`
  (Testcontainers) provokes a real unique violation and asserts `code() == "23505"`, and drops a
  cached statement with `DEALLOCATE ALL` to show the recovery now re-prepares and returns the row.
  `PgConnection.isStaleStatementError` is package-private so the predicate can be tested directly.
