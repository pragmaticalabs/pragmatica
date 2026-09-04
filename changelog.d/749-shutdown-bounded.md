### Fixed (2026-09-04 — #749, #750: node shutdown could hang forever under virtual-thread carrier starvation)

- **`Promise#timeout()`'s delayed-failure task shared the same virtual-thread executor as every other
  `.async()` offload in the codebase** — so a "bounded" operation was only actually bounded while that
  pool had a free carrier. CPU-bound work pinning every carrier (exactly the kind of load a timeout
  exists to guard against) prevented the timeout task from ever being scheduled, so it never fired.
  `AsyncExecutor` now runs timeout scheduling on a dedicated `ScheduledThreadPoolExecutor` (4 daemon
  platform threads, named `promise-timeout-scheduler-N`), decoupled from the JVM's virtual-thread
  carrier pool entirely; general `.async()` offload work is unchanged.
  [mechanism: `Promise#allOf`'s aggregation callback runs via `withResult`, which dispatches inline on
  whatever thread resolves each input promise, not offloaded — so decoupling only the timeout-*firing*
  thread is sufficient to make an entire `allOf(...).map(...)` chain (the exact shape
  `EmberCluster#stop()` and `Main#shutdownNode` use) resolve independent of the shared, starvable pool]
  [verified: `core/src/test/java/org/pragmatica/lang/PromiseAllOfCarrierStarvationTest.java` —
  drives 5 never-resolving promises through `.timeout(800ms)` + `Promise#allOf` + `.map(...)`, exactly
  as production does, while every virtual-thread carrier is pinned by non-yielding busy-spin work;
  asserts the aggregate resolves within 2000ms despite full carrier saturation]
- Red-before-green: reverting only the `AsyncExecutor` scheduler change makes the same test fail with
  a genuine `Failure(Timeout[...])` result (the outer `.await()` itself timing out, not a compile
  error and not a silent pass) — restoring the change makes it pass again.
  [verified: same test as above]
- **`Main#shutdownNode` now bounds `node.stop()` at 30 seconds.** On expiry it logs an ERROR, dumps
  every live thread (name, state, lock, full stack trace), and exits with code `2`, rather than the
  previous unbounded shutdown hook that could park forever with no signal to the operator. Which call
  inside `node.stop()` blocks in a real occurrence remains **unknown** — this fix bounds the hang and
  makes it loud and diagnosable, it does not identify a root cause inside `AetherNode#stop()` itself.
  [design intent — unverified: the 30s-then-exit-2 branch itself has no end-to-end test; only the
  underlying bounded-timeout mechanism it depends on is verified above]
  Exit code `2` and the rest of the node process's exit-code contract are now documented at
  [`aether/docs/reference/node-operations.md`](../aether/docs/reference/node-operations.md#exit-codes)
  (new page).
- **Review follow-up: `Promise#timeout()`'s scheduled failure task was never cancelled on early
  resolution.** The dedicated scheduler above closed the *liveness* gap (the fail task always fires),
  but every `.timeout()` call still discarded its `ScheduledFuture`, so a promise that resolved
  microseconds after `.timeout()` was attached left its failure task queued for the full timeout
  duration regardless — harmless per-promise (a late `.fail()` on an already-resolved promise is a
  CAS no-op in `resolve()`) but unbounded in aggregate: a server minting thousands of 30-second-timeout
  promises per second would retain thousands of dead entries, each holding a closure and a
  context-propagation snapshot, at any given moment. `.timeout()` now captures the `ScheduledFuture`
  and cancels it via `withResult(...)` — an inline, same-thread hook, not offloaded to the
  virtual-thread executor — the instant the promise resolves by any means (success, application
  failure, or the timeout itself firing). `timeoutScheduler` now runs with
  `setRemoveOnCancelPolicy(true)` so a cancelled task is purged from the queue immediately rather than
  lingering until its original fire time.
  [mechanism: `withResult`/`replaceResult` dispatch via `CompletionMap`, which runs inline on whichever
  thread calls `resolve()` — no dependency on the (potentially starved) virtual-thread executor, so
  cleanup cannot be blocked by the same carrier exhaustion #749's primary fix addresses]
  [verified: `core/src/test/java/org/pragmatica/lang/PromiseTimeoutCancellationTest.java` — asserts
  `AsyncExecutor`'s scheduler queue depth increments by exactly one when `.timeout()` is attached and
  drops back to baseline immediately after early success or early application failure; red-before-green
  confirmed reverting only the cancellation hook leaves the queue depth elevated]
  The general-purpose `async(TimeSpan, Consumer)` overload (used elsewhere for delay-then-run patterns
  on already-resolved promises, e.g. DNS cache-entry TTL eviction) is deliberately left unchanged:
  cancel-on-resolution would fire immediately on an already-resolved promise and defeat those callers'
  delay outright, so this fix is scoped to `.timeout()`'s own method body only.
