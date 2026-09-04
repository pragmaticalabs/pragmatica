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
  Review round 1 (#838) found the test itself under-saturated on machines with more than 8 CPUs: it
  sized its carrier count with `Math.max(availableProcessors(), 8)` against a pinned
  `-Djdk.virtualThreadScheduler.parallelism=8` (`pom.xml`'s `vthread.argLine`), so on a 12-CPU box the
  test waited for more saturator threads to start than the JVM had carriers to run them on, and — because
  that wait sat outside the `try`/`finally` — a timeout there left every saturator running for the rest
  of the fork, pinning every carrier for every later test. Fixed by reading the configured parallelism
  and taking `Math.min` against it, and moving the wait inside the `try` so saturators are always
  signalled to stop.
- Red-before-green: reverting only the `AsyncExecutor` scheduler change makes the same test fail with
  a genuine `Failure(Timeout[...])` result (the outer `.await()` itself timing out, not a compile
  error and not a silent pass) — restoring the change makes it pass again.
  [verified: same test as above]
- **`Main#shutdownNode` now bounds `node.stop()` at 30 seconds.** On expiry it logs an ERROR, dumps
  every live thread (name, state, lock, full stack trace, including virtual threads), flushes log4j2
  synchronously, and exits with code `3`, rather than the previous unbounded shutdown hook that could
  park forever with no signal to the operator. Which call inside `node.stop()` blocks in a real
  occurrence remains **unknown** — this fix bounds the hang and makes it loud and diagnosable, it does
  not identify a root cause inside `AetherNode#stop()` itself.
  [design intent — unverified: the 30s-then-halt branch itself has no end-to-end test; only the
  underlying bounded-timeout mechanism it depends on is verified above]
  Exit code `3` and the rest of the node process's exit-code contract are now documented at
  [`aether/docs/reference/node-operations.md`](../aether/docs/reference/node-operations.md#exit-codes)
  (new page).
- **Review round 1 (#838): `System.exit(2)` deadlocks from inside a shutdown hook.** A shutdown hook
  runs on the thread `Shutdown.runHooks()` uses to run every hook to completion; `System.exit()`'s own
  `Shutdown.exit()` blocks waiting for that same lock, so the hook thread joins itself. Proven by the
  reviewer: the process hung past 2 minutes, ignored `SIGTERM`, and needed `SIGKILL`. Fixed by replacing
  `System.exit(2)` with `Runtime.getRuntime().halt(3)`, called only after the thread dump is logged and
  `LogManager.shutdown()` has flushed the log4j2 context synchronously — `halt()` runs no further hooks
  and flushes no appenders of its own, so both must happen first. This also resolves the exit-code
  collision noted above: the timeout path no longer shares code `2` with `AetherNode`'s own
  drain-completed self-exit (`AetherNode.java:415,437`, which already used `halt(2)`).
  [verified: `aether/node/src/test/java/org/pragmatica/aether/MainShutdownTest.java` — pins that the
  expiry path flushes logs before halting, with its own exit code, via an injected `IntConsumer` seam;
  the real `System.exit` deadlock is not itself reproduced in a fast unit test — that would require a
  real JVM shutdown-hook execution — so this is a design fix responding to the reviewer's own
  execution proof, not an independently-reproduced regression test]
- **Review round 1 (#838): thread dump omitted virtual threads.** The prior implementation used
  `ThreadMXBean#dumpAllThreads`, which does not include virtual threads — on a node hung inside
  virtual-thread-heavy code (the exact scenario #749 addresses), the dump would omit the very threads
  most likely to be blocked. Replaced with `Main#captureThreadDump`, which uses
  `com.sun.management.HotSpotDiagnosticMXBean#dumpThreads` via a temporary file.
  [verified: `aether/node/src/test/java/org/pragmatica/aether/MainShutdownTest.java` — parks a
  uniquely-named virtual thread and asserts its name appears in the captured dump; a standalone
  scratch check (not part of the suite) confirmed the legacy API does not include it under the same
  conditions]
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

### Changed (2026-09-04 — #749, #750, #838 review round 1)

- **`Promise#timeout(TimeSpan)` now returns a different object than `this`.** The cancel-on-resolution
  fix above needs to observe the original promise's resolution without itself being that resolution, so
  `.timeout()` now returns a *derived* promise created via `withResult(...)`/`replaceResult(...)`
  rather than the receiver. The scheduled timeout failure still resolves the ORIGINAL promise (the one
  `.timeout()` was called on); the derived promise returned to the caller is resolved FROM that,
  inline, on whichever thread resolves the original — including `AsyncExecutor#timeoutScheduler`'s own
  platform thread on the timeout-fired path, and `Promise#allOf`'s aggregation, since `allOf` also
  registers via `withResult(...)`. This is what makes `PromiseAllOfCarrierStarvationTest` pass:
  original → derived → `allOf`'s collector → `allOf`'s aggregate all resolve on the single scheduler
  thread with no virtual-thread carrier involved anywhere in the chain, which is exactly what #749
  condition 1 required. A repo-wide sweep found no call site comparing a `.timeout()` result to its
  receiver by identity (`==`), but code outside this repo doing so would now observe a different
  object — a genuine, if narrow, behavior change, not merely an implementation detail.
  [mechanism: `withResult`/`replaceResult`'s `PromiseImpl` override registers a `CompletionMap` on the
  original promise and returns a new `PromiseImpl`, resolved by that map's callback at `resolve()`
  time — the same inline-dispatch contract the cancellation fix above relies on]
  [verified: `core/src/test/java/org/pragmatica/lang/PromiseAllOfCarrierStarvationTest.java` — exercises
  the full original→derived→`allOf` inline chain under carrier saturation; a break in the chain would
  show up as the aggregate never resolving, since no carrier is available to run it any other way]
- **`Promise.promise(TimeSpan, Supplier<Result<T>>)` now also runs on the dedicated timeout scheduler**,
  as a side effect of `AsyncExecutor`'s change above rather than separate new work — this overload was
  already implemented in terms of the same scheduling path `.timeout()` uses. The one identified
  production caller is `EmberCluster#handleStartResults`'s 2-second post-start cluster-stabilization
  delay (`aether/ember/.../EmberCluster.java:672`), which now runs off the virtual-thread carrier pool
  the same way `.timeout()` does, for the same reason: a stabilization delay that depended on carrier
  availability could itself be starved by the load it exists to wait out.
- **`VirtualThreadScheduler`'s and `AsyncExecutor.timeoutScheduler`'s docs now cross-reference each
  other.** `VirtualThreadScheduler`'s stated principle is "never run a task body inline" — only ever
  submit to a virtual-thread-per-task executor from its own dedicated timer thread.
  `AsyncExecutor#timeoutScheduler` deliberately does the opposite (runs the timeout fire action inline,
  on one of its own 4 platform threads) because the fire action must not itself depend on carrier
  availability, or #749's original starvation bug reappears one layer down inside whatever executes the
  fire. The cost is bounded, not eliminated: a blocking `.map()`/`.flatMap()`/`withResult()`
  continuation on a fired timeout occupies one of only 4 dedicated threads, so more than 4 concurrent
  blocking continuations would starve every *other* pending timeout process-wide. This was already true
  before #838 round 1; the round only made it documented rather than implicit.
