### Fixed (2026-09-14 — #914: `Promise.await()` ignored interruption and spun at 100% CPU)
- **`await()` and `await(TimeSpan)` parked in a loop that never consulted the interrupt status.**
  `LockSupport.park`/`parkNanos` return immediately, flag intact, on an interrupted thread, so an
  interrupted waiter re-parked forever: the unbounded form never returned and the bounded form
  spun to its deadline. Measured before the fix by the new test: five seconds after `interrupt()`
  the waiter was RUNNABLE and had consumed **4,953 ms of CPU** — a whole core, not a park. This is
  why an 8-minute JUnit backstop could not end a 586 s `EmberCluster` hang (#727/#913) and why a
  waiter flipped to RUNNABLE under `interrupt()` in #915: no supervisor could end any wait built on
  `await`.
  [verified: `core/src/test/java/org/pragmatica/lang/PromiseAwaitInterruptionTest.java` — a parked
  waiter interrupted on a fresh thread returns within the 5 s join with `CoreError.Interrupted`, the
  flag still set, and < 500 ms of thread CPU; the bounded form returns before its 30 s deadline; an
  already-interrupted caller returns `Interrupted` without parking; a resolved promise still answers
  on an interrupted thread; resolution still wins for an uninterrupted waiter]
- **Policy chosen: interruption ENDS the wait with a typed failure and PRESERVES the flag.** New
  `CoreError.Interrupted` (additive to the sealed interface; every `switch` over `CoreError` in the
  tree carries a `default`). The promise itself stays unresolved, exactly as on a timeout, so a
  later `await` by an uninterrupted thread still gets the value. The flag is preserved because the
  interrupt addressed the thread, not this wait — an outer loop that checks `isInterrupted()` must
  stop too. The alternative the ticket allows, "uninterruptible but non-spinning", was rejected:
  every test backstop and executor shutdown in this repository relies on interrupt actually ending
  a wait, and every `await()` caller already handles a failed `Result` (the 91 unbounded call sites
  in main sources — 28 in `aether/cli`, 13 in `integrations/storage`, the rest spread over
  `jbct/`, `aether/node`, `integrations/net|db` — are CLI blocking calls, dedicated background
  threads folding failure to a logged no-op, or startup waits; none retries on failure in a loop).
  Consequence to state: a thread that is interrupted and keeps calling `await()` now gets an
  immediate `Interrupted` failure each time instead of an immediate return-and-spin — the same
  contract as `Future.get` throwing, minus the exception.
  [mechanism: `PromiseImpl.await` checks `thread.isInterrupted()` before each park; no flag is
  cleared anywhere]
- Not changed: the `CompletionJoin` pushed for the waiter stays on the promise's completion stack
  after an interrupted return, exactly as after a timed-out return — the eventual `unpark` of a
  thread that has moved on is a documented no-op.
