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
- **A management client error holds the failure `Cause` and renders its client text (#3794).** `Cause` gains
  `clientMessage()`: a wrapper derives its text from its origin's client text, a throwable gives `Internal error`, any other
  cause gives its own message. Literal management text goes through a separate factory, so it cannot be mistaken for a
  held failure. A single-member composite renders its member's client text.
- **Every HTTP response codec writes a failure's client text.** `JsonMapper.clientFacing()` derives a mapper whose `Result`
  failure and `Cause` serializers write `clientMessage()`; `JsonCodecAdapter` applies it to whatever mapper it wraps, so a
  failed `Result` or a `Cause` inside a slice, management or Maven-protocol response body carries no class name and no trace.
  The general mapper (CLI, cloud clients, state files) is unchanged, so the `Result` round trip keeps the message.
- **The Forge API error body renders through `HttpError.clientMessage`** at both sites that wrote `Cause.message()` (a failing
  route handler, a success value that cannot be serialized).
- **A failed serialization carries fixed text.** `JsonCodecAdapter.serialize` no longer copies the mapper's failure message
  (it quotes the value and the serializer's exception: class names, a reference chain) into its `CodecError`; the error
  says `Serialization failed` and keeps the mapper's failure as its source, for the server-side cause chain.
