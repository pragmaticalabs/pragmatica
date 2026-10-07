### Fixed (2026-10-07 — #1976: a refused restart commit of a stream owner read back as landed and was never retried)
- **The owner's lineage commit decided "landed" by re-reading the ownership record.** On a restart the old record already
  names a start, so a guarded commit refused because a competing write (an ISR change) moved the record still read as
  landed: the activation latched the ring incarnation and the restart (new term and epoch) was never retried.
- The commit now fails unless THIS write's own transaction result is accepted. A refused commit does not latch, and the
  existing re-drive retries it with backoff. After 5 consecutive refusals of one partition the operator gets one
  `LineageRefused` block (a CRITICAL log line and the partition status read); when the commit lands, one `RECOVERED` line.
