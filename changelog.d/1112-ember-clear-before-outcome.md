### Fixed (2026-09-14 — #1112: `EmberCluster` cleared the node registry on a virtual thread that raced the caller's outcome)
- **`abortStart`, `handleStartResults` and `stop` all cleared the registry via `.onSuccess(clear)`**,
  which `Promise` dispatches to a virtual thread, while the `.flatMap` that produces the caller's
  outcome ran synchronously and scheduled the caller's own `onResult` continuation onto another
  virtual thread. The two raced, so a caller awaiting a failed `start()` could still read the aborted
  nodes as `inactive` — the ordering the #913 contract (and this class's own doc comment) asserted was
  never provided, and `LifecycleAwaitStartFailureTest` went red on CI whenever the race landed that
  way. [mechanism: `PromiseImpl.processActions` runs `CompletionOnResult` asynchronously and
  `CompletionMap`/`CompletionFold` on the resolving thread]
- All three paths now go through one chain, `EmberCluster.clearThenSettle`, which runs the clear as
  a `map` on the resolving thread before the outcome is produced, so the outcome cannot resolve
  before the registry is empty. `ClusterSnapshot.render` therefore no longer risks choosing the live
  branch over the retained `lastStartFailure` capture after a start failure.
  [verified: `aether/ember/src/test/java/org/pragmatica/aether/ember/EmberClusterClearBeforeOutcomeTest.java`
  — 20,000 iterations of the product's chain with the stops resolved from another thread; 134 of
  20,000 non-empty on the `onSuccess` shape, 0 of 20,000 on `map`]
- The clear moves from a virtual thread onto the last node's stop-callback thread: seven map/counter
  operations, no I/O, no locks. [design intent — unverified]
