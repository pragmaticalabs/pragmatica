### Fixed (2026-11-26 — #3723: a 500 response body carried the stack trace of a caught throwable)
- **`Causes.fromThrowable` now answers a distinguishable `Causes.ThrowableCause`.** Its `message()` is unchanged (the printed
  stack trace, for logs and every existing caller); the type is the marker a client-body renderer needs.
- **`HttpError.clientMessage(Cause)` answers the fixed text `Internal error` for a `ThrowableCause`**, alone, as the origin of
  an `HttpError` wrapper, or as a member of a composite. No class name, no trace, no message of the throwable reaches the body.
- **The remaining client-body writers that echoed `Cause.message()` render through the same renderer:** the four
  `ManagementServerError` variants that carry a cause, the per-item outcome of a batch stream publish, the stream-materialize
  refusal and the blueprint validation errors.
- **A success response whose serialization fails is logged** (WARN, with the requestId and the cause chain); the client body
  still says only "Serialization failed".
