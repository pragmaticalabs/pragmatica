### Fixed (2026-09-04 — #749, #750: node shutdown could hang forever under virtual-thread carrier starvation)

- **`Promise#timeout()`'s delayed-failure task shared the same virtual-thread executor as every other
  `.async()` offload in the codebase** — so a "bounded" operation was only actually bounded while that
  pool had a free carrier. CPU-bound work pinning every carrier (exactly the kind of load a timeout
  exists to guard against) prevented the timeout task from ever being scheduled, so it never fired.
  `AsyncExecutor` now runs timeout scheduling on a dedicated `ScheduledExecutorService` (4 daemon
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
