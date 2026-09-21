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
- **`close_countsBothUnsettledCommits_whenPeriodicAndFinalCommitShareOneConsumer` rewritten on the #1393
  seams (part of #1388).** With #1393's registration fix in place it still reddened once (run 35548028994,
  `expected: 2L but was: 1L`, 5.063 s): the store returned a pending promise, so the periodic commit's own 5 s
  timeout was armed before `close()` and raced the shutdown bound — it fired first, the bound rightly skipped
  the already-settled periodic (one `unsettled at the bound` line, for the final), and the periodic's
  increment arrived on an async Promise event handler 0.3 ms later (`(local) failed: timed out`), after
  `close()` had returned and the test had read 1. The test now holds the periodic's store call open until
  after `close()` returns (its timeout cannot be armed before the bound), asserts through
  `inFlightCommitCount()` that the periodic was registered before its store call and that both commits are
  in flight while `close()` waits, reads 2 as of `close()` returning, then releases and fails the periodic
  and checks the count stays 2. The wall-clock arming race is not merely unlikely: the timeout does not exist
  until the test lets the store return. [verified: red 2/14 with the bound reporting only the first
  in-flight commit (this test and the #1393 held-store pin); green at head]
- **Product change (part of #1388): a cursor commit's incident is counted in the same frame that settles its
  handle, never on an async event.** `issueTrackedCommit` attached the outcome handlers with `onSuccess`/
  `onFailure`, which `Promise.processActions` dispatches to the executor, while the handle settled inline via
  `withResult` — so a commit that settled by its own timeout just before the shutdown bound had a settled
  handle (correctly skipped by the bound) and an increment that landed after the caller had read the count.
  The outcome is now recorded with `withSuccess`/`withFailure`, attached BEFORE the unregister and the
  handle's `withResult`; attachment order is execution order for `with*()` actions, so nothing that sees the
  handle settled — `close()`'s bound above all — can see the count short. [verified:
  `commitFails_incidentIsCountedBeforeItsHandleSettles_observedFromTheSameResolveFrame` — the periodic's
  store promise is failed on the test thread and the FINAL commit's store call, which the periodic slot issues
  inline in that same frame, reads the count: 1 with no wait; with the increment back on `onFailure` it reads
  0 in 10/10 runs of the pin alone (12/13 overall — the async handler is dispatched before the inline actions
  run, so an inline observer can only race it, not exclude it); `close()` then returns inside the bound with
  both counted] [unverified: the attachment ORDER (count before the handle) — its only observer past the
  handle's settle is `close()`'s join waking a thread, a nanosecond window no test can close; stated at the
  attachment site]
- Also added, the CI shape itself as a regression test:
  `periodicCommitTimedOutByItsBoundWhileCloseWaits_bothIncidentsAreCountedAsOfCloseReturning` — the periodic's
  store promise is failed by its own bound while `close()` waits, the final fails synchronously, and the count
  read on the closing thread with no wait is 2. Green deterministically with the fix; it does NOT redden with the
  increment back on `onFailure` (0/10 on the build host — the closing thread's wake-up loses to the executor,
  which had the whole inline frame's head start), which is why the same-frame observer test above is the pin.
