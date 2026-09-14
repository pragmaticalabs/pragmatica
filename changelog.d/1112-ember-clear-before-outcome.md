### Fixed (2026-09-14 — #1112: `EmberCluster` cleared the node registry on a virtual thread that raced the caller's outcome)
- **`abortStart`, `handleStartResults` and `stop` all cleared the registry via `.onSuccess(clear)`**,
  which `Promise` dispatches to a virtual thread, while the `.flatMap` that produces the caller's
  outcome ran synchronously and scheduled the caller's own `onResult` continuation onto another
  virtual thread. The two raced, so a caller awaiting a failed `start()` could still read the aborted
  nodes as `inactive` — the ordering the #913 contract (and this class's own doc comment) asserted was
  never provided, and `LifecycleAwaitStartFailureTest` went red on CI whenever the race landed that
  way. [mechanism: `PromiseImpl.processActions` runs `CompletionOnResult` asynchronously and
  `CompletionMap`/`CompletionFold` inline, never dispatched]
- All three paths now go through one chain, `EmberCluster.clearThenSettle`, which runs the clear
  inside the `flatMap` that produces the outcome. The ordering is structural, not a thread property:
  `CompletionFold.complete` (`core/.../Promise.java`) applies the transformer and only then resolves
  the derived promise, on whichever thread resolved the stops (the last node's stop callback, or the
  timeout scheduler when a stop hit `NODE_TIMEOUT`), and no path in `PromiseImpl.processActions`
  dispatches a `CompletionFold`/`CompletionMap` — so the clear has returned before the outcome can
  resolve. `ClusterSnapshot.render` therefore no longer risks choosing the live branch over the
  retained `lastStartFailure` capture after a start failure.
  [verified: `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberClusterClearBeforeOutcomeTest.java`
  — 20,000 iterations of the product's chain with the stops resolved from another thread; 134 of
  20,000 non-empty on the `onSuccess` shape, 0 of 20,000 on the ordered shape]
- The wiring of each call site through that chain is pinned on the REAL paths, deterministically:
  `EmberClusterTeardownWiringTest` seeds fake nodes, holds the registry's `clear()` (a
  `computeIfAbsent` in flight blocks it on its bin) and demands that `abortStart`,
  `handleStartResults` and `stop` each stay unresolved while the clear is held. Rewiring any one
  site back to the old `onSuccess` chain reddens exactly that site's test.
  [verified: three per-site mutations, each red in its own test only]
- A clear that throws now settles the outcome as a failure carrying the throwable's cause instead
  of never resolving it (core's total-mapper contract: a throw inside a mapper hangs every
  dependent). Both clear bodies are total today; the lift is the guard for the next edit.
  [verified: `EmberClusterClearBeforeOutcomeTest.aThrowingClear_settlesAFailure_neverAHang` —
  timed out unresolved before, settles a failure after]
- The clear moves from a virtual thread onto the thread that settles the stops: seven map/counter
  operations, no I/O, no locks. [design intent — unverified]
