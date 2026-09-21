### Fixed (2026-09-21 — #1401: `unsubscribe_thenResubscribe_resumesFromCommittedCursor_noRedelivery` detached before the runtime had accounted the delivery)
- **The test called `unsubscribe` the instant its handler ran, and asserted the detach flush committed 1.**
  From the producer: the handler (which counts the test's latch down) runs in `invokeHandler`; the cursor
  advance runs in `deliverySucceeded`, after the handler's promise settles; and the detach flush
  (`cleanupConsumer` → `flushCursorForKey`) commits the cursor AS IT STANDS. An `unsubscribe` in that gap
  commits 0 — CI run 35545733848: `expected: 1L but was: 0L`, elapsed 0.011 s, the store's `commit` called
  synchronously with 0. Neither of the ticket's candidates: the flush had settled (the stub resolves inline)
  and the fenced `fetchCursor` (#1335) is not on the path of the assertion that failed. The test now awaits
  the runtime's own accounting (`cursorPosition == 1`) before detaching, under the same 5 s deadline; nothing
  widened. [verified: the unmodified test fails `but was: 0L` under a 100 ms stall placed before
  `advanceCursor`; the awaiting test passes under the same stall]
- Recorded, not changed: an `unsubscribe` issued while a delivery's handler has returned but its advance has
  not run commits the pre-delivery cursor, and the late advance's checkpoint bails on the cancelled consumer,
  so the event is redelivered on reattach — the runtime's at-least-once contract at detach, not a stale-read
  window. [design intent — unverified]
