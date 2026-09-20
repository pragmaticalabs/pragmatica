### Fixed (2026-09-20 — #1388: two `aether-stream` tests asserted asynchronous state without awaiting it; one of them exposed a real shutdown-accounting window)
- **`DlqStreamSinkTest.malformedTopicEnvelope_isQuarantinedRaw_andPartitionContinues` read the cursor
  the instant the good event was delivered.** From the producer: `ConsumerRuntimeState.deliverSingleEvent`
  runs the handler (which fills `delivered`) and advances the cursor in `deliverySucceeded` only after the
  handler's promise settles — so the advance past the good event is not ordered before the delivery poll.
  The dead-letter hold does order the quarantine entry and the advance past the garbage before the good
  event, which is why only the final `== 2` could flake. The test now awaits `cursorPosition == 2` under
  the SAME 3 s deadline; nothing widened. [verified: the unmodified test fails `expected: 2L but was: 1L`
  under a 100 ms stall placed before `advanceCursor`; the awaiting test passes under the same stall]
- **`issueTrackedCommit` registered a commit in `inFlightCommits` only AFTER the store call returned.**
  The periodic checkpoint's `store.commit(...)` runs inline on the delivering thread, and `close()` takes
  its bound-await snapshot the moment it sees that commit issued; a store that stalls, or a thread
  descheduled between the call and the `add`, left the commit out of the snapshot — neither bound-awaited
  nor counted for THIS shutdown — and, because the periodic 5 s timeout is armed after the same return, its
  own failure could land after the shutdown bound too. That is CI run 35515972207's
  `close_countsBothUnsettledCommits_whenPeriodicAndFinalCommitShareOneConsumer` `expected: 2L but was: 1L`
  at 5.06 s. **Product change: a cursor commit is counted in flight from BEFORE its store call.** The
  `TrackedCommit` carries a handle minted and registered before `store.commit(...)` is invoked, settled
  from the chain as an immediate completion; the outcome handlers stay on the chain itself so a
  synchronously-failing store is still counted before `close()` returns (the first attempt moved them to the
  pending handle and `close_countsFailure_whenFinalCommitFailsSynchronously_andDoesNotWaitOutTheBound` went
  red: handlers on a pending promise dispatch asynchronously). [verified: new
  `close_countsAPeriodicCommitWhoseStoreCallIsStillInProgress_asUnsettled` — the store stub reads the
  package-private `inFlightCommitCount()` from INSIDE `commit()` (must already be 1) and then parks there
  until the test releases it after `close()` returned; red at the base with `but was: 1L`, red 3/3 with
  the registration moved back after the store call (`but was: 0`), green with the fix;
  `pendingCommitsSettledAfterCloseBegan_closeReturnsWhenTheyDo_countsNothing` (rev1393 M1) — the handle settles with
  the chain: both commits pending at `close()` and settled while it waits, `close()` returns when they do and counts
  nothing; red with the `withResult` forwarding deleted (`close()` returned only at the 5 s bound); the base also fails 3/3 under a 200 ms stall between the store call and the
  registration, and the fix passes 3/3 under the same stall placed after the call]
  [mechanism: `Promise.processActions` — `onResult`/`onSuccess`/`onFailure` attached to a pending promise
  run on `AsyncExecutor`, `withResult`/`map` completions run inline on resolution]
- The existing wall-clock sibling is unchanged apart from a comment: its `commitsIssued` latch fires inside
  the store call, which the registration now precedes, so the latch is a sufficient condition for the
  snapshot to hold both commits.
- [unverified: CI's rate under `-T 1C` after the fix — both classes ran 20/20 locally (68 tests each run,
  counted from surefire XML); the deterministic pins are the evidence]
