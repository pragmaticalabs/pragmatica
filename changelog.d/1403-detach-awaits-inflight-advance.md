### Fixed (2026-10-04 — #1403: a graceful unsubscribe or close during an in-flight delivery committed the pre-delivery cursor)
- **A graceful detach committed the cursor as it stood, before the in-flight delivery's advance.** A handler that
  had completed but whose cursor advance had not yet run, or that completed moments after the detach, was
  committed at the pre-delivery offset; the late advance's checkpoint then bailed on the cancelled consumer, and
  the event was redelivered on reattach. The docs describe a graceful detach as flushing the exact cursor.
- The consumer now holds an in-flight-advance slot from each handler call (and each retry and dead-letter append)
  until its outcome has run, and the detach flush of `unsubscribe` and `close()` is chained behind it, bounded by
  `DETACH_ADVANCE_BOUND` (1 s, under the 5 s shutdown bound). At the bound the flush commits the cursor as it
  stands, so an event whose handler has not completed is redelivered on reattach (at-least-once), and a WARNING
  names the consumer and the offset. No operator event: that redelivery is the ordinary at-least-once contract and
  no operator action clears it. The flush is chained, never blocked on, so a handler that unsubscribes its own
  consumer does not wait on itself.
  `[verified: aether/aether-stream/src/test/java/org/pragmatica/aether/stream/DetachAwaitsInFlightAdvanceTest.java]`
  (in-process: delivery, bound, `close()`, retry and dead-letter cases).
- The 1 s bound is a judgment call, not derived from a measured handler latency `[design intent — unverified]`.
