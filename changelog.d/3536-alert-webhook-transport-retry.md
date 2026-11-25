### Fixed (2026-11-24 — #3536: alert webhook retry skipped transport failures)
- **A webhook that could not be reached was tried once, whatever `retry_count` said.** The forwarder's retry ran only
  on the status code of a response; a transport failure (connection refused or reset, DNS failure, timeout) fails the send
  before any status exists, so it bypassed the retry and the alert was dropped after one attempt with an error log line.
  That is the common case for a receiver that is restarting. A transport failure now takes the same path as a
  retryable status: it is retried under the same `retry_count` and jittered backoff, and given up (logged as a `WebhookError`
  carrying the transport cause) once they are spent. [verified: `AlertForwarderTransportRetryTest`]
